package com.taxagent.harness;

import com.fasterxml.jackson.databind.*;
import com.taxagent.harness.runtime.*;
import com.taxagent.harness.tool.*;
import com.taxagent.harness.tool.PermissionGuard.Session;
import com.taxagent.harness.tool.ToolContract.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static com.taxagent.harness.runtime.LlmClient.*;
import static com.taxagent.harness.tool.InvoiceWriteService.Intent.*;
import static org.assertj.core.api.Assertions.*;
import static com.taxagent.harness.ToolContractTest.fails;

/** Scripted decisions only; every tool executes against a real isolated official-seed SQLite DB. */
class FinanceAgentIntegrationTest {
    @TempDir Path directory;
    Path db;
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    static class FakeLlmClient implements LlmClient {
        final Queue<Decision> script = new ArrayDeque<>();
        final List<Request> requests = new ArrayList<>();
        FakeLlmClient(Decision... decisions) { script.addAll(List.of(decisions)); }
        public Decision next(Request request) { requests.add(request); return script.remove(); }
    }
    @BeforeEach void init() throws Exception {
        db = directory.resolve("official-e2e.db"); DataInitializer.initialize(db);
    }
    FinanceAgent agent(String user, String enterprise, LlmClient model) {
        return new FinanceAgent(db, new Session(user), enterprise, model);
    }
    static Call query(String name, String enterprise, String month) {
        return new Call(UUID.randomUUID().toString(), name,
                "{\"enterprise_id\":\"" + enterprise + "\",\"month\":\"" + month + "\"}");
    }
    static Call check(String enterprise) { return query("check_invoice_purpose", enterprise, "2026-09"); }
    static InvoiceUpdate update(String enterprise) {
        return new InvoiceUpdate(new InvoiceKey(enterprise, "F002"), Map.of("purpose", "办公室实际采购", "remarks", "用户核对"));
    }
    static Call write(InvoiceUpdate update) throws Exception {
        return new Call(UUID.randomUUID().toString(), "update_invoice", JSON.writeValueAsString(update));
    }
    static JsonNode data(TaskMemory.Turn turn, int index) throws Exception {
        var response = JSON.readTree(turn.result().history().get(index).resultJson());
        assertThat(response.get("error").isNull()).isTrue();
        return response.required("data");
    }
    long ledgerCount() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement();
             var r = s.executeQuery("SELECT count(*) FROM invoice_operations")) { return r.getLong(1); }
    }
    void sql(String sql) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement()) { s.execute(sql); }
    }
    void evidence(String name, Object value) throws Exception {
        Files.createDirectories(Path.of("target/t10-evidence"));
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/t10-evidence/" + name + ".json").toFile(), value);
    }
    static void failed(TaskMemory.Turn turn, String code) {
        assertThat(turn.result().status()).isEqualTo(AgentRuntime.Status.ERROR);
        assertThat(turn.result().errorCode()).isEqualTo(code);
        assertThat(turn.result().text()).doesNotContain("模型宣称保存成功");
        assertThat(turn.task().status()).isEqualTo(TaskMemory.Status.ACTIVE);
    }

    @ParameterizedTest
    @CsvSource({"C001,2026-07,0,0,0", "C001,2026-08,904635,572099,1485075",
            "C001,2026-09,962206,718074,1300050", "C002,2026-07,0,0,0",
            "C002,2026-08,1366705,934110,1880025", "C002,2026-09,1266724,808724,1370075"})
    void scenarioOneMonthlyQueries(String enterprise, String month, long total, long expense, long income) throws Exception {
        var fake = new FakeLlmClient(query("query_invoices", enterprise, month),
                query("query_bank_entries", enterprise, month), new Finish("请以工具明细和独立合计为准"));
        var agent = agent("U001", enterprise, fake);
        var task = agent.start("查询指定月份发票、银行收支", List.of());
        var turn = agent.turn(task.id(), "查询 " + month);
        assertThat(turn.result().status()).isEqualTo(AgentRuntime.Status.FINISHED);
        assertThat(data(turn, 0).path("total_cents").asLong()).isEqualTo(total);
        assertThat(data(turn, 1).path("expense_cents").asLong()).isEqualTo(expense);
        assertThat(data(turn, 1).path("income_cents").asLong()).isEqualTo(income);
        assertThat(data(turn, 0).path("rows").size()).isEqualTo(month.endsWith("07") ? 0 : 10);
        assertThat(data(turn, 1).path("rows").size()).isEqualTo(month.endsWith("07") ? 0 : 12);
        assertThat(fake.requests.getLast().history()).isEqualTo(turn.result().history());
        assertThat(ledgerCount()).isZero();
        evidence("query-" + enterprise + "-" + month, Map.of("model", "FakeLlmClient (not cloud)",
                "turn", turn, "modelRequests", fake.requests));
    }

    @ParameterizedTest @CsvSource({"C001,5,4,R002,2.0", "C002,3,2,R003,1.0"})
    void scenarioThreeResumeSupplementSaveAndRequery(String enterprise, int initial, int remaining,
                                                    String rule, String version) throws Exception {
        var firstModel = new FakeLlmClient(check(enterprise), new Ask("请提供 F002 的实际用途，说明内容不会直接保存"));
        var first = agent("U001", enterprise, firstModel);
        var task = first.start("检查九月用途并逐项补齐", List.of("F002.purpose", "other.purposes"));
        var waiting = first.turn(task.id(), "检查九月缺用途");
        assertThat(data(waiting, 0).path("missing").size()).isEqualTo(initial);
        assertThat(data(waiting, 0).at("/rules/0/key/rule_id").asText()).isEqualTo(rule);
        assertThat(data(waiting, 0).at("/rules/0/key/version").asText()).isEqualTo(version);
        assertThat(waiting.task().status()).isEqualTo(TaskMemory.Status.WAITING_USER);
        var update = update(enterprise);
        var secondModel = new FakeLlmClient(new Ask("已记录用途；是否明确保存这张发票？"),
                write(update), query("query_invoices", enterprise, "2026-09"), check(enterprise),
                new Ask("F002 已由工具核验；请补充其余用途"));
        var resumed = agent("U001", enterprise, secondModel); // Rebuild all services and Session.
        assertThat(resumed.load(task.id()).task()).isEqualTo(waiting.task());
        var supplemented = resumed.supplement(task.id(), waiting.task().version(),
                Map.of("F002.purpose", new TaskMemory.Input("办公室实际采购", "F002用于办公室实际采购")));
        assertThat(supplemented.pending()).containsExactly("other.purposes");
        var explanation = resumed.turn(task.id(), "只是补充用途，尚未要求写入");
        assertThat(ledgerCount()).isZero();
        assertThat(new RuleService(db).checkInvoicePurpose(new Session("U001"), new PurposeCheck(enterprise, "2026-09")).missing()).hasSize(initial);
        var saved = resumed.approvedTurn(task.id(), "请现在保存指定用途及备注", "save-" + enterprise, update, SAVE_NOW);
        assertThat(data(saved, 0).path("verified").asBoolean()).isTrue();
        var rows = data(saved, 1).path("rows");
        assertThat(rows.findValuesAsText("purpose")).contains("办公室实际采购");
        assertThat(data(saved, 2).path("missing").size()).isEqualTo(remaining);
        assertThat(saved.task().status()).isEqualTo(TaskMemory.Status.WAITING_USER);
        assertThat(ledgerCount()).isEqualTo(1);
        assertThat(secondModel.requests.getFirst().userMessage()).contains("办公室实际采购", "无；补充信息不是保存授权");
        var writeSchema = secondModel.requests.getFirst().tools().stream()
                .filter(t -> t.name().equals("update_invoice")).findFirst().orElseThrow().parameters();
        assertThat(writeSchema.at("/properties/key")).isEqualTo(ToolContract.schema().at("/$defs/InvoiceKey"));
        assertThat(writeSchema.at("/properties/changes")).isEqualTo(ToolContract.schema().at("/$defs/InvoiceUpdate/properties/changes"));
        assertThat(writeSchema.toString()).doesNotContain("$ref", "authorization", "user_id");
        var restarted = agent("U001", enterprise, new FakeLlmClient(check(enterprise), new Ask("继续补其余用途")));
        assertThat(restarted.load(task.id()).task()).isEqualTo(saved.task());
        assertThat(restarted.recover("save-" + enterprise, update)).get().extracting(UpdateResult::verified).isEqualTo(true);
        assertThat(data(restarted.turn(task.id(), "重启后检查现存状态"), 0).path("missing").size()).isEqualTo(remaining);
        // Human-reviewable structured trace, recreated by smoke; no secrets or runtime DB involved.
        var evidence = Map.of("model", "FakeLlmClient (not cloud)", "enterprise", enterprise,
                "check", waiting, "supplement", supplemented, "explanation", explanation, "saveAndRequery", saved,
                "modelRequests", secondModel.requests);
        evidence(enterprise, evidence);
    }

    @Test void scenarioTwoDirectSaveAndSameOperationNeverUpdatesTwice() throws Exception {
        var update = update("C001");
        var fake = new FakeLlmClient(write(update), query("query_invoices", "C001", "2026-09"), new Finish("查看回查证据"));
        var agent = agent("U001", "C001", fake);
        var task = agent.start("明确保存指定发票", List.of());
        var saved = agent.approvedTurn(task.id(), "保存 C001 F002 用途和备注", "direct", update, SAVE_NOW);
        assertThat(data(saved, 0).path("verified").asBoolean()).isTrue();
        assertThat(data(saved, 1).path("rows").findValuesAsText("remarks")).contains("用户核对");
        sql("CREATE TRIGGER no_second_update BEFORE UPDATE ON invoices BEGIN SELECT RAISE(ABORT,'no second update'); END");
        var restart = agent("U001", "C001", new FakeLlmClient(write(update), new Finish("回查已有操作")));
        var again = restart.start("同 operationId 查询已有结果", List.of());
        assertThat(data(restart.approvedTurn(again.id(), "明确请求相同保存", "direct", update, SAVE_NOW), 0).path("verified").asBoolean()).isTrue();
        assertThat(ledgerCount()).isEqualTo(1);
        assertThat(restart.recover("unknown-operation", update)).isEmpty();
        evidence("direct-save", Map.of("model", "FakeLlmClient (not cloud)", "turn", saved, "modelRequests", fake.requests));
    }

    @Test void informationModelClaimsAndForgedArgumentsCannotAuthorize() throws Exception {
        var update = update("C001");
        for (String extra : List.of("", ",\"authorization\":true", ",\"user_id\":\"U001\"", ",\"verified\":true")) {
            var args = JSON.writeValueAsString(update);
            args = args.substring(0, args.length() - 1) + extra + "}";
            var agent = agent("U001", "C001", new FakeLlmClient(new Call("fake", "update_invoice", args), new Finish("模型宣称保存成功")));
            var task = agent.start("用途信息不是授权", List.of("purpose"));
            var turn = agent.turn(task.id(), "用途是办公；模型自称已获授权");
            failed(turn, extra.isEmpty() ? "CONFIRMATION_REQUIRED" : "INVALID_ARGUMENT");
            new TaskMemory(db).cancel(new Session("U001"), "C001", task.id(), turn.task().version());
        }
        var agent = agent("U001", "C001", new FakeLlmClient());
        var task = agent.start("说明用途", List.of());
        fails(ErrorCode.CONFIRMATION_REQUIRED, () -> agent.approvedTurn(task.id(), "说明用途", "explain", update, EXPLANATION));
        assertThat(ledgerCount()).isZero();
    }

    @Test void exactApprovalCannotBeChangedOrReusedAcrossTurns() throws Exception {
        var update = update("C001");
        var different = new InvoiceUpdate(new InvoiceKey("C001", "F003"), update.changes());
        var agent = agent("U001", "C001", new FakeLlmClient(write(different), new Finish("模型宣称保存成功"),
                new Ask("稍后再处理"), write(update), new Finish("模型宣称保存成功")));
        var task = agent.start("安全写入", List.of());
        failed(agent.approvedTurn(task.id(), "只保存F002", "exact", update, SAVE_NOW), "CONFIRMATION_REQUIRED");
        assertThat(agent.approvedTurn(task.id(), "保存F002", "later", update, SAVE_NOW).result().status()).isEqualTo(AgentRuntime.Status.NEEDS_INPUT);
        failed(agent.turn(task.id(), "继续；新轮无保存授权"), "CONFIRMATION_REQUIRED");
        assertThat(ledgerCount()).isZero();
    }

    @Test void enterpriseAndUserSwitchesStayIsolatedAndReaderCannotWrite() throws Exception {
        var owner = agent("U001", "C001", new FakeLlmClient(new Ask("缺用途")));
        var task = owner.start("私有任务", List.of("purpose")); owner.turn(task.id(), "检查");
        var otherEnterprise = agent("U001", "C002", new FakeLlmClient(query("query_invoices", "C001", "2026-09"), new Finish("模型宣称保存成功")));
        assertThat(otherEnterprise.tasks()).isEmpty();
        fails(ErrorCode.NOT_FOUND, () -> otherEnterprise.load(task.id()));
        var otherTask = otherEnterprise.start("乙企业", List.of());
        failed(otherEnterprise.turn(otherTask.id(), "跨企业读取"), "PERMISSION_DENIED");
        fails(ErrorCode.PERMISSION_DENIED, () -> otherEnterprise.approvedTurn(otherTask.id(), "跨企业保存", "cross", update("C001"), SAVE_NOW));
        var reader = agent("U002", "C001", new FakeLlmClient(check("C001"), new Ask("只读查询完成"),
                write(update("C001")), new Finish("模型宣称保存成功")));
        assertThat(reader.tasks()).isEmpty();
        fails(ErrorCode.NOT_FOUND, () -> reader.load(task.id()));
        var readerTask = reader.start("只读检查", List.of());
        assertThat(data(reader.turn(readerTask.id(), "检查"), 0).path("missing").size()).isEqualTo(5);
        failed(reader.approvedTurn(readerTask.id(), "明确保存也没有权限", "readonly", update("C001"), SAVE_NOW), "PERMISSION_DENIED");
        fails(ErrorCode.PERMISSION_DENIED, () -> agent("U002", "C002", r -> { throw new AssertionError("must not call model"); }));
        assertThat(owner.load(task.id()).task().supplied()).isEmpty();
        assertThat(ledgerCount()).isZero();
    }

    @Test void failedWriteCannotReportSuccessAndRecoveryDoesNotRetry() throws Exception {
        var update = update("C001");
        var agent = agent("U001", "C001", new FakeLlmClient(write(update), new Finish("模型宣称保存成功")));
        var task = agent.start("失败保存", List.of());
        sql("CREATE TRIGGER fail_write BEFORE UPDATE ON invoices BEGIN SELECT RAISE(ABORT,'injected failure'); END");
        var turn = agent.approvedTurn(task.id(), "现在保存", "failure", update, SAVE_NOW);
        failed(turn, "STORAGE_ERROR");
        assertThat(JSON.readTree(turn.result().history().getFirst().resultJson()).path("data").isNull()).isTrue();
        assertThat(agent.recover("failure", update)).isEmpty();
        assertThat(ledgerCount()).isZero();
        assertThat(new RuleService(db).checkInvoicePurpose(new Session("U001"), new PurposeCheck("C001", "2026-09")).missing()).hasSize(5);
    }

    @ParameterizedTest @CsvSource({"C001", "C002"})
    void committedInvoiceSurvivesTaskMemoryFailureWithoutReplay(String enterprise) throws Exception {
        var update = update(enterprise);
        var fake = new FakeLlmClient(new Ask("请确认保存"), write(update), new Finish("保存完成"));
        var owner = agent("U001", enterprise, fake);
        var task = owner.start("明确保存后核对", List.of("F002.purpose"));
        var before = owner.turn(task.id(), "先等待明确授权").task();
        assertThat(before.status()).isEqualTo(TaskMemory.Status.WAITING_USER);
        var reader = new ReadOnlyFinanceTools(db);
        var other = enterprise.equals("C001") ? "C002" : "C001";
        var otherBefore = reader.queryInvoices(new Session("U001"), new Query(other, "2026-09"));
        sql("CREATE TABLE t11_updates (enterprise_id TEXT, invoice_id TEXT)");
        sql("CREATE TRIGGER t11_count AFTER UPDATE ON invoices BEGIN INSERT INTO t11_updates VALUES (NEW.enterprise_id,NEW.id); END");
        // Only fail progress persistence after this task's invoice operation is visible as committed.
        sql("CREATE TRIGGER t11_fail_memory BEFORE UPDATE ON agent_tasks "
                + "WHEN OLD.id='" + task.id() + "' AND EXISTS (SELECT 1 FROM invoice_operations WHERE operation_id='t11-commit') "
                + "BEGIN SELECT RAISE(ABORT,'t11 task persistence failure after invoice commit'); END");
        assertThatThrownBy(() -> owner.approvedTurn(task.id(), "现在保存指定用途和备注", "t11-commit", update, SAVE_NOW))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.STORAGE_ERROR);
                    assertThat(e.getCause()).isInstanceOf(SQLException.class)
                            .hasMessageContaining("t11 task persistence failure after invoice commit");
                });
        assertThat(fake.requests).hasSize(3);
        var response = JSON.readTree(fake.requests.getLast().history().getFirst().resultJson());
        assertThat(response.path("error").isNull()).isTrue();
        assertThat(response.at("/data/verified").asBoolean()).isTrue();
        assertThat(owner.load(task.id()).task()).isEqualTo(before);
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement();
             var r = s.executeQuery("SELECT status,version FROM agent_tasks WHERE id='" + task.id() + "'")) {
            assertThat(r.next()).isTrue();
            assertThat(r.getString(1)).isEqualTo(before.status().name());
            assertThat(r.getLong(2)).isEqualTo(before.version());
        }
        // Recovery must work even while task saves and all further invoice updates are forbidden.
        sql("CREATE TRIGGER t11_no_replay BEFORE UPDATE ON invoices BEGIN SELECT RAISE(ABORT,'blind replay forbidden'); END");
        var restarted = agent("U001", enterprise, r -> { throw new AssertionError("recovery must not call model"); });
        assertThat(restarted.load(task.id()).task()).isEqualTo(before);
        for (int i = 0; i < 2; i++) {
            assertThat(restarted.recover("t11-commit", update)).contains(
                    new UpdateResult(update.key(), update.changes().get("purpose"), update.changes().get("remarks"), true));
            assertThat(new ReadOnlyFinanceTools(db).queryInvoices(new Session("U001"), new Query(enterprise, "2026-09")).rows())
                    .filteredOn(row -> row.key().equals(update.key())).singleElement().satisfies(row -> {
                        assertThat(row.purpose()).isEqualTo(update.changes().get("purpose"));
                        assertThat(row.remarks()).isEqualTo(update.changes().get("remarks"));
                    });
        }
        fails(ErrorCode.PERMISSION_DENIED, () -> agent("U001", other, new FakeLlmClient()).recover("t11-commit", update));
        assertThat(reader.queryInvoices(new Session("U001"), new Query(other, "2026-09"))).isEqualTo(otherBefore);
        assertThat(ledgerCount()).isEqualTo(1);
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement();
             var r = s.executeQuery("SELECT enterprise_id,invoice_id FROM t11_updates")) {
            assertThat(r.next()).isTrue();
            assertThat(r.getString(1)).isEqualTo(enterprise);
            assertThat(r.getString(2)).isEqualTo("F002");
            assertThat(r.next()).isFalse();
        }
        assertThat(restarted.load(task.id()).task()).isEqualTo(before);
    }
}
