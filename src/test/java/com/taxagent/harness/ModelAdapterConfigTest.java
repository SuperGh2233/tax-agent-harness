package com.taxagent.harness;

import com.taxagent.harness.runtime.AnthropicMessagesClient;
import com.taxagent.harness.runtime.OpenAiCompatibleClient;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ModelAdapterConfigTest {
    @Test void modelNamesComeFromConfigurationNotHardcodedWhitelists() {
        assertThatCode(() -> new AnthropicMessagesClient(URI.create("https://example.com/anthropic"),
                "key", "any-anthropic-model", Duration.ofSeconds(1))).doesNotThrowAnyException();
        assertThatCode(() -> new OpenAiCompatibleClient(URI.create("https://example.com/v1/responses"),
                "key", "any-responses-model", Duration.ofSeconds(1))).doesNotThrowAnyException();
        assertThatCode(() -> new OpenAiCompatibleClient(URI.create("https://example.com/v1/chat/completions"),
                "key", "any-chat-model", Duration.ofSeconds(1))).doesNotThrowAnyException();
    }

    @Test void baseUrlIsNormalizedToChatCompletionsOnce() {
        var base = new OpenAiCompatibleClient(URI.create("https://qianfan.baidubce.com/v2/tokenplan/personal"),
                "key", "deepseek-v4.1-flash-1017", Duration.ofSeconds(60));
        assertThat(base.endpointUri()).hasToString("https://qianfan.baidubce.com/v2/tokenplan/personal/chat/completions");
        var full = new OpenAiCompatibleClient(URI.create("https://qianfan.baidubce.com/v2/tokenplan/personal/chat/completions"),
                "key", "deepseek-v4.1-flash-1017", Duration.ofSeconds(60));
        assertThat(full.endpointUri()).hasToString("https://qianfan.baidubce.com/v2/tokenplan/personal/chat/completions");
        var responses = new OpenAiCompatibleClient(URI.create("https://example.com/v1/responses"),
                "key", "any-responses-model", Duration.ofSeconds(60));
        assertThat(responses.endpointUri()).hasToString("https://example.com/v1/responses");
    }

    @Test void blankModelStillFailsReadably() {
        assertThatThrownBy(() -> new AnthropicMessagesClient(URI.create("https://example.com/anthropic"),
                "key", " ", Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }
}