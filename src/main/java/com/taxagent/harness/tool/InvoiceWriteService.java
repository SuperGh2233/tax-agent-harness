package com.taxagent.harness.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taxagent.harness.tool.PermissionGuard.Session;
import com.taxagent.harness.tool.ToolContract.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local trusted backend capability only; no HTTP endpoint or model tool registration. */
public final class InvoiceWriteService {
    public enum Intent { EXPLANATION, SAVE_NOW, CLEAR_NOW }
    public static final class Authorization {
        private final Session session;
        private final String operationId;
        private final InvoiceUpdate update;
        private final AtomicBoolean consumed = new AtomicBoolean();
        private Authorization(Session session, String operationId, InvoiceUpdate update) {
            this.session = session; this.operationId = operationId; this.update = update;
        }
    }
    private final String url;
    private final PermissionGuard guard = new PermissionGuard();

    /** Explicit additive migration on the supplied existing database, never imports business rows. */
    public InvoiceWriteService(Path database) {
        url = "jdbc:sqlite:" + database.toAbsolutePath().toUri().toASCIIString() + "?mode=rw";
        try (var c = connect(); var in = getClass().getResourceAsStream("/invoice-operations.sql");
             var s = c.createStatement()) {
            s.execute(new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) { throw storage(e); }
    }

    /** Trusted UI/controller must establish intent from actual user input, never model inference.
     * This method and Authorization are deliberately absent from ToolContract.decode/schema.
     * CLEAR_NOW means the user explicitly requested clearing every supplied blank field.
     */
    public static Authorization authorizeExplicitRequest(Session session, String operationId,
                                                         InvoiceUpdate update, Intent intent) {
        validate(session, operationId, update);
        if (intent == null || intent == Intent.EXPLANATION)
            throw new DomainException(ErrorCode.CONFIRMATION_REQUIRED, "Explicit immediate modification required");
        if (intent != Intent.CLEAR_NOW && update.changes().values().stream().anyMatch(String::isBlank))
            throw new DomainException(ErrorCode.CONFIRMATION_REQUIRED, "Explicit clearing request required");
        return new Authorization(session, operationId, update);
    }

    public UpdateResult execute(Session session, String operationId, InvoiceUpdate update, Authorization authorization) {
        validate(session, operationId, update);
        if (authorization == null || !authorization.session.equals(session)
                || !authorization.operationId.equals(operationId) || !authorization.update.equals(update))
            throw new DomainException(ErrorCode.CONFIRMATION_REQUIRED, "Exact trusted authorization required");
        if (!authorization.consumed.compareAndSet(false, true))
            throw new DomainException(ErrorCode.CONFLICT, "Authorization consumed; query operation status");
        return transaction(session, update, c -> {
            var prior = recorded(c, session, operationId, update);
            if (prior != null) return prior; // Never execute UPDATE again, even after later external changes.
            var old = read(c, update.key());
            RuleService.requireSupportedWriteRules(c, update.key().enterpriseId(), old.invoiceDate());
            String purpose = update.changes().getOrDefault("purpose", old.purpose());
            String remarks = update.changes().getOrDefault("remarks", old.remarks());
            if (!purpose.equals(old.purpose()) || !remarks.equals(old.remarks())) {
                try (var s = c.prepareStatement("UPDATE invoices SET purpose=?,remarks=? WHERE enterprise_id=? AND id=?")) {
                    s.setString(1, purpose); s.setString(2, remarks); key(s, 3, update.key());
                    if (s.executeUpdate() != 1) throw conflict();
                }
            }
            var actual = read(c, update.key());
            if (!actual.equals(new Invoice(old.key(), old.invoiceDate(), old.seller(), old.item(),
                    old.amountCents(), purpose, remarks))) throw conflict();
            try (var s = c.prepareStatement("INSERT INTO invoice_operations VALUES (?,?,?,?,?,?,?)")) {
                s.setString(1, operationId); s.setString(2, session.userId()); key(s, 3, update.key());
                s.setString(5, payload(update)); s.setString(6, actual.purpose()); s.setString(7, actual.remarks());
                s.executeUpdate();
            }
            // Also catch triggers on the ledger insert changing business data before commit.
            var saved = read(c, update.key());
            if (!saved.equals(actual)) throw conflict();
            return result(saved);
        });
    }

    /** Required after timeout/unknown outcome. Empty means no committed operation observed, NOT permission to retry.
     * Never writes business data. Missing record requires investigation/new explicit authorization.
     */
    public Optional<UpdateResult> recover(Session session, String operationId, InvoiceUpdate update) {
        validate(session, operationId, update);
        return Optional.ofNullable(transaction(session, update, c -> {
            read(c, update.key()); // Query actual state even when the operation is absent.
            return recorded(c, session, operationId, update);
        }));
    }
    private UpdateResult recorded(Connection c, Session session, String id, InvoiceUpdate update) throws Exception {
        try (var s = c.prepareStatement("SELECT * FROM invoice_operations WHERE operation_id=?")) {
            s.setString(1, id);
            try (var r = s.executeQuery()) {
                if (!r.next()) return null;
                if (!session.userId().equals(r.getString("user_id"))
                        || !update.key().enterpriseId().equals(r.getString("enterprise_id"))
                        || !update.key().invoiceId().equals(r.getString("invoice_id"))
                        || !payload(update).equals(r.getString("payload"))) throw conflict();
                var actual = read(c, update.key());
                if (!actual.purpose().equals(r.getString("purpose")) || !actual.remarks().equals(r.getString("remarks")))
                    throw conflict();
                return result(actual);
            }
        }
    }
    private Invoice read(Connection c, InvoiceKey key) throws SQLException {
        try (var s = c.prepareStatement("SELECT * FROM invoices WHERE enterprise_id=? AND id=?")) {
            key(s, 1, key);
            try (var r = s.executeQuery()) {
                if (!r.next()) throw new DomainException(ErrorCode.NOT_FOUND, "Invoice not found");
                return new Invoice(key, LocalDate.parse(r.getString("invoice_date")), r.getString("seller"),
                        r.getString("item"), r.getLong("amount_cents"), r.getString("purpose"), r.getString("remarks"));
            }
        }
    }
    private Connection connect() throws SQLException {
        var c = DriverManager.getConnection(url);
        try (var s = c.createStatement()) { s.execute("PRAGMA foreign_keys=ON"); s.execute("PRAGMA busy_timeout=5000"); }
        return c;
    }
    private <T> T transaction(Session session, InvoiceUpdate update, Operation<T> operation) {
        try (var c = connect()) {
            c.setAutoCommit(false);
            try {
                guard.require(c, session, update.key().enterpriseId(), PermissionGuard.Access.WRITE);
                T result = operation.run(c); c.commit(); return result;
            } catch (Exception e) { c.rollback(); throw e; }
        } catch (DomainException e) { throw e;
        } catch (Exception e) { throw storage(e); }
    }
    private static void validate(Session session, String id, InvoiceUpdate update) {
        if (session == null) throw new DomainException(ErrorCode.AUTHENTICATION_REQUIRED, "Trusted session required");
        text(id); if (update == null) throw ToolContract.invalid("Update required");
        text(update.key().enterpriseId()); text(update.key().invoiceId());
        update.changes().values().forEach(v -> {
            if (v.indexOf('\0') >= 0 || v.chars().anyMatch(ch -> Character.isISOControl(ch) && ch != '\n' && ch != '\r' && ch != '\t'))
                throw ToolContract.invalid("Invalid control character");
        });
    }
    private static void text(String value) {
        ToolContract.required(value);
        if (!value.equals(value.strip()) || value.chars().anyMatch(Character::isISOControl))
            throw ToolContract.invalid("Invalid identifier");
    }
    private static String payload(InvoiceUpdate update) throws Exception {
        return new ObjectMapper().writeValueAsString(new TreeMap<>(update.changes()));
    }
    private static void key(PreparedStatement s, int offset, InvoiceKey key) throws SQLException {
        s.setString(offset, key.enterpriseId()); s.setString(offset + 1, key.invoiceId());
    }
    private static UpdateResult result(Invoice actual) { return new UpdateResult(actual.key(), actual.purpose(), actual.remarks(), true); }
    private static DomainException conflict() { return new DomainException(ErrorCode.CONFLICT, "Operation payload or saved record differs; inspect current state"); }
    private static DomainException storage(Exception e) {
        return new DomainException(ErrorCode.STORAGE_ERROR, "Write outcome may be unknown; query operation and actual record before any further action", e);
    }
    @FunctionalInterface private interface Operation<T> { T run(Connection c) throws Exception; }
}
