package com.taxagent.harness;

import com.fasterxml.jackson.databind.*;
import com.taxagent.harness.demo.*;
import com.taxagent.harness.runtime.*;
import com.taxagent.harness.tool.*;
import com.taxagent.harness.tool.InvoiceWriteService.*;
import com.taxagent.harness.tool.ToolContract.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.*;
import static com.taxagent.harness.runtime.LlmClient.*;
import static com.taxagent.harness.ToolContractTest.fails;
import static com.taxagent.harness.tool.InvoiceWriteService.authorizeExplicitBatch;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.*;

/** T47: one approval for several invoices, saved in one transaction with a program-made review list. */
class BatchSaveTest {
    @TempDir Path directory;
    Path db;
    InvoiceWriteService service;
    final PermissionGuard.Session user = new PermissionGuard.Session("U001");
    static final String TASK = "batch-task";

    @BeforeEach void setup() throws Exception {
        db = directory.resolve("batch.db"); DataInitializer.initialize(db); service = new InvoiceWriteService(db);
    }
    static InvoiceUpdate update(String invoice, String field, String value) {
        return new InvoiceUpdate(new InvoiceKey("C001", invoice), Map.of(field, value));
    }
    static BatchItem item(String op, String invoice, String field, String value) { return new BatchItem(op, update(invoice, field, value)); }
    BatchUpdateResult save(String batch, List<BatchItem> items, Intent intent) {
        return service.executeBatch(user, TASK, batch, items, authorizeExplicitBatch(user, TASK, batch, items, intent));
    }
    String value(String invoice, String column) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement();
             var r = s.executeQuery("SELECT " + column + " FROM invoices WHERE enterprise_id='C001' AND id='" + invoice + "'")) { return r.getString(1); }
    }
    long ledger() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement();
             var r = s.executeQuery("SELECT count(*) FROM invoice_operations")) { return r.getLong(1); }
    }
    void sql(String sql) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement()) { s.execute(sql); }
    }

    @Test void wholeBatchIsVerifiedAndTheProgramListsWhatStillNeedsReview() throws Exception {
        var items = List.of(item("op-1", "F002", "purpose", "复印资料"),
                item("op-2", "F001", "purpose", "办公用品"),      // replaces "购买办公文具"
                item("op-3", "F006", "remarks", ""));              // clears "按月结算"; purpose still empty
        var result = save("batch-1", items, Intent.CLEAR_NOW);
        assertThat(result.verified()).isTrue();
        assertThat(result.results()).extracting(r -> r.key().invoiceId()).containsExactly("F002", "F001", "F006");
        assertThat(result.results()).allSatisfy(r -> assertThat(r.verified()).isTrue());
        assertThat(result.review()).extracting(r -> r.key().invoiceId(), ReviewItem::reasons).containsExactly(
                tuple("F001", List.of("OVERWROTE_PURPOSE")),
                tuple("F006", List.of("CLEARED_REMARKS", "PURPOSE_STILL_REQUIRED")));
        assertThat(result.note()).contains("3", "2");
        assertThat(value("F002", "purpose")).isEqualTo("复印资料");
        assertThat(value("F001", "purpose")).isEqualTo("办公用品");
        assertThat(value("F006", "remarks")).isEmpty();
        assertThat(ledger()).isEqualTo(3);
        // Each invoice keeps its own operation id, so the read-only verify works per invoice.
        String task = new TaskMemory(db).start(user, "C001", "verify", List.of()).id();
        var owned = List.of(item("v-1", "F004", "purpose", "出差住宿"), item("v-2", "F003", "purpose", "寄送合同"));
        service.executeBatch(user, task, "v-batch", owned, authorizeExplicitBatch(user, task, "v-batch", owned, Intent.SAVE_NOW));
        assertThat(new InvoiceWriteService(db, false).verify(user, "C001", task, "v-2").status()).isEqualTo(VerificationStatus.VERIFIED);
    }

    @Test void aCommittedBatchIsNeverExecutedAgain() throws Exception {
        var items = List.of(item("op-1", "F002", "purpose", "复印资料"), item("op-2", "F004", "purpose", "出差住宿"));
        var authorization = authorizeExplicitBatch(user, TASK, "batch-1", items, Intent.SAVE_NOW);
        service.executeBatch(user, TASK, "batch-1", items, authorization);
        fails(ErrorCode.CONFLICT, () -> service.executeBatch(user, TASK, "batch-1", items, authorization)); // consumed
        sql("UPDATE invoices SET remarks='外部备注' WHERE enterprise_id='C001' AND id='F009'"); // unrelated change
        var replay = save("batch-1", items, Intent.SAVE_NOW);
        assertThat(replay.verified()).isTrue();
        assertThat(ledger()).isEqualTo(2);
        assertThat(service.recoverBatch(user, TASK, "batch-1", items)).isPresent();
        assertThat(service.recoverBatch(user, TASK, "batch-2", List.of(item("x-1", "F002", "purpose", "a"), item("x-2", "F004", "purpose", "b")))).isEmpty();
        // Half a batch in the ledger cannot happen through executeBatch; if it is ever seen, it needs a person.
        fails(ErrorCode.CONFLICT, () -> service.recoverBatch(user, TASK, "batch-3", List.of(items.getFirst(), item("op-new", "F003", "purpose", "c"))));
    }

    @Test void anyFailureLeavesEveryInvoiceUnchanged() throws Exception {
        var missing = List.of(item("op-1", "F002", "purpose", "复印资料"), item("op-2", "F999", "purpose", "不存在"));
        assertThatThrownBy(() -> save("batch-1", missing, Intent.SAVE_NOW)).isInstanceOfSatisfying(DomainException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(e.getMessage()).contains("F999", "整批未修改");
        });
        assertThat(value("F002", "purpose")).isEmpty();
        sql("CREATE TRIGGER fail_second BEFORE INSERT ON invoice_operations WHEN NEW.invoice_id='F004' BEGIN SELECT RAISE(ABORT,'failure'); END");
        var items = List.of(item("op-3", "F002", "purpose", "复印资料"), item("op-4", "F004", "purpose", "出差住宿"));
        fails(ErrorCode.STORAGE_ERROR, () -> save("batch-2", items, Intent.SAVE_NOW));
        assertThat(value("F002", "purpose")).isEmpty();
        assertThat(value("F004", "purpose")).isEmpty();
        assertThat(ledger()).isZero();
    }

    @Test void fencesHoldBeforeAnythingIsRead() {
        var one = List.of(item("op-1", "F002", "purpose", "a"));
        fails(ErrorCode.INVALID_ARGUMENT, () -> authorizeExplicitBatch(user, TASK, "b", one, Intent.SAVE_NOW));
        fails(ErrorCode.INVALID_ARGUMENT, () -> authorizeExplicitBatch(user, TASK, "b",
                List.of(item("op-1", "F002", "purpose", "a"), item("op-2", "F002", "remarks", "b")), Intent.SAVE_NOW));
        fails(ErrorCode.INVALID_ARGUMENT, () -> authorizeExplicitBatch(user, TASK, "b",
                List.of(item("op-1", "F002", "purpose", "a"), item("op-1", "F004", "purpose", "b")), Intent.SAVE_NOW));
        fails(ErrorCode.INVALID_ARGUMENT, () -> authorizeExplicitBatch(user, TASK, "op-1",
                List.of(item("op-1", "F002", "purpose", "a"), item("op-2", "F004", "purpose", "b")), Intent.SAVE_NOW));
        fails(ErrorCode.PERMISSION_DENIED, () -> authorizeExplicitBatch(user, TASK, "b",
                List.of(item("op-1", "F002", "purpose", "a"), new BatchItem("op-2", new InvoiceUpdate(new InvoiceKey("C002", "F002"), Map.of("purpose", "b")))), Intent.SAVE_NOW));
        fails(ErrorCode.CONFIRMATION_REQUIRED, () -> authorizeExplicitBatch(user, TASK, "b",
                List.of(item("op-1", "F002", "purpose", ""), item("op-2", "F004", "purpose", "b")), Intent.SAVE_NOW));
        fails(ErrorCode.CONFIRMATION_REQUIRED, () -> authorizeExplicitBatch(user, TASK, "b",
                List.of(item("op-1", "F002", "purpose", "a"), item("op-2", "F004", "purpose", "b")), Intent.EXPLANATION));
        var many = new ArrayList<BatchItem>();
        for (int i = 0; i <= InvoiceWriteService.MAX_BATCH; i++) many.add(item("op-" + i, "X" + i, "purpose", "a"));
        fails(ErrorCode.INVALID_ARGUMENT, () -> authorizeExplicitBatch(user, TASK, "b", many, Intent.SAVE_NOW));
        var approved = List.of(item("op-1", "F002", "purpose", "a"), item("op-2", "F004", "purpose", "b"));
        var authorization = authorizeExplicitBatch(user, TASK, "b", approved, Intent.SAVE_NOW);
        var other = List.of(item("op-1", "F002", "purpose", "a"), item("op-2", "F004", "purpose", "changed"));
        fails(ErrorCode.CONFIRMATION_REQUIRED, () -> service.executeBatch(user, TASK, "b", other, authorization));
    }

    @Test void approvedBatchTurnClearsEveryStatedFieldOnlyWithItsVerifiedReadBack() throws Exception {
        var model = new ContextProjectorTest.FakeLlmClient(new Call("save", "update_invoice", "{}"), new Finish("两张发票已保存并核验。"));
        var agent = new FinanceAgent(db, user, "C001", model);
        var task = agent.start("补充九月用途", List.of("F002.purpose", "F004.purpose"));
        task = agent.supplement(task.id(), task.version(), Map.of(
                "F002.purpose", new TaskMemory.Input("复印资料", "F002 是复印资料"),
                "F004.purpose", new TaskMemory.Input("出差住宿", "F004 是出差住宿")));
        assertThat(task.awaitingSave()).containsExactly("F002.purpose", "F004.purpose");
        var items = List.of(item("op-1", "F002", "purpose", "复印资料"), item("op-2", "F004", "purpose", "出差住宿"));
        var turn = agent.approvedBatchTurn(task.id(), "现在批量保存", "batch-1", items, Intent.SAVE_NOW, progress -> {});
        assertThat(turn.result().status()).isEqualTo(AgentRuntime.Status.FINISHED);
        assertThat(turn.task().awaitingSave()).isEmpty();
        assertThat(turn.task().status()).isEqualTo(TaskMemory.Status.COMPLETED);
        assertThat(value("F004", "purpose")).isEqualTo("出差住宿");
        // The model was told about the whole batch, and only ever sent {}.
        assertThat(model.requests.getFirst().userMessage()).contains("批量保存 2 张发票", "F002", "F004");

        var silent = new ContextProjectorTest.FakeLlmClient(new Finish("已保存。"));
        var other = new FinanceAgent(db, user, "C001", silent);
        var next = other.start("再改两张", List.of());
        var more = List.of(item("op-3", "F003", "purpose", "寄送合同"), item("op-4", "F009", "purpose", "设备维修"));
        var unsaved = other.approvedBatchTurn(next.id(), "保存", "batch-2", more, Intent.SAVE_NOW, progress -> {});
        assertThat(unsaved.result().errorCode()).isEqualTo("WRITE_NOT_EXECUTED");
        assertThat(value("F003", "purpose")).isEmpty();
    }

    @Test void batchApprovalThroughTheApiAuditsEveryInvoice() throws Exception {
        var model = new ContextProjectorTest.FakeLlmClient(new Call("save", "update_invoice", "{}"), new Finish("已保存。"));
        MockMvc mvc = standaloneSetup(new DemoController(db.toString(), model))
                .setControllerAdvice(new DemoController.Errors()).addFilters(new DemoSecurity()).build();
        var json = new ObjectMapper();
        var first = mvc.perform(get("/api/demo/session")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) first.getRequest().getSession();
        var csrf = json.readTree(first.getResponse().getContentAsString()).path("csrfToken").asText();
        var selected = mvc.perform(post("/api/demo/session").contentType("application/json").content("{\"profile\":\"operator\",\"enterprise\":\"C001\"}")
                .session(session).header("X-CSRF-Token", csrf)).andExpect(status().isOk()).andReturn();
        session = (MockHttpSession) selected.getRequest().getSession();
        csrf = json.readTree(selected.getResponse().getContentAsString()).path("csrfToken").asText();
        var created = mvc.perform(post("/api/demo/tasks").contentType("application/json").content("{\"goal\":\"批量补充用途\",\"pending\":[]}")
                .session(session).header("X-CSRF-Token", csrf)).andExpect(status().isOk()).andReturn();
        var task = json.readTree(created.getResponse().getContentAsString()).path("id").asText();
        String batch = UUID.randomUUID().toString(), op1 = UUID.randomUUID().toString(), op2 = UUID.randomUUID().toString();
        var approval = Map.of("operationId", batch, "confirmed", true, "items", List.of(
                Map.of("operationId", op1, "invoiceId", "F002", "changes", Map.of("purpose", "复印资料")),
                Map.of("operationId", op2, "invoiceId", "F004", "changes", Map.of("purpose", "出差住宿"))));
        var result = mvc.perform(post("/api/demo/tasks/" + task + "/turn").contentType("application/json")
                .content(json.writeValueAsString(Map.of("message", "现在批量保存", "approval", approval)))
                .session(session).header("X-CSRF-Token", csrf)).andExpect(status().isOk()).andReturn();
        JsonNode view = json.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(view.path("operationId").asText()).isEqualTo(batch);
        assertThat(view.path("verification").isNull()).isTrue();
        assertThat(view.at("/batch/verified").asBoolean()).isTrue();
        assertThat(view.at("/batch/results")).hasSize(2);
        assertThat(view.path("message").asText()).contains("批量 2 张");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement();
             var r = s.executeQuery("SELECT count(*) FROM demo_audit WHERE tool='update_invoice' AND verified=1 AND operation_id IN ('" + op1 + "','" + op2 + "')")) {
            assertThat(r.getLong(1)).isEqualTo(2);
        }
        // A batch mixed with single-save fields is refused before any model call.
        var mixed = new HashMap<String, Object>(approval); mixed.put("invoiceId", "F002");
        mvc.perform(post("/api/demo/tasks/" + task + "/turn").contentType("application/json")
                .content(json.writeValueAsString(Map.of("message", "再保存", "approval", mixed)))
                .session(session).header("X-CSRF-Token", csrf)).andExpect(status().isBadRequest());
    }
}
