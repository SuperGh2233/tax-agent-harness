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
                        String outcome, String errorCode, boolean verified, String time) {}
    public static final class Failure extends RuntimeException { public Failure() { super("AUDIT_STORAGE_ERROR"); } }
    public DemoAudit(Path db) { url = "jdbc:sqlite:" + db.toAbsolutePath().toUri().toASCIIString() + "?mode=rw"; }
    private Connection open() throws SQLException {
        var c = DriverManager.getConnection(url);
        try (var s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS demo_audit (id INTEGER PRIMARY KEY AUTOINCREMENT,user_id TEXT NOT NULL,enterprise_id TEXT NOT NULL,request_id TEXT NOT NULL,task_id TEXT,tool TEXT,call_id TEXT,month TEXT,invoice_id TEXT,fields TEXT,operation_id TEXT,outcome TEXT NOT NULL,error_code TEXT,verified INTEGER NOT NULL,time TEXT NOT NULL)");
            s.execute("CREATE TRIGGER IF NOT EXISTS demo_audit_no_update BEFORE UPDATE ON demo_audit BEGIN SELECT RAISE(ABORT,'append only'); END");
            s.execute("CREATE TRIGGER IF NOT EXISTS demo_audit_no_replace BEFORE INSERT ON demo_audit WHEN EXISTS (SELECT 1 FROM demo_audit WHERE id=NEW.id) BEGIN SELECT RAISE(ABORT,'append only'); END");
            s.execute("CREATE TRIGGER IF NOT EXISTS demo_audit_no_delete BEFORE DELETE ON demo_audit BEGIN SELECT RAISE(ABORT,'append only'); END");
            return c;
        } catch (SQLException e) { c.close(); throw e; }
    }
    public void append(PermissionGuard.Session user, String enterprise, Event event) {
        try (var c = open()) {
            c.setAutoCommit(false);
            new PermissionGuard().require(c, user, enterprise, PermissionGuard.Access.READ);
            try (var s = c.prepareStatement("INSERT INTO demo_audit(user_id,enterprise_id,request_id,task_id,tool,call_id,month,invoice_id,fields,operation_id,outcome,error_code,verified,time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                s.setString(1, user.userId()); s.setString(2, enterprise); s.setString(3, event.requestId());
                s.setString(4, event.taskId()); s.setString(5, event.tool()); s.setString(6, event.callId());
                s.setString(7, event.month()); s.setString(8, event.invoiceId()); s.setString(9, event.fields());
                s.setString(10, event.operationId()); s.setString(11, event.outcome()); s.setString(12, event.errorCode());
                s.setBoolean(13, event.verified()); s.setString(14, Instant.now().toString()); s.executeUpdate();
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
                        r.getString("error_code"), r.getBoolean("verified"), r.getString("time"))); }
            }
            c.commit(); return List.copyOf(events);
        } catch (DomainException e) { throw e; } catch (Exception e) { throw new Failure(); }
    }
}
