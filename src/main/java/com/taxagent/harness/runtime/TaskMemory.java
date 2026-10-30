package com.taxagent.harness.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taxagent.harness.tool.*;
import com.taxagent.harness.tool.PermissionGuard.Session;
import java.nio.file.Path;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Trusted backend service only. Never expose Session or explicit-input methods as model tools. */
public final class TaskMemory {
    public enum Status { ACTIVE, WAITING_USER, COMPLETED, CANCELLED }
    public record Input(String value, String userRequest) {
        public Input { text(value); text(userRequest); }
    }
    public record Summary(String tool, String outcome) {}
    public record Task(String id, Status status, long version, String goal, List<String> pending,
                       Map<String, Input> supplied, String question, List<Summary> recentTools) {
        public Task { pending = List.copyOf(pending); supplied = Map.copyOf(supplied); recentTools = List.copyOf(recentTools); }
    }
    public record Context(Task task, Map<String, Input> preferences) {
        public Context { preferences = Map.copyOf(preferences); }
    }
    public record Turn(Task task, AgentRuntime.Result result) {}
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String url;
    private final PermissionGuard guard = new PermissionGuard();

    /** Existing initialized DB only; additive migration never imports or updates business records. */
    public TaskMemory(Path database) {
        url = "jdbc:sqlite:" + database.toAbsolutePath().toUri().toASCIIString() + "?mode=rw";
        try (var c = connect(); var in = TaskMemory.class.getResourceAsStream("/task-memory.sql")) {
            c.setAutoCommit(false);
            try (var s = c.createStatement()) {
                for (var sql : new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8).split(";"))
                    if (!sql.isBlank()) s.execute(sql);
            }
            c.commit();
        } catch (Exception e) { throw storage(e); }
    }
    public Task start(Session session, String enterprise, String explicitGoal, List<String> pending) {
        text(explicitGoal); if (pending == null) throw invalid(); pending.forEach(TaskMemory::text);
        var task = new Task(UUID.randomUUID().toString(), Status.ACTIVE, 0, explicitGoal, pending, Map.of(), null, List.of());
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
            var next = new Task(id, Status.ACTIVE, version + 1, old.goal(), pending, supplied, null, old.recentTools());
            save(c, session, enterprise, old, next); return next;
        });
    }
    public Task cancel(Session session, String enterprise, String id, long version) {
        return access(session, enterprise, c -> {
            var old = get(c, session, enterprise, id); open(old, version);
            var next = new Task(id, Status.CANCELLED, version + 1, old.goal(), old.pending(), old.supplied(), null, old.recentTools());
            save(c, session, enterprise, old, next); return next;
        });
    }
    public Map<String, Input> preferences(Session session, String enterprise) {
        return access(session, enterprise, c -> preferences(c, session, enterprise));
    }
    /** Explicit long-term preference request must be confirmed by trusted caller, no automatic extraction. */
    public void putPreference(Session session, String enterprise, String name, Input explicitRequest) {
        text(name); if (explicitRequest == null) throw invalid();
        access(session, enterprise, c -> {
            try (var s = c.prepareStatement("INSERT INTO agent_preferences VALUES (?,?,?,?,?) ON CONFLICT(user_id,enterprise_id,name) DO UPDATE SET value=excluded.value,user_request=excluded.user_request")) {
                scope(s, session, enterprise); s.setString(3, name); s.setString(4, explicitRequest.value());
                s.setString(5, explicitRequest.userRequest()); s.executeUpdate();
            } return null;
        });
    }
    public void deletePreference(Session session, String enterprise, String name) {
        text(name);
        access(session, enterprise, c -> {
            try (var s = c.prepareStatement("DELETE FROM agent_preferences WHERE user_id=? AND enterprise_id=? AND name=?")) {
                scope(s, session, enterprise); s.setString(3, name); s.executeUpdate();
            } return null;
        });
    }
    /** Reloads authorized context on every turn; persists only deterministic status and tool metadata. */
    public Turn continueTask(Session session, String enterprise, String id, String message, AgentRuntime runtime) {
        return continueTask(session, enterprise, id, message, runtime, null);
    }
    public Turn continueTask(Session session, String enterprise, String id, String message, AgentRuntime runtime,
                             AgentRuntime.ApprovedWrite approval) {
        text(message);
        var context = load(session, enterprise, id); var old = context.task(); open(old, old.version());
        String memory;
        try { memory = JSON.writeValueAsString(context); } catch (Exception e) { throw storage(e); }
        var result = runtime.runScoped(session, enterprise, message, memory, approval);
        return access(session, enterprise, c -> {
            var current = get(c, session, enterprise, id); open(current, old.version());
            var summaries = result.history().stream().map(e -> {
                try {
                    var error = JSON.readTree(e.resultJson()).get("error");
                    return new Summary(e.call().name(), error == null || error.isNull() ? "SUCCESS" : error.path("code").asText("ERROR"));
                } catch (Exception failure) { throw storage(failure); }
            }).toList();
            var status = switch (result.status()) {
                case NEEDS_INPUT -> Status.WAITING_USER;
                case FINISHED -> Status.COMPLETED;
                case ERROR -> Status.ACTIVE;
            };
            var next = new Task(id, status, old.version() + 1, old.goal(), old.pending(), old.supplied(),
                    result.status() == AgentRuntime.Status.NEEDS_INPUT ? result.text() : null, summaries);
            save(c, session, enterprise, old, next); return new Turn(next, result);
        });
    }
    private Map<String, Input> preferences(Connection c, Session session, String enterprise) throws SQLException {
        var values = new LinkedHashMap<String, Input>();
        try (var s = c.prepareStatement("SELECT name,value,user_request FROM agent_preferences WHERE user_id=? AND enterprise_id=? ORDER BY name")) {
            scope(s, session, enterprise);
            try (var r = s.executeQuery()) { while (r.next()) values.put(r.getString(1), new Input(r.getString(2), r.getString(3))); }
        } return Map.copyOf(values);
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
        var c = DriverManager.getConnection(url);
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
