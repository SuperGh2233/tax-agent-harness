package com.taxagent.harness;

import com.taxagent.harness.AgentConfigTest.Recorder;
import com.taxagent.harness.runtime.*;
import com.taxagent.harness.runtime.AgentDirectory.Spec;
import com.taxagent.harness.runtime.CapabilityRegistry.*;
import com.taxagent.harness.tool.*;
import com.taxagent.harness.tool.PermissionGuard.Session;
import com.taxagent.harness.tool.ToolContract.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.taxagent.harness.runtime.LlmClient.*;
import static org.assertj.core.api.Assertions.*;
import static com.taxagent.harness.ToolContractTest.fails;

class CapabilityTest {
    @TempDir Path directory;
    Path db;
    AgentDirectory agents;
    CapabilityRegistry registry;
    final Session operator = new Session("U001"), viewer = new Session("U002");
    static final Spec READER = new Spec("只读查询员", "只回答查询", "", "default", null,
            List.of("query_invoices", "query_rules"), List.of("C001"));

    @BeforeEach void setup() throws Exception {
        db = directory.resolve("capabilities.db"); DataInitializer.initialize(db);
        agents = new AgentDirectory(db); registry = new CapabilityRegistry(db);
        agents.save(operator, "C001", "finance-reader", READER);
    }
    FinanceAgent run(String agentId, LlmClient model) {
        return new FinanceAgent(db, operator, "C001", ModelProfiles.single(model), agents.resolve(operator, "C001", agentId));
    }
    static Call call(String name, String args) { return new Call(UUID.randomUUID().toString(), name, args); }
    static String errorOf(TaskMemory.Turn turn, int index) {
        var json = turn.result().history().get(index).resultJson();
        var m = java.util.regex.Pattern.compile("\"code\":\"([A-Z_]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
    ToolInfo tool(String name) { return registry.tools(operator, "C001").stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow(); }

    @Test void oneToolServesTwoAgentsAndSwitchingItOffAffectsBoth() throws Exception {
        assertThat(tool("query_rules")).extracting(ToolInfo::version, ToolInfo::enabled).containsExactly("1.0", true);
        assertThat(tool("query_rules").usedBy()).containsExactly("finance-assistant", "finance-reader");
        assertThat(tool("check_invoice_purpose").version()).isEqualTo("2.0");

        assertThat(registry.setToolEnabled(operator, "C001", "query_rules", false).enabled()).isFalse();
        for (var agentId : List.of("finance-assistant", "finance-reader")) {
            var model = Recorder.of(call("query_rules", "{\"enterprise_id\":\"C001\",\"date\":\"2026-09-15\"}"), new Ask("还需要什么？"));
            var agent = run(agentId, model);
            var turn = agent.turn(agent.start("规则", List.of()).id(), "R002 是什么");
            assertThat(model.requests.getFirst().tools()).as(agentId).extracting(Tool::name).doesNotContain("query_rules");
            assertThat(errorOf(turn, 0)).as(agentId).isEqualTo("CAPABILITY_DISABLED");
            assertThat(turn.result().errorCode()).isEqualTo("CAPABILITY_DISABLED");
            new TaskMemory(db).cancel(operator, "C001", turn.task().id(), turn.task().version());
        }
        registry.setToolEnabled(operator, "C001", "query_rules", true);
        var model = Recorder.of(new Ask("x"));
        var agent = run("finance-reader", model);
        agent.turn(agent.start("规则", List.of()).id(), "R002");
        assertThat(model.requests.getFirst().tools()).extracting(Tool::name).contains("query_rules");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement()) {
            assertThat(s.executeQuery("SELECT count(*) FROM tool_status WHERE tool='query_rules'").getInt(1)).isEqualTo(2);
            assertThatThrownBy(() -> s.execute("UPDATE tool_status SET enabled=1")).hasMessageContaining("append-only");
            assertThatThrownBy(() -> s.execute("DELETE FROM tool_status")).hasMessageContaining("append-only");
        }
    }

    @Test void switchingOffSavingBlocksApprovedSavesBeforeTheModelRuns() throws Exception {
        registry.setToolEnabled(operator, "C001", "update_invoice", false);
        var model = Recorder.of(call("update_invoice", "{}"), new Finish("已保存"));
        var agent = run("finance-assistant", model);
        var task = agent.start("保存", List.of());
        var update = new InvoiceUpdate(new InvoiceKey("C001", "F002"), Map.of("purpose", "办公"));
        fails(ErrorCode.CAPABILITY_DISABLED, () -> agent.approvedTurn(task.id(), "保存", "op-1", update, InvoiceWriteService.Intent.SAVE_NOW));
        assertThat(model.requests).isEmpty();
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement()) {
            assertThat(s.executeQuery("SELECT count(*) FROM invoice_operations").getInt(1)).isZero();
        }
    }

