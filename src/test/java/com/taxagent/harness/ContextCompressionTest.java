package com.taxagent.harness;

import com.fasterxml.jackson.databind.*;
import com.taxagent.harness.runtime.*;
import com.taxagent.harness.tool.*;
import com.taxagent.harness.tool.PermissionGuard.Session;
import com.taxagent.harness.tool.ToolContract.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.taxagent.harness.runtime.LlmClient.*;
import static org.assertj.core.api.Assertions.*;
import static com.taxagent.harness.ToolContractTest.fails;

class ContextCompressionTest {
    static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path directory;
    Path db;
    final Session operator = new Session("U001");

    /** Answers every turn with a scripted decision, defaulting to a question so the task stays open. */
    static final class Script implements LlmClient {
        final List<Request> requests = new ArrayList<>();
        final Queue<Function<Request, Decision>> next = new ArrayDeque<>();
        public Decision next(Request request) { requests.add(request); var step = next.poll(); return step == null ? new Ask("还有吗？") : step.apply(request); }
    }
    @BeforeEach void setup() throws Exception { db = directory.resolve("context.db"); DataInitializer.initialize(db); }
    void sql(String sql) throws Exception { try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement()) { s.execute(sql); } }
    static JsonNode conversation(Request request) throws Exception {
        var text = request.userMessage();
        var json = text.substring(text.indexOf('{'), text.lastIndexOf("\n当前企业：") > 0 ? text.lastIndexOf("\n当前企业：") : text.length());
        return JSON.readTree(json.strip()).path("conversation");
    }
    static Call call(String name, String args) { return new Call(UUID.randomUUID().toString(), name, args); }

    @Test void thirtyTurnsKeepEnterpriseMonthCorrectionsConstraintsAndPendingWithinBudget() throws Exception {
        var model = new Script();
        var agent = new FinanceAgent(db, operator, "C001", model);
        var task = agent.start("查询 C001 发票并补齐用途", List.of("F002.purpose", "F004.purpose"));
        var said = new TreeMap<Integer, String>(Map.of(3, "只看九月的发票", 10, "不对，是八月，不是九月", 15, "不要包含 F003",
                20, "以后金额用万元表示", 25, "F002 的销售方先不用管"));
        TaskMemory.Turn last = null;
        for (int turn = 1; turn <= 30; turn++) last = agent.turn(task.id(), said.getOrDefault(turn, "继续核对第 " + turn + " 步"));
        var request = model.requests.getLast();
        var memoryJson = last.modelInput();
        assertThat(memoryJson.length()).isLessThanOrEqualTo(ContextProjector.MAX_CHARS);
        assertThat(request.userMessage()).contains("当前企业：C001");
        var conv = conversation(request);
        assertThat(conv.path("turns_total").asInt()).isEqualTo(29);
        assertThat(conv.at("/key_objects/latest_month/value").asText()).isEqualTo("八月");
        assertThat(conv.at("/key_objects/latest_month/turn").asInt()).isEqualTo(10);
        assertThat(conv.at("/key_objects/months_mentioned").toString()).contains("九月", "八月");
        assertThat(conv.at("/key_objects/ids_mentioned").toString()).contains("C001", "F003", "F002");
        assertThat(conv.path("earlier_constraints").findValuesAsText("user"))
                .contains("只看九月的发票", "不对，是八月，不是九月", "不要包含 F003", "以后金额用万元表示", "F002 的销售方先不用管");
        assertThat(conv.path("recent_turns")).hasSizeLessThanOrEqualTo(ContextProjector.RECENT_TURNS);
        assertThat(conv.at("/omitted_turns/count").asInt()).isPositive();
        assertThat(memoryJson).contains("F002.purpose", "F004.purpose"); // Pending never drops.

        // The original of an omitted turn comes back through recall_context.
        model.next.add(r -> call("recall_context", "{\"source\":\"TURN\",\"seq\":3}"));
        var recalled = agent.turn(task.id(), "第三轮我具体怎么说的？");
        var data = JSON.readTree(recalled.result().history().getFirst().resultJson()).path("data");
        assertThat(data.path("content").asText()).contains("用户：只看九月的发票");
        assertThat(data.path("reference").asText()).isEqualTo("#3");
    }

    @Test void largeResultReachesTheModelCompactedWhileEvidenceKeepsEveryRow() throws Exception {
        var values = new StringJoiner(",");
        for (int i = 0; i < 60; i++) values.add("('C001','X%03d','2026-10-%02d','销售方','较长的票面项目名称用于测试截断',%d,'','')".formatted(i, 1 + i % 28, 10000 + i));
        sql("INSERT INTO invoices VALUES " + values);
        var model = new Script();
        var queryId = "q-october";
        model.next.add(r -> new Call(queryId, "query_invoices", "{\"enterprise_id\":\"C001\",\"month\":\"2026-10\"}"));
        model.next.add(r -> call("recall_context", "{\"source\":\"TOOL_RESULT\",\"call_id\":\"" + queryId + "\",\"offset\":20,\"limit\":20}"));
        var agent = new FinanceAgent(db, operator, "C001", model);
        var turn = agent.turn(agent.start("十月发票", List.of()).id(), "十月发票");
        var exchange = turn.result().history().getFirst();
        var full = JSON.readTree(exchange.resultJson());
        assertThat(full.at("/data/rows")).hasSize(60);
        var seen = JSON.readTree(model.requests.get(1).history().getFirst().forModel());
        assertThat(seen.at("/data/rows")).hasSize(20);
        assertThat(seen.at("/data/rows_total").asInt()).isEqualTo(60);
        assertThat(seen.at("/data/total_cents").asLong()).isEqualTo(full.at("/data/total_cents").asLong()); // Totals cover every row.
        assertThat(seen.at("/data/note").asText()).contains("recall_context", queryId);
        var page = JSON.readTree(turn.result().history().get(1).resultJson()).path("data");
        assertThat(page.path("total").asInt()).isEqualTo(60);
        assertThat(page.path("offset").asInt()).isEqualTo(20);
        assertThat(page.path("returned").asInt()).isEqualTo(20);
        // Same order as the full result: the page starts at its 21st row.
        assertThat(JSON.readTree(page.path("content").asText()).at("/data/rows/0/key/invoice_id").asText())
                .isEqualTo(full.at("/data/rows/20/key/invoice_id").asText());
        // A later turn can still page the stored result.
        model.next.add(r -> call("recall_context", "{\"source\":\"TOOL_RESULT\",\"call_id\":\"" + queryId + "\",\"offset\":40,\"limit\":50}"));
        var later = agent.turn(turn.task().id(), "再看后面的");
        var rest = JSON.readTree(later.result().history().getFirst().resultJson()).path("data");
        assertThat(rest.path("returned").asInt()).isEqualTo(20);
        assertThat(rest.path("note").asText()).contains("最后一行");
    }

    @Test void largeDocumentIsAnsweredBySearchThenRecallOfTheOriginalSegment() throws Exception {
        var paragraphs = new ArrayList<String>();
        for (int i = 1; i <= 200; i++) paragraphs.add("第" + i + "条 一般性说明，与差旅无关的条款内容。");
        paragraphs.set(149, "第150条 差旅住宿费每晚不超过 450 元，超出部分自理。");
        var files = new FileService(db);
        var file = files.upload(operator, "C001", "财务制度大全.docx", KnowledgeServiceTest.docx(paragraphs.toArray(String[]::new))).file();
        var model = new Script();
        model.next.add(r -> call("search_knowledge", "{\"enterprise_id\":\"C001\",\"query\":\"住宿费上限\",\"limit\":1}"));
        model.next.add(r -> {
            var hit = r.history().getFirst().resultJson();
            try {
                var h = JSON.readTree(hit).at("/data/hits/0");
                return call("recall_context", "{\"source\":\"FILE\",\"file_id\":\"" + h.path("source_id").asText()
                        + "\",\"location\":\"" + h.path("location").asText() + "\",\"limit\":1}");
            } catch (Exception e) { throw new IllegalStateException(e); }
        });
        var agent = new FinanceAgent(db, operator, "C001", model);
        var turn = agent.turn(agent.start("制度问答", List.of()).id(), "住宿费上限是多少？");
        var recalled = JSON.readTree(turn.result().history().get(1).resultJson()).path("data");
        assertThat(recalled.path("reference").asText()).isEqualTo("财务制度大全.docx v1 · 第150段");
        assertThat(recalled.path("content").asText()).contains("第149段：", "第150段：第150条 差旅住宿费每晚不超过 450 元", "第151段：");
        // Switched-off files and other enterprises' files cannot be read back.
        files.setKnowledgeEnabled(operator, "C001", file.id(), false);
        var recall = new ContextRecall(db, new TaskMemory(db), operator, "C001", turn.task().id());
        fails(ErrorCode.NOT_FOUND, () -> { try { recall.recall(new RecallQuery("FILE", null, null, file.id(), "第150段", null, 1), List.of()); }
            catch (DomainException e) { throw e; } catch (Exception e) { throw new IllegalStateException(e); } });
        var other = new ContextRecall(db, new TaskMemory(db), operator, "C002", turn.task().id());
        fails(ErrorCode.NOT_FOUND, () -> { try { other.recall(new RecallQuery("TURN", 1, null, null, null, null, null), List.of()); }
            catch (DomainException e) { throw e; } catch (Exception e) { throw new IllegalStateException(e); } });
    }

    @Test void aLargeRecallPageIsCutToWhatReachesTheModelWholeAndSaysWhereToContinue() throws Exception {
        // T39: a recalled page over 6000 characters used to be compacted again into a 3000-character preview.
        var values = new StringJoiner(",");
        for (int i = 0; i < 60; i++) values.add("('C001','Y%03d','2026-10-%02d','较长的合成销售方名称有限公司','较长的合成票面项目名称用于触发结果压缩第%d张',%d,'办公用品采购','合成数据')"
                .formatted(i, 1 + i % 28, i, 10000 + i));
        sql("INSERT INTO invoices VALUES " + values);
        var model = new Script();
        model.next.add(r -> new Call("q-big", "query_invoices", "{\"enterprise_id\":\"C001\",\"month\":\"2026-10\"}"));
        model.next.add(r -> call("recall_context", "{\"source\":\"TOOL_RESULT\",\"call_id\":\"q-big\",\"offset\":20,\"limit\":50}"));
        var agent = new FinanceAgent(db, operator, "C001", model);
        var turn = agent.turn(agent.start("十月发票", List.of()).id(), "十月发票");
        var recall = turn.result().history().get(1);
        assertThat(recall.modelJson()).isNull(); // Not compacted: the model receives the page whole.
        var page = JSON.readTree(recall.resultJson()).path("data");
        int returned = page.path("returned").asInt();
        assertThat(returned).isBetween(1, 39);
        assertThat(JSON.readTree(page.path("content").asText()).at("/data/rows")).hasSize(returned);
        assertThat(page.path("note").asText()).contains("大小上限", "offset=" + (20 + returned));
    }

    @Test void aMadeUpCallIdIsAnsweredWithTheIdsThatExistAndCanBeCorrected() throws Exception {
        // Seen with the real model (T38): it guessed "query_invoices_C001_2026-08" because ids are only shown in compaction notes.
        var model = new Script();
        model.next.add(r -> new Call("q-sept", "query_invoices", "{\"enterprise_id\":\"C001\",\"month\":\"2026-09\"}"));
        model.next.add(r -> new Finish("九月发票已查询"));
        var agent = new FinanceAgent(db, operator, "C001", model);
        var task = agent.start("九月发票", List.of("F002.purpose"));
        agent.turn(task.id(), "查九月发票");
        model.next.add(r -> call("recall_context", "{\"source\":\"TOOL_RESULT\",\"call_id\":\"query_invoices_C001_2026-09\"}"));
        model.next.add(r -> call("recall_context", "{\"source\":\"TOOL_RESULT\",\"call_id\":\"q-sept\",\"limit\":5}"));
        model.next.add(r -> new Finish("已取回九月查询结果"));
        var turn = agent.turn(task.id(), "把上一轮查到的发票再拿出来看看");
        var wrong = JSON.readTree(turn.result().history().getFirst().resultJson()).path("error");
        assertThat(wrong.path("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(wrong.path("message").asText()).contains("不能自拟", "q-sept（第 1 轮 query_invoices", "2026-09");
        assertThat(JSON.readTree(turn.result().history().get(1).resultJson()).at("/data/returned").asInt()).isEqualTo(5);
        assertThat(turn.result().errorCode()).isNull(); // Corrected by the same tool, so the turn does not fail.
    }

    @Test void recallArgumentsAreStrictAndCannotNameAnotherTask() {
        fails(ErrorCode.INVALID_ARGUMENT, () -> ToolContract.decode("{\"source\":\"TURN\",\"seq\":1,\"task_id\":\"x\"}", RecallQuery.class));
        fails(ErrorCode.INVALID_ARGUMENT, () -> new RecallQuery("TURN", null, null, null, null, null, null));
        fails(ErrorCode.INVALID_ARGUMENT, () -> new RecallQuery("TOOL_RESULT", 1, "c", null, null, null, null));
        fails(ErrorCode.INVALID_ARGUMENT, () -> new RecallQuery("FILE", null, null, "f", null, null, null));
        fails(ErrorCode.INVALID_ARGUMENT, () -> new RecallQuery("FILE", null, null, "f", "第1段", null, 4));
        fails(ErrorCode.INVALID_ARGUMENT, () -> new RecallQuery("SQL", null, null, null, null, null, null));
    }
}
