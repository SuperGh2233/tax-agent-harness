package com.taxagent.harness;

import com.taxagent.harness.runtime.*;
import com.taxagent.harness.tool.*;
import com.taxagent.harness.tool.PermissionGuard.Session;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.taxagent.harness.FinanceAgentIntegrationTest.*;
import static com.taxagent.harness.runtime.LlmClient.*;
import static com.taxagent.harness.tool.InvoiceWriteService.Intent.SAVE_NOW;
import static org.assertj.core.api.Assertions.*;

/**
 * T36 fault injection (model timeout, repeated errors, lost response, process restart): completed work is never
 * redone, unconfirmed work never runs, and the reply states the real data state.
 */
class FaultRecoveryTest {
    @TempDir Path directory;
    Path db;
    final Session user = new Session("U001");
    // The write service creates the ledger table ledger() counts; tests that skip FinanceAgent would lack it.
    @BeforeEach void init() throws Exception {
        db = directory.resolve("faults.db"); DataInitializer.initialize(db); new InvoiceWriteService(db);
    }
    FinanceAgent agent(LlmClient model) { return new FinanceAgent(db, user, "C001", model); }
    long ledger() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement();
             var r = s.executeQuery("SELECT count(*) FROM invoice_operations")) { return r.getLong(1); }
    }
    String purpose() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement();
             var r = s.executeQuery("SELECT purpose FROM invoices WHERE enterprise_id='C001' AND id='F002'")) { return r.getString(1); }
    }
    void sql(String sql) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement()) { s.execute(sql); }
    }

    @Test void modelTimeoutLeavesTheTaskOpenAndSaysNothingChanged() throws Exception {
        var memory = new TaskMemory(db);
        var task = memory.start(user, "C001", "查询九月发票", List.of());
        var runtime = new AgentRuntime(r -> { Thread.sleep(5_000); return new Finish("迟到的成功"); },
                new ReadOnlyFinanceTools(db), new RuleService(db),
                new AgentRuntime.Limits(4, Duration.ofMillis(200), 0, Set.of("query_invoices")));
        var turn = memory.continueTask(user, "C001", task.id(), "查询九月", runtime);
        assertThat(turn.result().errorCode()).isEqualTo("MODEL_TIMEOUT");
        assertThat(turn.task().status()).isEqualTo(TaskMemory.Status.ACTIVE);
        assertThat(turn.result().text()).contains("没有修改任何发票", "不会自动重发").doesNotContain("迟到的成功");
        assertThat(ledger()).isZero();
    }

    @Test void repeatedToolErrorsTripTheBreakerInsteadOfSpendingEveryStep() throws Exception {
        var calls = new ArrayList<Decision>();
        for (int i = 0; i < 6; i++) calls.add(query("query_invoices", "C002", "2026-09")); // another enterprise each time
        calls.add(new Finish("查询完成"));
        var model = new FakeLlmClient(calls.toArray(Decision[]::new));
        var agent = agent(model);
        var turn = agent.turn(agent.start("查询发票", List.of()).id(), "查九月发票");
        assertThat(turn.result().errorCode()).isEqualTo("TOOL_ERRORS_REPEATED");
        assertThat(turn.result().history()).hasSize(3);
        assertThat(model.requests).hasSize(3);
        assertThat(turn.result().text()).contains("连续 3 次", "没有修改任何发票").doesNotContain("查询完成");
        assertThat(turn.task().status()).isEqualTo(TaskMemory.Status.ACTIVE);
    }

    @Test void aSuccessBetweenErrorsResetsTheBreaker() throws Exception {
        var bad = (java.util.function.Supplier<Call>) () -> query("query_invoices", "C002", "2026-09");
        var model = new FakeLlmClient(bad.get(), bad.get(), query("query_invoices", "C001", "2026-09"), bad.get(), bad.get(),
                new Finish("已说明"));
        var agent = agent(model);
        var turn = agent.turn(agent.start("查询发票", List.of()).id(), "查九月发票");
        // Not the breaker: the turn reaches the model's answer, which still fails closed on the earlier tool error.
        assertThat(turn.result().errorCode()).isEqualTo("PERMISSION_DENIED");
        assertThat(turn.result().history()).hasSize(5);
    }

    @Test void unknownWriteOutcomeStopsAtOnceAndIsNeverRetried() throws Exception {
        var update = update("C001");
        var model = new FakeLlmClient(write(update), write(update), new Finish("模型宣称保存成功"));
        var agent = agent(model);
        var task = agent.start("保存 F002", List.of());
        sql("CREATE TRIGGER fail_write BEFORE UPDATE ON invoices BEGIN SELECT RAISE(ABORT,'disk went away'); END");
        var turn = agent.approvedTurn(task.id(), "现在保存", "unknown-op", update, SAVE_NOW);
        assertThat(turn.result().errorCode()).isEqualTo("STORAGE_ERROR");
        // The model is not asked again after the failed write, so it cannot retry or claim success.
        assertThat(model.requests).hasSize(1);
        assertThat(turn.result().history()).hasSize(1);
        assertThat(turn.result().text()).contains("结果未知", "不要重复保存", "核验").doesNotContain("模型宣称保存成功");
        assertThat(turn.task().status()).isEqualTo(TaskMemory.Status.ACTIVE);
        assertThat(agent.recover(task.id(), "unknown-op", update)).isEmpty();
        assertThat(ledger()).isZero();
    }

    @Test void lostResponseAndRestartNeitherRedoTheSaveNorLoseIt() throws Exception {
        var update = update("C001");
        var first = agent(new FakeLlmClient(write(update), new Finish("已保存")));
        // F004 stays pending, so the task is still open when the same approval is sent again.
        var task = first.start("保存 F002 并补 F004", List.of("F004.purpose"));
        first.approvedTurn(task.id(), "现在保存", "lost-op", update, SAVE_NOW); // The client never sees this reply.
        assertThat(ledger()).isEqualTo(1);
        sql("CREATE TRIGGER no_second_update BEFORE UPDATE ON invoices BEGIN SELECT RAISE(ABORT,'must not replay'); END");
        // Process restart: every service rebuilt from the database.
        var restarted = agent(new FakeLlmClient(write(update), new Finish("核对已有保存")));
        assertThat(restarted.recover(task.id(), "lost-op", update)).get().extracting(ToolContract.UpdateResult::verified).isEqualTo(true);
        // Resending the same approval reuses the ledger entry instead of writing again.
        var again = restarted.approvedTurn(task.id(), "现在保存", "lost-op", update, SAVE_NOW);
        assertThat(data(again, 0).path("verified").asBoolean()).isTrue();
        assertThat(again.task().status()).isEqualTo(TaskMemory.Status.WAITING_USER);
        assertThat(ledger()).isEqualTo(1);
        assertThat(purpose()).isEqualTo("办公室实际采购");
    }

    @Test void anApprovalThatNeverRanIsGoneAfterRestart() throws Exception {
        var update = update("C001");
        var task = agent(new FakeLlmClient()).start("保存 F002", List.of());
        // An approval was issued, then the process died before the turn ran: approvals live only in memory.
        InvoiceWriteService.authorizeExplicitRequest(user, task.id(), "never-ran", update, SAVE_NOW);
        var steps = new AtomicInteger();
        var restarted = agent(r -> steps.getAndIncrement() == 0 ? write(update) : new Finish("已保存"));
        var turn = restarted.turn(task.id(), "继续刚才的保存");
        assertThat(turn.result().status()).isEqualTo(AgentRuntime.Status.ERROR);
        assertThat(turn.result().errorCode()).isEqualTo("CONFIRMATION_REQUIRED");
        assertThat(turn.result().text()).contains("没有修改任何发票");
        assertThat(ledger()).isZero();
        assertThat(purpose()).isEmpty();
    }
}
