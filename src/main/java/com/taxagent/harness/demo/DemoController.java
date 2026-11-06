package com.taxagent.harness.demo;

import com.fasterxml.jackson.databind.*;
import com.taxagent.harness.runtime.*;
import com.taxagent.harness.tool.*;
import com.taxagent.harness.tool.PermissionGuard.Session;
import com.taxagent.harness.tool.ToolContract.*;
import jakarta.servlet.http.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/demo")
public final class DemoController {
    // ponytail: bounded stripes serialize this single-JVM demo; not a distributed lock.
    private static final Object[] MUTATION_LOCKS = new Object[64];
    static { Arrays.setAll(MUTATION_LOCKS, ignored -> new Object()); }
    private static Object mutationLock(DemoPrincipal p) {
        return MUTATION_LOCKS[Math.floorMod(Objects.hash(p.trustedSession().userId(), p.enterprise()), MUTATION_LOCKS.length)];
    }
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final long TURN_CACHE_TTL_MS = 10 * 60_000;
    private static final int TURN_CACHE_MAX = 512;
    private final Map<String, CachedTurn> turnCache = new ConcurrentHashMap<>();
    private record CachedTurn(String fingerprint, TurnView view, long expiresAt) {}
    private final Path database;
    private final LlmClient model;
    private final DemoAudit audit;
    // Never deserialized from HTTP. Only the fixed profile mapping below constructs this identity.
    record DemoPrincipal(String profile, String enterprise, Session trustedSession) {}
    public record Selection(String profile, String enterprise) {}
    public record Start(String goal, List<String> pending) {}
    public record Approval(String operationId, String invoiceId, Map<String,String> changes, boolean confirmed) {}
    public record Turn(String message, Approval approval, String requestId) {}
    public record Supplement(long version, Map<String,TaskMemory.Input> statements) {}
    public record Preference(String name, String value, String userRequest) {}
    public record Cancel(Long version, Boolean confirmed) {}
    public record TurnView(TaskMemory.Turn turn, UpdateResult verification, String errorCode, String message,
                           String operationId, String modelInput) {
        public TurnView(TaskMemory.Turn turn, UpdateResult verification, String errorCode, String message,
                        String operationId) {
            this(turn, verification, errorCode, message, operationId, null);
        }
    }
    public DemoController(@Value("${taxagent.database:data/harness.db}") String database, LlmClient model) {
        this.database = Path.of(database); this.model = model; audit = new DemoAudit(this.database);
    }
    private static <T> T decode(JsonNode body, Class<T> type) {
        try { T value = JSON.treeToValue(body, type); if (value == null) throw invalid(); return value; }
        catch (Exception e) { throw invalid(); }
    }
    private static DomainException invalid() { return new DomainException(ErrorCode.INVALID_ARGUMENT, "Invalid demo request"); }
    private static void taskId(String id) {
        try { if (!UUID.fromString(id).toString().equals(id)) throw invalid(); } catch (Exception e) { throw invalid(); }
    }
    private static void text(String value) { if (value == null || value.isBlank()) throw invalid(); }
    private DemoPrincipal principal(HttpSession session) {
        var p = (DemoPrincipal) session.getAttribute(DemoSecurity.PRINCIPAL);
        if (p == null) throw new DomainException(ErrorCode.AUTHENTICATION_REQUIRED, "Select local demo identity");
        return p;
    }
    private FinanceAgent agent(DemoPrincipal p) { return new FinanceAgent(database, p.trustedSession(), p.enterprise(), model); }
    private DemoAudit.Event event(String request, String task, String tool, String call, String month,
                                  String invoice, String fields, String operation, String outcome, String code, boolean verified) {
        return event(request, task, tool, call, month, invoice, fields, operation, outcome, code, verified, null, null, null);
    }
    private DemoAudit.Event event(String request, String task, String tool, String call, String month,
                                  String invoice, String fields, String operation, String outcome, String code,
                                  boolean verified, String ruleVersion, String memorySource, Long durationMs) {
        return new DemoAudit.Event(0, request, task, tool, call, month, invoice, fields, operation, outcome, code,
                verified, null, ruleVersion, memorySource, durationMs);
    }
    private void log(DemoPrincipal p, String request, String task, String action, String outcome, String code) {
        log(p, request, task, action, outcome, code, null, null);
    }
    private void log(DemoPrincipal p, String request, String task, String action, String outcome, String code,
                     String memorySource, Long durationMs) {
        audit.append(p.trustedSession(), p.enterprise(), event(request, task, action, null, null, null, null, null,
                outcome, code, false, null, memorySource, durationMs));
    }
    private <T> T request(DemoPrincipal p, String task, String action, Supplier<T> work) {
        String id = UUID.randomUUID().toString();
        if (task != null) {
            try { agent(p).load(task); }
            catch (DomainException e) {
                if (e.code() == ErrorCode.NOT_FOUND || e.code() == ErrorCode.PERMISSION_DENIED)
                    log(p,id,null,action,"DENIED",e.code().name());
                throw e;
            }
        }
        log(p,id,task,action,"REQUESTED",null); // Fail closed before mutations if audit storage is unavailable.
        try {
            T result = work.get();
            String completedTask = result instanceof TaskMemory.Task created ? created.id() : task;
            log(p,id,completedTask,action,"COMPLETED",null); return result;
        } catch (DomainException e) { log(p,id,task,action,"ERROR",e.code().name()); throw e; }
    }
    @GetMapping("/session") public Object session(HttpSession session) {
        var p = (DemoPrincipal)session.getAttribute(DemoSecurity.PRINCIPAL);
        return Map.of("csrfToken", DemoSecurity.token(session), "identityNotice", "本地模拟身份，不是生产认证",
                "profile", p == null ? "" : p.profile(), "enterprise", p == null ? "" : p.enterprise());
    }
    @PostMapping("/session") public Object select(@RequestBody JsonNode body, HttpServletRequest req) {
        Selection selection = decode(body, Selection.class);
        String user = switch (selection.profile() == null ? "" : selection.profile()) {
            case "operator" -> "U001"; case "viewer" -> "U002"; default -> throw invalid();
        };
        text(selection.enterprise());
        var p = new DemoPrincipal(selection.profile(), selection.enterprise(), new Session(user));
        agent(p).tasks(); // Database permissions, including U002/C002 denial. No model invocation.
        log(p,UUID.randomUUID().toString(),null,"session","SELECTED",null);
        var old = req.getSession(); old.invalidate();
        var fresh = req.getSession(true); fresh.setAttribute(DemoSecurity.PRINCIPAL,p);
        return session(fresh);
    }
    @DeleteMapping("/session") public Object clear(HttpSession session) {
        var p = principal(session); log(p,UUID.randomUUID().toString(),null,"session","CLEARED",null);
        session.invalidate(); return Map.of("message","本地会话已退出；任务仍按用户及企业隔离保存");
    }
    @GetMapping("/preferences") public Object preferences(HttpSession session) {
        var p = principal(session);
        return request(p, null, "preferences", () -> agent(p).preferences());
    }
    @PostMapping("/preferences") public Object putPreference(@RequestBody JsonNode body, HttpSession session) {
        var input = decode(body, Preference.class); var p = principal(session);
        text(input.name()); text(input.value()); text(input.userRequest());
        return request(p, null, "preference-put", () -> {
            agent(p).putPreference(input.name(), new TaskMemory.Input(input.value(), input.userRequest()));
            return agent(p).preferences();
        });
    }
    @DeleteMapping("/preferences/{name}") public Object deletePreference(@PathVariable String name, HttpSession session) {
        var p = principal(session); text(name);
        return request(p, null, "preference-delete", () -> {
            agent(p).deletePreference(name);
            return agent(p).preferences();
        });
    }
    @GetMapping("/tasks") public Object tasks(HttpSession session) {
        var p = principal(session); return request(p,null,"tasks",() -> agent(p).tasks());
    }
    @PostMapping("/tasks") public Object start(@RequestBody JsonNode body, HttpSession session) {
        var input = decode(body, Start.class); var p = principal(session);
        return request(p,null,"start",() -> agent(p).start(input.goal(),input.pending() == null ? List.of() : input.pending()));
    }
    @GetMapping("/tasks/{id}") public Object load(@PathVariable String id, HttpSession session) {
        var p = principal(session); taskId(id); return request(p,id,"load",() -> agent(p).load(id));
    }
    @PostMapping("/tasks/{id}/supplement") public Object supplement(@PathVariable String id, @RequestBody JsonNode body, HttpSession session) {
        var input = decode(body, Supplement.class); var p = principal(session); taskId(id);
        // Only manually entered structured values and original statements; never extract from model text.
        synchronized (mutationLock(p)) {
            return request(p,id,"supplement",() -> agent(p).supplement(id,input.version(),input.statements()));
        }
    }
    @PostMapping("/tasks/{id}/cancel") public Object cancel(@PathVariable String id, @RequestBody JsonNode body, HttpSession session) {
        var input = decode(body, Cancel.class); var p = principal(session); taskId(id);
        if (!Boolean.TRUE.equals(input.confirmed()))
            throw new DomainException(ErrorCode.CONFIRMATION_REQUIRED, "Explicit task cancellation required");
        if (input.version() == null || input.version() < 0) throw invalid();
        // Existing TaskMemory.cancel enforces trusted identity, enterprise, open status and optimistic version.
        synchronized (mutationLock(p)) {
            return request(p,id,"cancel",() -> agent(p).cancel(id,input.version()));
        }
    }
    @PostMapping("/tasks/{id}/turn") public TurnView turn(@PathVariable String id, @RequestBody JsonNode body, HttpSession session) {
        var input = decode(body,Turn.class); text(input.message()); taskId(id);
        var p = principal(session);
        synchronized (mutationLock(p)) { return run(p,id,input); }
    }
    @PostMapping("/tasks/{id}/operations/{operationId}/verify")
    public InvoiceWriteService.Verification verify(@PathVariable String id, @PathVariable String operationId, HttpSession session) {
        var p = principal(session); taskId(id); taskId(operationId);
        synchronized (mutationLock(p)) {
            String requestId = UUID.randomUUID().toString();
            try {
                var result = new InvoiceWriteService(database, false).verify(p.trustedSession(), p.enterprise(), id, operationId);
                audit.append(p.trustedSession(), p.enterprise(), event(requestId,id,"verify",null,null,null,null,
                        operationId,result.status().name(),null,result.status() == InvoiceWriteService.VerificationStatus.VERIFIED));
                return result;
            } catch (DomainException e) {
                audit.append(p.trustedSession(), p.enterprise(), event(requestId,id,"verify",null,null,null,null,
                        operationId,e.code() == ErrorCode.NOT_FOUND ? "NOT_FOUND" : "ERROR",e.code().name(),false));
                throw e;
            }
        }
    }
    private TurnView run(DemoPrincipal p, String taskId, Turn input) {
        var a = agent(p); a.load(taskId);
        if (input.approval() != null && !"operator".equals(p.profile()))
            throw new DomainException(ErrorCode.PERMISSION_DENIED, "Only operator may approve writes");
        String requestId = input.requestId() == null ? UUID.randomUUID().toString() : input.requestId();
        if (!requestId.matches("[A-Za-z0-9-]{1,64}"))
            throw new DomainException(ErrorCode.INVALID_ARGUMENT, "Invalid requestId");
        String cacheKey = p.trustedSession().userId() + "|" + p.enterprise() + "|" + taskId + "|" + requestId;
        String fingerprint = fingerprint(input);
        long now = System.currentTimeMillis();
        turnCache.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
        var cached = turnCache.get(cacheKey);
        if (cached != null) {
            if (!cached.fingerprint().equals(fingerprint))
                throw new DomainException(ErrorCode.CONFLICT, "requestId reused with different payload");
            return cached.view();
        }
        Approval approval = input.approval(); InvoiceUpdate update = null;
        if (approval != null) {
            if (!approval.confirmed()) throw new DomainException(ErrorCode.CONFIRMATION_REQUIRED,"Structured confirmation required");
            try { if (!UUID.fromString(approval.operationId()).toString().equals(approval.operationId())) throw invalid(); }
            catch (Exception e) { throw invalid(); }
            update = new InvoiceUpdate(new InvoiceKey(p.enterprise(),approval.invoiceId()),approval.changes());
        }
        log(p,requestId,taskId,"turn","REQUESTED",null);
        long turnStarted = System.nanoTime();
        TaskMemory.Turn turn;
        try {
            turn = update == null ? a.turn(taskId,input.message()) : a.approvedTurn(taskId,input.message(),approval.operationId(),update,
                    update.changes().values().stream().anyMatch(String::isBlank) ? InvoiceWriteService.Intent.CLEAR_NOW : InvoiceWriteService.Intent.SAVE_NOW);
        } catch (DomainException failure) {
            if (update != null) {
                var recovered = a.recover(taskId,approval.operationId(),update); // Query only, never retry a financial update.
                if (recovered.isPresent()) {
                    try {
                        audit.append(p.trustedSession(),p.enterprise(),event(requestId,taskId,"recover",null,null,safe(update.key().invoiceId()),
                                fields(update.changes().keySet()),approval.operationId(),"RECOVERABLE_TASK_STATE_FAILURE",failure.code().name(),recovered.get().verified()));
                    } catch (DemoAudit.Failure auditFailure) {
                        return new TurnView(null,recovered.get(),"AUDIT_STORAGE_ERROR","财务写入已核验但任务状态保存失败；恢复审计记录也失败，请保留 operationId 核查，绝不重复写入",approval.operationId());
                    }
                    return new TurnView(null,recovered.get(),"TASK_STATE_SAVE_FAILED","财务写入已核验但任务状态保存失败；请恢复任务并查询，绝不重复写入",approval.operationId());
                }
            }
            log(p,requestId,taskId,"turn","ERROR",failure.code().name(), null,
                    (System.nanoTime() - turnStarted) / 1_000_000); throw failure;
        }
        long turnDurationMs = Math.max(0, (System.nanoTime() - turnStarted) / 1_000_000);
        UpdateResult verified = null;
        try {
            for (var exchange : turn.result().history()) {
                var call = exchange.call(); JsonNode args;
                try { args = JSON.readTree(call.arguments()); } catch (Exception malformed) { args = JSON.createObjectNode(); }
                JsonNode result = JSON.readTree(exchange.resultJson());
                String tool = Set.of("query_invoices","query_bank_entries","check_invoice_purpose","query_rules","update_invoice").contains(call.name()) ? call.name() : "rejected_tool";
                boolean ok = result.path("error").isNull();
                boolean saved = "update_invoice".equals(tool) && ok && result.at("/data/verified").asBoolean(false);
                String code = ok ? null : safe(result.at("/error/code").asText());
                audit.append(p.trustedSession(),p.enterprise(),event(requestId,taskId,tool,safe(call.id()),
                        month(args.path("month").asText()),safe(args.at("/key/invoice_id").asText()),
                        fields(keys(args.path("changes"))),approval == null ? null : approval.operationId(),ok ? "TOOL_RESULT" : "TOOL_ERROR",code,saved,
                        ruleVersion(result),turn.memorySource(),exchange.durationMs()));
                if (saved && update != null) verified = a.recover(taskId,approval.operationId(),update).orElse(null);
            }
            log(p,requestId,taskId,"turn",turn.result().status().name(),turn.result().errorCode(),
                    turn.memorySource(), turnDurationMs);
        } catch (DemoAudit.Failure failure) {
            // A committed write stays committed. Return explicit audit failure plus read-only verification.
            if (update != null) verified = a.recover(taskId,approval.operationId(),update).orElse(null);
            return new TurnView(turn,verified,"AUDIT_STORAGE_ERROR","审计记录失败；以回读核验为准，请勿重复写入",approval == null ? null : approval.operationId());
        } catch (DomainException e) { throw e;
        } catch (Exception e) { throw new DemoAudit.Failure(); }
        String message;
        if (verified != null) message = "实际工具 verified=true 且数据库回读已核验";
        else if ("WRITE_NOT_EXECUTED".equals(turn.result().errorCode()))
            message = "本轮未执行保存；旧批准已结束，如需保存请重新确认";
        else if ("WRITE_NOT_VERIFIED".equals(turn.result().errorCode()))
            message = "写入结果未核验；保留操作锁，请核查台账与发票";
        else message = "模型文字不是写入证据；请查看实际工具结果";
        var view = new TurnView(turn,verified,turn.result().errorCode(),message,approval == null ? null : approval.operationId(),
                turn.modelInput());
        if (turnCache.size() >= TURN_CACHE_MAX) turnCache.clear();
        turnCache.put(cacheKey, new CachedTurn(fingerprint, view, System.currentTimeMillis() + TURN_CACHE_TTL_MS));
        return view;
    }
    private static Set<String> keys(JsonNode node) { var keys = new HashSet<String>(); node.fieldNames().forEachRemaining(keys::add); return keys; }
    private static String fields(Set<String> keys) { return String.join(",",keys.stream().filter(Set.of("purpose","remarks")::contains).sorted().toList()); }
    private static String fingerprint(Turn input) {
        var approval = input.approval();
        return input.message() + "|" + (approval == null ? "" : approval.operationId() + "|" + approval.invoiceId()
                + "|" + approval.changes());
    }
    private static String safe(String value) { return value != null && value.matches("[A-Za-z0-9_-]{1,80}") ? value : null; }
    private static String ruleVersion(JsonNode result) {
        var rules = result.at("/data/rules");
        if (!rules.isArray()) return null;
        var values = new ArrayList<String>();
        rules.forEach(rule -> {
            var id = rule.at("/key/rule_id").asText();
            var version = rule.at("/key/version").asText();
            if (!id.isBlank() && !version.isBlank()) values.add(id + "@" + version);
        });
        var joined = String.join(",", values);
        return joined.matches("[A-Za-z0-9_.@,-]{1,200}") ? joined : null;
    }
    private static String month(String value) { return value != null && value.matches("[0-9]{4}-(0[1-9]|1[0-2])") ? value : null; }
    @GetMapping("/audit") public Object audit(HttpSession session) {
        var p = principal(session); return audit.list(p.trustedSession(),p.enterprise());
    }
    @GetMapping("/audit/export") public ResponseEntity<String> exportAudit(HttpSession session) {
        var p = principal(session);
        var events = audit.list(p.trustedSession(), p.enterprise());
        var csv = new StringBuilder("id,time,requestId,taskId,tool,callId,outcome,errorCode,verified,ruleVersion,memorySource,durationMs\n");
        for (var e : events) csv.append(e.id()).append(',').append(csv(e.time())).append(',').append(csv(e.requestId()))
                .append(',').append(csv(e.taskId())).append(',').append(csv(e.tool())).append(',').append(csv(e.callId()))
                .append(',').append(csv(e.outcome())).append(',').append(csv(e.errorCode())).append(',').append(e.verified())
                .append(',').append(csv(e.ruleVersion())).append(',').append(csv(e.memorySource())).append(',')
                .append(e.durationMs() == null ? "" : e.durationMs()).append('\n');
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"taxagent-audit.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8")).body(csv.toString());
    }
    private static String csv(String value) {
        if (value == null) return "";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
    @RestControllerAdvice(assignableTypes=DemoController.class)
    public static class Errors {
        @ExceptionHandler(DomainException.class) public ResponseEntity<?> domain(DomainException e) {
            int status = switch(e.code()) { case AUTHENTICATION_REQUIRED -> 401; case PERMISSION_DENIED -> 403;
                case NOT_FOUND -> 404; case CONFLICT -> 409; case STORAGE_ERROR -> 500; default -> 400; };
            return ResponseEntity.status(status).body(Map.of("errorCode",e.code().name(),"message","请求未完成："+e.code().name()));
        }
        @ExceptionHandler(DemoAudit.Failure.class) public ResponseEntity<?> audit() {
            return ResponseEntity.status(500).body(Map.of("errorCode","AUDIT_STORAGE_ERROR","message","审计记录失败；不能声明整体成功，请先核查实际写入状态"));
        }
        @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
        public ResponseEntity<?> malformed() { return ResponseEntity.badRequest().body(Map.of("errorCode","INVALID_ARGUMENT")); }
    }
}
