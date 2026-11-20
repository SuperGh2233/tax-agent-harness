package com.taxagent.harness.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taxagent.harness.tool.*;
import com.taxagent.harness.tool.PermissionGuard.Session;
import java.nio.file.Path;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Trusted backend service only. Never expose Session or explicit-input methods as model tools. */
public final class TaskMemory {
    public enum Status { ACTIVE, WAITING_USER, COMPLETED, CANCELLED }
    public record Input(String value, String userRequest) {
        public Input { text(value); text(userRequest); }
    }
    public record Summary(String tool, String outcome) {}
    public record PlanStep(String key, String title, String state, String detail) {}
    public record Plan(List<PlanStep> steps) {
        public Plan { steps = List.copyOf(steps); }
        public static Plan empty() { return new Plan(List.of()); }
    }
    /**
     * T35 awaitingSave: invoice fields (F002.purpose) the user has stated but that no verified write of this task has
     * saved yet. A statement is not a save, so the task cannot complete while any remain; tasks stored before T35
     * have none.
     */
    public record Task(String id, Status status, long version, String goal, List<String> pending,
                       Map<String, Input> supplied, String question, List<Summary> recentTools, Plan plan,
                       List<String> awaitingSave) {
        public Task {
            pending = List.copyOf(pending); supplied = Map.copyOf(supplied); recentTools = List.copyOf(recentTools);
            plan = plan == null ? Plan.empty() : plan;
            awaitingSave = awaitingSave == null ? List.of() : List.copyOf(awaitingSave);
        }
        public Task(String id, Status status, long version, String goal, List<String> pending,
                    Map<String, Input> supplied, String question, List<Summary> recentTools, Plan plan) {
            this(id, status, version, goal, pending, supplied, question, recentTools, plan, List.of());
        }
        public Task(String id, Status status, long version, String goal, List<String> pending,
                    Map<String, Input> supplied, String question, List<Summary> recentTools) {
            this(id, status, version, goal, pending, supplied, question, recentTools, Plan.empty());
        }
    }
    private static final java.util.regex.Pattern INVOICE_FIELD = java.util.regex.Pattern.compile("[^.\\s]+\\.(purpose|remarks)");
    static boolean invoiceField(String key) { return INVOICE_FIELD.matcher(key).matches(); }
    public record Context(Task task, Map<String, Input> preferences) {
        public Context { preferences = Map.copyOf(preferences); }
    }
    public record Turn(Task task, AgentRuntime.Result result, String memorySource, String modelInput,
                       AgentRuntime.Trace promptTrace) {
        public Turn(Task task, AgentRuntime.Result result) { this(task, result, null, null, null); }
        public Turn(Task task, AgentRuntime.Result result, String memorySource) { this(task, result, memorySource, null, null); }
        public Turn(Task task, AgentRuntime.Result result, String memorySource, String modelInput) {
            this(task, result, memorySource, modelInput, null);
        }
    }
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String url;
    private final Path database;
    private final PermissionGuard guard = new PermissionGuard();

