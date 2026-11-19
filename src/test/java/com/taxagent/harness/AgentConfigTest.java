package com.taxagent.harness;

import com.taxagent.harness.runtime.*;
import com.taxagent.harness.runtime.AgentDirectory.Spec;
import com.taxagent.harness.tool.*;
import com.taxagent.harness.tool.PermissionGuard.Session;
import com.taxagent.harness.tool.ToolContract.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.taxagent.harness.runtime.LlmClient.*;
import static org.assertj.core.api.Assertions.*;
import static com.taxagent.harness.ToolContractTest.fails;

class AgentConfigTest {
    @TempDir Path directory;
    Path db;
    AgentDirectory agents;
    final Session operator = new Session("U001"), viewer = new Session("U002");
    static final List<String> READ_TOOLS = List.of("query_invoices", "query_bank_entries", "check_invoice_purpose", "query_rules", "search_knowledge");
    static final Spec READER = new Spec("只读查询员", "只回答查询与规则问题，不保存任何修改", "回答尽量简短，并注明规则版本。",
            "default", null, READ_TOOLS, List.of("C001"));

    /** Scripted model that records every request it receives. */
    static final class Recorder implements LlmClient {
        final List<Request> requests = new ArrayList<>();
        final Queue<Function<Request, Decision>> script = new ArrayDeque<>();
        @SafeVarargs Recorder(Function<Request, Decision>... steps) { script.addAll(List.of(steps)); }
        static Recorder of(Decision... decisions) {
            var r = new Recorder(); for (var d : decisions) r.script.add(ignored -> d); return r;
        }
        public Decision next(Request request) { requests.add(request); return script.remove().apply(request); }
    }
    @BeforeEach void setup() throws Exception {
        db = directory.resolve("agents.db"); DataInitializer.initialize(db); agents = new AgentDirectory(db);
    }
    long ledger() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement();
             var r = s.executeQuery("SELECT count(*) FROM invoice_operations")) { return r.getLong(1); }
    }
    FinanceAgent run(String agentId, ModelProfiles profiles) {
        return new FinanceAgent(db, operator, "C001", profiles, agents.resolve(operator, "C001", agentId));
    }
    static Call call(String name, String args) { return new Call(UUID.randomUUID().toString(), name, args); }
    static final InvoiceUpdate F002 = new InvoiceUpdate(new InvoiceKey("C001", "F002"), Map.of("purpose", "办公"));

    @Test void defaultAgentIsSeededOnceWithEveryToolAndEnterprise() {
        var all = agents.list(operator, "C001");
        assertThat(all).extracting(AgentDirectory.Agent::id).containsExactly(AgentDirectory.DEFAULT_ID);
        assertThat(all.getFirst().spec().tools()).containsExactlyInAnyOrderElementsOf(AgentDirectory.TOOLS);
        assertThat(all.getFirst().spec().enterprises()).containsExactly("C001", "C002");
        new AgentDirectory(db); // Re-running the migration never adds or rewrites the seed.
        assertThat(agents.history(operator, "C001", AgentDirectory.DEFAULT_ID)).hasSize(1);
    }

    @Test void secondAgentIsCreatedByConfigurationAndVersioned() throws Exception {
        var v1 = agents.save(operator, "C001", "finance-reader", READER);
        assertThat(v1.version()).isEqualTo(1);
        assertThat(v1.enabled()).isTrue();
        var v2 = agents.save(operator, "C001", "finance-reader", new Spec("只读查询员", READER.responsibility(), "更简短。",
                "default", null, List.of("query_rules"), List.of("C001")));
        assertThat(v2.version()).isEqualTo(2);
        assertThat(agents.setEnabled(operator, "C001", "finance-reader", false)).extracting(AgentDirectory.Agent::version, AgentDirectory.Agent::enabled)
                .containsExactly(3, false);
        assertThat(agents.history(operator, "C001", "finance-reader")).extracting(AgentDirectory.Agent::version).containsExactly(3, 2, 1);
        fails(ErrorCode.PERMISSION_DENIED, () -> agents.resolve(operator, "C001", "finance-reader"));
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement()) {
            assertThatThrownBy(() -> s.execute("UPDATE agent_definitions SET enabled=1")).hasMessageContaining("immutable");
            assertThatThrownBy(() -> s.execute("DELETE FROM agent_definitions")).hasMessageContaining("retained");
        }
    }

    @Test void configurationCannotWidenRightsAndIsValidated() {
        // U002 only reads C001: cannot configure agents, and nobody can cover an enterprise they cannot modify.
        fails(ErrorCode.PERMISSION_DENIED, () -> agents.save(viewer, "C001", "finance-reader", READER));
        fails(ErrorCode.PERMISSION_DENIED, () -> agents.setEnabled(viewer, "C001", AgentDirectory.DEFAULT_ID, false));
        fails(ErrorCode.PERMISSION_DENIED, () -> agents.save(operator, "C001", "ghost", new Spec("x", "y", "", "default", null, READ_TOOLS, List.of("C999"))));
        fails(ErrorCode.INVALID_ARGUMENT, () -> agents.save(operator, "C001", "Bad Id", READER));
        fails(ErrorCode.INVALID_ARGUMENT, () -> agents.save(operator, "C001", "tool-x", new Spec("x", "y", "", "default", null, List.of("delete_bank"), List.of("C001"))));
        fails(ErrorCode.INVALID_ARGUMENT, () -> agents.save(operator, "C001", "writer", new Spec("x", "y", "", "default", null, List.of("update_invoice"), List.of("C001"))));
        fails(ErrorCode.INVALID_ARGUMENT, () -> agents.save(operator, "C001", "long-x", new Spec("x", "y", "z".repeat(4001), "default", null, READ_TOOLS, List.of("C001"))));
        fails(ErrorCode.INVALID_ARGUMENT, () -> agents.save(operator, "C001", "prof-x", new Spec("x", "y", "", "bad profile!", null, READ_TOOLS, List.of("C001"))));
        // An agent that does not cover C002 cannot be used there, even by a user who can modify C002.
        agents.save(operator, "C001", "finance-reader", READER);
        fails(ErrorCode.PERMISSION_DENIED, () -> agents.resolve(operator, "C002", "finance-reader"));
        fails(ErrorCode.NOT_FOUND, () -> agents.resolve(operator, "C001", "no-such-agent"));
    }

    @Test void readOnlyAgentNeverGetsTheWriteToolAndCannotBeHandedASave() throws Exception {
        agents.save(operator, "C001", "finance-reader", READER);
        var model = Recorder.of(call("update_invoice", "{}"), new Finish("模型宣称保存成功"));
        var agent = run("finance-reader", ModelProfiles.single(model));
        var task = agent.start("只读", List.of());
        // Approved save through a read-only agent: refused before any model call, whatever the user's own rights.
        fails(ErrorCode.CAPABILITY_NOT_GRANTED, () -> agent.approvedTurn(task.id(), "保存", "op-1", F002, InvoiceWriteService.Intent.SAVE_NOW));
        assertThat(model.requests).isEmpty();
        // A forged write call on an ordinary turn: the tool is not offered and execution is refused.
        var turn = agent.turn(task.id(), "直接保存 F002");
        assertThat(model.requests.getFirst().tools()).extracting(Tool::name).doesNotContain("update_invoice");
        assertThat(turn.result().status()).isEqualTo(AgentRuntime.Status.ERROR);
        assertThat(ledger()).isZero();
    }

    @Test void agentToolListNarrowsWhatTheModelSeesAndCanCall() {
        agents.save(operator, "C001", "rules-only", new Spec("规则顾问", "只解释规则", "", "default", null, List.of("query_rules"), List.of("C001")));
        var model = Recorder.of(call("query_invoices", "{\"enterprise_id\":\"C001\",\"month\":\"2026-09\"}"), new Finish("x"));
        var agent = run("rules-only", ModelProfiles.single(model));
        var turn = agent.turn(agent.start("规则", List.of()).id(), "九月发票合计");
        assertThat(model.requests.getFirst().tools()).extracting(Tool::name).containsExactly("query_rules");
        assertThat(turn.result().errorCode()).isEqualTo("CAPABILITY_NOT_GRANTED");
    }

    @Test void roleAndVersionReachTheModelInputAndNamedProfilesAreUsed() {
        agents.save(operator, "C001", "finance-reader", new Spec(READER.name(), READER.responsibility(), READER.instructions(),
                "SECOND", "qwen-test", READ_TOOLS, List.of("C001")));
        var second = Recorder.of(new Finish("好的"));
        var traces = new ArrayList<AgentRuntime.Trace>();
        ModelProfiles profiles = name -> "SECOND".equals(name) ? second : ModelProfiles.NOT_CONFIGURED;
        var agent = run("finance-reader", profiles);
        var turn = agent.turn(agent.start("问答", List.of()).id(), "你好");
        assertThat(turn.result().status()).isEqualTo(AgentRuntime.Status.FINISHED);
        assertThat(second.requests).hasSize(1);
        assertThat(turn.promptTrace().systemPrompt()).contains("[Agent finance-reader v1 · 只读查询员]",
                "只回答查询与规则问题", "回答尽量简短，并注明规则版本。", "never overrides");
        // A profile that is not configured never falls back to another model.
        agents.save(operator, "C001", "offline", new Spec("x", "y", "", "MISSING", null, READ_TOOLS, List.of("C001")));
        var offline = run("offline", profiles);
        assertThat(offline.turn(offline.start("x", List.of()).id(), "你好").result().errorCode()).isEqualTo("MODEL_NOT_CONFIGURED");
    }

    @Test void changeDuringATurnAppliesFromTheNextTurnOnly() {
        agents.save(operator, "C001", "finance-reader", READER);
        var narrowed = new Spec(READER.name(), READER.responsibility(), "", "default", null, List.of("query_rules"), List.of("C001"));
        var model = new Recorder(
                request -> { agents.save(operator, "C001", "finance-reader", narrowed); // Edited mid-turn.
                             return call("query_invoices", "{\"enterprise_id\":\"C001\",\"month\":\"2026-09\"}"); },
                request -> new Ask("本轮仍按 v1；还要查哪个月？"), // Keeps the task open for a second turn.
                request -> new Finish("下一轮按 v2"));
        var first = run("finance-reader", ModelProfiles.single(model));
        var task = first.start("查询", List.of());
        var turn = first.turn(task.id(), "九月发票");
        assertThat(turn.result().status()).isEqualTo(AgentRuntime.Status.NEEDS_INPUT); // query_invoices still allowed in v1.
        assertThat(turn.result().history().getFirst().resultJson()).contains("\"error\":null");
        assertThat(model.requests.get(1).tools()).extracting(Tool::name).contains("query_invoices");
        var next = run("finance-reader", ModelProfiles.single(model));
        var later = next.turn(task.id(), "再看一下");
        assertThat(model.requests.get(2).tools()).extracting(Tool::name).containsExactly("query_rules");
        assertThat(later.promptTrace().systemPrompt()).contains("[Agent finance-reader v2");
    }
}
