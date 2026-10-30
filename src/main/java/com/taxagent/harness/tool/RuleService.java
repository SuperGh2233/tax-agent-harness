package com.taxagent.harness.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import static com.taxagent.harness.tool.ToolContract.*;

/** Local service only. Known official rule executor, not a general text/RAG interpreter. */
public final class RuleService {
    private final String url;
    private final PermissionGuard guard = new PermissionGuard();
    private static final Map<RuleKey, RuleDefinition> OFFICIAL = officialDefinitions();

    public RuleService() { this(Path.of("data/harness.db")); }
    public RuleService(Path database) {
        url = "jdbc:sqlite:" + database.toAbsolutePath().toUri().toASCIIString();
    }

    public record StoredRule(RuleKey key, RuleDefinition definition) {}

    /** Capability created only by trusted local bootstrap code, never from session/tool input.
     * No production entry point currently issues this capability. */
    public static final class ManagementContext {
        private ManagementContext() {}
    }
    public static ManagementContext trustedLocalManagement() { return new ManagementContext(); }

    public List<StoredRule> queryRules(PermissionGuard.Session session, String enterprise, LocalDate date) {
        required(enterprise);
        return transaction(false, c -> {
            guard.require(c, session, enterprise, PermissionGuard.Access.READ);
            return rules(c, enterprise, date);
        });
    }

    /** All ordinary session mutations are denied, including U001 and self-declared admins. */
    public void manageRules(PermissionGuard.Session session, RuleRequest request) {
        if (request == null || request.action() == RuleAction.GET) throw invalid("Mutation required");
        transaction(false, c -> {
            guard.require(c, session, request.enterpriseId(), PermissionGuard.Access.MANAGE_RULES);
            return null;
        });
    }

    /** New versions are immutable; duplicate keys conflict. Associations are imported atomically. */
    public StoredRule importVersion(ManagementContext context, RuleKey key, RuleDefinition definition,
                                    Set<String> enterprises) {
        requireManagement(context);
        if (key == null || definition == null || enterprises == null || enterprises.isEmpty())
            throw invalid("Rule, definition and enterprises required");
        var scope = Set.copyOf(enterprises);
        scope.forEach(ToolContract::required);
        return transaction(true, c -> {
            for (String enterprise : scope) {
                try (var s = c.prepareStatement("SELECT 1 FROM enterprises WHERE id=?")) {
                    s.setString(1, enterprise);
                    try (var r = s.executeQuery()) {
                        if (!r.next()) throw invalid("Unknown enterprise");
                    }
                }
            }
            try (var s = c.prepareStatement("SELECT 1 FROM rules WHERE id=? AND version=?")) {
                s.setString(1, key.ruleId()); s.setString(2, key.version());
                try (var r = s.executeQuery()) {
                    if (r.next()) throw new DomainException(ErrorCode.CONFLICT, "Rule version already exists");
                }
            }
            try (var s = c.prepareStatement("INSERT INTO rules VALUES (?,?,?,?,?,?)")) {
                s.setString(1, key.ruleId()); s.setString(2, key.version()); s.setString(3, definition.name());
                s.setString(4, definition.effectiveFrom().toString()); s.setString(5, definition.status().name());
                s.setString(6, definition.content()); s.executeUpdate();
            }
            try (var s = c.prepareStatement("INSERT INTO rule_enterprises VALUES (?,?,?)")) {
                for (String enterprise : scope) {
                    s.setString(1, key.ruleId()); s.setString(2, key.version()); s.setString(3, enterprise);
                    s.executeUpdate();
                }
            }
            return new StoredRule(key, definition);
        });
    }

    /** Status belongs to the version globally, so disabling affects every associated enterprise. */
    public void disableVersion(ManagementContext context, RuleKey key) {
        requireManagement(context);
        if (key == null) throw invalid("Rule key required");
        transaction(true, c -> {
            try (var s = c.prepareStatement("UPDATE rules SET status='已停用' WHERE id=? AND version=?")) {
                s.setString(1, key.ruleId()); s.setString(2, key.version());
                if (s.executeUpdate() == 0) throw new DomainException(ErrorCode.NOT_FOUND, "Rule version not found");
            }
            return null;
        });
    }