    /** Existing initialized DB only; additive migration never imports or updates business records. */
    public TaskMemory(Path database) {
        this.database = database;
        url = "jdbc:sqlite:" + database.toAbsolutePath().toUri().toASCIIString() + "?mode=rw";
        try (var c = connect(); var in = TaskMemory.class.getResourceAsStream("/task-memory.sql")) {
            c.setAutoCommit(false);
            try (var s = c.createStatement()) {
                for (var sql : new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8).split(";"))
                    if (!sql.isBlank()) s.execute(sql);
            }
            // T33: expiry and change time on the pre-existing per-user-per-enterprise table. Separate statements:
            // sqlite-jdbc finalizes a statement once its result set is closed.
            var columns = new HashSet<String>();
            try (var q = c.createStatement(); var r = q.executeQuery("PRAGMA table_info(agent_preferences)")) {
                while (r.next()) columns.add(r.getString("name"));
            }
            try (var s = c.createStatement()) {
                if (!columns.contains("expires_on")) s.execute("ALTER TABLE agent_preferences ADD COLUMN expires_on TEXT");
                if (!columns.contains("updated_at")) s.execute("ALTER TABLE agent_preferences ADD COLUMN updated_at TEXT");
            }
            c.commit();
        } catch (Exception e) { throw storage(e); }
    }
    public Task start(Session session, String enterprise, String explicitGoal, List<String> pending) {
        text(explicitGoal); if (pending == null) throw invalid(); pending.forEach(TaskMemory::text);
        var task = new Task(UUID.randomUUID().toString(), Status.ACTIVE, 0, explicitGoal, pending, Map.of(), null,
                List.of(), TaskPlan.derive(Status.ACTIVE, pending, List.of(), List.of()));
        return access(session, enterprise, c -> {
            try (var s = c.prepareStatement("INSERT INTO agent_tasks VALUES (?,?,?,?,?,?)")) {
                scope(s, session, enterprise); s.setString(3, task.id()); s.setString(4, task.status().name());
                s.setLong(5, 0); s.setString(6, JSON.writeValueAsString(task)); s.executeUpdate();
            } catch (SQLException e) {
                if (e.getErrorCode() == 19) throw conflict();
                throw e;
            }
            return task;
        });
    }
    public List<Task> tasks(Session session, String enterprise) {
        return access(session, enterprise, c -> {
            var tasks = new ArrayList<Task>();
            try (var s = c.prepareStatement("SELECT payload FROM agent_tasks WHERE user_id=? AND enterprise_id=? ORDER BY id")) {
                scope(s, session, enterprise);
                try (var r = s.executeQuery()) { while (r.next()) tasks.add(JSON.readValue(r.getString(1), Task.class)); }
            }
            return List.copyOf(tasks);
        });
    }
    public Context load(Session session, String enterprise, String id) {
        text(id);
        return access(session, enterprise, c -> new Context(get(c, session, enterprise, id), preferences(c, session, enterprise)));
    }
    /** Caller verifies these are explicit user statements, never model inference or business facts. */
    public Task supplement(Session session, String enterprise, String id, long version, Map<String, Input> changes) {
        if (changes == null || changes.isEmpty() || changes.values().stream().anyMatch(Objects::isNull)) throw invalid();
        changes.keySet().forEach(TaskMemory::text);
        return access(session, enterprise, c -> {
            var old = get(c, session, enterprise, id); open(old, version);
            var fields = new HashSet<>(old.pending()); fields.addAll(old.supplied().keySet());
            if (!fields.containsAll(changes.keySet())) throw invalid();
            var supplied = new HashMap<>(old.supplied()); supplied.putAll(changes);
            var pending = old.pending().stream().filter(k -> !changes.containsKey(k)).toList();
            // T35: a stated invoice value still has to be saved; a correction reopens only the field it names.
            var awaiting = new ArrayList<>(old.awaitingSave());
            changes.keySet().stream().filter(TaskMemory::invoiceField).sorted()
                    .filter(k -> !awaiting.contains(k)).forEach(awaiting::add);
            var next = new Task(id, Status.ACTIVE, version + 1, old.goal(), pending, supplied, null, old.recentTools(),
                    TaskPlan.derive(Status.ACTIVE, pending, awaiting, old.recentTools(), List.of()), awaiting);
            save(c, session, enterprise, old, next); return next;
        });
    }
    public Task cancel(Session session, String enterprise, String id, long version) {
        return access(session, enterprise, c -> {
            var old = get(c, session, enterprise, id); open(old, version);
            var next = new Task(id, Status.CANCELLED, version + 1, old.goal(), old.pending(), old.supplied(), null,
                    old.recentTools(), TaskPlan.derive(Status.CANCELLED, old.pending(), old.awaitingSave(), old.recentTools(), List.of()),
                    old.awaitingSave());
            save(c, session, enterprise, old, next); return next;
        });
    }
    /** Effective memories as the model receives them (scope-prefixed names), after expired ones are purged. */
    public Map<String, Input> preferences(Session session, String enterprise) {
        return access(session, enterprise, c -> preferences(c, session, enterprise));
    }
    /** Explicit long-term preference request must be confirmed by trusted caller, no automatic extraction. */
    public void putPreference(Session session, String enterprise, String name, Input explicitRequest) {
        putMemory(session, enterprise, Scope.USER_ENTERPRISE, name, explicitRequest, null);
    }
    public void deletePreference(Session session, String enterprise, String name) {
        deleteMemory(session, enterprise, Scope.USER_ENTERPRISE, name);
    }

    // ---- T34 conversation log: the original behind the compressed model view ----

    public record TurnLog(int seq, String userMessage, String agentText, String status) {}
    public record StoredToolResult(int turnSeq, String callId, String tool, String arguments, String resultJson) {}

    public List<TurnLog> turns(Session session, String enterprise, String taskId) {
        return access(session, enterprise, c -> { get(c, session, enterprise, taskId); return turns(c, session, enterprise, taskId); });
    }
    private static List<TurnLog> turns(Connection c, Session session, String enterprise, String taskId) throws SQLException {
        var out = new ArrayList<TurnLog>();
        try (var st = c.prepareStatement("SELECT seq,user_message,agent_text,status FROM task_turns WHERE user_id=? AND enterprise_id=? AND task_id=? ORDER BY seq")) {
            scope(st, session, enterprise); st.setString(3, taskId);
            try (var r = st.executeQuery()) { while (r.next()) out.add(new TurnLog(r.getInt(1), r.getString(2), r.getString(3), r.getString(4))); }
        }
        return List.copyOf(out);
    }
    /** Latest stored result for a call id in this task; the same id may recur across turns. */
    public Optional<StoredToolResult> toolResult(Session session, String enterprise, String taskId, String callId) {
        return access(session, enterprise, c -> {
            get(c, session, enterprise, taskId);
            try (var st = c.prepareStatement("SELECT turn_seq,call_id,tool,arguments,result_json FROM task_tool_results "
                    + "WHERE user_id=? AND enterprise_id=? AND task_id=? AND call_id=? ORDER BY turn_seq DESC LIMIT 1")) {
                scope(st, session, enterprise); st.setString(3, taskId); st.setString(4, callId);
                try (var r = st.executeQuery()) {
                    return r.next() ? Optional.of(new StoredToolResult(r.getInt(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5)))
                            : Optional.<StoredToolResult>empty();
                }
            }
        });
    }
    /** Newest stored calls of this task without their results, so recall_context can name the ids that exist (T38). */
    public List<StoredToolResult> recentToolCalls(Session session, String enterprise, String taskId, int limit) {
        return access(session, enterprise, c -> {
            get(c, session, enterprise, taskId);
            var out = new ArrayList<StoredToolResult>();
            try (var st = c.prepareStatement("SELECT turn_seq,call_id,tool,arguments FROM task_tool_results WHERE user_id=? AND enterprise_id=? "
                    + "AND task_id=? AND tool<>'recall_context' ORDER BY turn_seq DESC, rowid DESC LIMIT ?")) {
                scope(st, session, enterprise); st.setString(3, taskId); st.setInt(4, limit);
                try (var r = st.executeQuery()) {
                    while (r.next()) out.add(new StoredToolResult(r.getInt(1), r.getString(2), r.getString(3), r.getString(4), null));
                }
            }
            return List.copyOf(out);
        });
    }

    // ---- T33 long-term memory lifecycle (FINAL_ROUND_CONTRACT B6) ----

    /**
     * USER_ENTERPRISE: this user in this enterprise (the pre-T33 behaviour). PERSONAL: this user in every
     * enterprise; it may not carry enterprise, invoice or bank identifiers, so one enterprise's facts never reach
     * another. ENTERPRISE: shared by the enterprise's users; only users who may modify the enterprise change it.
     * For one name the most specific scope wins: USER_ENTERPRISE, then PERSONAL, then ENTERPRISE.
     */
    public enum Scope { USER_ENTERPRISE, PERSONAL, ENTERPRISE }
    public record Memory(Scope scope, String name, String value, String userRequest, String expiresOn, String updatedAt,
                         boolean effective) {}
    public static final String PERSONAL_PREFIX = "个人·", ENTERPRISE_PREFIX = "企业·";
    static final String DELETED_MARK = "[已删除的记忆]";
    /** The append-only guard of agent_trace; lifted only inside the transaction that erases a deleted memory. */
    public static final String TRACE_NO_UPDATE = "CREATE TRIGGER IF NOT EXISTS agent_trace_no_update BEFORE UPDATE ON agent_trace BEGIN SELECT RAISE(ABORT,'append only'); END";
    private static final java.util.regex.Pattern BUSINESS_ID = java.util.regex.Pattern.compile("(?<![A-Za-z0-9])[CFTcft][0-9]{3}(?![0-9])");

    public List<Memory> memories(Session session, String enterprise) {
        return access(session, enterprise, c -> {
            purgeExpired(c, session, enterprise);
            var all = load(c, session, enterprise);
            var winners = winners(all);
            return all.stream().map(m -> new Memory(m.scope(), m.name(), m.value(), m.userRequest(), m.expiresOn(), m.updatedAt(),
                    winners.get(m.name()) == m.scope())).toList();
        });
    }

    /** The newest explicit statement for a name replaces the old one; the old value is never offered again. */
    public Memory putMemory(Session session, String enterprise, Scope scope, String name, Input explicitRequest, String expiresOn) {
        text(name); if (scope == null || explicitRequest == null) throw invalid();
        text(explicitRequest.value()); text(explicitRequest.userRequest());
        if (name.contains("·") || name.length() > 80) throw new DomainException(ErrorCode.INVALID_ARGUMENT, "Invalid memory name");
        if (explicitRequest.value().length() > 500 || explicitRequest.userRequest().length() > 1000)
            throw new DomainException(ErrorCode.INVALID_ARGUMENT, "Memory too long");
        if (scope == Scope.PERSONAL && (BUSINESS_ID.matcher(explicitRequest.value()).find() || BUSINESS_ID.matcher(name).find()))
            throw new DomainException(ErrorCode.INVALID_ARGUMENT, "Personal memory cannot hold enterprise, invoice or bank identifiers");
        String expiry = null;
        if (expiresOn != null && !expiresOn.isBlank()) {
            try { expiry = java.time.LocalDate.parse(expiresOn.strip()).toString(); }
            catch (Exception e) { throw new DomainException(ErrorCode.INVALID_ARGUMENT, "expiresOn must be yyyy-MM-dd"); }
            if (java.time.LocalDate.parse(expiry).isBefore(java.time.LocalDate.now())) throw new DomainException(ErrorCode.INVALID_ARGUMENT, "Expiry is in the past");
        }
        var now = java.time.Instant.now().toString();
        var exp = expiry;
        return access(session, enterprise, c -> {
            if (scope == Scope.ENTERPRISE) guard.require(c, session, enterprise, PermissionGuard.Access.WRITE);
            var sql = switch (scope) {
                case USER_ENTERPRISE -> "INSERT INTO agent_preferences(user_id,enterprise_id,name,value,user_request,expires_on,updated_at) VALUES (?,?,?,?,?,?,?) "
                        + "ON CONFLICT(user_id,enterprise_id,name) DO UPDATE SET value=excluded.value,user_request=excluded.user_request,expires_on=excluded.expires_on,updated_at=excluded.updated_at";
                case PERSONAL -> "INSERT INTO personal_preferences(user_id,name,value,user_request,expires_on,updated_at) VALUES (?,?,?,?,?,?) "
                        + "ON CONFLICT(user_id,name) DO UPDATE SET value=excluded.value,user_request=excluded.user_request,expires_on=excluded.expires_on,updated_at=excluded.updated_at";
                case ENTERPRISE -> "INSERT INTO enterprise_preferences(enterprise_id,name,value,user_request,expires_on,updated_at,updated_by) VALUES (?,?,?,?,?,?,?) "
                        + "ON CONFLICT(enterprise_id,name) DO UPDATE SET value=excluded.value,user_request=excluded.user_request,expires_on=excluded.expires_on,updated_at=excluded.updated_at,updated_by=excluded.updated_by";
            };
            // Overwritten text must not linger in free database pages either.
            try (var p = c.createStatement()) { p.execute("PRAGMA secure_delete=ON"); }
            try (var st = c.prepareStatement(sql)) {
                int i = 1;
                if (scope == Scope.USER_ENTERPRISE) { st.setString(i++, session.userId()); st.setString(i++, enterprise); }
                else if (scope == Scope.PERSONAL) st.setString(i++, session.userId());
                else st.setString(i++, enterprise);
                st.setString(i++, name); st.setString(i++, explicitRequest.value()); st.setString(i++, explicitRequest.userRequest());
                st.setString(i++, exp); st.setString(i++, now);
                if (scope == Scope.ENTERPRISE) st.setString(i, session.userId());
                st.executeUpdate();
            }
            var all = load(c, session, enterprise);
            var winners = winners(all);
            return all.stream().filter(m -> m.scope() == scope && m.name().equals(name)).findFirst()
                    .map(m -> new Memory(m.scope(), m.name(), m.value(), m.userRequest(), m.expiresOn(), m.updatedAt(), winners.get(name) == scope))
                    .orElseThrow();
        });
    }

    /** Deletion erases the memory from storage and from every stored model input in its scope. */
    public boolean deleteMemory(Session session, String enterprise, Scope scope, String name) {
        text(name); if (scope == null) throw invalid();
        return access(session, enterprise, c -> {
            if (scope == Scope.ENTERPRISE) guard.require(c, session, enterprise, PermissionGuard.Access.WRITE);
            return forget(c, scope, session.userId(), enterprise, name);
        });
    }

    private record Row(Scope scope, String name, String value, String userRequest, String expiresOn, String updatedAt) {}
    private static List<Row> load(Connection c, Session session, String enterprise) throws SQLException {
        var out = new ArrayList<Row>();
        try (var st = c.prepareStatement("SELECT name,value,user_request,expires_on,updated_at FROM agent_preferences WHERE user_id=? AND enterprise_id=? ORDER BY name")) {
            scope(st, session, enterprise);
            try (var r = st.executeQuery()) { while (r.next()) out.add(new Row(Scope.USER_ENTERPRISE, r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5))); }
        }
        try (var st = c.prepareStatement("SELECT name,value,user_request,expires_on,updated_at FROM personal_preferences WHERE user_id=? ORDER BY name")) {
            st.setString(1, session.userId());
            try (var r = st.executeQuery()) { while (r.next()) out.add(new Row(Scope.PERSONAL, r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5))); }
        }
        try (var st = c.prepareStatement("SELECT name,value,user_request,expires_on,updated_at FROM enterprise_preferences WHERE enterprise_id=? ORDER BY name")) {
            st.setString(1, enterprise);
            try (var r = st.executeQuery()) { while (r.next()) out.add(new Row(Scope.ENTERPRISE, r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5))); }
        }
        return out;
    }
    /** Winning scope per name: the most specific one (enum order). */
    private static Map<String, Scope> winners(List<Row> rows) {
        var out = new HashMap<String, Scope>();
        for (var row : rows) out.merge(row.name(), row.scope(), (a, b) -> a.ordinal() <= b.ordinal() ? a : b);
        return out;
    }
    private static String key(Scope scope, String name) {
        return switch (scope) { case USER_ENTERPRISE -> name; case PERSONAL -> PERSONAL_PREFIX + name; case ENTERPRISE -> ENTERPRISE_PREFIX + name; };
    }
    /** Expired memories are not merely hidden: they are erased exactly like a deletion, on first access. */
    private static void purgeExpired(Connection c, Session session, String enterprise) throws Exception {
        var today = java.time.LocalDate.now();
        for (var row : load(c, session, enterprise))
            if (row.expiresOn() != null && java.time.LocalDate.parse(row.expiresOn()).isBefore(today))
                forget(c, row.scope(), session.userId(), enterprise, row.name());
    }
    private static boolean forget(Connection c, Scope scope, String user, String enterprise, String name) throws Exception {
        try (var p = c.createStatement()) { p.execute("PRAGMA secure_delete=ON"); }
        int deleted;
        var sql = switch (scope) {
            case USER_ENTERPRISE -> "DELETE FROM agent_preferences WHERE user_id=? AND enterprise_id=? AND name=?";
            case PERSONAL -> "DELETE FROM personal_preferences WHERE user_id=? AND name=?";
            case ENTERPRISE -> "DELETE FROM enterprise_preferences WHERE enterprise_id=? AND name=?";
        };
        try (var st = c.prepareStatement(sql)) {
            int i = 1;
            if (scope != Scope.ENTERPRISE) st.setString(i++, user);
            if (scope != Scope.PERSONAL) st.setString(i++, enterprise);
            st.setString(i, name);
            deleted = st.executeUpdate();
        }
        redactTraces(c, scope, user, enterprise, name);
        return deleted > 0;
    }
    /**
     * Stored model inputs (agent_trace) contain the memory snapshot of each turn. Every value this memory ever had
     * under its key is replaced, by JSON structure rather than by text search, so ordinary words are untouched.
     */
    private static void redactTraces(Connection c, Scope scope, String user, String enterprise, String name) throws Exception {
        try (var st = c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='agent_trace'");
             var r = st.executeQuery()) { if (!r.next()) return; }
        var jsonKey = JSON.writeValueAsString(key(scope, name));
        var fragment = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(jsonKey)
                + ":\\{\"value\":\"(?:[^\"\\\\]|\\\\.)*\",\"userRequest\":\"(?:[^\"\\\\]|\\\\.)*\"\\}");
        var where = switch (scope) {
            case USER_ENTERPRISE -> "user_id=? AND enterprise_id=?";
            case PERSONAL -> "user_id=?";
            case ENTERPRISE -> "enterprise_id=?";
        };
        var updates = new LinkedHashMap<Long, String>();
        try (var st = c.prepareStatement("SELECT id,user_message FROM agent_trace WHERE " + where + " AND instr(user_message, ?) > 0")) {
            int i = 1;
            if (scope != Scope.ENTERPRISE) st.setString(i++, user);
            if (scope != Scope.PERSONAL) st.setString(i++, enterprise);
            st.setString(i, jsonKey + ":");
            try (var r = st.executeQuery()) {
                while (r.next()) {
                    var text = r.getString(2);
                    var redacted = fragment.matcher(text).replaceAll(java.util.regex.Matcher.quoteReplacement(jsonKey + ":" + JSON.writeValueAsString(DELETED_MARK)));
                    if (!redacted.equals(text)) updates.put(r.getLong(1), redacted);
                }
            }
        }
        if (updates.isEmpty()) return;
        // The append-only guard is lifted only inside this transaction, and only to erase a deleted memory.
        try (var st = c.createStatement()) { st.execute("DROP TRIGGER IF EXISTS agent_trace_no_update"); }
        try (var st = c.prepareStatement("UPDATE agent_trace SET user_message=? WHERE id=?")) {
            for (var e : updates.entrySet()) { st.setString(1, e.getValue()); st.setLong(2, e.getKey()); st.addBatch(); }
            st.executeBatch();
        }
        try (var st = c.createStatement()) { st.execute(TRACE_NO_UPDATE); }
    }
    /** Reloads authorized context on every turn; persists only deterministic status and tool metadata. */
    public Turn continueTask(Session session, String enterprise, String id, String message, AgentRuntime runtime) {
        return continueTask(session, enterprise, id, message, runtime, null);
    }
    public Turn continueTask(Session session, String enterprise, String id, String message, AgentRuntime runtime,
                             AgentRuntime.ApprovedWrite approval) {
        return continueTask(session, enterprise, id, message, runtime, approval, progress -> {});
    }
    public Turn continueTask(Session session, String enterprise, String id, String message, AgentRuntime runtime,
                             AgentRuntime.ApprovedWrite approval, Consumer<AgentRuntime.Progress> progressSink) {
        text(message);
        var context = load(session, enterprise, id); var old = context.task(); open(old, old.version());
        if (approval != null && !id.equals(approval.taskId()))
            throw new DomainException(ErrorCode.CONFIRMATION_REQUIRED, "Approval must belong to this task");
        var history = turns(session, enterprise, id);
        var projection = ContextProjector.project(context, message, history);
        var trace = new AtomicReference<AgentRuntime.Trace>();
        var recall = new ContextRecall(database, this, session, enterprise, id);
        var result = runtime.runScoped(session, enterprise, message, projection.memoryJson(), approval, trace::set, progressSink, recall);
        return access(session, enterprise, c -> {
            var current = get(c, session, enterprise, id); open(current, old.version());
            var summaries = result.history().stream().map(e -> {
                try {
                    var error = JSON.readTree(e.resultJson()).get("error");
                    return new Summary(e.call().name(), error == null || error.isNull() ? "SUCCESS" : error.path("code").asText("ERROR"));
                } catch (Exception failure) { throw storage(failure); }
            }).toList();
            // Only an actual, matching verified write from this exact approved turn can
            // satisfy pending fields. Model text, tool arguments, and prior-turn history cannot.
            var saved = verifiedApprovedFields(result, approval);
            var pending = old.pending().stream().filter(field -> !saved.containsKey(field)).toList();
            var awaiting = old.awaitingSave().stream().filter(field -> !saved.containsKey(field)).toList();
            // The approved, verified value is the user's latest explicit statement for that field; an approved clear
            // leaves no statement behind (Input cannot hold a blank value).
            var supplied = new HashMap<>(old.supplied());
            saved.forEach((field, value) -> {
                var stated = supplied.get(field);
                if (stated == null || stated.value().equals(value)) return;
                if (value.isBlank()) supplied.remove(field); else supplied.put(field, new Input(value, message));
            });
            var effective = result;
            String question = null;
            boolean unfinished = !pending.isEmpty() || !awaiting.isEmpty();
            if (unfinished && result.status() == AgentRuntime.Status.FINISHED) {
                // A model Finish is not task completion while explicit required inputs or unsaved statements remain.
                // T38 (user decision 2026-11-20): the answer stays visible; the fixed note after it says the task is
                // still open, and the stored question is that note alone.
                question = pendingQuestion(pending, awaiting, supplied);
                effective = new AgentRuntime.Result(AgentRuntime.Status.NEEDS_INPUT,
                        afterAnswer(result.text(), question), null, result.history());
            } else if (unfinished && result.status() == AgentRuntime.Status.NEEDS_INPUT) {
                effective = new AgentRuntime.Result(AgentRuntime.Status.NEEDS_INPUT,
                        withPendingQuestion(result.text(), pending, awaiting, supplied), result.errorCode(), result.history());
            }
            var status = switch (effective.status()) {
                case NEEDS_INPUT -> Status.WAITING_USER;
                case FINISHED -> Status.COMPLETED;
                case ERROR -> Status.ACTIVE;
            };
            var plan = TaskPlan.derive(status, pending, awaiting, summaries, result.history());
            var next = new Task(id, status, old.version() + 1, old.goal(), pending, supplied,
                    effective.status() == AgentRuntime.Status.NEEDS_INPUT ? (question != null ? question : effective.text()) : null,
                    summaries, plan, awaiting);
            save(c, session, enterprise, old, next);
            // T34: the original behind the compressed view; same transaction and version check as the task itself.
            int seq = history.size() + 1;
            try (var st = c.prepareStatement("INSERT INTO task_turns VALUES (?,?,?,?,?,?,?,?)")) {
                scope(st, session, enterprise); st.setString(3, id); st.setInt(4, seq); st.setString(5, message);
                st.setString(6, effective.text()); st.setString(7, effective.status().name());
                st.setString(8, java.time.Instant.now().toString()); st.executeUpdate();
            }
            try (var st = c.prepareStatement("INSERT OR REPLACE INTO task_tool_results VALUES (?,?,?,?,?,?,?,?)")) {
                for (var exchange : result.history()) {
                    scope(st, session, enterprise); st.setString(3, id); st.setInt(4, seq); st.setString(5, exchange.call().id());
                    st.setString(6, exchange.call().name()); st.setString(7, exchange.call().arguments());
                    st.setString(8, exchange.resultJson()); st.addBatch();
                }
                st.executeBatch();
            }
            return new Turn(next, effective, projection.truncated() ? "task-memory+projection" : "task-memory",
                    projection.memoryJson(), trace.get());
        });
    }
    /** Fields (F002.purpose → saved value) that this exact approved turn wrote and read back; empty otherwise. */
    private static Map<String, String> verifiedApprovedFields(AgentRuntime.Result result, AgentRuntime.ApprovedWrite approval) {
        if (approval == null) return Map.of();
        var resolved = new HashMap<String, String>();
        for (var exchange : result.history()) {
            if (!"update_invoice".equals(exchange.call().name())) continue;
            try {
                // The runtime executes only the approval payload, so the verified result is the evidence.
                var response = JSON.readTree(exchange.resultJson());
                var error = response.get("error");
                var data = response.get("data");
                if (error != null && !error.isNull() || data == null || !data.path("verified").asBoolean(false)) continue;
                var key = data.path("key");
                if (!approval.update().key().enterpriseId().equals(key.path("enterprise_id").asText())
                        || !approval.update().key().invoiceId().equals(key.path("invoice_id").asText())) continue;
                boolean valuesMatch = approval.update().changes().entrySet().stream()
                        .allMatch(change -> change.getValue().equals(data.path(change.getKey()).asText(null)));
                if (!valuesMatch) continue;
                approval.update().changes().forEach((field, value) ->
                        resolved.put(approval.update().key().invoiceId() + "." + field, value));
            } catch (Exception ignored) {
                // Malformed or unverifiable tool history never satisfies a required field.
            }
        }
        return resolved;
    }
    private static String pendingQuestion(List<String> pending, List<String> awaiting, Map<String, Input> supplied) {
        var parts = new ArrayList<String>();
        if (!pending.isEmpty())
            parts.add("还需要你确认这些信息：" + String.join("、", pending)
                    + "。可以在“补充 / 更正明确事实”里逐项填写字段、明确值和你的原话；"
                    + "我只记录你的声明，不会直接修改发票。补充完我再继续核对。");
        if (!awaiting.isEmpty())
            parts.add("这些声明已记录但尚未保存到发票：" + String.join("、", awaiting.stream().map(field ->
                    supplied.containsKey(field) ? field + "＝“" + supplied.get(field).value() + "”" : field).toList())
                    + "。如需写入，请在“审核与结果”里逐项确认保存；值有误可再次补充更正（只影响该字段）；不需要写入请取消任务。");
        return String.join("\n\n", parts);
    }
    private static String withPendingQuestion(String modelQuestion, List<String> pending, List<String> awaiting,
                                              Map<String, Input> supplied) {
        var required = pendingQuestion(pending, awaiting, supplied);
        return modelQuestion == null || modelQuestion.isBlank() ? required : modelQuestion + "\n\n" + required;
    }
    static final String STILL_OPEN = "\n\n——\n任务尚未完成。";
    private static String afterAnswer(String answer, String required) {
        return answer == null || answer.isBlank() ? required : answer + STILL_OPEN + required;
    }
    /** What the model receives: one value per name from its winning scope, with the scope visible in the key. */
    private Map<String, Input> preferences(Connection c, Session session, String enterprise) throws Exception {
        purgeExpired(c, session, enterprise);
        var rows = load(c, session, enterprise);
        var winners = winners(rows);
        var values = new LinkedHashMap<String, Input>();
        for (var row : rows)
            if (winners.get(row.name()) == row.scope()) values.put(key(row.scope(), row.name()), new Input(row.value(), row.userRequest()));
        return Map.copyOf(values);
    }
    private Task get(Connection c, Session session, String enterprise, String id) throws Exception {
        text(id);
        try (var s = c.prepareStatement("SELECT payload FROM agent_tasks WHERE user_id=? AND enterprise_id=? AND id=?")) {
            scope(s, session, enterprise); s.setString(3, id);
            try (var r = s.executeQuery()) {
                if (!r.next()) throw new DomainException(ErrorCode.NOT_FOUND, "Task not found");
                return JSON.readValue(r.getString(1), Task.class);
            }
        }
    }
    private void save(Connection c, Session session, String enterprise, Task old, Task next) throws Exception {
        try (var s = c.prepareStatement("UPDATE agent_tasks SET status=?,version=?,payload=? WHERE user_id=? AND enterprise_id=? AND id=? AND version=?")) {
            s.setString(1, next.status().name()); s.setLong(2, next.version()); s.setString(3, JSON.writeValueAsString(next));
            s.setString(4, session.userId()); s.setString(5, enterprise); s.setString(6, old.id()); s.setLong(7, old.version());
            if (s.executeUpdate() != 1) throw conflict();
        }
    }
    private Connection connect() throws SQLException {
        var c = com.taxagent.harness.tool.Sqlite.open(url);
        try (var s = c.createStatement()) { s.execute("PRAGMA foreign_keys=ON"); s.execute("PRAGMA busy_timeout=5000"); }
        return c;
    }
    private <T> T access(Session session, String enterprise, Operation<T> operation) {
        if (session == null) throw new DomainException(ErrorCode.AUTHENTICATION_REQUIRED, "Trusted session required");
        text(enterprise);
        try (var c = connect()) {
            c.setAutoCommit(false);
            try {
                guard.require(c, session, enterprise, PermissionGuard.Access.READ);
                var value = operation.execute(c); c.commit(); return value;
            } catch (Exception e) { c.rollback(); throw e; }
        } catch (DomainException e) { throw e;
        } catch (Exception e) { throw storage(e); }
    }
    private static void scope(PreparedStatement s, Session session, String enterprise) throws SQLException {
        s.setString(1, session.userId()); s.setString(2, enterprise);
    }
    private static void open(Task task, long version) {
        if (task.version() != version || task.status() == Status.CANCELLED || task.status() == Status.COMPLETED) throw conflict();
    }
    private static void text(String value) { if (value == null || value.isBlank()) throw invalid(); }
    private static DomainException invalid() { return new DomainException(ErrorCode.INVALID_ARGUMENT, "Explicit input required"); }
    private static DomainException conflict() { return new DomainException(ErrorCode.CONFLICT, "Task changed, closed or another task is unfinished; reload"); }
    private static DomainException storage(Exception e) { return new DomainException(ErrorCode.STORAGE_ERROR, "Task memory storage failed", e); }
    @FunctionalInterface private interface Operation<T> { T execute(Connection c) throws Exception; }
}
