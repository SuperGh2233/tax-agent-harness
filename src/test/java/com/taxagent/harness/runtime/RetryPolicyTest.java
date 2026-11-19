package com.taxagent.harness.runtime;

import com.taxagent.harness.DataInitializer;
import com.taxagent.harness.tool.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** T36 retry policy: a read gets one more attempt after a timeout or storage error; nothing else is retried. */
class RetryPolicyTest {
    @TempDir Path directory;
    AgentRuntime runtime;
    ExecutorService worker;
    @BeforeEach void init() throws Exception {
        var db = directory.resolve("retry.db"); DataInitializer.initialize(db);
        runtime = new AgentRuntime(r -> new LlmClient.Finish("x"), new ReadOnlyFinanceTools(db), new RuleService(db),
                new AgentRuntime.Limits(2, Duration.ofMillis(300), 0, Set.of("query_invoices")));
        worker = Executors.newSingleThreadExecutor();
    }
    @AfterEach void stop() { worker.shutdownNow(); }

    @Test void storageErrorIsRetriedOnceThenSucceeds() throws Exception {
        var attempts = new AtomicInteger();
        assertThat(runtime.readWithRetry(worker, () -> {
            if (attempts.getAndIncrement() == 0) throw new DomainException(ErrorCode.STORAGE_ERROR, "busy");
            return "rows";
        })).isEqualTo("rows");
        assertThat(attempts.get()).isEqualTo(2);
    }
    @Test void timeoutIsRetriedOnceThenSucceeds() throws Exception {
        var attempts = new AtomicInteger();
        assertThat(runtime.readWithRetry(worker, () -> {
            if (attempts.getAndIncrement() == 0) Thread.sleep(5_000);
            return "rows";
        })).isEqualTo("rows");
        assertThat(attempts.get()).isEqualTo(2);
    }
    @Test void aSecondFailureIsFinal() {
        var attempts = new AtomicInteger();
        assertThatThrownBy(() -> runtime.readWithRetry(worker, () -> {
            attempts.incrementAndGet(); throw new DomainException(ErrorCode.STORAGE_ERROR, "still busy");
        })).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.STORAGE_ERROR));
        assertThat(attempts.get()).isEqualTo(2);
    }
    @Test void businessRefusalsAreNeverRetried() {
        for (var code : List.of(ErrorCode.PERMISSION_DENIED, ErrorCode.INVALID_ARGUMENT, ErrorCode.UNSUPPORTED_SCOPE, ErrorCode.NOT_FOUND)) {
            var attempts = new AtomicInteger();
            assertThatThrownBy(() -> runtime.readWithRetry(worker, () -> {
                attempts.incrementAndGet(); throw new DomainException(code, "refused");
            })).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(code));
            assertThat(attempts.get()).as(code.name()).isEqualTo(1);
        }
    }
    @Test void honestReplyNamesTheRealWriteState() {
        var write = new LlmClient.Call("w", "update_invoice", "{}");
        var read = new LlmClient.Call("r", "query_invoices", "{}");
        var verified = new LlmClient.Exchange(write, "{\"data\":{\"verified\":true},\"error\":null}");
        var refused = new LlmClient.Exchange(write, "{\"data\":null,\"error\":{\"code\":\"PERMISSION_DENIED\",\"message\":\"x\"}}");
        var unknown = new LlmClient.Exchange(write, "{\"data\":null,\"error\":{\"code\":\"STORAGE_ERROR\",\"message\":\"x\"}}");
        var rows = new LlmClient.Exchange(read, "{\"data\":{\"rows\":[]},\"error\":null}");
        assertThat(AgentRuntime.honest("MODEL_TIMEOUT", List.of(rows, verified), true)).contains("verified=true", "不会重做", "1 次只读查询");
        assertThat(AgentRuntime.honest("PERMISSION_DENIED", List.of(refused), true)).contains("被拒绝", "没有被修改");
        assertThat(AgentRuntime.honest("STORAGE_ERROR", List.of(unknown), true)).contains("结果未知", "不要重复保存", "核验");
        assertThat(AgentRuntime.honest("TOOL_TIMEOUT", List.of(), true)).contains("结果未知");
        assertThat(AgentRuntime.honest("MODEL_RATE_LIMITED", List.of(), false)).contains("没有修改任何发票", "不会自动重发");
    }
}
