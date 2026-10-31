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
    @ParameterizedTest @ValueSource(strings={"cancel","supplement"})
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
        var mutationRequest = postJson("/tasks/"+id+"/"+mutation,input).session(otherSession).header("X-CSRF-Token",otherCsrf)
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
            assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
            assertThat(body(conflict).path("errorCode").asText()).isEqualTo("CONFLICT");
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
        var denied=turn(task,approved(UUID.randomUUID().toString()));
        assertThat(denied.path("errorCode").asText()).isEqualTo("PERMISSION_DENIED"); assertThat(count("invoice_operations")).isZero();
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
        String policy="default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'";
        for (String path:List.of("/","/index.html","/demo.js","/demo.css","/api/demo/session"))
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
    @Test void ordinaryChatCannotWriteAndExactConfirmationWritesOnceWithReadback() throws Exception {
        setup(new FakeLlmClient(new Call("unapproved","update_invoice",WRITE),new Ask("请确认"),new Call("approved","update_invoice",WRITE),new Ask("已保存请继续"),new Call("repeat","update_invoice",WRITE),new Ask("已回查")));
        select("operator","C001");String task=start();
        assertThat(turn(task,Map.of("message","保存用途" )).path("errorCode").asText()).isEqualTo("CONFIRMATION_REQUIRED");assertThat(purpose()).isEmpty();
        String operation=UUID.randomUUID().toString();
        var saved=turn(task,approved(operation));assertThat(saved.at("/verification/verified").asBoolean()).isTrue();assertThat(purpose()).isEqualTo("private-purpose");
        sql("CREATE TRIGGER no_rewrite BEFORE UPDATE ON invoices BEGIN SELECT RAISE(ABORT,'must not replay'); END");
        assertThat(turn(task,approved(operation)).at("/verification/verified").asBoolean()).isTrue();assertThat(count("invoice_operations")).isEqualTo(1);
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
        try { setup(context.getBean(LlmClient.class));select("operator","C001");
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
    @Test void staticUiUsesTextRenderingAndExplicitNativeConfirmation() throws Exception {
        String js=Files.readString(Path.of("src/main/resources/static/demo.js"));
        assertThat(js).contains("textContent","window.confirm","confirmed:true","databaseReadback").doesNotContain("innerHTML","insertAdjacentHTML","eval(");
        assertThat(Files.readString(Path.of("src/main/resources/static/index.html"))).contains("本地模拟身份，不是生产认证","MODEL_NOT_CONFIGURED","Viewer","Operator");
    }
}
