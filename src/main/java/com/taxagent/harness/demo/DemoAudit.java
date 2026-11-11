package com.taxagent.harness.demo;

import com.taxagent.harness.tool.*;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Metadata only. No arbitrary request, argument, financial result or error message columns. */
public final class DemoAudit {
    private final String url;
    public record Event(long id, String requestId, String taskId, String tool, String callId,
                        String month, String invoiceId, String fields, String operationId,
                        String outcome, String errorCode, boolean verified, String time,
                        String ruleVersion, String memorySource, Long durationMs) {
        public Event(long id, String requestId, String taskId, String tool, String callId,
                     String month, String invoiceId, String fields, String operationId,
                     String outcome, String errorCode, boolean verified, String time) {
            this(id, requestId, taskId, tool, callId, month, invoiceId, fields, operationId,
                    outcome, errorCode, verified, time, null, null, null);
        }
    }
    public static final class Failure extends RuntimeException { public Failure() { super("AUDIT_STORAGE_ERROR"); } }
    /** Stored model input of one turn: prompt, trusted message, tool schema names, context size. */
    public record TraceRecord(long id, String requestId, String taskId, String systemPrompt, String userMessage,
                              List<String> toolNames, int historySize, String memorySource, String outcome, String time) {
        public TraceRecord { toolNames = List.copyOf(toolNames); }
    }
    public DemoAudit(Path db) { url = "jdbc:sqlite:" + db.toAbsolutePath().toUri().toASCIIString() + "?mode=rw"; }
    private Connection open() throws SQLException {
        var c = DriverManager.getConnection(url);
        try (var s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS demo_audit (id INTEGER PRIMARY KEY AUTOINCREMENT,user_id TEXT NOT NULL,enterprise_id TEXT NOT NULL,request_id TEXT NOT NULL,task_id TEXT,tool TEXT,call_id TEXT,month TEXT,invoice_id TEXT,fields TEXT,operation_id TEXT,outcome TEXT NOT NULL,error_code TEXT,verified INTEGER NOT NULL,time TEXT NOT NULL,rule_version TEXT,memory_source TEXT,duration_ms INTEGER)");
            var columns = new HashSet<String>();
            try (var present = s.executeQuery("PRAGMA table_info(demo_audit)")) {
                while (present.next()) columns.add(present.getString("name"));
            }
            for (var added : List.of("rule_version TEXT", "memory_source TEXT", "duration_ms INTEGER")) {
                var name = added.substring(0, added.indexOf(' '));
                if (!columns.contains(name)) s.execute("ALTER TABLE demo_audit ADD COLUMN " + added);
            }
            s.execute("CREATE TRIGGER IF NOT EXISTS demo_audit_no_update BEFORE UPDATE ON demo_audit BEGIN SELECT RAISE(ABORT,'append only'); END");
            s.execute("CREATE TRIGGER IF NOT EXISTS demo_audit_no_replace BEFORE INSERT ON demo_audit WHEN EXISTS (SELECT 1 FROM demo_audit WHERE id=NEW.id) BEGIN SELECT RAISE(ABORT,'append only'); END");
            s.execute("CREATE TRIGGER IF NOT EXISTS demo_audit_no_delete BEFORE DELETE ON demo_audit BEGIN SELECT RAISE(ABORT,'append only'); END");
            s.execute("CREATE TABLE IF NOT EXISTS agent_trace (id INTEGER PRIMARY KEY AUTOINCREMENT,user_id TEXT NOT NULL,enterprise_id TEXT NOT NULL,request_id TEXT NOT NULL,task_id TEXT NOT NULL,system_prompt TEXT NOT NULL,user_message TEXT NOT NULL,tool_names TEXT NOT NULL,history_size INTEGER NOT NULL,memory_source TEXT,outcome TEXT,time TEXT NOT NULL)");
            s.execute("CREATE TRIGGER IF NOT EXISTS agent_trace_no_update BEFORE UPDATE ON agent_trace BEGIN SELECT RAISE(ABORT,'append only'); END");
            s.execute("CREATE TRIGGER IF NOT EXISTS agent_trace_no_delete BEFORE DELETE ON agent_trace BEGIN SELECT RAISE(ABORT,'append only'); END");
            return c;
        } catch (SQLException e) { c.close(); throw e; }
    }
    /** Persists the model input of a turn; append-only like the audit trail. */
    public void appendTrace(PermissionGuard.Session user, String enterprise, String requestId, String taskId,
                            String systemPrompt, String userMessage, List<String> toolNames, int historySize,
                            String memorySource, String outcome) {
        try (var c = open()) {
            c.setAutoCommit(false);
            new PermissionGuard().require(c, user, enterprise, PermissionGuard.Access.READ);
            try (var s = c.prepareStatement("INSERT INTO agent_trace(user_id,enterprise_id,request_id,task_id,system_prompt,user_message,tool_names,history_size,memory_source,outcome,time) VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
                s.setString(1, user.userId()); s.setString(2, enterprise); s.setString(3, requestId); s.setString(4, taskId);
                s.setString(5, systemPrompt); s.setString(6, userMessage);
                s.setString(7, String.join(",", toolNames)); s.setInt(8, historySize);
                s.setString(9, memorySource); s.setString(10, outcome); s.setString(11, Instant.now().toString());
                s.executeUpdate();
            }
            c.commit();
        } catch (DomainException e) { throw e; } catch (Exception e) { throw new Failure(); }
    }
    /** Latest stored inputs for a task, newest first; survives restart and reload. */
    public List<TraceRecord> traces(PermissionGuard.Session user, String enterprise, String taskId) {
        try (var c = open()) {
            c.setAutoCommit(false);
            new PermissionGuard().require(c, user, enterprise, PermissionGuard.Access.READ);
            var rows = new ArrayList<TraceRecord>();
            try (var s = c.prepareStatement("SELECT * FROM agent_trace WHERE user_id=? AND enterprise_id=? AND task_id=? ORDER BY id DESC LIMIT 5")) {
                s.setString(1, user.userId()); s.setString(2, enterprise); s.setString(3, taskId);
                try (var r = s.executeQuery()) {
                    while (r.next()) rows.add(new TraceRecord(r.getLong("id"), r.getString("request_id"), r.getString("task_id"),
                            r.getString("system_prompt"), r.getString("user_message"),
                            Arrays.stream(r.getString("tool_names").split(",")).filter(v -> !v.isBlank()).toList(),
                            r.getInt("history_size"), r.getString("memory_source"), r.getString("outcome"), r.getString("time")));
                }
            }
            c.commit(); return List.copyOf(rows);
        } catch (DomainException e) { throw e; } catch (Exception e) { throw new Failure(); }
    }
    public void append(PermissionGuard.Session user, String enterprise, Event event) {
        try (var c = open()) {
            c.setAutoCommit(false);
            new PermissionGuard().require(c, user, enterprise, PermissionGuard.Access.READ);
            try (var s = c.prepareStatement("INSERT INTO demo_audit(user_id,enterprise_id,request_id,task_id,tool,call_id,month,invoice_id,fields,operation_id,outcome,error_code,verified,time,rule_version,memory_source,duration_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                s.setString(1, user.userId()); s.setString(2, enterprise); s.setString(3, event.requestId());
                s.setString(4, event.taskId()); s.setString(5, event.tool()); s.setString(6, event.callId());
                s.setString(7, event.month()); s.setString(8, event.invoiceId()); s.setString(9, event.fields());
                s.setString(10, event.operationId()); s.setString(11, event.outcome()); s.setString(12, event.errorCode());
                s.setBoolean(13, event.verified()); s.setString(14, Instant.now().toString());
                s.setString(15, event.ruleVersion()); s.setString(16, event.memorySource());
                if (event.durationMs() == null) s.setNull(17, Types.INTEGER); else s.setLong(17, event.durationMs());
                s.executeUpdate();
            }
            c.commit();
        } catch (DomainException e) { throw e; } catch (Exception e) { throw new Failure(); }
    }
    public List<Event> list(PermissionGuard.Session user, String enterprise) {
        try (var c = open()) {
            c.setAutoCommit(false);
            new PermissionGuard().require(c, user, enterprise, PermissionGuard.Access.READ);
            var events = new ArrayList<Event>();
            try (var s = c.prepareStatement("SELECT * FROM demo_audit WHERE user_id=? AND enterprise_id=? ORDER BY id DESC LIMIT 200")) {
                s.setString(1, user.userId()); s.setString(2, enterprise);
                try (var r = s.executeQuery()) { while (r.next()) events.add(new Event(r.getLong("id"), r.getString("request_id"),
                        r.getString("task_id"), r.getString("tool"), r.getString("call_id"), r.getString("month"),
                        r.getString("invoice_id"), r.getString("fields"), r.getString("operation_id"), r.getString("outcome"),
                        r.getString("error_code"), r.getBoolean("verified"), r.getString("time"),
                        r.getString("rule_version"), r.getString("memory_source"),
                        r.getObject("duration_ms") == null ? null : r.getLong("duration_ms"))); }
            }
            c.commit(); return List.copyOf(events);
        } catch (DomainException e) { throw e; } catch (Exception e) { throw new Failure(); }
    }
}
