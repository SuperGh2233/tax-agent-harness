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

class OpenAiCompatibleClientTest {
    static final String FINISH = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"kind\\\":\\\"finish\\\",\\\"text\\\":\\\"完成\\\"}\"}}]}";
    @Test void localStubVerifiesWireHistorySchemaAndNoKeyNoNetwork() throws Exception {
        var count = new AtomicInteger();
        var captured = new AtomicReference<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            count.incrementAndGet();
            captured.set(new ObjectMapper().readTree(exchange.getRequestBody()));
            byte[] body = FINISH.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
            var schema = com.taxagent.harness.tool.ToolContract.schema().at("/$defs/Query");
            var request = new Request("查询", List.of(new Tool("query_invoices", schema)),
                    List.of(new Exchange(new Call("one", "query_invoices", "{}"), "{\"data\":null,\"error\":{\"code\":\"PERMISSION_DENIED\"}}")));
            var absent = new OpenAiCompatibleClient(uri, "", "stub", Duration.ofSeconds(2));
            assertThatThrownBy(() -> absent.next(request)).isInstanceOf(OpenAiCompatibleClient.ModelException.class).hasMessage("MODEL_NOT_CONFIGURED");
            assertThat(count.get()).isZero();
            var client = new OpenAiCompatibleClient(uri, "fake-test-key", "stub", Duration.ofSeconds(2));
            assertThat(client.next(request)).isEqualTo(new Finish("完成"));
            assertThat(count.get()).isEqualTo(1);
            assertThat(captured.get().at("/tools/0/function/parameters")).isEqualTo(schema);
            assertThat(captured.get().at("/messages/3/tool_call_id").asText()).isEqualTo("one");
            assertThat(captured.get().at("/messages/3/content").asText()).contains("PERMISSION_DENIED");
            assertThat(captured.get().toString()).doesNotContain("fake-test-key", "user_id");
        } finally { server.stop(0); }
    }
    @Test void parsesAskAndSingleToolRejectsMalformedTruncatedOrMultipleCalls() throws Exception {
        assertThat(OpenAiCompatibleClient.parse(FINISH.replace("finish\\\"", "ask\\\""))).isInstanceOf(Ask.class);
        String call = "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"tool_calls\":[{\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"query_invoices\",\"arguments\":\"{}\"}}]}}]}";
        assertThat(OpenAiCompatibleClient.parse(call)).isEqualTo(new Call("a", "query_invoices", "{}"));
        for (String invalid : List.of("{}", FINISH.replace("stop", "length"), call.replace("tool_calls\",", "stop\","),
                FINISH.replace("finish", "bogus"), FINISH + " {}")) {
            assertThatThrownBy(() -> OpenAiCompatibleClient.parse(invalid)).isInstanceOf(Exception.class);
        }
    }
}