    public PurposeResult checkInvoicePurpose(PermissionGuard.Session session, PurposeCheck query) {
        if (query == null) throw invalid("Purpose query required");
        return transaction(false, c -> {
            guard.require(c, session, query.enterpriseId(), PermissionGuard.Access.READ);
            var date = LocalDate.parse(query.month() + "-01");
            String id = switch (query.enterpriseId()) {
                case "C001" -> "R002";
                case "C002" -> "R003";
                default -> throw unsupported();
            };
            var candidates = rules(c, query.enterpriseId(), date).stream()
                    .filter(r -> r.key().ruleId().equals(id)).toList();
            // No invented precedence for multiple enabled versions.
            if (candidates.size() > 1) throw new DomainException(ErrorCode.CONFLICT, "Ambiguous enabled rule versions");
            if (candidates.isEmpty()) throw unsupported();
            var rule = candidates.getFirst();
            var expectedKey = new RuleKey(id, id.equals("R002") ? "2.0" : "1.0");
            if (!rule.key().equals(expectedKey) || !rule.definition().equals(OFFICIAL.get(expectedKey)))
                throw unsupported();
            var missing = new ArrayList<InvoiceKey>();
            try (var s = c.prepareStatement("""
                    SELECT id,amount_cents,purpose FROM invoices
                    WHERE enterprise_id=? AND invoice_date>=? AND invoice_date<? ORDER BY invoice_date,id
                    """)) {
                s.setString(1, query.enterpriseId()); s.setString(2, date.toString());
                s.setString(3, date.plusMonths(1).toString());
                try (var r = s.executeQuery()) {
                    while (r.next()) {
                        String purpose = r.getString("purpose");
                        if ((id.equals("R002") || r.getLong("amount_cents") >= 100_000)
                                && (purpose == null || purpose.isBlank()))
                            missing.add(new InvoiceKey(query.enterpriseId(), r.getString("id")));
                    }
                }
            }
            return new PurposeResult(missing, List.of(new RuleReference(rule.key(), rule.definition().name())));
        });
    }

    static void requireSupportedWriteRules(Connection c, String enterprise, LocalDate date) throws SQLException {
        if (!date.toString().startsWith("2026-09")) throw unsupported();
        var active = rules(c, enterprise, date);
        String purposeId = enterprise.equals("C001") ? "R002" : enterprise.equals("C002") ? "R003" : "";
        var purposeRules = active.stream().filter(r -> r.key().ruleId().equals(purposeId)).toList();
        if (purposeRules.size() > 1) throw new DomainException(ErrorCode.CONFLICT, "Ambiguous enabled rules");
        if (purposeRules.isEmpty()) throw unsupported();
        for (var rule : active)
            if (!rule.definition().equals(OFFICIAL.get(rule.key()))) throw unsupported();
    }

    private static List<StoredRule> rules(Connection c, String enterprise, LocalDate date) throws SQLException {
        var rows = new ArrayList<StoredRule>();
        try (var s = c.prepareStatement("""
                SELECT r.* FROM rules r JOIN rule_enterprises e ON r.id=e.rule_id AND r.version=e.version
                WHERE e.enterprise_id=? ORDER BY r.id,r.effective_from,r.version
                """)) {
            s.setString(1, enterprise);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    var definition = new RuleDefinition(r.getString("name"), LocalDate.parse(r.getString("effective_from")),
                            RuleStatus.valueOf(r.getString("status")), r.getString("content"));
                    if (date == null || (definition.status() == RuleStatus.启用 && !definition.effectiveFrom().isAfter(date)))
                        rows.add(new StoredRule(new RuleKey(r.getString("id"), r.getString("version")), definition));
                }
            }
        }
        return List.copyOf(rows);
    }

    private <T> T transaction(boolean write, Operation<T> operation) {
        try (var c = DriverManager.getConnection(url + (write ? "?mode=rw" : "?mode=ro"))) {
            try (var s = c.createStatement()) {
                s.execute("PRAGMA foreign_keys=ON"); s.execute("PRAGMA busy_timeout=5000");
            }
            c.setAutoCommit(false);
            try {
                T result = operation.run(c); c.commit(); return result;
            } catch (Exception e) { c.rollback(); throw e; }
        } catch (SQLException e) {
            throw new DomainException(ErrorCode.STORAGE_ERROR, "Rule storage operation failed", e);
        }
    }
    private static void requireManagement(ManagementContext context) {
        if (context == null) throw new DomainException(ErrorCode.PERMISSION_DENIED, "Trusted local management required");
    }
    private static DomainException unsupported() {
        return new DomainException(ErrorCode.UNSUPPORTED_SCOPE, "No supported enabled official purpose rule; inspect stored rule content");
    }
    private static Map<RuleKey, RuleDefinition> officialDefinitions() {
        // Seed is used only as an allowlist of executable semantics, never as current DB state.
        try (var input = RuleService.class.getResourceAsStream("/seed/official-v1.1.0.json")) {
            var result = new HashMap<RuleKey, RuleDefinition>();
            for (var row : new ObjectMapper().readTree(input).required("rules"))
                result.put(new RuleKey(row.get(0).asText(), row.get(1).asText()),
                        new RuleDefinition(row.get(2).asText(), LocalDate.parse(row.get(3).asText()),
                                RuleStatus.valueOf(row.get(4).asText()), row.get(5).asText()));
            return Map.copyOf(result);
        } catch (java.io.IOException e) { throw new ExceptionInInitializerError(e); }
    }
    @FunctionalInterface private interface Operation<T> { T run(Connection c) throws SQLException; }
}