    @Test void skillsComposeToolsAndInstructionsAndChangesApplyFromTheNextTurn() {
        // An agent with no tools of its own gets exactly the tools of its skill.
        agents.save(operator, "C001", "purpose-checker", new Spec("用途检查员", "只做用途缺口检查", "", "default", null,
                List.of(), List.of("C001"), List.of("purpose-gap-check")));
        var model = Recorder.of(new Ask("请确认月份"), new Ask("仍需月份"), new Ask("x"));
        var agent = run("purpose-checker", model);
        var task = agent.start("检查用途", List.of());
        var first = agent.turn(task.id(), "检查用途");
        assertThat(model.requests.getFirst().tools()).extracting(Tool::name)
                .containsExactlyInAnyOrder("query_rules", "check_invoice_purpose", "query_invoices");
        assertThat(first.promptTrace().systemPrompt()).contains("[Skill purpose-gap-check v1 · 用途缺口检查]", "先用 query_rules 确认");
        assertThat(registry.skills(operator, "C001")).filteredOn(i -> i.skill().id().equals("purpose-gap-check"))
                .singleElement().extracting(SkillInfo::usedBy).isEqualTo(List.of("purpose-checker"));

        // New skill version: next turn uses v2 text and tools.
        registry.saveSkill(operator, "C001", "purpose-gap-check", new SkillSpec("用途缺口检查", "v2", "只调用 check_invoice_purpose。",
                List.of("check_invoice_purpose")));
        var second = run("purpose-checker", model).turn(task.id(), "再查");
        assertThat(model.requests.get(1).tools()).extracting(Tool::name).containsExactly("check_invoice_purpose");
        assertThat(second.promptTrace().systemPrompt()).contains("[Skill purpose-gap-check v2", "只调用 check_invoice_purpose。");

        // Disabled skill: its instructions and tools are gone from the next turn.
        registry.setSkillEnabled(operator, "C001", "purpose-gap-check", false);
        var third = run("purpose-checker", model).turn(task.id(), "再查一次");
        assertThat(model.requests.get(2).tools()).isEmpty();
        assertThat(third.promptTrace().systemPrompt()).doesNotContain("[Skill purpose-gap-check");
        assertThat(registry.effective(agents.resolve(operator, "C001", "purpose-checker")).droppedSkills()).containsExactly("purpose-gap-check");
        assertThat(registry.skillHistory(operator, "C001", "purpose-gap-check")).extracting(Skill::version).containsExactly(3, 2, 1);
    }

    @Test void notGrantedAndDisabledAreReportedDistinctly() {
        var model = Recorder.of(call("check_invoice_purpose", "{\"enterprise_id\":\"C001\",\"month\":\"2026-09\"}"), new Ask("x"));
        var agent = run("finance-reader", model);
        var turn = agent.turn(agent.start("x", List.of()).id(), "检查用途");
        assertThat(errorOf(turn, 0)).isEqualTo("CAPABILITY_NOT_GRANTED");
    }

    @Test void skillsCannotGrantSavingAndGlobalChangesNeedRightsEverywhere() {
        fails(ErrorCode.INVALID_ARGUMENT, () -> registry.saveSkill(operator, "C001", "sneaky-save",
                new SkillSpec("x", "", "保存一切", List.of("query_invoices", "update_invoice"))));
        fails(ErrorCode.INVALID_ARGUMENT, () -> registry.saveSkill(operator, "C001", "Bad Id", new SkillSpec("x", "", "y", List.of("query_rules"))));
        fails(ErrorCode.INVALID_ARGUMENT, () -> registry.saveSkill(operator, "C001", "no-tools", new SkillSpec("x", "", "y", List.of())));
        fails(ErrorCode.PERMISSION_DENIED, () -> registry.setToolEnabled(viewer, "C001", "query_rules", false));
        fails(ErrorCode.PERMISSION_DENIED, () -> registry.saveSkill(viewer, "C001", "viewer-skill", new SkillSpec("x", "", "y", List.of("query_rules"))));
        fails(ErrorCode.PERMISSION_DENIED, () -> registry.setSkillEnabled(viewer, "C001", "bank-evidence", false));
        fails(ErrorCode.NOT_FOUND, () -> registry.setToolEnabled(operator, "C001", "delete_bank", false));
        fails(ErrorCode.INVALID_ARGUMENT, () -> agents.save(operator, "C001", "ghost-skill", new Spec("x", "y", "", "default", null,
                List.of("query_rules"), List.of("C001"), List.of("no-such-skill"))));
        // A viewer can still read the registry, including who uses what.
        assertThat(registry.tools(viewer, "C001")).hasSize(7); // T34 added recall_context.
        assertThat(registry.skills(viewer, "C001")).extracting(i -> i.skill().id()).containsExactly("bank-evidence", "purpose-gap-check");
    }
}
