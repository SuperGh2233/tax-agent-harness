package com.taxagent.harness.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** T36: Messages, Chat Completions and Responses treat malformed replies and HTTP failures the same way. */
class MalformedReplyConsistencyTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DECISION = "{\\\"kind\\\":\\\"finish\\\",\\\"text\\\":\\\"完成\\\"}";
    private static final Map<String, String> VALID = Map.of(
            "anthropic", "{\"type\":\"message\",\"role\":\"assistant\",\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"" + DECISION + "\"}]}",
            "chat", "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + DECISION + "\"},\"finish_reason\":\"stop\"}]}",
            "responses", "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"status\":\"completed\","
                    + "\"content\":[{\"type\":\"output_text\",\"text\":\"" + DECISION + "\"}]}]}");

    /** Replies in order (status, body); the last one repeats. Records every request body. */
    record Stub(HttpServer server, List<String> bodies, LlmClient client) implements AutoCloseable {
        public void close() { server.stop(0); }
    }
    static Stub stub(String protocol, List<Map.Entry<Integer, String>> replies) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var bodies = new CopyOnWriteArrayList<String>();
        var path = switch (protocol) { case "anthropic" -> "/anthropic/v1/messages"; case "chat" -> "/v1/chat/completions"; default -> "/v1/responses"; };
        server.createContext(path, exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var reply = replies.get(Math.min(bodies.size(), replies.size()) - 1);
            var bytes = reply.getValue().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.getKey(), bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        LlmClient client = switch (protocol) {
            case "anthropic" -> new AnthropicMessagesClient(URI.create(base + "/anthropic"), "stub-key", "stub-model", Duration.ofSeconds(5));
            case "chat" -> new OpenAiCompatibleClient(URI.create(base + "/v1"), "stub-key", "stub-model", Duration.ofSeconds(5));
            default -> new OpenAiCompatibleClient(URI.create(base + "/v1/responses"), "stub-key", "stub-model", Duration.ofSeconds(5));
        };
        return new Stub(server, bodies, client);
    }
    static LlmClient.Request request() { return new LlmClient.Request("查询", List.of(), List.of()); }

    @ParameterizedTest @ValueSource(strings = {"anthropic", "chat", "responses"})
    void oneMalformedReplyIsCorrectedOnceWithTheSameNote(String protocol) throws Exception {
        try (var stub = stub(protocol, List.of(Map.entry(200, "not json"), Map.entry(200, VALID.get(protocol))))) {
            assertThat(stub.client().next(request())).isEqualTo(new LlmClient.Finish("完成"));
            assertThat(stub.bodies()).hasSize(2);
            assertThat(stub.bodies().get(0)).doesNotContain("上一次回复无法被解析");
            // The correction is carried in the system prompt / instructions, never as a user statement.
            var retry = JSON.readTree(stub.bodies().get(1));
            var system = switch (protocol) {
                case "anthropic" -> retry.path("system").asText();
                case "chat" -> retry.at("/messages/1/content").asText();
                default -> retry.path("instructions").asText();
            };
            assertThat(system).contains(LlmClient.CORRECTION);
            if (protocol.equals("chat")) assertThat(retry.at("/messages/1/role").asText()).isEqualTo("system");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"anthropic", "chat", "responses"})
    void aSecondMalformedReplyFailsAsResponseInvalidWithoutAThirdRequest(String protocol) throws Exception {
        try (var stub = stub(protocol, List.of(Map.entry(200, "provider-secret-body")))) {
            assertThatThrownBy(() -> stub.client().next(request()))
                    .isInstanceOf(OpenAiCompatibleClient.ModelException.class)
                    .hasMessage("MODEL_RESPONSE_INVALID").hasNoCause()
                    .satisfies(e -> assertThat(e.toString()).doesNotContain("provider-secret-body"));
            assertThat(stub.bodies()).hasSize(2);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"anthropic", "chat", "responses"})
    void httpFailuresMapToTheSameCodesAndAreNeverRetried(String protocol) throws Exception {
        for (var expected : Map.of(401, "MODEL_AUTH_FAILED", 403, "MODEL_AUTH_FAILED", 429, "MODEL_RATE_LIMITED",
                500, "MODEL_HTTP_ERROR").entrySet()) {
            try (var stub = stub(protocol, List.of(Map.entry(expected.getKey(), "provider-secret-body")))) {
                assertThatThrownBy(() -> stub.client().next(request()))
                        .hasMessage(expected.getValue()).hasNoCause();
                assertThat(stub.bodies()).hasSize(1);
            }
        }
    }
}
