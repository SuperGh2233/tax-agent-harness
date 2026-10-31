package com.taxagent.harness.runtime;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.taxagent.harness.runtime.LlmClient.*;

class OpenAiResponsesClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CALL = """
        {"status":"completed","output":[
          {"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"opaque-test-reasoning"},
          {"type":"function_call","id":"fc_1","call_id":"call_exact","status":"completed",
           "name":"query_invoices","arguments":"{}"}]}
        """;
    private static String finalText(String kind) throws Exception {
        var root = JSON.createObjectNode().put("status", "completed");
        root.putArray("output").addObject().put("type", "message").put("role", "assistant")
                .put("status", "completed").putArray("content").addObject().put("type", "output_text")
                .put("text", JSON.writeValueAsString(java.util.Map.of("kind", kind, "text", "完成")));
        return root.toString();
    }
    @Test void loopbackWireCallReplayAndFinalDecision() throws Exception {
        var count = new AtomicInteger();
        var captured = new AtomicReference<JsonNode>();
        var reply = new AtomicReference<>(CALL);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            count.incrementAndGet();
            captured.set(JSON.readTree(exchange.getRequestBody()));
            var bytes = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses");
            var schema = com.taxagent.harness.tool.ToolContract.schema().at("/$defs/Query");
            var tools = List.of(new Tool("query_invoices", schema));
            var request = new Request("查询", tools, List.of());
            var missing = new OpenAiCompatibleClient(uri, "", "gpt-6-luna", Duration.ofSeconds(2));
            assertThatThrownBy(() -> missing.next(request)).hasMessage("MODEL_NOT_CONFIGURED");
            assertThat(count.get()).isZero();
            var client = new OpenAiCompatibleClient(uri, "stub-key", "gpt-6-luna", Duration.ofSeconds(2));
            var call = (Call) client.next(request);
            assertThat(call.id()).isEqualTo("call_exact");
            assertThat(call.name()).isEqualTo("query_invoices");
            assertThat(call.arguments()).isEqualTo("{}");
            var body = captured.get();
            assertThat(body.path("model").asText()).isEqualTo("gpt-6-luna");
            assertThat(body.at("/reasoning/effort").asText()).isEqualTo("max");
            assertThat(body.path("store").booleanValue()).isFalse();
            assertThat(body.path("parallel_tool_calls").booleanValue()).isFalse();
            assertThat(body.path("include").get(0).asText()).isEqualTo("reasoning.encrypted_content");
            assertThat(body.path("instructions").asText()).contains("exact current-turn trusted application approval");
            assertThat(body.at("/tools/0/parameters")).isEqualTo(schema);
            assertThat(body.at("/tools/0/name").asText()).isEqualTo("query_invoices");
            assertThat(body.at("/tools/0/strict").asBoolean()).isFalse();
            assertThat(body.has("messages")).isFalse();
            assertThat(body.toString()).doesNotContain("stub-key");
            var result = "{\"data\":null,\"error\":{\"code\":\"PERMISSION_DENIED\"}}";
            var history = List.of(new Exchange(call, result));
            assertThat(JSON.writeValueAsString(history)).doesNotContain("providerOutput", "encrypted_content", "opaque-test-reasoning", "fc_1");
            assertThat(call.toString()).doesNotContain("opaque-test-reasoning");
            // Consumers cannot mutate the retained provider state through its accessor.
            ((com.fasterxml.jackson.databind.node.ObjectNode) call.providerOutput().get(0)).put("encrypted_content", "changed");
            reply.set(finalText("finish"));
            assertThat(client.next(new Request("查询", tools, history))).isEqualTo(new Finish("完成"));
            body = captured.get();
            assertThat(body.at("/input/1")).isEqualTo(JSON.readTree(CALL).at("/output/0"));
            assertThat(body.at("/input/2")).isEqualTo(JSON.readTree(CALL).at("/output/1"));
            assertThat(body.at("/input/3/type").asText()).isEqualTo("function_call_output");
            assertThat(body.at("/input/3/call_id").asText()).isEqualTo("call_exact");
            assertThat(body.at("/input/3/output").asText()).isEqualTo(result);
            assertThat(body.has("previous_response_id")).isFalse();
            assertThat(count.get()).isEqualTo(2);
            var mismatch = new Call("wrong", call.name(), call.arguments(), call.providerOutput());
            assertThatThrownBy(() -> client.next(new Request("查询", tools, List.of(new Exchange(mismatch, result)))))
                    .hasMessage("MODEL_FAILURE");
            assertThat(count.get()).isEqualTo(2);
        } finally { server.stop(0); }
    }
    @Test void rejectsMalformedTruncatedMultipleAndInvalidDecisions() throws Exception {
        assertThat(OpenAiCompatibleClient.parseResponses(finalText("ask"))).isEqualTo(new Ask("完成"));
        var multiple = JSON.readTree(CALL).deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) multiple.path("output")).add(multiple.at("/output/1").deepCopy());
        for (var invalid : List.of("{}", CALL.substring(0, CALL.length() / 2), CALL + " {}",
                CALL.replace("completed", "incomplete"), CALL.replace("\"{}\"", "\"{\""),
                CALL.replace("call_exact", " "), CALL.replace("function_call", "unknown"),
                multiple.toString(), finalText("bogus"), finalText("finish").replace("output_text", "refusal"),
                CALL.replace("\"status\":\"completed\",\"output\"", "\"status\":\"completed\",\"error\":{},\"output\""))) {
            assertThatThrownBy(() -> OpenAiCompatibleClient.parseResponses(invalid)).isInstanceOf(Exception.class);
        }
    }
    @Test void transportRefusesRedirectAndSanitizesErrors() throws Exception {
        var targetCount = new AtomicInteger();
        var status = new AtomicInteger(302);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/target", exchange -> { targetCount.incrementAndGet(); exchange.close(); });
        server.createContext("/responses", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target");
            var bytes = "provider-secret-body".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            var client = new OpenAiCompatibleClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/responses"),
                    "stub-key", "gpt-6-luna", Duration.ofSeconds(2));
            for (int code : List.of(302, 500, 200)) {
                status.set(code);
                assertThatThrownBy(() -> client.next(new Request("test", List.of(), List.of())))
                        .hasMessage(code == 200 ? "MODEL_FAILURE" : "MODEL_HTTP_ERROR").hasNoCause();
            }
            assertThat(targetCount.get()).isZero();
        } finally { server.stop(0); }
        assertThatThrownBy(() -> new OpenAiCompatibleClient(URI.create("http://example.invalid/responses"), "stub", "gpt-6-luna", Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OpenAiCompatibleClient(URI.create("https://example.invalid/responses"), "stub", "gpt-6-luna", Duration.ofSeconds(61)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
