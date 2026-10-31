package com.taxagent.harness.demo;

import com.taxagent.harness.runtime.*;
import java.net.URI;
import java.time.Duration;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration
public class DemoConfiguration {
    @Bean LlmClient demoModel(Environment env) {
        String endpoint = env.getProperty("LLM_ENDPOINT"), key = env.getProperty("LLM_API_KEY"), model = env.getProperty("LLM_MODEL");
        if (endpoint == null || endpoint.isBlank() || key == null || key.isBlank() || model == null || model.isBlank())
            return request -> { throw new OpenAiCompatibleClient.ModelException("MODEL_NOT_CONFIGURED"); };
        // Construction performs no network request. Never log configuration or exception payloads.
        if (URI.create(endpoint).getPath().replaceAll("/+$", "").endsWith("/anthropic"))
            return new AnthropicMessagesClient(URI.create(endpoint), key, model, Duration.ofSeconds(60));
        return new OpenAiCompatibleClient(URI.create(endpoint), key, model, Duration.ofSeconds(15));
    }
}
