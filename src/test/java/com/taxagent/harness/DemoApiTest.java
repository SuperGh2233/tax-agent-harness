package com.taxagent.harness;

import com.fasterxml.jackson.databind.*;
import com.taxagent.harness.demo.*;
import com.taxagent.harness.runtime.*;
import com.taxagent.harness.tool.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static com.taxagent.harness.runtime.LlmClient.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.*;

class DemoApiTest {
    @TempDir Path directory;
    Path db;
    MockMvc mvc;
    MockHttpSession session;
    String csrf;
    static final ObjectMapper JSON = new ObjectMapper();
    static final String WRITE = "{\"key\":{\"enterprise_id\":\"C001\",\"invoice_id\":\"F002\"},\"changes\":{\"purpose\":\"private-purpose\"}}";
    static class FakeLlmClient implements LlmClient {
        final Queue<Decision> script = new ArrayDeque<>();
        final List<Request> seen = new ArrayList<>();
        FakeLlmClient(Decision... decisions) { script.addAll(List.of(decisions)); }
        public Decision next(Request request) { seen.add(request); return script.remove(); }
    }
    @BeforeEach void init() throws Exception {
        db = directory.resolve("demo.db"); DataInitializer.initialize(db);
        setup(new FakeLlmClient(new Ask("请明确月份")));
        bootstrap();
    }
    void setup(LlmClient model) {
        mvc = standaloneSetup(new DemoController(db.toString(),model))
                .setControllerAdvice(new DemoController.Errors()).addFilters(new DemoSecurity()).build();
    }
    void bootstrap() throws Exception {
        var result = mvc.perform(get("/api/demo/session")).andExpect(status().isOk()).andReturn();
        session = (MockHttpSession) result.getRequest().getSession(); csrf = body(result).path("csrfToken").asText();
    }
    JsonNode body(MvcResult result) throws Exception { return JSON.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)); }
    JsonNode ok(MockHttpServletRequestBuilder req) throws Exception {
        return body(mvc.perform(req.session(session).header("X-CSRF-Token",csrf)).andExpect(status().isOk()).andReturn());
    }
    MockHttpServletRequestBuilder postJson(String path, Object value) throws Exception {
        return post("/api/demo"+path).contentType("application/json").content(value instanceof String s ? s : JSON.writeValueAsString(value));
    }
    void select(String profile,String enterprise) throws Exception {
        var result = mvc.perform(postJson("/session",Map.of("profile",profile,"enterprise",enterprise)).session(session).header("X-CSRF-Token",csrf))
                .andExpect(status().isOk()).andReturn();
        session = (MockHttpSession) result.getRequest().getSession(); csrf = body(result).path("csrfToken").asText();
    }
    String start() throws Exception { return ok(postJson("/tasks",Map.of("goal","检查九月并补充","pending",List.of("F002.purpose")))).path("id").asText(); }
    JsonNode turn(String task,Object input) throws Exception { return ok(postJson("/tasks/"+task+"/turn",input)); }
    Map<String,Object> approved(String operation) { return Map.of("message","现在保存后回查", "approval",Map.of("operationId",operation,"invoiceId","F002","changes",Map.of("purpose","private-purpose"),"confirmed",true)); }
    long count(String table) throws Exception {
        try (var c=DriverManager.getConnection("jdbc:sqlite:"+db); var s=c.createStatement(); var r=s.executeQuery("SELECT count(*) FROM "+table)) { return r.getLong(1); }
    }
    void sql(String sql) throws Exception { try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var s=c.createStatement()){s.execute(sql);} }
    String purpose() throws Exception {
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var s=c.createStatement();var r=s.executeQuery("SELECT purpose FROM invoices WHERE enterprise_id='C001' AND id='F002'")){return r.getString(1);}
    }
    String verificationPath(String task, String operation) { return "/tasks/"+task+"/operations/"+operation+"/verify"; }
    JsonNode verifyOperation(String task, String operation) throws Exception {
        return ok(postJson(verificationPath(task,operation),Map.of()));
    }
    @Test void lostResponseCanBeVerifiedRepeatedlyWithoutFinancialWrites() throws Exception {
        setup(new FakeLlmClient(new Call("save","update_invoice",WRITE),new Finish("saved")));
        select("operator","C001"); String task=start(), op=UUID.randomUUID().toString();
        turn(task,approved(op)); // Discard the original response.
        sql("CREATE TRIGGER no_verify_update BEFORE UPDATE ON invoices BEGIN SELECT RAISE(ABORT,'no update'); END");
        sql("CREATE TRIGGER no_verify_insert BEFORE INSERT ON invoice_operations BEGIN SELECT RAISE(ABORT,'no insert'); END");
        var result=verifyOperation(task,op);
        assertThat(result.path("status").asText()).isEqualTo("VERIFIED");
        assertThat(result.path("operationId").asText()).isEqualTo(op);
        assertThat(result.at("/verification/verified").isBoolean()).isTrue();
        assertThat(result.at("/verification/verified").booleanValue()).isTrue();
        assertThat(verifyOperation(task,op)).isEqualTo(result);
        assertThat(count("invoice_operations")).isEqualTo(1);
        var events=ok(get("/api/demo/audit"));
        assertThat(events.findValuesAsText("outcome")).contains("VERIFIED");
        var event=events.get(0);
        assertThat(event.path("taskId").asText()).isEqualTo(task);
        assertThat(event.path("operationId").asText()).isEqualTo(op);
        assertThat(event.path("tool").asText()).isEqualTo("verify");
        assertThat(event.toString()).doesNotContain("private-purpose");
    }
    MockHttpServletRequestBuilder upload(String name, byte[] bytes, int declaredLength) {
        return multipart("/api/demo/files").file(new org.springframework.mock.web.MockMultipartFile("file", name, null, bytes))
                .with(req -> { if (declaredLength >= 0) req.setContent(new byte[declaredLength]); return req; });
    }
    @Test void uploadEndpointRequiresWriteAccessDeclaredLengthAndIsAudited() throws Exception {
        var bytes = FileServiceTest.workbook(true, new Object[]{"C001","F101","2026-10-08","销售方","项目",10,"",""});
        select("operator","C001");
        var result = body(mvc.perform(upload("十月.xlsx",bytes,bytes.length).session(session).header("X-CSRF-Token",csrf))
                .andExpect(status().isOk()).andReturn());
        assertThat(result.path("status").asText()).isEqualTo("IMPORTED");
        assertThat(result.path("invoicesImported").asInt()).isEqualTo(1);
        var id = result.at("/file/id").asText();
        assertThat(ok(get("/api/demo/files"))).hasSize(1);
        assertThat(ok(get("/api/demo/files/"+id)).path("chunks").size()).isPositive();
        assertThat(ok(get("/api/demo/audit")).toString()).contains("file-upload");
        mvc.perform(upload("十月.xlsx",bytes,bytes.length).session(session)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("CSRF_REQUIRED"));
        mvc.perform(upload("十月.xlsx",bytes,-1).session(session).header("X-CSRF-Token",csrf)).andExpect(status().is(411));
        mvc.perform(upload("大.xlsx",bytes,11*1024*1024).session(session).header("X-CSRF-Token",csrf)).andExpect(status().is(413));
        select("viewer","C001");
        mvc.perform(upload("十月-v2.xlsx",bytes,bytes.length).session(session).header("X-CSRF-Token",csrf))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("PERMISSION_DENIED"));
        assertThat(count("uploaded_files")).isEqualTo(1);
    }
    @Test void agentsAreConfiguredOverHttpAndBindTasks() throws Exception {
        var fake = new FakeLlmClient(new Ask("请明确月份"), new Ask("仍需月份"));
        setup(fake); select("operator","C001");
        var listed = ok(get("/api/demo/agents"));
        assertThat(listed.path("agents").findValuesAsText("id")).containsExactly("finance-assistant");
        assertThat(listed.path("tools").size()).isEqualTo(7); // T34 added recall_context.
        var spec = Map.of("name","只读查询员","responsibility","只回答查询，不保存","instructions","简短回答",
                "modelProfile","default","tools",List.of("query_invoices","query_rules"),"enterprises",List.of("C001"));
        var saved = ok(postJson("/agents", Map.of("id","finance-reader","spec",spec)));
        assertThat(saved.path("version").asInt()).isEqualTo(1);
        String task = ok(postJson("/tasks",Map.of("goal","只读查询","pending",List.of(),"agentId","finance-reader"))).path("id").asText();
        assertThat(ok(get("/api/demo/tasks/"+task)).at("/agent/id").asText()).isEqualTo("finance-reader");
        var turn = turn(task, Map.of("message","查询发票"));
        assertThat(turn.at("/promptTrace/systemPrompt").asText()).contains("[Agent finance-reader v1 · 只读查询员]");
        assertThat(fake.seen.getFirst().tools()).extracting(Tool::name).containsExactlyInAnyOrder("query_invoices","query_rules");
        // A read-only agent is never handed a save, even for an operator.
        mvc.perform(postJson("/tasks/"+task+"/turn",approved(UUID.randomUUID().toString())).session(session).header("X-CSRF-Token",csrf))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("CAPABILITY_NOT_GRANTED"));
        assertThat(purpose()).isEmpty();
        ok(postJson("/agents/finance-reader/enabled", Map.of("enabled", false)));
        mvc.perform(postJson("/tasks/"+task+"/turn",Map.of("message","再查")).session(session).header("X-CSRF-Token",csrf))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("PERMISSION_DENIED"));
        assertThat(ok(get("/api/demo/agents/finance-reader/history"))).hasSize(2);
        assertThat(ok(get("/api/demo/audit")).toString()).contains("agent-save","agent-disable");
        select("viewer","C001");
        assertThat(ok(get("/api/demo/agents")).path("agents")).hasSize(2);
        mvc.perform(postJson("/agents", Map.of("id","viewer-agent","spec",spec)).session(session).header("X-CSRF-Token",csrf))
                .andExpect(status().isForbidden());
    }
    @Test void deletedMemoryIsErasedFromStoredModelInputsButOtherMemoriesStay() throws Exception {
        setup(new FakeLlmClient(new Ask("好的"), new Ask("好的")));
        select("operator","C001");
        ok(postJson("/memories", Map.of("scope","PERSONAL","name","tone","value","先给结论再展开","userRequest","以后先给结论")));
        ok(postJson("/memories", Map.of("scope","USER_ENTERPRISE","name","focus","value","关注九月发票","userRequest","我主要看九月")));
        String task=start(); turn(task, Map.of("message","你好"));
        var before = ok(get("/api/demo/tasks/"+task+"/traces")).get(0).path("userMessage").asText();
        assertThat(before).contains("个人·tone","先给结论再展开","关注九月发票");
        var listed = ok(delete("/api/demo/memories/PERSONAL/tone"));
        assertThat(listed.findValuesAsText("name")).containsExactly("focus");
        setup(new FakeLlmClient()); // Restart: a new controller over the same database.
        var after = ok(get("/api/demo/tasks/"+task+"/traces")).get(0).path("userMessage").asText();
        assertThat(after).doesNotContain("先给结论再展开","以后先给结论").contains("个人·tone","[已删除的记忆]","关注九月发票");
        // The append-only guard is back in force after the erase.
        assertThatThrownBy(() -> sql("UPDATE agent_trace SET user_message='x'")).isInstanceOf(SQLException.class).hasMessageContaining("append only");
        assertThat(ok(get("/api/demo/audit")).toString()).contains("memory-delete-personal").doesNotContain("先给结论再展开");
        // Enterprise memory: viewers read it but cannot change it.
        ok(postJson("/memories", Map.of("scope","ENTERPRISE","name","approval","value","报销需负责人签字","userRequest","公司规定")));
        select("viewer","C001");
        assertThat(ok(get("/api/demo/memories")).findValuesAsText("name")).containsExactly("approval");
        mvc.perform(postJson("/memories", Map.of("scope","ENTERPRISE","name","approval","value","无需签字","userRequest","x")).session(session).header("X-CSRF-Token",csrf))
                .andExpect(status().isForbidden());
    }
    @Test void parallelReadsAndWritesDoNotFailOnStorageLocks() throws Exception {
        select("operator","C001");
        // The workspace fires these together after login; each used to run schema migrations on its own connection.
        var paths = List.of("/api/demo/tasks","/api/demo/agents","/api/demo/capabilities","/api/demo/memories","/api/demo/files","/api/demo/preferences");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(12);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<String>>();
            for (int round = 0; round < 4; round++)
                for (var path : paths)
                    futures.add(pool.submit(() -> {
                        var response = mvc.perform(get(path).session(session).header("X-CSRF-Token",csrf)).andReturn().getResponse();
                        return response.getStatus() == 200 ? "OK" : path + " " + response.getStatus() + " " + response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                    }));
            // Writes in the same burst: creating the task (as the workspace does right after login) and memory saves.
            futures.add(pool.submit(() -> {
                var response = mvc.perform(postJson("/tasks", Map.of("goal","并发创建","pending",List.of())).session(session).header("X-CSRF-Token",csrf)).andReturn().getResponse();
                return response.getStatus() == 200 ? "OK" : "POST /tasks " + response.getStatus() + " " + response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            }));
            for (int i = 0; i < 6; i++) {
                int n = i;
                futures.add(pool.submit(() -> {
                    var response = mvc.perform(postJson("/memories", Map.of("scope","USER_ENTERPRISE","name","m" + n,"value","v" + n,"userRequest","r" + n))
                            .session(session).header("X-CSRF-Token",csrf)).andReturn().getResponse();
                    return response.getStatus() == 200 ? "OK" : "POST /memories " + response.getStatus() + " " + response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                }));
            }
            var failures = new ArrayList<String>();
            for (var f : futures) { var r = f.get(60, java.util.concurrent.TimeUnit.SECONDS); if (!r.equals("OK")) failures.add(r); }
            assertThat(failures).isEmpty();
            assertThat(count("agent_tasks")).isEqualTo(1);
            assertThat(count("agent_preferences")).isEqualTo(6);
        } finally { pool.shutdownNow(); }
    }
    @Test void approvedWriteWithEmptyArgumentsAuditsTrustedInvoiceAndFields() throws Exception {
        setup(new FakeLlmClient(new Call("save","update_invoice","{}"),new Finish("saved")));
        select("operator","C001"); String task=start(), op=UUID.randomUUID().toString();
        var result=turn(task,approved(op));
        assertThat(result.at("/verification/verified").booleanValue()).isTrue();
        assertThat(purpose()).isEqualTo("private-purpose");
        JsonNode write=null;
        for (var event:ok(get("/api/demo/audit"))) if ("update_invoice".equals(event.path("tool").asText())) write=event;
        assertThat(write).isNotNull();
        assertThat(write.path("invoiceId").asText()).isEqualTo("F002");
        assertThat(write.path("fields").asText()).isEqualTo("purpose");
        assertThat(write.toString()).doesNotContain("private-purpose");
    }
    @Test void approvedFinishWithoutWriteIsTerminalNotExecutedAndKeepsTaskOpen() throws Exception {
        setup(new FakeLlmClient(new Finish("已保存")));
        select("operator","C001"); String task=start(), op=UUID.randomUUID().toString();
        String before=purpose();
        var result=turn(task,approved(op));
        assertThat(result.path("errorCode").asText()).isEqualTo("WRITE_NOT_EXECUTED");
        assertThat(result.path("verification").isNull()).isTrue();
        assertThat(result.path("operationId").asText()).isEqualTo(op);
        assertThat(result.path("message").asText()).contains("本轮未执行保存");
        assertThat(result.at("/turn/task/status").asText()).isEqualTo("ACTIVE");
        assertThat(result.at("/turn/result/status").asText()).isEqualTo("ERROR");
        assertThat(purpose()).isEqualTo(before);
        assertThat(count("invoice_operations")).isZero();
        assertThat(ok(get("/api/demo/audit")).findValuesAsText("tool")).doesNotContain("update_invoice");
    }
    @Test void ruleQueryIsAuditedWithVersionDurationAndMemorySource() throws Exception {
        setup(new FakeLlmClient(new Call("rules","query_rules","{\"enterprise_id\":\"C001\",\"date\":\"2026-09-15\"}"), new Finish("依据 R004 1.0")));
        select("operator","C001"); String task=start();
        var result=turn(task, Map.of("message","C001 九月银行支出能证明 F002 已付款吗"));
        assertThat(result.path("modelInput").asText()).contains("F002.purpose");
        assertThat(result.path("promptTrace").isObject()).isTrue();
        assertThat(result.at("/promptTrace/userMessage").asText()).contains("F002.purpose");
        assertThat(result.at("/promptTrace/tools").isArray()).isTrue();
        var events=ok(get("/api/demo/audit"));
        JsonNode ruleEvent=null;
        for (var event:events) if ("query_rules".equals(event.path("tool").asText())) ruleEvent=event;
        assertThat(ruleEvent).isNotNull();
        assertThat(ruleEvent.path("ruleVersion").asText()).contains("R004@1.0");
        assertThat(ruleEvent.path("durationMs").isNumber()).isTrue();
        assertThat(ruleEvent.path("memorySource").asText()).isEqualTo("task-memory");
    }
    @Test void sameRequestIdReplaysApprovedWriteWithoutSecondExecution() throws Exception {
        var fake = new FakeLlmClient(new Call("save","update_invoice",WRITE), new Finish("saved"));
        setup(fake); select("operator","C001"); String task=start();
        String op=UUID.randomUUID().toString(), rid=UUID.randomUUID().toString();
        var input = new HashMap<String,Object>(approved(op)); input.put("requestId",rid);
        var first=turn(task,input);
        int modelCalls=fake.seen.size();
        var second=turn(task,input);
        assertThat(second).isEqualTo(first);
        assertThat(fake.seen).hasSize(modelCalls);
        assertThat(count("invoice_operations")).isEqualTo(1);
        var changed = new HashMap<String,Object>(input); changed.put("message","完全不同的请求");
        mvc.perform(postJson("/tasks/"+task+"/turn",changed).session(session).header("X-CSRF-Token",csrf))
                .andExpect(status().isConflict());
        assertThat(fake.seen).hasSize(modelCalls);
    }
    @Test void preferencesAreExplicitScopedAndDeletable() throws Exception {
        select("operator","C001");
        assertThat(ok(get("/api/demo/preferences")).size()).isZero();
        var saved=ok(postJson("/preferences",Map.of("name","style","value","简洁","userRequest","用户喜欢简洁")));
        assertThat(saved.path("style").path("value").asText()).isEqualTo("简洁");
        var updated=ok(postJson("/preferences",Map.of("name","style","value","详细","userRequest","用户改为详细")));
        assertThat(updated.path("style").path("value").asText()).isEqualTo("详细");
        assertThat(ok(get("/api/demo/audit")).toString()).doesNotContain("用户喜欢简洁","简洁","详细");
        mvc.perform(delete("/api/demo/preferences/style").session(session).header("X-CSRF-Token",csrf)).andExpect(status().isOk());
        assertThat(ok(get("/api/demo/preferences")).size()).isZero();
    }
    @Test void auditExportIsASanitizedCsv() throws Exception {
        setup(new FakeLlmClient(new Finish("查询完成")));
        select("operator","C001"); String task=start();
        turn(task, Map.of("message","查询说明"));
        var result=mvc.perform(get("/api/demo/audit/export").session(session)).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getContentType()).contains("text/csv");
        assertThat(result.getResponse().getHeader("Content-Disposition")).contains("taxagent-audit.csv");
        var csv=result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("requestId","turn","taskId").doesNotContain("private-purpose");
    }
    /** T51: an accountant opening the export in Excel reads Chinese headers; the original keys stay in brackets. */
    @Test void auditExportHeadersAreReadableAndKeepTheOriginalKeys() throws Exception {
        setup(new FakeLlmClient(new Finish("查询完成")));
        select("operator","C001"); start();
        var csv=mvc.perform(get("/api/demo/audit/export").session(session)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv.lines().findFirst().orElseThrow()).startsWith("序号(id),时间UTC(time),请求编号(requestId),任务编号(taskId)")
                .endsWith("耗时毫秒(durationMs)").doesNotContain(" ");
    }
    @Test void ordinaryTurnHidesWriteToolAndApprovedTurnPublishesIt() throws Exception {
        var fake = new FakeLlmClient(new Finish("查询完成"), new Finish("已保存"));
        setup(fake); select("operator","C001"); String task=start();
        turn(task, Map.of("message","查询说明"));
        assertThat(fake.seen.get(0).tools()).extracting(Tool::name).doesNotContain("update_invoice");
        turn(task, approved(UUID.randomUUID().toString()));
        assertThat(fake.seen.get(1).tools()).extracting(Tool::name).contains("update_invoice");
    }
    @Test void viewerCannotRequestApprovedWrite() throws Exception {
        setup(new FakeLlmClient(new Call("save","update_invoice",WRITE), new Finish("saved")));
        select("viewer","C001"); String task=start();
        mvc.perform(postJson("/tasks/"+task+"/turn", approved(UUID.randomUUID().toString()))
                        .session(session).header("X-CSRF-Token",csrf))
                .andExpect(status().isForbidden());
        assertThat(count("invoice_operations")).isZero();
    }
    @Test void verificationFailsClosedForMissingMismatchedLegacyAndDriftedOperations() throws Exception {
        setup(new FakeLlmClient(new Call("save","update_invoice",WRITE),new Finish("saved")));
        select("operator","C001"); String task=start(), op=UUID.randomUUID().toString();
        turn(task,approved(op)); String other=start();
        for (String id:List.of(op,UUID.randomUUID().toString())) {
            var result=verifyOperation(other,id);
            assertThat(result.path("status").asText()).isEqualTo("NOT_FOUND");
            assertThat(result.path("verification").isNull()).isTrue();
            assertThat(result.toString()).doesNotContain("private-purpose");
        }
        sql("UPDATE invoices SET remarks='later' WHERE enterprise_id='C001' AND id='F002'");
        var drift=verifyOperation(task,op);
        assertThat(drift.path("status").asText()).isEqualTo("DIVERGED");
        assertThat(drift.path("verification").isNull()).isTrue();
        sql("UPDATE invoice_operations SET task_id=NULL");
        assertThat(verifyOperation(task,op).path("status").asText()).isEqualTo("NOT_FOUND");
        select("viewer","C001");
        mvc.perform(postJson(verificationPath(task,op),Map.of()).session(session).header("X-CSRF-Token",csrf)).andExpect(status().isNotFound());
        select("operator","C002");
        mvc.perform(postJson(verificationPath(task,op),Map.of()).session(session).header("X-CSRF-Token",csrf)).andExpect(status().isNotFound());
    }
    @Test void verificationRequiresCsrfCanonicalIdsAndSuccessfulAudit() throws Exception {
        setup(new FakeLlmClient(new Call("save","update_invoice",WRITE),new Finish("saved")));
        select("operator","C001"); String task=start(), op=UUID.randomUUID().toString();
        turn(task,approved(op));
        mvc.perform(postJson(verificationPath(task,op),Map.of()).session(session)).andExpect(status().isForbidden());
        for (String path:List.of(verificationPath("bad",op),verificationPath(task,"bad")))
            mvc.perform(postJson(path,Map.of()).session(session).header("X-CSRF-Token",csrf)).andExpect(status().isBadRequest());
        sql("CREATE TRIGGER fail_verification_audit BEFORE INSERT ON demo_audit WHEN NEW.tool='verify' BEGIN SELECT RAISE(ABORT,'audit failure'); END");
        mvc.perform(postJson(verificationPath(task,op),Map.of()).session(session).header("X-CSRF-Token",csrf)).andExpect(status().isInternalServerError());
        assertThat(count("invoice_operations")).isEqualTo(1);
        sql("DROP TRIGGER fail_verification_audit");
        assertThat(verifyOperation(task,op).path("status").asText()).isEqualTo("VERIFIED");
    }
    @ParameterizedTest @ValueSource(strings={"cancel","supplement","verify"})
    void crossSessionMutationWaitsForApprovedTurn(String mutation) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var attempted = new CountDownLatch(1);
        var calls = new AtomicInteger();
        setup(request -> {
            if (calls.getAndIncrement() != 0) return new Finish("saved");
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("model latch timed out");
            return new Call("write-1","update_invoice",WRITE);
        });
        select("operator","C001");
        String id = start(), operation = UUID.randomUUID().toString();
        var turnSession = session; String turnCsrf = csrf;
        bootstrap(); select("operator","C001");
        var otherSession = session; String otherCsrf = csrf;
        assertThat(otherSession).isNotSameAs(turnSession);
        var turnRequest = postJson("/tasks/"+id+"/turn",approved(operation)).session(turnSession).header("X-CSRF-Token",turnCsrf);
        Object input = mutation.equals("cancel") ? Map.of("version",0,"confirmed",true)
                : Map.of("version",0,"statements",Map.of("F002.purpose",Map.of("value","fact","userRequest","explicit fact")));
        var mutationRequest = postJson(mutation.equals("verify") ? verificationPath(id,operation) : "/tasks/"+id+"/"+mutation,input).session(otherSession).header("X-CSRF-Token",otherCsrf)
                .with(r -> { attempted.countDown(); return r; });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var running = executor.submit(() -> mvc.perform(turnRequest).andExpect(status().isOk()).andReturn());
            assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            var competing = executor.submit(() -> mvc.perform(mutationRequest).andReturn());
            assertThat(attempted.await(3,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> competing.get(250,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(count("invoice_operations")).isZero();
            release.countDown();
            var result = body(running.get(5,TimeUnit.SECONDS));
            assertThat(result.at("/turn/task/status").asText()).isEqualTo("COMPLETED");
            assertThat(result.at("/verification/verified").asBoolean()).isTrue();
            var conflict = competing.get(5,TimeUnit.SECONDS);
            if (mutation.equals("verify")) {
                assertThat(conflict.getResponse().getStatus()).isEqualTo(200);
                assertThat(body(conflict).path("status").asText()).isEqualTo("VERIFIED");
            } else {
                assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
                assertThat(body(conflict).path("errorCode").asText()).isEqualTo("CONFLICT");
            }
            assertThat(ok(get("/api/demo/tasks/"+id)).at("/task/status").asText()).isEqualTo("COMPLETED");
            assertThat(count("invoice_operations")).isEqualTo(1);
            assertThat(purpose()).isEqualTo("private-purpose");
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5,TimeUnit.SECONDS)).isTrue();
        }
    }
    @ParameterizedTest @ValueSource(strings={"127.0.0.1","::1","0:0:0:0:0:0:0:1"})
    void loopbackAccepted(String address) throws Exception {
        mvc.perform(get("/api/demo/session").with(r->{r.setRemoteAddr(address);return r;})).andExpect(status().isOk());
    }
    @ParameterizedTest @ValueSource(strings={"192.168.1.2","8.8.8.8","::2","127.0.0.1.evil","localhost"})
    void externalAddressRejected(String address) throws Exception {
        mvc.perform(get("/api/demo/session").with(r->{r.setRemoteAddr(address);return r;})).andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("LOOPBACK_REQUIRED"));
    }
    @Test void browserOriginHostAndCorsAreRestricted() throws Exception {
        mvc.perform(get("/api/demo/session").header("Origin","https://evil.example")).andExpect(status().isForbidden());
        mvc.perform(get("/api/demo/session").with(r->{r.setServerName("evil.example");return r;})).andExpect(status().isForbidden());
        mvc.perform(get("/api/demo/session").header("X-Forwarded-For","127.0.0.1")).andExpect(status().isForbidden());
        mvc.perform(options("/api/demo/tasks").header("Origin","http://evil.example")).andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(get("/api/demo/session").header("Origin","http://localhost")).andExpect(status().isOk());
    }
    @Test void csrfRequiredOnAllMutationsAndRotatesOnSelection() throws Exception {
        mvc.perform(postJson("/session",Map.of("profile","operator","enterprise","C001")).session(session)).andExpect(status().isForbidden());
        String old = csrf; select("operator","C001"); assertThat(csrf).isNotEqualTo(old);
        mvc.perform(postJson("/tasks",Map.of("goal","query")).session(session).header("X-CSRF-Token",old)).andExpect(status().isForbidden());
        mvc.perform(postJson("/tasks/"+UUID.randomUUID()+"/supplement",Map.of()).session(session)).andExpect(status().isForbidden());
        mvc.perform(postJson("/tasks/"+UUID.randomUUID()+"/turn",Map.of()).session(session)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/demo/session").session(session)).andExpect(status().isForbidden());
        ok(delete("/api/demo/session"));
        mvc.perform(get("/api/demo/tasks")).andExpect(status().isUnauthorized());
    }
    @Test void nullRequestBodyIsInvalidRatherThanServerFailure() throws Exception {
        mvc.perform(postJson("/session","null").session(session).header("X-CSRF-Token",csrf)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"));
    }
    @Test void fixedProfilesAndArbitraryIdentityFieldsRejected() throws Exception {
        mvc.perform(postJson("/session","{\"profile\":\"operator\",\"enterprise\":\"C001\",\"userId\":\"U001\"}").session(session).header("X-CSRF-Token",csrf)).andExpect(status().isBadRequest());
        mvc.perform(postJson("/session",Map.of("profile","U001","enterprise","C001")).session(session).header("X-CSRF-Token",csrf)).andExpect(status().isBadRequest());
        select("viewer","C001");
        mvc.perform(postJson("/tasks","{\"goal\":\"x\",\"userId\":\"U001\"}").session(session).header("X-CSRF-Token",csrf)).andExpect(status().isBadRequest());
        String task=start();
        mvc.perform(postJson("/tasks/"+task+"/turn","{\"message\":\"x\",\"user_id\":\"U001\"}").session(session).header("X-CSRF-Token",csrf)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/demo/tasks?userId=U001").session(session)).andExpect(status().isForbidden());
    }
    @Test void viewerReadsC001CannotWriteOrSelectC002() throws Exception {
        setup(new FakeLlmClient(new Call("q","query_invoices","{\"enterprise_id\":\"C001\",\"month\":\"2026-09\"}"),new Ask("只读查询完成"),new Call("w","update_invoice",WRITE),new Finish("不能掩盖错误")));
        select("viewer","C001"); String task=start();
        var queried=turn(task,Map.of("message","查询九月"));
        assertThat(queried.at("/turn/result/history/0/resultJson").asText()).contains("962206");
        var denied=mvc.perform(postJson("/tasks/"+task+"/turn",approved(UUID.randomUUID().toString()))
                .session(session).header("X-CSRF-Token",csrf)).andExpect(status().isForbidden()).andReturn();
        assertThat(body(denied).path("errorCode").asText()).isEqualTo("PERMISSION_DENIED");
        assertThat(count("invoice_operations")).isZero();
        mvc.perform(postJson("/session",Map.of("profile","viewer","enterprise","C002")).session(session).header("X-CSRF-Token",csrf)).andExpect(status().isForbidden());
    }
    @Test void tasksAndAuditIsolateUsersEnterprisesAndMissingSessions() throws Exception {
        select("operator","C001"); String task=start(); assertThat(ok(get("/api/demo/tasks"))).hasSize(1);
        var operatorSession=session;
        assertThat(ok(get("/api/demo/audit")).toString()).contains(task);
        bootstrap(); mvc.perform(get("/api/demo/tasks").session(session)).andExpect(status().isUnauthorized());
        select("viewer","C001"); assertThat(ok(get("/api/demo/tasks"))).isEmpty();
        assertTaskAccessDeniedWithoutAuditId(task);
        select("operator","C002"); assertThat(ok(get("/api/demo/tasks"))).isEmpty();
        assertTaskAccessDeniedWithoutAuditId(task);
        session=operatorSession; csrf=ok(get("/api/demo/session")).path("csrfToken").asText();
        assertThat(ok(get("/api/demo/tasks/"+task)).at("/task/id").asText()).isEqualTo(task);
        var ownerAudit=ok(get("/api/demo/audit"));
        assertThat(ownerAudit.toString()).contains(task);
        assertThat(ownerAudit.findValuesAsText("outcome")).doesNotContain("DENIED");
    }
    void assertTaskAccessDeniedWithoutAuditId(String task) throws Exception {
        mvc.perform(get("/api/demo/tasks/"+task).session(session)).andExpect(status().isNotFound());
        var supplement=Map.of("version",0,"statements",Map.of("F002.purpose",Map.of("value","explicit","userRequest","explicit original statement")));
        mvc.perform(postJson("/tasks/"+task+"/supplement",supplement).session(session).header("X-CSRF-Token",csrf)).andExpect(status().isNotFound());
        mvc.perform(postJson("/tasks/"+task+"/turn",Map.of("message","query")).session(session).header("X-CSRF-Token",csrf)).andExpect(status().isNotFound());
        var events=ok(get("/api/demo/audit"));
        assertThat(events.toString()).doesNotContain(task);
        var denied=new ArrayList<JsonNode>();
        events.forEach(event->{if ("NOT_FOUND".equals(event.path("errorCode").asText())) denied.add(event);});
        assertThat(denied).hasSize(2);
        assertThat(denied.stream().map(event->event.path("tool").asText()).toList()).containsExactlyInAnyOrder("load","supplement");
        denied.forEach(event->{
            assertThat(event.path("taskId").isNull()).isTrue();
            assertThat(event.path("requestId").asText()).isNotBlank();
            assertThat(event.path("outcome").asText()).isEqualTo("DENIED");
        });
    }
    @Test void existingOpenTaskRequiresExplicitCancellationBeforeNewTask() throws Exception {
        select("operator","C001");
        String first = start();
        mvc.perform(postJson("/tasks",Map.of("goal","另一个任务","pending",List.of())).session(session).header("X-CSRF-Token",csrf))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("CONFLICT"));
        mvc.perform(postJson("/tasks/"+first+"/cancel",Map.of("version",0,"confirmed",true)).session(session))
                .andExpect(status().isForbidden());
        mvc.perform(postJson("/tasks/"+first+"/cancel",Map.of("version",0,"confirmed",false)).session(session).header("X-CSRF-Token",csrf))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("CONFIRMATION_REQUIRED"));
        mvc.perform(postJson("/tasks/"+first+"/cancel",Map.of("version",99,"confirmed",true)).session(session).header("X-CSRF-Token",csrf))
                .andExpect(status().isConflict());
        JsonNode cancelled = ok(postJson("/tasks/"+first+"/cancel",Map.of("version",0,"confirmed",true)));
        assertThat(cancelled.path("id").asText()).isEqualTo(first);
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.path("version").asInt()).isEqualTo(1);
        assertThat(ok(get("/api/demo/audit")).toString()).contains("cancel",first);
        String second = start();
        assertThat(second).isNotEqualTo(first);
        var waiting = turn(second,Map.of("message","继续查询"));
        assertThat(waiting.at("/turn/task/status").asText()).isEqualTo("WAITING_USER");
        JsonNode cancelledWaiting = ok(postJson("/tasks/"+second+"/cancel",Map.of("version",1,"confirmed",true)));
        assertThat(cancelledWaiting.path("status").asText()).isEqualTo("CANCELLED");
        String third = start();
        assertThat(third).isNotIn(first,second);
        assertThat(ok(get("/api/demo/tasks/"+first)).at("/task/status").asText()).isEqualTo("CANCELLED");
        assertThat(count("invoice_operations")).isZero();
        mvc.perform(postJson("/tasks/"+first+"/cancel",Map.of("version",1,"confirmed",true)).session(session).header("X-CSRF-Token",csrf))
                .andExpect(status().isConflict());
    }
    @Test void cancellationIsScopedAndCannotExposeAnotherUsersTaskId() throws Exception {
        select("operator","C001"); String ownerTask = start();
        select("viewer","C001");
        mvc.perform(postJson("/tasks/"+ownerTask+"/cancel",Map.of("version",0,"confirmed",true)).session(session).header("X-CSRF-Token",csrf))
                .andExpect(status().isNotFound());
        JsonNode audit = ok(get("/api/demo/audit"));
        assertThat(audit.toString()).doesNotContain(ownerTask);
        var denied = new ArrayList<JsonNode>();
        audit.forEach(event -> { if ("cancel".equals(event.path("tool").asText())) denied.add(event); });
        assertThat(denied).hasSize(1);
        assertThat(denied.getFirst().path("taskId").isNull()).isTrue();
        assertThat(denied.getFirst().path("errorCode").asText()).isEqualTo("NOT_FOUND");
    }
    @Test void strictCspCoversPageResourcesAndApiIncludingRejections() throws Exception {
        String policy="default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'";
        for (String path:List.of("/","/react-app/","/api/demo/session"))
            mvc.perform(get(path)).andExpect(header().string("Content-Security-Policy",policy));
        mvc.perform(get("/api/demo/session").header("Origin","https://evil.example"))
                .andExpect(status().isForbidden()).andExpect(header().string("Content-Security-Policy",policy));
    }
    @Test void queryAskSupplementAndResumeOnlyUseExplicitFacts() throws Exception {
        var model=new FakeLlmClient(new Call("q","query_invoices","{\"enterprise_id\":\"C001\",\"month\":\"2026-09\"}"),new Ask("F002实际用途是什么？"),new Ask("尚未授权保存"));
        setup(model); select("operator","C001");String task=start();
        var first=turn(task,Map.of("message","检查九月")); assertThat(first.at("/turn/task/status").asText()).isEqualTo("WAITING_USER");
        assertThat(first.at("/turn/task/supplied").isEmpty()).isTrue();
        var facts=Map.of("F002.purpose",Map.of("value","private-purpose","userRequest","原话 private-purpose"));
        ok(postJson("/tasks/"+task+"/supplement",Map.of("version",1,"statements",facts)));
        setup(model); select("operator","C001");
        var resumed=ok(get("/api/demo/tasks/"+task));assertThat(resumed.at("/task/supplied/F002.purpose/userRequest").asText()).isEqualTo("原话 private-purpose");
        turn(task,Map.of("message","只是补充"));assertThat(purpose()).isEmpty();assertThat(count("invoice_operations")).isZero();
        assertThat(model.seen.getLast().userMessage()).contains("private-purpose","无；补充信息不是保存授权");
    }
    @Test void modelFinishCannotCloseTaskWithPendingFieldsAtHttpBoundary() throws Exception {
        setup(new FakeLlmClient(
                new Call("query","query_invoices","""
                {"enterprise_id":"C001","month":"2026-09"}
                """),
                new Finish("模型认为任务已完成")));
        select("operator","C001"); String task=start();
        var first=turn(task,Map.of("message","查询发票并检查 F002 用途"));
        assertThat(first.at("/turn/result/status").asText()).isEqualTo("NEEDS_INPUT");
        assertThat(first.at("/turn/task/status").asText()).isEqualTo("WAITING_USER");
        assertThat(first.at("/turn/task/pending").toString()).contains("F002.purpose");
        assertThat(first.at("/turn/task/question").asText()).contains("F002.purpose","补充");
        assertThat(first.at("/turn/result/text").asText()).startsWith("模型认为任务已完成").contains("任务尚未完成。","F002.purpose");
        assertThat(first.at("/turn/task/question").asText()).doesNotContain("模型认为任务已完成");
        var supplemented=ok(postJson("/tasks/"+task+"/supplement",Map.of("version",1,
                "statements",Map.of("F002.purpose",Map.of("value","办公室采购","userRequest","用户明确补充：办公室采购")))));
        assertThat(supplemented.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(supplemented.path("pending").isEmpty()).isTrue();
        assertThat(supplemented.path("awaitingSave").toString()).isEqualTo("[\"F002.purpose\"]");
        // T35: a stated value is not a save, so the task stays open and the review page keeps its save button.
        setup(new FakeLlmClient(new Finish("补充后核查完成")));
        var resumed=turn(task,Map.of("message","根据已补充事实继续完成核查"));
        assertThat(resumed.at("/turn/result/status").asText()).isEqualTo("NEEDS_INPUT");
        assertThat(resumed.at("/turn/task/status").asText()).isEqualTo("WAITING_USER");
        assertThat(resumed.at("/turn/task/pending").isEmpty()).isTrue();
        assertThat(resumed.at("/turn/task/awaitingSave").toString()).isEqualTo("[\"F002.purpose\"]");
        assertThat(resumed.at("/turn/task/question").asText()).contains("办公室采购","尚未保存");
        assertThat(purpose()).isEmpty();
    }
    @Test void ordinaryChatCannotWriteAndExactConfirmationWritesOnceWithReadback() throws Exception {
        setup(new FakeLlmClient(new Call("unapproved","update_invoice",WRITE),new Ask("请确认"),new Call("approved","update_invoice",WRITE),new Ask("已保存请继续"),new Call("repeat","update_invoice",WRITE),new Ask("已回查")));
        select("operator","C001");String task=start();
        assertThat(turn(task,Map.of("message","保存用途" )).path("errorCode").asText()).isEqualTo("CONFIRMATION_REQUIRED");assertThat(purpose()).isEmpty();
        String operation=UUID.randomUUID().toString();
        var saved=turn(task,approved(operation));assertThat(saved.at("/verification/verified").asBoolean()).isTrue();assertThat(purpose()).isEqualTo("private-purpose");
        sql("CREATE TRIGGER no_rewrite BEFORE UPDATE ON invoices BEGIN SELECT RAISE(ABORT,'must not replay'); END");
        assertThat(turn(task,approved(operation)).at("/verification/verified").asBoolean()).isTrue();assertThat(count("invoice_operations")).isEqualTo(1);
    }
    /** SSE frames in order as (event, data) pairs. */
    static List<Map.Entry<String,JsonNode>> frames(String body) throws Exception {
        var frames = new ArrayList<Map.Entry<String,JsonNode>>();
        for (var block : body.split("\n\n")) {
            String event = null; var data = new StringBuilder();
            for (var line : block.split("\n")) {
                if (line.startsWith("event:")) event = line.substring(6).strip();
                else if (line.startsWith("data:")) data.append(line.substring(5));
            }
            if (event != null) frames.add(Map.entry(event, JSON.readTree(data.toString())));
        }
        return frames;
    }
    @Test void streamEndsWithDoneCarryingTheVerifiedResult() throws Exception {
        setup(new FakeLlmClient(new Call("save","update_invoice",WRITE),new Finish("已保存并回查")));
        select("operator","C001"); String task=start(); String operation=UUID.randomUUID().toString();
        var started=mvc.perform(postJson("/tasks/"+task+"/turn/stream",approved(operation)).session(session).header("X-CSRF-Token",csrf))
                .andExpect(request().asyncStarted()).andReturn();
        started.getAsyncResult(20_000);
        var frames=frames(started.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(frames.getFirst().getKey()).isEqualTo("status");
        assertThat(frames).extracting(Map.Entry::getKey).contains("tool","text");
        // Status and verification come only from the terminal frame; text chunks are presentation.
        var done=frames.getLast();
        assertThat(done.getKey()).isEqualTo("done");
        assertThat(done.getValue().at("/verification/verified").asBoolean()).isTrue();
        assertThat(done.getValue().at("/turn/task/status").asText()).isEqualTo("COMPLETED");
        assertThat(frames).filteredOn(f -> f.getKey().equals("done")).hasSize(1);
        assertThat(purpose()).isEqualTo("private-purpose"); assertThat(count("invoice_operations")).isEqualTo(1);
    }
    @Test void clientDisconnectMidStreamDoesNotChangeTheBusinessOutcome() throws Exception {
        var reachedFinal=new CountDownLatch(1); var disconnected=new CountDownLatch(1);
        var steps=new AtomicInteger();
        setup(request -> {
            if (steps.getAndIncrement()==0) return new Call("save","update_invoice",WRITE);
            reachedFinal.countDown();
            assertThat(disconnected.await(10, TimeUnit.SECONDS)).isTrue();
            return new Finish("已保存并回查");
        });
        select("operator","C001"); String task=start(); String operation=UUID.randomUUID().toString();
        long versionBefore=ok(get("/api/demo/tasks/"+task)).at("/task/version").asLong();
        var started=mvc.perform(postJson("/tasks/"+task+"/turn/stream",approved(operation)).session(session).header("X-CSRF-Token",csrf))
                .andExpect(request().asyncStarted()).andReturn();
        assertThat(reachedFinal.await(10, TimeUnit.SECONDS)).isTrue();
        // The write has run; now the browser goes away, as a servlet container reports it.
        var async=(org.springframework.mock.web.MockAsyncContext) started.getRequest().getAsyncContext();
        for (var listener : List.copyOf(async.getListeners()))
            listener.onError(new jakarta.servlet.AsyncEvent(async, new java.io.IOException("client disconnected")));
        disconnected.countDown();
        // The turn still finishes and is persisted exactly once; nothing is replayed or rolled back.
        JsonNode stored=null;
        for (int i=0; i<200; i++) {
            stored=ok(get("/api/demo/tasks/"+task)).path("task");
            if (stored.path("version").asLong()>versionBefore) break;
            Thread.sleep(50);
        }
        assertThat(stored.path("version").asLong()).isEqualTo(versionBefore+1);
        assertThat(stored.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(purpose()).isEqualTo("private-purpose"); assertThat(count("invoice_operations")).isEqualTo(1);
        assertThat(verifyOperation(task,operation).path("status").asText()).isEqualTo("VERIFIED");
        assertThat(steps.get()).isEqualTo(2);
    }
    @Test void falseConfirmationAndModelParameterMismatchNeverWrite() throws Exception {
        setup(new FakeLlmClient(new Call("mismatch","update_invoice",WRITE.replace("private-purpose","model-guessed")),new Finish("模型声称成功")));
        select("operator","C001");String task=start();
        var notConfirmed=Map.of("message","save","approval",Map.of("operationId",UUID.randomUUID().toString(),"invoiceId","F002","changes",Map.of("purpose","private-purpose"),"confirmed",false));
        mvc.perform(postJson("/tasks/"+task+"/turn",notConfirmed).session(session).header("X-CSRF-Token",csrf)).andExpect(status().isBadRequest());
        var result=turn(task,approved(UUID.randomUUID().toString())); assertThat(result.path("errorCode").asText()).isEqualTo("CONFIRMATION_REQUIRED");assertThat(purpose()).isEmpty();
    }
    @Test void unconfiguredRealClientReturnsExplicitErrorWithoutFake() throws Exception {
        // Configuration bean is package-private; obtain through Spring with a deliberately empty environment.
        var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        context.setEnvironment(new MockEnvironment());context.register(DemoConfiguration.class);context.refresh();
        try { setup(context.getBean(com.taxagent.harness.runtime.ModelProfiles.class).profile("default"));select("operator","C001");
            var result=turn(start(),Map.of("message","查询九月"));
            assertThat(result.path("errorCode").asText()).isEqualTo("MODEL_NOT_CONFIGURED");assertThat(result.at("/turn/result/history")).isEmpty();assertThat(count("invoice_operations")).isZero();
        } finally {context.close();}
    }
    @Test void auditAppendsMetadataOnlyAndCannotUpdateOrDelete() throws Exception {
        setup(new FakeLlmClient(new Call("q","query_bank_entries","{\"enterprise_id\":\"C001\",\"month\":\"2026-09\"}"),new Ask("查询完成"),new Call("save","update_invoice",WRITE),new Ask("继续")));
        select("operator","C001");String task=start();long before=count("demo_audit");
        turn(task,Map.of("message","不要记录这段private-message"));turn(task,approved(UUID.randomUUID().toString()));
        assertThat(count("demo_audit")).isGreaterThan(before);var events=ok(get("/api/demo/audit"));
        assertThat(events.toString()).contains("query_bank_entries","2026-09","F002","purpose","\"verified\":true").doesNotContain("private-purpose","private-message","718074","对方名称");
        assertThatThrownBy(()->sql("UPDATE demo_audit SET outcome='edited'" )).isInstanceOf(SQLException.class).hasMessageContaining("append only");
        assertThatThrownBy(()->sql("DELETE FROM demo_audit")).isInstanceOf(SQLException.class).hasMessageContaining("append only");
        assertThatThrownBy(()->sql("INSERT OR REPLACE INTO demo_audit SELECT * FROM demo_audit LIMIT 1")).isInstanceOf(SQLException.class).hasMessageContaining("append only");
        select("viewer","C001");assertThat(ok(get("/api/demo/audit")).toString()).doesNotContain("query_bank_entries",task);
    }
    @Test void failedTaskPersistenceRecoversCommittedWriteWithoutReplay() throws Exception {
        setup(new FakeLlmClient(new Call("save","update_invoice",WRITE),new Ask("继续")));
        select("operator","C001");String task=start();String operation=UUID.randomUUID().toString();
        sql("CREATE TRIGGER fail_task BEFORE UPDATE ON agent_tasks BEGIN SELECT RAISE(ABORT,'task persistence failure'); END");
        var recovered=turn(task,approved(operation));
        assertThat(recovered.path("errorCode").asText()).isEqualTo("TASK_STATE_SAVE_FAILED");assertThat(recovered.path("message").asText()).contains("财务写入已核验但任务状态保存失败");
        assertThat(recovered.at("/verification/verified").asBoolean()).isTrue();assertThat(count("invoice_operations")).isEqualTo(1);assertThat(purpose()).isEqualTo("private-purpose");
        assertThat(ok(get("/api/demo/tasks/"+task)).at("/task/version").asInt()).isZero();
        assertThat(ok(get("/api/demo/audit")).toString()).contains("RECOVERABLE_TASK_STATE_FAILURE",operation);
        sql("CREATE TRIGGER no_rewrite BEFORE UPDATE ON invoices BEGIN SELECT RAISE(ABORT,'must not replay'); END");
        setup(new FakeLlmClient(new Call("save-again","update_invoice",WRITE),new Ask("继续")));
        var again=turn(task,approved(operation));assertThat(again.at("/verification/verified").asBoolean()).isTrue();assertThat(count("invoice_operations")).isEqualTo(1);
        sql("CREATE TRIGGER fail_recover_audit BEFORE INSERT ON demo_audit WHEN NEW.tool='recover' BEGIN SELECT RAISE(ABORT,'audit failure'); END");
        setup(new FakeLlmClient(new Call("recover-no-replay","update_invoice",WRITE),new Ask("继续")));
        var auditFailed=turn(task,approved(operation));
        assertThat(auditFailed.path("errorCode").asText()).isEqualTo("AUDIT_STORAGE_ERROR");
        assertThat(auditFailed.path("message").asText()).contains("财务写入已核验但任务状态保存失败","审计记录也失败");
        assertThat(auditFailed.at("/verification/verified").asBoolean()).isTrue();assertThat(count("invoice_operations")).isEqualTo(1);
    }
    @Test void unavailableAuditFailsClosedBeforeWrite() throws Exception {
        setup(new FakeLlmClient(new Call("save","update_invoice",WRITE),new Finish("完成")));
        select("operator","C001");String task=start();
        sql("CREATE TRIGGER fail_audit BEFORE INSERT ON demo_audit BEGIN SELECT RAISE(ABORT,'audit failure'); END");
        mvc.perform(postJson("/tasks/"+task+"/turn",approved(UUID.randomUUID().toString())).session(session).header("X-CSRF-Token",csrf))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.errorCode").value("AUDIT_STORAGE_ERROR"));
        assertThat(purpose()).isEmpty();assertThat(count("invoice_operations")).isZero();
    }
    @Test void failedAuditAfterCommitCannotReportOverallSuccess() throws Exception {
        setup(new FakeLlmClient(new Call("save","update_invoice",WRITE),new Ask("继续")));
        select("operator","C001");String task=start();
        sql("CREATE TRIGGER fail_tool_audit BEFORE INSERT ON demo_audit WHEN NEW.tool='update_invoice' BEGIN SELECT RAISE(ABORT,'audit failure'); END");
        var result=turn(task,approved(UUID.randomUUID().toString()));
        assertThat(result.path("errorCode").asText()).isEqualTo("AUDIT_STORAGE_ERROR");assertThat(result.at("/verification/verified").asBoolean()).isTrue();assertThat(count("invoice_operations")).isEqualTo(1);
    }
    @Test void auditRowsAreAppendOnlyAndRetentionNeedsExportFirst() throws Exception {
        setup(new FakeLlmClient(new Finish("完成")));
        select("operator","C001");String task=start();turn(task,Map.of("message","查询九月"));
        assertThatThrownBy(() -> sql("UPDATE demo_audit SET tool='tampered'")).hasMessageContaining("append only");
        assertThatThrownBy(() -> sql("DELETE FROM demo_audit")).hasMessageContaining("append only");
    }
    }
