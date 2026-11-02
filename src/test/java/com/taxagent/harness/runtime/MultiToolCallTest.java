package com.taxagent.harness.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.taxagent.harness.DataInitializer;
import com.taxagent.harness.tool.*;
import com.taxagent.harness.tool.PermissionGuard.Session;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.taxagent.harness.runtime.LlmClient.*;
import com.taxagent.harness.runtime.LlmClient.Batch;

/** Provider-batch regression tests. Uses synthetic data and a loopback-only model stub. */
class MultiToolCallTest {
    @TempDir Path temp;
    static final ObjectMapper JSON = new ObjectMapper();
    static final String INPUT = "{\"enterprise_id\":\"C001\",\"month\":\"2026-09\"}";
    static final String MULTI = """
        {"type":"message","role":"assistant","stop_reason":"tool_use","content":[
          {"type":"thinking","thinking":"internal-only","signature":"hidden-signature"},
          {"type":"text","text":"查询中"},
          {"type":"tool_use","id":"a1","name":"query_invoices","input":{"enterprise_id":"C001","month":"2026-09"}},
          {"type":"tool_use","id":"b2","name":"query_bank_entries","input":{"enterprise_id":"C001","month":"2026-09"}}
        ]}
        """;
    static final String FINISH = """
        {"type":"message","role":"assistant","stop_reason":"end_turn",
         "content":[{"type":"text","text":"完成"}]}
        """;

    static class Fake implements LlmClient {
        final Queue<Decision> decisions = new ArrayDeque<>();
        final List<Request> requests = new ArrayList<>();
        Fake(Decision... decisions) { this.decisions.addAll(List.of(decisions)); }
        @Override public Decision next(Request request) {
            requests.add(request);
            return decisions.remove();
        }
    }
    AgentRuntime runtime(Path db, LlmClient model) {
        return new AgentRuntime(model, new ReadOnlyFinanceTools(db), new RuleService(db),
                AgentRuntime.Limits.defaults());
    }
    static Call call(String id, String tool, String args) { return new Call(id, tool, args); }
    static Batch twoReads() {
        return new Batch(List.of(call("a1","query_invoices", INPUT),
                call("b2","query_bank_entries", INPUT)));
    }

    @Test void batchParsesAndRejectsMalformedProviderTurns() throws Exception {
        var batch = assertInstanceOf(Batch.class, AnthropicMessagesClient.parse(MULTI));
        assertEquals(2, batch.calls().size());
        assertEquals("a1", batch.calls().get(0).id());
        assertEquals("b2", batch.calls().get(1).id());
        assertEquals(batch.calls().get(0).providerOutput(), batch.calls().get(1).providerOutput());
        assertInstanceOf(Call.class, AnthropicMessagesClient.parse(MULTI.replace(
                ",\n  {\"type\":\"tool_use\",\"id\":\"b2\",\"name\":\"query_bank_entries\",\"input\":{\"enterprise_id\":\"C001\",\"month\":\"2026-09\"}}", "")));
        assertThrows(Exception.class, () -> AnthropicMessagesClient.parse(MULTI.replace("\"b2\"","\"a1\"")));
        assertThrows(Exception.class, () -> AnthropicMessagesClient.parse(MULTI.replace("\"tool_use\",\"content\"","\"end_turn\",\"content\"")));
        assertThrows(Exception.class, () -> AnthropicMessagesClient.parse(MULTI.replace("\"month\":\"2026-09\"}}", "\"month\":\"2026-09\"}} extra")));
        assertThrows(Exception.class, () -> AnthropicMessagesClient.parse(MULTI.replace(
                "\"input\":{\"enterprise_id\":\"C001\",\"month\":\"2026-09\"}}",
                "\"input\":[]}" )));
        assertThrows(IllegalArgumentException.class, () ->
                new Batch(List.of(call("x","query_invoices", INPUT), call("x","query_bank_entries",INPUT))));
        assertFalse(JSON.writeValueAsString(batch).contains("internal-only"));
        assertFalse(batch.toString().contains("hidden-signature"));
    }

