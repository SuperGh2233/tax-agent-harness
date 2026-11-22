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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
    private final ModelProfiles profiles;
    private final DemoAudit audit;
    /** T48: reads scanned invoices; NOT_CONFIGURED unless LLM_VISION_* is set. */
    private final FileService.ScanReader scanReader;
    // Presentation transport only; each turn still runs under the same mutation lock and audit rules.
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool(task -> {
        var thread = new Thread(task, "turn-stream"); thread.setDaemon(true); return thread;
    });
    // Never deserialized from HTTP. Only the fixed profile mapping below constructs this identity.
    record DemoPrincipal(String profile, String enterprise, Session trustedSession) {}
    public record Selection(String profile, String enterprise) {}
    public record Start(String goal, List<String> pending, String agentId) {}
    public record AgentSave(String id, AgentDirectory.Spec spec) {}
    public record AgentToggle(Boolean enabled) {}
    /** Task context plus the agent the task is bound to (its current version, even if since disabled). */
    public record LoadView(TaskMemory.Task task, Map<String, TaskMemory.Input> preferences, AgentDirectory.Agent agent) {}
    /** T47: one invoice of a batch approval, with its own operationId (the batch's own id is Approval.operationId). */
    public record BatchEntry(String operationId, String invoiceId, Map<String,String> changes) {}
    /** A single save sets invoiceId and changes; a batch (T47) sets items instead and leaves both null. */
    public record Approval(String operationId, String invoiceId, Map<String,String> changes, boolean confirmed, List<BatchEntry> items) {}
    public record Turn(String message, Approval approval, String requestId) {}
    public record Supplement(long version, Map<String,TaskMemory.Input> statements) {}
    public record Preference(String name, String value, String userRequest) {}
    public record MemoryInput(TaskMemory.Scope scope, String name, String value, String userRequest, String expiresOn) {}
    public record Cancel(Long version, Boolean confirmed) {}
    /** batch (T47): the verified read-back and program review of an approved batch; verification stays null then. */
    public record TurnView(TaskMemory.Turn turn, UpdateResult verification, String errorCode, String message,
                           String operationId, String modelInput, AgentRuntime.Trace promptTrace, BatchUpdateResult batch) {
        public TurnView(TaskMemory.Turn turn, UpdateResult verification, String errorCode, String message,
                        String operationId, String modelInput, AgentRuntime.Trace promptTrace) {
            this(turn, verification, errorCode, message, operationId, modelInput, promptTrace, null);
        }
        public TurnView(TaskMemory.Turn turn, UpdateResult verification, String errorCode, String message,
                        String operationId) {
            this(turn, verification, errorCode, message, operationId, null, null);
        }
        public TurnView(TaskMemory.Turn turn, UpdateResult verification, String errorCode, String message,
                        String operationId, String modelInput) {
            this(turn, verification, errorCode, message, operationId, modelInput, null);
        }
    }
    @org.springframework.beans.factory.annotation.Autowired
    public DemoController(@Value("${taxagent.database:data/harness.db}") String database, ModelProfiles profiles,
                          FileService.ScanReader scanReader) {
        this.database = Path.of(database); this.profiles = profiles; audit = new DemoAudit(this.database);
        this.scanReader = Objects.requireNonNull(scanReader);
    }
    public DemoController(String database, ModelProfiles profiles) { this(database, profiles, VisionClient.NOT_CONFIGURED); }
    /** One client as the default profile (tests and single-model setups). */
    public DemoController(String database, LlmClient model) { this(database, ModelProfiles.single(model)); }
    private static <T> T decode(JsonNode body, Class<T> type) {
        try { T value = JSON.treeToValue(body, type); if (value == null) throw invalid(); return value; }
        catch (Exception e) { throw invalid(); }
    }
    private static DomainException invalid() { return new DomainException(ErrorCode.INVALID_ARGUMENT, "Invalid demo request"); }
    private static void taskId(String id) {
        try { if (!UUID.fromString(id).toString().equals(id)) throw invalid(); } catch (Exception e) { throw invalid(); }
    }
    private static void text(String value) { if (value == null || value.isBlank()) throw invalid(); }
    private static void uuid(String id) {
        try { if (!UUID.fromString(id).toString().equals(id)) throw invalid(); } catch (Exception e) { throw invalid(); }
    }
    private DemoPrincipal principal(HttpSession session) {
        var p = (DemoPrincipal) session.getAttribute(DemoSecurity.PRINCIPAL);
        if (p == null) throw new DomainException(ErrorCode.AUTHENTICATION_REQUIRED, "Select local demo identity");
        return p;
    }
    /** Task, memory and verification operations; no model call happens through this agent. */
    private FinanceAgent agent(DemoPrincipal p) { return new FinanceAgent(database, p.trustedSession(), p.enterprise(), profiles.profile("default")); }
    private AgentDirectory agents() { return new AgentDirectory(database); }
    /** A turn runs with the version of the task's agent in force now; later edits apply from the next turn. */
    private FinanceAgent turnAgent(DemoPrincipal p, String taskId) {
        agent(p).load(taskId); // Missing or foreign tasks stay NOT_FOUND before any agent lookup.
        var directory = agents();
        var definition = directory.resolve(p.trustedSession(), p.enterprise(), directory.agentOf(p.trustedSession(), p.enterprise(), taskId));
        return new FinanceAgent(database, p.trustedSession(), p.enterprise(), profiles, definition);
    }
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
            var user = p.trustedSession().userId() + "|";
            turnCache.keySet().removeIf(key -> key.startsWith(user)); // A replay must not bring the deleted memory back.
            return agent(p).preferences();
        });
    }
    // Uploads are scoped to the session's enterprise; FileService rechecks WRITE in its own transaction.
    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object upload(@RequestParam("file") MultipartFile file,
                         @RequestParam(value = "mapping", required = false) String mapping, HttpSession session) {
        var p = principal(session);
        if (file == null || file.isEmpty() || file.getSize() > FileService.MAX_BYTES) throw invalid();
        // T46: the accountant's explicit column mapping, strictly decoded; the service validates every field.
        List<FileService.SheetMapping> mappings = List.of();
        if (mapping != null && !mapping.isBlank()) {
            if (mapping.length() > 64 * 1024) throw invalid();
            try { mappings = List.of(JSON.readValue(mapping, FileService.SheetMapping[].class)); }
            catch (Exception e) { throw invalid(); }
        }
        byte[] content;
        try { content = file.getBytes(); } catch (java.io.IOException e) { throw invalid(); }
        var applied = mappings;
        synchronized (mutationLock(p)) {
            return request(p, null, applied.isEmpty() ? "file-upload" : "file-upload-mapped", () -> new FileService(database)
                    .upload(p.trustedSession(), p.enterprise(), file.getOriginalFilename(), content, applied));
        }
    }
    /**
     * T48: a scanned invoice PDF. The file is stored like any upload and read by the vision profile into a draft;
     * no invoice exists until the accountant confirms it. Without a vision model the draft is MANUAL.
     */
    @PostMapping(value = "/files/scan", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object scan(@RequestParam("file") MultipartFile file, HttpSession session) {
        var p = principal(session);
        if (file == null || file.isEmpty() || file.getSize() > FileService.MAX_BYTES) throw invalid();
        byte[] content;
        try { content = file.getBytes(); } catch (java.io.IOException e) { throw invalid(); }
        synchronized (mutationLock(p)) {
            return request(p, null, "file-scan", () -> new FileService(database)
                    .recognize(p.trustedSession(), p.enterprise(), file.getOriginalFilename(), content, scanReader));
        }
    }
    public record ScanConfirm(String invoiceId, String invoiceDate, String seller, String item, String amount,
                              String remarks, String amountConfirmation, boolean confirmed) {}
    /** T48: the accountant's field-by-field confirmation; only this creates the invoice, and only for a missing key. */
    @PostMapping("/files/scan/{draftId}/confirm")
    public Object confirmScan(@PathVariable String draftId, @RequestBody JsonNode body, HttpSession session) {
        var p = principal(session); uuid(draftId);
        var input = decode(body, ScanConfirm.class);
        if (!input.confirmed()) throw new DomainException(ErrorCode.CONFIRMATION_REQUIRED, "Structured confirmation required");
        synchronized (mutationLock(p)) {
            return request(p, null, "file-scan-confirm", () -> new FileService(database).confirm(p.trustedSession(), p.enterprise(), draftId,
                    new FileService.ScanConfirmation(input.invoiceId(), input.invoiceDate(), input.seller(), input.item(),
                            input.amount(), input.remarks(), input.amountConfirmation())));
        }
    }
    public record KnowledgeToggle(Boolean enabled) {}
    /** Enable/disable a file as knowledge (T32); the next search reflects it. The file itself never changes. */
    @PostMapping("/files/{id}/knowledge") public Object knowledge(@PathVariable String id, @RequestBody JsonNode body, HttpSession session) {
        var p = principal(session); taskId(id);
        var toggle = decode(body, KnowledgeToggle.class);
        if (toggle.enabled() == null) throw invalid();
        synchronized (mutationLock(p)) {
            return request(p, null, toggle.enabled() ? "knowledge-enable" : "knowledge-disable",
                    () -> new FileService(database).setKnowledgeEnabled(p.trustedSession(), p.enterprise(), id, toggle.enabled()));
        }
    }
    @GetMapping("/files") public Object files(HttpSession session) {
        var p = principal(session);
        return request(p, null, "files", () -> new FileService(database).list(p.trustedSession(), p.enterprise()));
    }
    @GetMapping("/files/{id}") public Object file(@PathVariable String id, HttpSession session) {
        var p = principal(session); taskId(id);
        return request(p, null, "file", () -> new FileService(database).detail(p.trustedSession(), p.enterprise(), id));
    }
    // T30 agent configuration. Reading needs READ on the session enterprise; changes need WRITE on every covered enterprise.
    @GetMapping("/agents") public Object listAgents(HttpSession session) {
        var p = principal(session);
        return request(p, null, "agents", () -> {
            var list = agents().list(p.trustedSession(), p.enterprise());
            var registry = new CapabilityRegistry(database);
            var effective = new LinkedHashMap<String, CapabilityRegistry.Effective>();
            for (var a : list) effective.put(a.id(), registry.effective(a));
            return Map.of("agents", list, "effective", effective, "profiles", profiles.describe(),
                    "tools", AgentDirectory.TOOLS.stream().sorted().toList());
        });
    }
    // T31 capabilities: tools (global switch), skills (versioned) and who uses them.
    public record SkillSave(String id, CapabilityRegistry.SkillSpec spec) {}
    @GetMapping("/capabilities") public Object capabilities(HttpSession session) {
        var p = principal(session);
        return request(p, null, "capabilities", () -> {
            var registry = new CapabilityRegistry(database);
            return Map.of("tools", registry.tools(p.trustedSession(), p.enterprise()), "skills", registry.skills(p.trustedSession(), p.enterprise()));
        });
    }
    @PostMapping("/capabilities/tools/{name}/enabled") public Object toggleTool(@PathVariable String name, @RequestBody JsonNode body, HttpSession session) {
        var input = decode(body, AgentToggle.class); var p = principal(session); text(name);
        if (input.enabled() == null) throw invalid();
        synchronized (mutationLock(p)) {
            return request(p, null, input.enabled() ? "tool-enable" : "tool-disable",
                    () -> new CapabilityRegistry(database).setToolEnabled(p.trustedSession(), p.enterprise(), name, input.enabled()));
        }
    }
    @PostMapping("/capabilities/skills") public Object saveSkill(@RequestBody JsonNode body, HttpSession session) {
        var input = decode(body, SkillSave.class); var p = principal(session);
        synchronized (mutationLock(p)) {
            return request(p, null, "skill-save", () -> new CapabilityRegistry(database).saveSkill(p.trustedSession(), p.enterprise(), input.id(), input.spec()));
        }
    }
    @PostMapping("/capabilities/skills/{id}/enabled") public Object toggleSkill(@PathVariable String id, @RequestBody JsonNode body, HttpSession session) {
        var input = decode(body, AgentToggle.class); var p = principal(session); text(id);
        if (input.enabled() == null) throw invalid();
        synchronized (mutationLock(p)) {
            return request(p, null, input.enabled() ? "skill-enable" : "skill-disable",
                    () -> new CapabilityRegistry(database).setSkillEnabled(p.trustedSession(), p.enterprise(), id, input.enabled()));
        }
    }
    @GetMapping("/capabilities/skills/{id}/history") public Object skillHistory(@PathVariable String id, HttpSession session) {
        var p = principal(session); text(id);
        return request(p, null, "skill-history", () -> new CapabilityRegistry(database).skillHistory(p.trustedSession(), p.enterprise(), id));
    }
    @GetMapping("/agents/{id}/history") public Object agentHistory(@PathVariable String id, HttpSession session) {
        var p = principal(session); text(id);
        return request(p, null, "agent-history", () -> agents().history(p.trustedSession(), p.enterprise(), id));
    }
    @PostMapping("/agents") public Object saveAgent(@RequestBody JsonNode body, HttpSession session) {
        var input = decode(body, AgentSave.class); var p = principal(session);
        synchronized (mutationLock(p)) {
            return request(p, null, "agent-save", () -> agents().save(p.trustedSession(), p.enterprise(), input.id(), input.spec()));
        }
    }
    @PostMapping("/agents/{id}/enabled") public Object toggleAgent(@PathVariable String id, @RequestBody JsonNode body, HttpSession session) {
        var input = decode(body, AgentToggle.class); var p = principal(session); text(id);
        if (input.enabled() == null) throw invalid();
        synchronized (mutationLock(p)) {
            return request(p, null, input.enabled() ? "agent-enable" : "agent-disable",
                    () -> agents().setEnabled(p.trustedSession(), p.enterprise(), id, input.enabled()));
        }
    }
    // T33 long-term memory with scopes and expiry. Deleting erases stored values and stored model inputs.
    @GetMapping("/memories") public Object memories(HttpSession session) {
        var p = principal(session);
        return request(p, null, "memories", () -> agent(p).memories());
    }
    @PostMapping("/memories") public Object putMemory(@RequestBody JsonNode body, HttpSession session) {
        var input = decode(body, MemoryInput.class); var p = principal(session);
        if (input.scope() == null) throw invalid();
        text(input.name()); text(input.value()); text(input.userRequest());
        return request(p, null, "memory-put-" + input.scope().name().toLowerCase(Locale.ROOT), () -> {
            agent(p).putMemory(input.scope(), input.name(), new TaskMemory.Input(input.value(), input.userRequest()), input.expiresOn());
            return agent(p).memories();
        });
    }
    @DeleteMapping("/memories/{scope}/{name}") public Object deleteMemory(@PathVariable String scope, @PathVariable String name, HttpSession session) {
        var p = principal(session); text(name);
        TaskMemory.Scope parsed;
        try { parsed = TaskMemory.Scope.valueOf(scope); } catch (Exception e) { throw invalid(); }
        return request(p, null, "memory-delete-" + parsed.name().toLowerCase(Locale.ROOT), () -> {
            agent(p).deleteMemory(parsed, name);
            // Cached turn replies carry model input; a deleted memory must not come back through a replay.
            var user = p.trustedSession().userId() + "|";
            turnCache.keySet().removeIf(key -> parsed == TaskMemory.Scope.ENTERPRISE ? key.contains("|" + p.enterprise() + "|") : key.startsWith(user));
            return agent(p).memories();
        });
    }
    @GetMapping("/tasks") public Object tasks(HttpSession session) {
        var p = principal(session); return request(p,null,"tasks",() -> agent(p).tasks());
    }
    @PostMapping("/tasks") public Object start(@RequestBody JsonNode body, HttpSession session) {
        var input = decode(body, Start.class); var p = principal(session);
        return request(p,null,"start",() -> {
            // The agent must be usable here before the task exists, so a task is never bound to an agent it cannot use.
            var directory = agents();
            var chosen = directory.resolve(p.trustedSession(), p.enterprise(), input.agentId());
            var created = agent(p).start(input.goal(),input.pending() == null ? List.of() : input.pending());
            directory.bindTask(p.trustedSession(), p.enterprise(), created.id(), chosen.id());
            return created;
        });
    }
    @GetMapping("/tasks/{id}") public Object load(@PathVariable String id, HttpSession session) {
        var p = principal(session); taskId(id);
        return request(p,id,"load",() -> {
            var context = agent(p).load(id);
            var directory = agents();
            var bound = directory.agentOf(p.trustedSession(), p.enterprise(), id);
            var current = directory.list(p.trustedSession(), p.enterprise()).stream().filter(a -> a.id().equals(bound)).findFirst().orElse(null);
            return new LoadView(context.task(), context.preferences(), current);
        });
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
    /** Same turn execution as /turn, streamed as SSE: tool progress, then the final text in chunks.
     *  Text events are presentation only; clients must use the terminal `done` payload for status and verification. */
    @PostMapping(value = "/tasks/{id}/turn/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter turnStream(@PathVariable String id, @RequestBody JsonNode body, HttpSession session) {
        var input = decode(body,Turn.class); text(input.message()); taskId(id);
        var p = principal(session);
        var emitter = new SseEmitter(120_000L);
        streamExecutor.execute(() -> {
            try {
                emit(emitter, "status", Map.of("phase", "started"));
                TurnView view;
                synchronized (mutationLock(p)) {
                    view = run(p, id, input, progress -> emit(emitter, "tool", Map.of(
                            "phase", progress.phase(), "callId", progress.callId(), "name", progress.name(),
                            "durationMs", progress.durationMs(),
                            "outcome", progress.outcome() == null ? "" : progress.outcome())));
                }
                if (view.turn() != null && view.turn().result() != null)
                    streamText(emitter, view.turn().result().text());
                emit(emitter, "done", view);
                close(emitter);
            } catch (DomainException e) {
                emit(emitter, "error", Map.of("errorCode", e.code().name(), "message", "请求未完成：" + e.code().name()));
                close(emitter);
            } catch (Exception e) {
                emit(emitter, "error", Map.of("errorCode", "RUNTIME_FAILURE", "message", "流式请求失败"));
                close(emitter);
            }
        });
        return emitter;
    }
    /** T36: completing a stream the client already left must not throw; the turn's outcome is already persisted. */
    private static void close(SseEmitter emitter) {
        try { emitter.complete(); } catch (Exception ignored) { }
    }
    private static void emit(SseEmitter emitter, String event, Object data) {
        try { emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON)); }
        catch (Exception ignored) { /* client disconnected: the turn still completes and is audited */ }
    }
    private static void streamText(SseEmitter emitter, String text) {
        if (text == null || text.isBlank()) return;
        int size = 24;
        for (int start = 0; start < text.length(); start += size) {
            emit(emitter, "text", Map.of("text", text.substring(start, Math.min(text.length(), start + size))));
            try { Thread.sleep(18); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
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
        return run(p, taskId, input, progress -> {});
    }
    private TurnView run(DemoPrincipal p, String taskId, Turn input, Consumer<AgentRuntime.Progress> progress) {
        var a = turnAgent(p, taskId);
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
        List<InvoiceWriteService.BatchItem> batch = null;
        if (approval != null) {
            if (!approval.confirmed()) throw new DomainException(ErrorCode.CONFIRMATION_REQUIRED,"Structured confirmation required");
            uuid(approval.operationId());
            if (approval.items() != null) {
                // T47: every invoice was confirmed one by one in the trusted UI; the server re-checks each of them.
                if (approval.invoiceId() != null || approval.changes() != null || approval.items().isEmpty()
                        || approval.items().size() > InvoiceWriteService.MAX_BATCH) throw invalid();
                batch = new ArrayList<>();
                for (var entry : approval.items()) {
                    if (entry == null) throw invalid();
                    uuid(entry.operationId());
                    batch.add(new InvoiceWriteService.BatchItem(entry.operationId(),
                            new InvoiceUpdate(new InvoiceKey(p.enterprise(), entry.invoiceId()), entry.changes())));
                }
                update = batch.getFirst().update();
            } else update = new InvoiceUpdate(new InvoiceKey(p.enterprise(),approval.invoiceId()),approval.changes());
        }
        var items = batch;
        log(p,requestId,taskId,"turn","REQUESTED",null);
        long turnStarted = System.nanoTime();
        TaskMemory.Turn turn;
        try {
            turn = update == null ? a.turn(taskId,input.message(),progress)
                    : items != null ? a.approvedBatchTurn(taskId, input.message(), approval.operationId(), items,
                        items.stream().anyMatch(i -> i.update().changes().values().stream().anyMatch(String::isBlank))
                                ? InvoiceWriteService.Intent.CLEAR_NOW : InvoiceWriteService.Intent.SAVE_NOW, progress)
                    : a.approvedTurn(taskId,input.message(),approval.operationId(),update,
                    update.changes().values().stream().anyMatch(String::isBlank) ? InvoiceWriteService.Intent.CLEAR_NOW : InvoiceWriteService.Intent.SAVE_NOW, progress);
        } catch (DomainException failure) {
            if (items != null) {
                var recovered = a.recoverBatch(taskId, approval.operationId(), items); // Query only, never a retry.
                if (recovered.isPresent()) {
                    try {
                        audit.append(p.trustedSession(),p.enterprise(),event(requestId,taskId,"recover",null,null,null,null,
                                approval.operationId(),"RECOVERABLE_TASK_STATE_FAILURE",failure.code().name(),recovered.get().verified()));
                    } catch (DemoAudit.Failure auditFailure) {
                        return new TurnView(null,null,"AUDIT_STORAGE_ERROR","批量写入已核验但任务状态保存失败；恢复审计记录也失败，请保留 operationId 核查，绝不重复写入",approval.operationId(),null,null,recovered.get());
                    }
                    return new TurnView(null,null,"TASK_STATE_SAVE_FAILED","批量写入已核验但任务状态保存失败；请恢复任务并查询，绝不重复写入",approval.operationId(),null,null,recovered.get());
                }
            } else if (update != null) {
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
        BatchUpdateResult batchResult = null;
        try {
            var trace = turn.promptTrace();
            if (trace != null) audit.appendTrace(p.trustedSession(), p.enterprise(), requestId, taskId,
                    trace.systemPrompt(), trace.userMessage(), trace.tools().stream().map(t -> t.name()).toList(),
                    trace.historySize(), turn.memorySource(), turn.result().status().name());
            for (var exchange : turn.result().history()) {
                var call = exchange.call(); JsonNode args;
                try { args = JSON.readTree(call.arguments()); } catch (Exception malformed) { args = JSON.createObjectNode(); }
                JsonNode result = JSON.readTree(exchange.resultJson());
                String tool = Set.of("query_invoices","query_bank_entries","check_invoice_purpose","query_rules","search_knowledge","update_invoice").contains(call.name()) ? call.name() : "rejected_tool";
                boolean ok = result.path("error").isNull();
                boolean saved = "update_invoice".equals(tool) && ok && result.at("/data/verified").asBoolean(false);
                String code = ok ? null : safe(result.at("/error/code").asText());
                // An approved write executes the trusted approval payload, not the model's (empty) arguments.
                boolean approvedWrite = "update_invoice".equals(tool) && update != null;
                if (approvedWrite && items != null) {
                    // T47: one audit row per invoice of the batch, each with its own operationId.
                    for (var item : items)
                        audit.append(p.trustedSession(),p.enterprise(),event(requestId,taskId,tool,safe(call.id()),null,
                                safe(item.update().key().invoiceId()),fields(item.update().changes().keySet()),item.operationId(),
                                ok ? "TOOL_RESULT" : "TOOL_ERROR",code,saved,ruleVersion(result),turn.memorySource(),exchange.durationMs()));
                    if (saved) batchResult = a.recoverBatch(taskId, approval.operationId(), items).orElse(null);
                    continue;
                }
                audit.append(p.trustedSession(),p.enterprise(),event(requestId,taskId,tool,safe(call.id()),
                        month(args.path("month").asText()),
                        approvedWrite ? safe(update.key().invoiceId()) : safe(args.at("/key/invoice_id").asText()),
                        fields(approvedWrite ? update.changes().keySet() : keys(args.path("changes"))),approval == null ? null : approval.operationId(),ok ? "TOOL_RESULT" : "TOOL_ERROR",code,saved,
                        ruleVersion(result),turn.memorySource(),exchange.durationMs()));
                if (saved && update != null) verified = a.recover(taskId,approval.operationId(),update).orElse(null);
            }
            log(p,requestId,taskId,"turn",turn.result().status().name(),turn.result().errorCode(),
                    turn.memorySource(), turnDurationMs);
        } catch (DemoAudit.Failure failure) {
            // A committed write stays committed. Return explicit audit failure plus read-only verification.
            if (items != null) batchResult = a.recoverBatch(taskId, approval.operationId(), items).orElse(null);
            else if (update != null) verified = a.recover(taskId,approval.operationId(),update).orElse(null);
            return new TurnView(turn,verified,"AUDIT_STORAGE_ERROR","审计记录失败；以回读核验为准，请勿重复写入",approval == null ? null : approval.operationId(),null,null,batchResult);
        } catch (DomainException e) { throw e;
        } catch (Exception e) { throw new DemoAudit.Failure(); }
        String message;
        if (batchResult != null) message = "批量 " + batchResult.results().size() + " 张发票在同一事务内保存，逐张 verified=true 且数据库回读已核验"
                + (batchResult.review().isEmpty() ? "；程序检查无需复核" : "；其中 " + batchResult.review().size() + " 张需要复核");
        else if (verified != null) message = "实际工具 verified=true 且数据库回读已核验";
        else if ("WRITE_NOT_EXECUTED".equals(turn.result().errorCode()))
            message = "本轮未执行保存；旧批准已结束，如需保存请重新确认";
        else if ("WRITE_NOT_VERIFIED".equals(turn.result().errorCode()))
            message = "写入结果未核验；保留操作锁，请核查台账与发票";
        else message = "模型文字不是写入证据；请查看实际工具结果";
        var view = new TurnView(turn,verified,turn.result().errorCode(),message,approval == null ? null : approval.operationId(),
                turn.modelInput(), turn.promptTrace(), batchResult);
        if (turnCache.size() >= TURN_CACHE_MAX) turnCache.clear();
        turnCache.put(cacheKey, new CachedTurn(fingerprint, view, System.currentTimeMillis() + TURN_CACHE_TTL_MS));
        return view;
    }
    private static Set<String> keys(JsonNode node) { var keys = new HashSet<String>(); node.fieldNames().forEachRemaining(keys::add); return keys; }
    private static String fields(Set<String> keys) { return String.join(",",keys.stream().filter(Set.of("purpose","remarks")::contains).sorted().toList()); }
    private static String fingerprint(Turn input) {
        var approval = input.approval();
        return input.message() + "|" + (approval == null ? "" : approval.operationId() + "|" + approval.invoiceId()
                + "|" + approval.changes() + "|" + approval.items());
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
    /** Stored model input of recent turns; reload-safe replacement for the in-memory trace. */
    @GetMapping("/tasks/{id}/traces") public Object traces(@PathVariable String id, HttpSession session) {
        var p = principal(session);
        taskId(id);
        return request(p, id, "traces", () -> audit.traces(p.trustedSession(), p.enterprise(), id));
    }
    @GetMapping("/audit") public Object audit(HttpSession session) {
        var p = principal(session); return audit.list(p.trustedSession(),p.enterprise());
    }
    @GetMapping("/audit/export") public ResponseEntity<String> exportAudit(HttpSession session) {
        var p = principal(session);
        var events = audit.list(p.trustedSession(), p.enterprise());
        // T51: readable Chinese headers that keep the original key in brackets, so scripts and earlier readers still match.
        var csv = new StringBuilder("序号(id),时间UTC(time),请求编号(requestId),任务编号(taskId),工具或接口(tool),调用编号(callId),结果(outcome),"
                + "错误码(errorCode),已回读核验(verified),规则版本(ruleVersion),记忆来源(memorySource),耗时毫秒(durationMs)\n");
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
            int status = switch(e.code()) { case AUTHENTICATION_REQUIRED -> 401; case PERMISSION_DENIED, CAPABILITY_NOT_GRANTED -> 403;
                case CAPABILITY_DISABLED -> 409;
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
