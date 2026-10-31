package com.taxagent.harness.runtime;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FinanceAgentTimeoutTest {
    @Test void qwenUsesSixtySecondRuntimeLimitAndOtherModelsKeepDefault() {
        var qwen = new AnthropicMessagesClient(
                URI.create("https://dashscope.aliyuncs.com/apps/anthropic"),
                "stub-only", "qwen3.8-flash", AnthropicMessagesClient.DEFAULT_TIMEOUT);
        assertEquals(Duration.ofSeconds(60), FinanceAgent.runtimeLimits(qwen).timeout());
        assertEquals(Duration.ofSeconds(15), FinanceAgent.runtimeLimits(request -> new LlmClient.Finish("done")).timeout());
    }
}
