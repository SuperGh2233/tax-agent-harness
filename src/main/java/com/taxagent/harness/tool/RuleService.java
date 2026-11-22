package com.taxagent.harness.tool;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import static com.taxagent.harness.tool.ToolContract.*;

/** Local service only. Known official rule executor, not a general text/RAG interpreter. */
public final class RuleService {
    private final String url;
    private final PermissionGuard guard = new PermissionGuard();

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

    /** Model-facing read-only rule lookup: active versions effective on the explicit business date. */
    public RuleQueryResult queryRules(PermissionGuard.Session session, RuleQuery query) {
        if (query == null) throw invalid("Rule query required");
        return transaction(false, c -> {
            guard.require(c, session, query.enterpriseId(), PermissionGuard.Access.READ);
            return new RuleQueryResult(rules(c, query.enterpriseId(), query.date()).stream()
                    .map(rule -> new RuleSummary(rule.key(), rule.definition().name(),
                            rule.definition().effectiveFrom(), rule.definition().status(),
                            rule.definition().content()))
                    .toList());
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

    /**
     * T51: checked by the rule CLI before an import, so the operator learns about unusable wording before an
     * immutable version exists. Returns why this text would be a purpose rule the checker cannot execute, or null
     * when it is executable or not a purpose rule. Storing such a version stays possible (T28: a reworded version is
     * kept and refused at check time); this method only reads.
     */
    public String purposeWordingProblem(ManagementContext context, String ruleId, String content, Set<String> enterprises) {
        requireManagement(context);
        if (ruleId == null || content == null || enterprises == null) throw invalid("Rule id, content and enterprises required");
        boolean states = MUST_FILL.matcher(content.replaceAll("\\s+", "")).find();
        // Same test purposeRule uses: an id is a purpose rule once any stored version for the enterprise states one.
        boolean purposeId = !states && this.<Boolean>transaction(false, c -> {
            for (String enterprise : enterprises)
                for (var rule : rules(c, enterprise, null)) {
                    var text = rule.definition().content();
                    if (rule.key().ruleId().equals(ruleId) && text != null && MUST_FILL.matcher(text.replaceAll("\\s+", "")).find()) return true;
                }
            return false;
        });
        if (!states && !purposeId) return null;
        try { requirement(content); return null; }
        catch (DomainException unsupported) {
            return (states ? "正文写了「必须填写用途」" : "规则 " + ruleId + " 已有版本是用途规则")
                    + "，但这段正文不是可执行的用途要求。目前只支持两种写法：「每张发票都必须填写用途」，"
                    + "或「……大于或等于 X 元……必须填写用途」（X 最多两位小数），两种不能写在同一版本里。"
                    + "这个版本启用后，用途检查和保存都会返回 UNSUPPORTED_SCOPE。";
        }
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

    /**
     * Executable semantics parsed from the stored rule text. Only the two documented requirement
     * forms are interpreted; any other wording is refused instead of guessed.
     */
    record PurposeRequirement(boolean everyInvoice, long thresholdCents) {
        boolean requires(long amountCents) { return everyInvoice || amountCents >= thresholdCents; }
        String describe() {
            return everyInvoice ? "每张发票都必须填写用途"
                    : "价税合计大于或等于 " + BigDecimal.valueOf(thresholdCents, 2).toPlainString() + " 元时必须填写用途";
        }
    }
    private static final Pattern THRESHOLD = Pattern.compile("大于或等于([0-9]+(?:\\.[0-9]{1,2})?)元");
    private static final Pattern MUST_FILL = Pattern.compile("必须填写用途");

    static PurposeRequirement requirement(String content) {
        if (content == null) throw unsupported();
        var text = content.replaceAll("\\s+", "");
        if (!MUST_FILL.matcher(text).find()) throw unsupported();
        var threshold = THRESHOLD.matcher(text);
        boolean every = text.contains("每张发票都必须填写用途");
        if (every && threshold.find()) throw unsupported(); // contradictory wording: refuse, never guess
        if (every) return new PurposeRequirement(true, 0);
        if (threshold.find()) {
            var cents = new BigDecimal(threshold.group(1)).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY);
            return new PurposeRequirement(false, cents.longValueExact());
        }
        throw unsupported();
    }

    public PurposeResult checkInvoicePurpose(PermissionGuard.Session session, PurposeCheck query) {
        if (query == null) throw invalid("Purpose query required");
        return transaction(false, c -> {
            guard.require(c, session, query.enterpriseId(), PermissionGuard.Access.READ);
            var month = LocalDate.parse(query.month() + "-01");
            // Each invoice is judged by the rule in force on its own date, as writes are; a version
            // that takes effect mid-month therefore applies from that day, never to the whole month.
            var byDate = new HashMap<LocalDate, StoredRule>();
            var used = new LinkedHashMap<RuleKey, RuleReference>();
            var missing = new ArrayList<InvoiceKey>();
            try (var s = c.prepareStatement("""
                    SELECT id,invoice_date,amount_cents,purpose FROM invoices
                    WHERE enterprise_id=? AND invoice_date>=? AND invoice_date<? ORDER BY invoice_date,id
                    """)) {
                s.setString(1, query.enterpriseId()); s.setString(2, month.toString());
                s.setString(3, month.plusMonths(1).toString());
                try (var r = s.executeQuery()) {
                    while (r.next()) {
                        var date = LocalDate.parse(r.getString("invoice_date"));
                        var rule = byDate.get(date);
                        if (rule == null) { rule = purposeRule(c, query.enterpriseId(), date); byDate.put(date, rule); }
                        used.putIfAbsent(rule.key(), new RuleReference(rule.key(), rule.definition().name()));
                        String purpose = r.getString("purpose");
                        if (requirement(rule.definition().content()).requires(r.getLong("amount_cents"))
                                && (purpose == null || purpose.isBlank()))
                            missing.add(new InvoiceKey(query.enterpriseId(), r.getString("id")));
                    }
                }
            }
            if (used.isEmpty()) { // No invoices: still cite the rule in force, or refuse when none applies.
                var rule = purposeRule(c, query.enterpriseId(), month);
                requirement(rule.definition().content());
                used.put(rule.key(), new RuleReference(rule.key(), rule.definition().name()));
            }
            return new PurposeResult(missing, List.copyOf(used.values()));
        });
    }

    /**
     * The single enabled purpose rule of the enterprise, found from stored rules rather than a fixed id map.
     * A rule id is a purpose rule when any stored version for the enterprise states a purpose requirement,
     * so a reworded later version stays in scope and is refused instead of silently ignored.
     */
    private static StoredRule purposeRule(Connection c, String enterprise, LocalDate date) throws SQLException {
        var ids = new HashSet<String>();
        for (var rule : rules(c, enterprise, null)) {
            var content = rule.definition().content();
            if (content != null && MUST_FILL.matcher(content.replaceAll("\\s+", "")).find()) ids.add(rule.key().ruleId());
        }
        var candidates = rules(c, enterprise, date).stream().filter(r -> ids.contains(r.key().ruleId())).toList();
        // No invented precedence for multiple enabled versions or multiple purpose rule ids.
        if (candidates.size() > 1) throw new DomainException(ErrorCode.CONFLICT, "Ambiguous enabled rule versions");
        if (candidates.isEmpty()) throw unsupported();
        return candidates.getFirst();
    }

    /** T47: whether the purpose rule in force on this date requires a purpose for an invoice of this amount. */
    static boolean purposeRequired(Connection c, String enterprise, LocalDate date, long amountCents) throws SQLException {
        return requirement(purposeRule(c, enterprise, date).definition().content()).requires(amountCents);
    }

    /** Writes need an executable purpose rule in force on the invoice date; no month is special-cased. */
    static void requireSupportedWriteRules(Connection c, String enterprise, LocalDate date) throws SQLException {
        requirement(purposeRule(c, enterprise, date).definition().content());
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
        try (var c = Sqlite.open(url + (write ? "?mode=rw" : "?mode=ro"))) {
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
        return new DomainException(ErrorCode.UNSUPPORTED_SCOPE,
                "No executable purpose rule: check the enabled version's wording, or disable conflicting versions");
    }
    @FunctionalInterface private interface Operation<T> { T run(Connection c) throws SQLException; }
}
