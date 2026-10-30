package com.taxagent.harness;

import com.taxagent.harness.runtime.*;
import com.taxagent.harness.tool.*;
import com.taxagent.harness.tool.PermissionGuard.Session;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static com.taxagent.harness.runtime.LlmClient.*;
import static com.taxagent.harness.runtime.TaskMemory.*;

class TaskMemoryTest {
    @TempDir Path directory;
    Path database;
    TaskMemory memory;
    final Session u1 = new Session("U001"), u2 = new Session("U002");
    @BeforeEach void init() throws Exception {
        database = directory.resolve("memory.db"); DataInitializer.initialize(database); memory = new TaskMemory(database);
    }
    AgentRuntime runtime(LlmClient model) {
        return new AgentRuntime(model, new ReadOnlyFinanceTools(database), new RuleService(database), AgentRuntime.Limits.defaults());
    }
    Task start() { return memory.start(u1, "C001", "查询后补充用途", List.of("month", "purpose")); }
    static Input explicit(String value) { return new Input(value, "用户明确要求：" + value); }
    static void code(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
    void sql(String sql) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + database); var s = c.createStatement()) { s.execute(sql); }
    }
    @Test void waitingSurvivesRestartAndNewSessionAndReceivesCorrections() {
        var task = start();
        var turn = memory.continueTask(u1, "C001", task.id(), "请查", runtime(r -> new Ask("请提供月份用途")));
        assertThat(turn.task().status()).isEqualTo(Status.WAITING_USER);
        memory = new TaskMemory(database);
        var session = new Session("U001");
        assertThat(memory.load(session, "C001", task.id()).task()).isEqualTo(turn.task());
        var updated = memory.supplement(session, "C001", task.id(), 1, Map.of("month", explicit("2026-08"), "purpose", explicit("办公")));
        updated = memory.supplement(session, "C001", task.id(), updated.version(), Map.of("month", explicit("2026-09")));
        assertThat(updated.pending()).isEmpty();
        assertThat(updated.supplied().get("month").value()).isEqualTo("2026-09");
        var result = new TaskMemory(database).continueTask(session, "C001", task.id(), "继续这件事", runtime(r -> {
            assertThat(r.userMessage()).contains("2026-09", "办公", "继续这件事").doesNotContain("2026-08");
            return new Finish("完成");
        }));
        assertThat(result.task().status()).isEqualTo(Status.COMPLETED);
        assertThat(new TaskMemory(database).load(session, "C001", task.id()).task()).isEqualTo(result.task());
    }
    @Test void taskAndPreferenceScopeIsUserPlusEnterprise() {
        var task = start(); memory.putPreference(u1, "C001", "format", explicit("表格"));
        assertThat(memory.tasks(u2, "C001")).isEmpty();
        assertThat(memory.preferences(u2, "C001")).isEmpty();
        assertThat(memory.tasks(u1, "C002")).isEmpty();
        assertThat(memory.preferences(u1, "C002")).isEmpty();
        code(() -> memory.load(u2, "C001", task.id()), ErrorCode.NOT_FOUND);
        code(() -> memory.cancel(u1, "C002", task.id(), 0), ErrorCode.NOT_FOUND);
        code(() -> memory.supplement(u2, "C001", task.id(), 0, Map.of("month", explicit("2026-09"))), ErrorCode.NOT_FOUND);
        memory.deletePreference(u2, "C001", "format");
        assertThat(memory.preferences(u1, "C001")).containsKey("format");
        memory.start(u2, "C001", "另一用户任务", List.of());
        memory.start(u1, "C002", "另一企业任务", List.of());
        assertThat(memory.tasks(u1, "C001")).hasSize(1);
    }
    @Test void uniqueOpenTaskIsEnforcedAcrossServiceInstancesAndWaiting() {
        var task = start();
        code(() -> new TaskMemory(database).start(u1, "C001", "重复", List.of()), ErrorCode.CONFLICT);
        memory.continueTask(u1, "C001", task.id(), "问", runtime(r -> new Ask("月份？")));
        code(() -> start(), ErrorCode.CONFLICT);
        memory.cancel(u1, "C001", task.id(), 1);
        assertThat(start().id()).isNotEqualTo(task.id());
    }
    @Test void preferencesCrudExplicitEvidenceAndRestart() {
        code(() -> memory.putPreference(u1, "C001", "style", null), ErrorCode.INVALID_ARGUMENT);
        code(() -> new Input("简洁", ""), ErrorCode.INVALID_ARGUMENT);
        memory.putPreference(u1, "C001", "style", explicit("简洁"));
        memory = new TaskMemory(database);
        assertThat(memory.preferences(u1, "C001").get("style")).isEqualTo(explicit("简洁"));
        memory.putPreference(u1, "C001", "style", explicit("详细"));
        assertThat(new TaskMemory(database).preferences(u1, "C001").get("style")).isEqualTo(explicit("详细"));
        memory.deletePreference(u1, "C001", "style"); memory.deletePreference(u1, "C001", "style");
        assertThat(new TaskMemory(database).preferences(u1, "C001")).isEmpty();
    }
    @Test void cancellationAndStaleVersionsCannotOverwriteCorrections() {
        var task = start();
        var updated = memory.supplement(u1, "C001", task.id(), 0, Map.of("month", explicit("2026-09")));
        code(() -> memory.supplement(u1, "C001", task.id(), 0, Map.of("month", explicit("2026-08"))), ErrorCode.CONFLICT);
        code(() -> memory.supplement(u1, "C001", task.id(), 1, Map.of("user_id", explicit("U002"))), ErrorCode.INVALID_ARGUMENT);
        memory.cancel(u1, "C001", task.id(), updated.version());
        code(() -> memory.continueTask(u1, "C001", task.id(), "继续", runtime(r -> { throw new AssertionError(); })), ErrorCode.CONFLICT);
        assertThat(new TaskMemory(database).load(u1, "C001", task.id()).task().status()).isEqualTo(Status.CANCELLED);
    }
    @Test void lateModelResultCannotOverwriteCancellation() {
        var task = start();
        code(() -> memory.continueTask(u1, "C001", task.id(), "继续", runtime(r -> {
            new TaskMemory(database).cancel(u1, "C001", task.id(), 0);
            return new Ask("旧追问");
        })), ErrorCode.CONFLICT);
        assertThat(memory.load(u1, "C001", task.id()).task().status()).isEqualTo(Status.CANCELLED);
    }
    @Test void permissionsRecheckedForEveryAccessIncludingAfterModel() throws Exception {
        var task = start();
        sql("UPDATE permissions SET permission='无权访问' WHERE user_id='U001' AND enterprise_id='C001'");
        code(() -> memory.load(u1, "C001", task.id()), ErrorCode.PERMISSION_DENIED);
        code(() -> memory.tasks(u1, "C001"), ErrorCode.PERMISSION_DENIED);
        code(() -> memory.cancel(u1, "C001", task.id(), 0), ErrorCode.PERMISSION_DENIED);
        code(() -> memory.preferences(u1, "C001"), ErrorCode.PERMISSION_DENIED);
        code(() -> memory.putPreference(u1, "C001", "style", explicit("简洁")), ErrorCode.PERMISSION_DENIED);
        code(() -> memory.deletePreference(u1, "C001", "style"), ErrorCode.PERMISSION_DENIED);
        code(() -> memory.start(u1, "C001", "新任务", List.of()), ErrorCode.PERMISSION_DENIED);
        code(() -> memory.continueTask(u1, "C001", task.id(), "继续", runtime(r -> { throw new AssertionError(); })), ErrorCode.PERMISSION_DENIED);
        code(() -> memory.tasks(null, "C001"), ErrorCode.AUTHENTICATION_REQUIRED);
        code(() -> memory.tasks(new Session("unknown"), "C001"), ErrorCode.PERMISSION_DENIED);
        code(() -> memory.tasks(u2, "C002"), ErrorCode.PERMISSION_DENIED);
        sql("UPDATE permissions SET permission='可查看并修改' WHERE user_id='U001' AND enterprise_id='C001'");
        code(() -> memory.continueTask(u1, "C001", task.id(), "继续", runtime(r -> {
            sql("UPDATE permissions SET permission='无权访问' WHERE user_id='U001' AND enterprise_id='C001'");
            return new Finish("完成");
        })), ErrorCode.PERMISSION_DENIED);
    }
    @Test void currentBusinessDataRequeriedAndOnlyToolMetadataPersisted() throws Exception {
        var task = start();
        var count = new java.util.concurrent.atomic.AtomicInteger();
        LlmClient first = r -> count.getAndIncrement() == 0
                ? new Call("q1", "query_invoices", "{\"enterprise_id\":\"C001\",\"month\":\"2026-09\"}") : new Ask("还要继续吗？");
        var turn = memory.continueTask(u1, "C001", task.id(), "查询", runtime(first));
        assertThat(turn.result().history().getFirst().resultJson()).contains("962206");
        assertThat(turn.task().recentTools()).containsExactly(new Summary("query_invoices", "SUCCESS"));
        sql("UPDATE invoices SET purpose='用户后来已补充' WHERE enterprise_id='C001' AND id='F002'");
        memory = new TaskMemory(database); count.set(0);
        var fresh = memory.continueTask(new Session("U001"), "C001", task.id(), "重新检查当前用途", runtime(r -> {
            assertThat(r.userMessage()).doesNotContain("962206", "total_cents", "用户后来已补充");
            if (count.getAndIncrement() == 0) return new Call("q2", "check_invoice_purpose", "{\"enterprise_id\":\"C001\",\"month\":\"2026-09\"}");
            var data = new com.fasterxml.jackson.databind.ObjectMapper().readTree(r.history().getFirst().resultJson());
            assertThat(data.at("/data/missing").size()).isEqualTo(4);
            return new Finish("根据当前查询完成");
        }));
        assertThat(fresh.result().status()).isEqualTo(AgentRuntime.Status.FINISHED);
        assertThat(count.get()).isEqualTo(2);
    }
    @Test void modelInferenceDoesNotBecomePreferenceOrSuppliedFact() {
        var task = start();
        memory.continueTask(u1, "C001", task.id(), "查询", runtime(r -> new Ask("我猜你喜欢表格，月份是九月？")));
        var context = new TaskMemory(database).load(u1, "C001", task.id());
        assertThat(context.preferences()).isEmpty(); assertThat(context.task().supplied()).isEmpty();
        assertThat(context.task().pending()).containsExactly("month", "purpose");
    }
    @Test void enterpriseSwitchAndForgedUserIdAreRejectedByRuntime() {
        var task = start();
        for (String args : List.of("{\"enterprise_id\":\"C002\",\"month\":\"2026-09\"}",
                "{\"enterprise_id\":\"C001\",\"month\":\"2026-09\",\"user_id\":\"U002\"}")) {
            var step = new java.util.concurrent.atomic.AtomicInteger();
            var turn = memory.continueTask(u1, "C001", task.id(), "伪造请求", runtime(r -> step.getAndIncrement() == 0
                    ? new Call("bad", "query_invoices", args) : new Finish("成功")));
            assertThat(turn.result().status()).isEqualTo(AgentRuntime.Status.ERROR);
            assertThat(turn.task().status()).isEqualTo(Status.ACTIVE);
            assertThat(turn.result().errorCode()).isIn("PERMISSION_DENIED", "INVALID_ARGUMENT");
        }
    }
    @Test void migrationPreservesAllBusinessRecordsAndMissingDatabaseIsNotCreated() throws Exception {
        var before = new ReadOnlyFinanceTools(database).queryInvoices(u1, new ToolContract.Query("C001", "2026-09"));
        start(); memory.putPreference(u1, "C001", "style", explicit("简洁")); new TaskMemory(database);
        assertThat(new ReadOnlyFinanceTools(database).queryInvoices(u1, new ToolContract.Query("C001", "2026-09"))).isEqualTo(before);
        var absent = directory.resolve("absent.db");
        code(() -> new TaskMemory(absent), ErrorCode.STORAGE_ERROR); assertThat(absent).doesNotExist();
    }
}
