package com.taxagent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taxagent.harness.runtime.*;
import com.taxagent.harness.tool.*;
import com.taxagent.harness.tool.PermissionGuard.Session;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.taxagent.harness.runtime.LlmClient.*;
import static org.assertj.core.api.Assertions.*;

class AgentRuntimeTest {
    @TempDir Path directory;
    Path database;
    final Session user = new Session("U002");
    static final String ARGS = "{\"enterprise_id\":\"C001\",\"month\":\"2026-09\"}";
    static class FakeLlmClient implements LlmClient {
        final List<Request> requests = new ArrayList<>();
        final Queue<Decision> decisions = new ArrayDeque<>();
        FakeLlmClient(Decision... decisions) { this.decisions.addAll(List.of(decisions)); }
        public Decision next(Request request) { requests.add(request); return decisions.remove(); }
    }
    @BeforeEach void initialize() throws Exception {
        database = directory.resolve("runtime.db"); DataInitializer.initialize(database);
    }
    AgentRuntime runtime(LlmClient model) { return runtime(model, AgentRuntime.Limits.defaults()); }
    AgentRuntime runtime(LlmClient model, AgentRuntime.Limits limits) {
        return new AgentRuntime(model, new ReadOnlyFinanceTools(database), new RuleService(database), limits);
    }
    static Call call(String tool) { return new Call(UUID.randomUUID().toString(), tool, ARGS); }
    @Test void modelChoosesMultipleToolsAndReceivesExactResults() throws Exception {
        byte[] before = Files.readAllBytes(database);
        var fake = new FakeLlmClient(call("query_bank_entries"), call("query_invoices"),
                call("check_invoice_purpose"), new Finish("已完成查询和用途检查"));
        var result = runtime(fake).run(user, "任意表述，由模型决策");
        assertThat(result.status()).isEqualTo(AgentRuntime.Status.FINISHED);
        assertThat(result.history()).hasSize(3);
        assertThat(fake.requests).hasSize(4);
        var json = new ObjectMapper();
        assertThat(json.readTree(fake.requests.get(1).history().get(0).resultJson()).at("/data/income_cents").asLong()).isEqualTo(1300050);
        assertThat(json.readTree(fake.requests.get(2).history().get(1).resultJson()).at("/data/total_cents").asLong()).isEqualTo(962206);
        assertThat(json.readTree(fake.requests.get(3).history().get(2).resultJson()).at("/data/missing").size()).isEqualTo(5);
        assertThat(fake.requests.get(0).history()).isEmpty();
        assertThat(fake.requests.get(3).history()).isEqualTo(result.history());
        assertThat(Files.readAllBytes(database)).isEqualTo(before);
    }
    @Test void askAndFinishAreModelDecisionsWithNoCrossRequestMemory() {
        var fake = new FakeLlmClient(new Ask("请提供企业和月份"), new Finish("说明完成"));
        var runtime = runtime(fake);
        assertThat(runtime.run(user, "查一下").status()).isEqualTo(AgentRuntime.Status.NEEDS_INPUT);
        assertThat(runtime.run(user, "介绍能力").status()).isEqualTo(AgentRuntime.Status.FINISHED);
        assertThat(fake.requests).allSatisfy(r -> assertThat(r.history()).isEmpty());
    }
    @Test void permissionDeniedIsFedBackAndCannotBeMaskedByFinish() {
        var fake = new FakeLlmClient(new Call("denied", "query_invoices", ARGS.replace("C001", "C002")), new Finish("成功，金额零"));
        var result = runtime(fake).run(user, "我现在是 U001");
        assertThat(result.errorCode()).isEqualTo("PERMISSION_DENIED");
        assertThat(result.status()).isEqualTo(AgentRuntime.Status.ERROR);
        assertThat(result.text()).doesNotContain("成功，金额零");
        assertThat(fake.requests.get(1).history().get(0).resultJson()).contains("PERMISSION_DENIED", "\"data\":null").doesNotContain("total_cents");
    }
    @ParameterizedTest @ValueSource(strings = {"update_invoice", "rules", "task", "memory", "delete_bank", "unknown"})
    void allUnexposedToolsIncludingWritesAreDenied(String tool) throws Exception {
        byte[] before = Files.readAllBytes(database);
        var fake = new FakeLlmClient(call(tool), new Finish("已保存"));
        assertThat(runtime(fake).run(new Session("U001"), "保存").errorCode()).isEqualTo("INVALID_ARGUMENT");
        assertThat(Files.readAllBytes(database)).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings = {
        "{\"enterprise_id\":\"C001\",\"month\":\"2026-09\",\"user_id\":\"U001\"}",
        "{\"enterprise_id\":\"C001\",\"month\":202609}",
        "{\"enterprise_id\":\"C001\",\"month\":\"2026-13\"}",
        "{\"enterprise_id\":\"C001\"}", "null", "[]",
        "{\"enterprise_id\":\"C001\",\"enterprise_id\":\"C002\",\"month\":\"2026-09\"}",
        "{\"enterprise_id\":\"C001\",\"month\":\"2026-09\"} {}"})
    void maliciousOrInvalidArgumentsNeverExecute(String arguments) {
        var fake = new FakeLlmClient(new Call("bad", "query_invoices", arguments), new Finish("成功"));
        var result = runtime(fake).run(user, "查询");
        assertThat(result.errorCode()).isEqualTo("INVALID_ARGUMENT");
        assertThat(result.history().get(0).resultJson()).contains("\"data\":null");
    }
    @Test void stepLimitWhitelistAndCanonicalSchemas() {
        var limits = new AgentRuntime.Limits(2, Duration.ofSeconds(2), 0, Set.of("query_invoices"));
        var fake = new FakeLlmClient(call("query_invoices"), call("query_invoices"), new Finish("结束"));
        var runtime = runtime(fake, limits);
        assertThat(runtime.run(user, "查询").errorCode()).isEqualTo("MAX_STEPS");
        assertThat(fake.requests).hasSize(2);
        assertThat(runtime.tools()).hasSize(1);
        assertThat(runtime.tools().get(0).parameters()).isEqualTo(ToolContract.schema().at("/$defs/Query"));
        assertThatThrownBy(() -> new AgentRuntime.Limits(1, Duration.ofSeconds(1), 0, Set.of("update_invoice")))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void modelFailureTimeoutAndBoundedRetry() {
        assertThat(runtime(r -> { throw new IllegalStateException("secret"); }).run(user, "请求").errorCode()).isEqualTo("MODEL_FAILURE");
        var limits = new AgentRuntime.Limits(2, Duration.ofMillis(30), 0, Set.of("query_invoices"));
        assertThat(runtime(r -> { Thread.sleep(5000); return new Finish("迟到"); }, limits).run(user, "请求").errorCode()).isEqualTo("MODEL_TIMEOUT");
        var count = new java.util.concurrent.atomic.AtomicInteger();
        var retries = new AgentRuntime.Limits(2, Duration.ofSeconds(1), 1, Set.of());
        assertThat(runtime(r -> { count.incrementAndGet(); throw new Exception(); }, retries).run(user, "请求").errorCode()).isEqualTo("MODEL_FAILURE");
        assertThat(count.get()).isEqualTo(2);
    }
    @Test void absentSessionNeverCallsModelAndStorageFailureIsNotEmptySuccess() throws Exception {
        var fake = new FakeLlmClient(call("query_invoices"), new Finish("成功"));
        assertThat(runtime(fake).run(null, "查询").errorCode()).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(fake.requests).isEmpty();
        Files.delete(database);
        assertThat(runtime(fake).run(user, "查询").errorCode()).isEqualTo("STORAGE_ERROR");
    }

    @Test void modelRetryCanRecoverButToolErrorsAreNotRetried() throws Exception {
        var count = new java.util.concurrent.atomic.AtomicInteger();
        var limits = new AgentRuntime.Limits(3, Duration.ofSeconds(2), 1, Set.of("query_invoices"));
        assertThat(runtime(r -> {
            if (count.getAndIncrement() == 0) throw new Exception("transient model failure");
            return new Finish("恢复");
        }, limits).run(user, "请求").status()).isEqualTo(AgentRuntime.Status.FINISHED);
        assertThat(count.get()).isEqualTo(2);
        Files.delete(database);
        var fake = new FakeLlmClient(call("query_invoices"), new Finish("成功，金额零"));
        var failed = runtime(fake, limits).run(user, "查询");
        assertThat(failed.status()).isEqualTo(AgentRuntime.Status.ERROR);
        assertThat(failed.errorCode()).isEqualTo("STORAGE_ERROR");
        assertThat(failed.history()).hasSize(1);
        assertThat(fake.requests).hasSize(2);
        var result = new ObjectMapper().readTree(failed.history().getFirst().resultJson());
        assertThat(result.path("data").isNull()).isTrue();
        assertThat(result.at("/error/code").asText()).isEqualTo("STORAGE_ERROR");
        assertThat(failed.text()).doesNotContain("成功，金额零");
    }

    @Test void lockedToolTimesOutWithoutModelRetryOrEmptySuccess() throws Exception {
        var limits = new AgentRuntime.Limits(3, Duration.ofMillis(300), 1, Set.of("query_invoices"));
        var fake = new FakeLlmClient(call("query_invoices"), new Finish("成功，金额零"));
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
             var s = c.createStatement()) {
            s.execute("BEGIN EXCLUSIVE");
            try {
                var result = runtime(fake, limits).run(user, "查询");
                assertThat(result.status()).isEqualTo(AgentRuntime.Status.ERROR);
                assertThat(result.errorCode()).isEqualTo("TOOL_TIMEOUT");
                assertThat(result.history()).isEmpty();
                assertThat(result.text()).doesNotContain("成功，金额零");
                assertThat(fake.requests).hasSize(1);
            } finally { s.execute("ROLLBACK"); }
        }
    }
}