    @Test void groupedReplayCorrelatesBothResultsAndDoesNotDuplicateAssistant() throws Exception {
        var requests = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/anthropic/v1/messages", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var response = requests.size() == 1 ? MULTI : FINISH;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var client = new AnthropicMessagesClient(URI.create("http://127.0.0.1:" +
                    server.getAddress().getPort() + "/anthropic"), "stub-only", "qwen3.8-flash", Duration.ofSeconds(3));
            var tools = List.of(new Tool("query_invoices",JSON.readTree("{\"type\":\"object\"}")),
                    new Tool("query_bank_entries",JSON.readTree("{\"type\":\"object\"}")));
            var batch = assertInstanceOf(Batch.class, client.next(new Request("查询", tools, List.of())));
            var history = List.of(new Exchange(batch.calls().get(0), "{\"invoice\":10}"),
                    new Exchange(batch.calls().get(1), "{\"bank\":20}"));
            assertInstanceOf(Finish.class, client.next(new Request("查询", tools, history)));
            var body = JSON.readTree(requests.get(1));
            assertEquals(3, body.path("messages").size());
            assertEquals(JSON.readTree(MULTI).path("content"), body.at("/messages/1/content"));
            assertEquals(2, body.at("/messages/2/content").size());
            assertEquals("a1", body.at("/messages/2/content/0/tool_use_id").asText());
            assertEquals("b2", body.at("/messages/2/content/1/tool_use_id").asText());
            assertEquals("{\"bank\":20}", body.at("/messages/2/content/1/content").asText());
            assertFalse(requests.get(1).contains("stub-only"));
            assertThrows(OpenAiCompatibleClient.ModelException.class,
                    () -> client.next(new Request("查询", tools, history.subList(0, 1))));
            assertThrows(OpenAiCompatibleClient.ModelException.class,
                    () -> client.next(new Request("查询", tools, List.of(history.get(0), history.get(0)))));
            assertThrows(OpenAiCompatibleClient.ModelException.class,
                    () -> client.next(new Request("查询", tools, List.of(history.get(1), history.get(0)))));
        } finally { server.stop(0); }
    }

    @Test void runtimeExecutesTwoReadsOnceAndFeedsBackBothResults() throws Exception {
        Path db = temp.resolve("reads.db"); DataInitializer.initialize(db);
        var model = new Fake(twoReads(), new Finish("已汇总"));
        var result = runtime(db, model).run(new Session("U002"), "发票与流水");
        assertEquals(AgentRuntime.Status.FINISHED, result.status());
        assertEquals(2, result.history().size());
        assertEquals(2, model.requests.size());
        assertEquals("a1", result.history().get(0).call().id());
        assertEquals("b2", result.history().get(1).call().id());
        assertEquals(962206, JSON.readTree(result.history().get(0).resultJson()).at("/data/total_cents").asLong());
        assertEquals(1300050, JSON.readTree(result.history().get(1).resultJson()).at("/data/income_cents").asLong());
        assertEquals(result.history(), model.requests.get(1).history());
    }

    @Test void runtimePreflightFailsClosedBeforeAnyBatchMemberExecutes() throws Exception {
        Path db = temp.resolve("preflight.db"); DataInitializer.initialize(db);
        var bad = new Batch(List.of(call("a1","query_invoices",INPUT),
                call("b2","query_bank_entries", INPUT.replace("C001","C002"))));
        var model = new Fake(bad);
        var result = runtime(db,model).runScoped(new Session("U001"),"C001", "查询", "{}", null);
        assertEquals(AgentRuntime.Status.ERROR, result.status());
        assertEquals("PERMISSION_DENIED", result.errorCode());
        assertTrue(result.history().isEmpty());
        assertEquals(1, model.requests.size());
        var forbidden = new Batch(List.of(call("q1","query_invoices",INPUT),
                call("w2","update_invoice","{}")));
        var writeModel = new Fake(forbidden);
        var writeResult = runtime(db,writeModel).run(new Session("U001"),"保存");
        assertEquals(AgentRuntime.Status.ERROR, writeResult.status());
        assertEquals("CONFIRMATION_REQUIRED",writeResult.errorCode());
        assertTrue(writeResult.history().isEmpty());
    }

    @Test void runtimeToolFailureCannotBeOverriddenByModelFinish() throws Exception {
        Path db = temp.resolve("failed.db"); DataInitializer.initialize(db);
        var model = new Fake(new Batch(List.of(
                call("first","query_invoices",INPUT),
                call("second","query_bank_entries",INPUT.replace("C001","C002")))),
                new Finish("全部成功"));
        var result = runtime(db,model).run(new Session("U002"),"查询");
        assertEquals(AgentRuntime.Status.ERROR,result.status());
        assertEquals("PERMISSION_DENIED",result.errorCode());
        assertEquals(2,result.history().size());
        assertNotEquals("全部成功",result.text());
        assertEquals("PERMISSION_DENIED",JSON.readTree(result.history().get(1).resultJson()).at("/error/code").asText());
    }
}
