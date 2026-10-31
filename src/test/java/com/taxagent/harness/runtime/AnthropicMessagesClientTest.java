package com.taxagent.harness.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnthropicMessagesClientTest {
    private static final String CALL = "{\"type\":\"message\",\"role\":\"assistant\",\"stop_reason\":\"tool_use\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"private-state\",\"signature\":\"signature\"},{\"type\":\"tool_use\",\"id\":\"c1\",\"name\":\"query\",\"input\":{}}]}";
    @Test void replayAndTransport() throws Exception {
        var json = new ObjectMapper();
        var requests = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/apps/anthropic/v1/messages", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("stub-only", exchange.getRequestHeaders().getFirst("x-api-key"));
            requests.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] response = CALL.replace("private-state", "").replace("\"signature\":\"signature\"", "\"signature\":\"\"").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            var client = new AnthropicMessagesClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/apps/anthropic"), "stub-only", "qwen3.8-flash", Duration.ofSeconds(2));
            var tools = List.of(new LlmClient.Tool("query", json.readTree("{\"type\":\"object\"}")));
            var call = (LlmClient.Call) client.next(new LlmClient.Request("query", tools, List.of()));
            client.next(new LlmClient.Request("query", tools, List.of(new LlmClient.Exchange(call, "{\"ok\":true}"))));
            var body = json.readTree(requests.get(1));
            assertEquals("qwen3.8-flash", body.path("model").asText());
            assertEquals(8192, body.path("max_tokens").asInt());
            assertEquals("medium", body.at("/output_config/effort").asText());
            assertEquals("json_schema", body.at("/output_config/format/type").asText());
            assertEquals("object", body.at("/output_config/format/schema/type").asText());
            assertEquals("ask", body.at("/output_config/format/schema/properties/kind/enum/0").asText());
            assertEquals("finish", body.at("/output_config/format/schema/properties/kind/enum/1").asText());
            assertFalse(body.at("/output_config/format/schema/additionalProperties").asBoolean());
            assertEquals("object", body.at("/tools/0/input_schema/type").asText());
            assertEquals(json.readTree(CALL.replace("private-state", "").replace("\"signature\":\"signature\"", "\"signature\":\"\"" )).get("content"), body.at("/messages/1/content"));
            assertEquals("c1", body.at("/messages/2/content/0/tool_use_id").asText());
            assertEquals("{\"ok\":true}", body.at("/messages/2/content/0/content").asText());
            assertFalse(json.writeValueAsString(call).contains("private-state"));
            assertFalse(call.toString().contains("signature"));
            assertFalse(requests.getFirst().contains("stub-only"));
        } finally { server.stop(0); }
    }
    @Test void strictParsing() throws Exception {
        assertInstanceOf(LlmClient.Call.class, AnthropicMessagesClient.parse(CALL));
        var finish = "{\"type\":\"message\",\"role\":\"assistant\",\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"\"},{\"type\":\"text\",\"text\":\"{\\\"kind\\\":\\\"finish\\\",\\\"text\\\":\\\"OK\\\"}\"}]}";
        var ask = finish.replace("finish", "ask").replace("OK", "请提供月份");
        assertInstanceOf(LlmClient.Finish.class, AnthropicMessagesClient.parse(finish));
        assertInstanceOf(LlmClient.Ask.class, AnthropicMessagesClient.parse(ask));
        var prose = "{\"type\":\"message\",\"role\":\"assistant\",\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"只读查询已完成。\"}]}";
        var plain = (LlmClient.Finish) AnthropicMessagesClient.parse(prose);
        assertEquals("只读查询已完成。", plain.text());
        var callWithPreamble = CALL.replace("\"content\":[", "\"content\":[{\"type\":\"text\",\"text\":\"正在查询。\"},");
        assertInstanceOf(LlmClient.Call.class, AnthropicMessagesClient.parse(callWithPreamble));
        var truncated = CALL.replace("tool_use\",\"content", "max_tokens\",\"content");
        var outputLimit = assertThrows(OpenAiCompatibleClient.ModelException.class, () -> AnthropicMessagesClient.parse(truncated));
        assertEquals("MODEL_OUTPUT_LIMIT", outputLimit.code());
        for (String invalid : List.of(CALL.replace("\"input\":{}", "\"input\":[]"),
                CALL.replace("\"thinking\"", "\"unknown\""),
                CALL + "{}"))
            assertThrows(Exception.class, () -> AnthropicMessagesClient.parse(invalid));
    }
    @Test void redirectAndErrorsStaySanitized() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var count = new java.util.concurrent.atomic.AtomicInteger();
        var followed = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/must-not-follow", exchange -> {
            followed.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        server.createContext("/anthropic/v1/messages", exchange -> {
            count.incrementAndGet();
            exchange.getResponseHeaders().add("Location", "/must-not-follow");
            byte[] marker = "dummy-provider-marker".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(302, marker.length);
            exchange.getResponseBody().write(marker); exchange.close();
        });
        server.start();
        try {
            var base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/anthropic");
            var request = new LlmClient.Request("test", List.of(), List.of());
            var client = new AnthropicMessagesClient(base, "stub-only", "qwen3.8-flash", Duration.ofSeconds(2));
            var error = assertThrows(OpenAiCompatibleClient.ModelException.class, () -> client.next(request));
            assertEquals("MODEL_HTTP_ERROR", error.getMessage());
            assertFalse(error.toString().contains("dummy-provider-marker"));
            assertNull(error.getCause());
            assertEquals(0, followed.get());
            assertEquals(1, count.get());
            var missing = new AnthropicMessagesClient(base, "", "qwen3.8-flash", Duration.ofSeconds(2));
            assertThrows(OpenAiCompatibleClient.ModelException.class, () -> missing.next(request));
            assertEquals(1, count.get());
        } finally { server.stop(0); }
    }
}
