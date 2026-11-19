package com.taxagent.harness.runtime;

import com.fasterxml.jackson.databind.*;
import com.taxagent.harness.tool.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * T30 agent configuration (FINAL_ROUND_CONTRACT A5/B4). An agent is a role: instructions, a model profile and the
 * tools and enterprises it may use. It never widens a user's rights: a turn may only use tools in
 * (agent tools) and data the PermissionGuard already allows the user. Versions are immutable; the highest version
 * is in force, and a turn resolves it once at the start, so a change applies from the next turn on.
 */
public final class AgentDirectory {
    public static final String DEFAULT_ID = "finance-assistant";
    public static final String WRITE_TOOL = "update_invoice";
    public static final Set<String> TOOLS = Set.of("query_invoices", "query_bank_entries", "check_invoice_purpose",
            "query_rules", "search_knowledge", "recall_context", WRITE_TOOL);
    static final int MAX_INSTRUCTIONS = 4000;
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final String url;
    private final PermissionGuard guard = new PermissionGuard();

    /** The configurable part of an agent. Model keys never live here; only a profile name from the environment. */
    public record Spec(String name, String responsibility, String instructions, String modelProfile, String model,
                       List<String> tools, List<String> enterprises, List<String> skills) {
        public Spec(String name, String responsibility, String instructions, String modelProfile, String model,
                    List<String> tools, List<String> enterprises) {
            this(name, responsibility, instructions, modelProfile, model, tools, enterprises, List.of());
        }
        /** Definitions saved before T31 have no skills field. */
        public List<String> skillIds() { return skills == null ? List.of() : skills; }
    }
    public record Agent(String id, int version, Spec spec, boolean enabled, String changedBy, String changedAt) {
        public boolean allows(String tool) { return spec.tools().contains(tool); }
        /** Trace header: identifies exactly which agent version produced a turn. */
        public String header() { return "[Agent " + id + " v" + version + " · " + spec.name() + "]"; }
    }

    public AgentDirectory(Path database) {
        url = "jdbc:sqlite:" + database.toAbsolutePath().toUri().toASCIIString() + "?mode=rw";
        Migrations.once(url, "agent-config", c -> Migrations.script(c, AgentDirectory.class, "/agent-config.sql"));
        Migrations.once(url, "agent-seed", AgentDirectory::seedDefault);
    }

    /** The pre-T30 behaviour, as an ordinary editable agent. Seeded once; never overwritten. */
    private static void seedDefault(Connection c) throws Exception {
        try (var s = c.prepareStatement("SELECT 1 FROM agent_definitions WHERE id=?")) {
            s.setString(1, DEFAULT_ID);
            try (var r = s.executeQuery()) { if (r.next()) return; }
        }
        var enterprises = new ArrayList<String>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT id FROM enterprises ORDER BY id")) {
            while (r.next()) enterprises.add(r.getString(1));
        }
        var spec = new Spec("财税助手", "查询发票与银行收支、解释规则、检查用途缺口，并在用户逐项确认后保存用途或备注", "",
                "default", null, TOOLS.stream().sorted().toList(), enterprises);
        insert(c, DEFAULT_ID, 1, spec, true, "system");
    }

    public List<Agent> list(PermissionGuard.Session session, String enterprise) {
        return transaction(c -> {
            guard.require(c, session, enterprise, PermissionGuard.Access.READ);
            var out = new ArrayList<Agent>();
            try (var s = c.prepareStatement("""
                    SELECT a.* FROM agent_definitions a
                    WHERE a.version=(SELECT MAX(b.version) FROM agent_definitions b WHERE b.id=a.id) ORDER BY a.id
                    """); var r = s.executeQuery()) { while (r.next()) out.add(agent(r)); }
            return List.copyOf(out);
        });
    }

    public List<Agent> history(PermissionGuard.Session session, String enterprise, String id) {
        return transaction(c -> {
            guard.require(c, session, enterprise, PermissionGuard.Access.READ);
            var out = new ArrayList<Agent>();
            try (var s = c.prepareStatement("SELECT * FROM agent_definitions WHERE id=? ORDER BY version DESC")) {
                s.setString(1, id);
                try (var r = s.executeQuery()) { while (r.next()) out.add(agent(r)); }
            }
            if (out.isEmpty()) throw new DomainException(ErrorCode.NOT_FOUND, "Agent not found");
            return List.copyOf(out);
        });
    }

    /**
     * Creates an agent or saves a new version of it. The user must be able to modify every enterprise the agent
     * covers, so nobody can configure an agent for data they could not change themselves.
     */
    public Agent save(PermissionGuard.Session session, String enterprise, String id, Spec spec) {
        var clean = validate(id, spec);
        return transaction(c -> {
            guard.require(c, session, enterprise, PermissionGuard.Access.WRITE);
            for (var covered : clean.enterprises()) guard.require(c, session, covered, PermissionGuard.Access.WRITE);
            for (var skill : clean.skillIds())
                if (!CapabilityRegistry.skillExists(c, skill)) throw invalid("Unknown skill " + skill);
            var current = current(c, id);
            int version = current == null ? 1 : current.version() + 1;
            insert(c, id, version, clean, current == null || current.enabled(), session.userId());
            return current(c, id);
        });
    }

    /** Enabling or disabling is a new version too, so the history shows who changed what and when. */
    public Agent setEnabled(PermissionGuard.Session session, String enterprise, String id, boolean enabled) {
        return transaction(c -> {
            guard.require(c, session, enterprise, PermissionGuard.Access.WRITE);
            var current = current(c, id);
            if (current == null) throw new DomainException(ErrorCode.NOT_FOUND, "Agent not found");
            for (var covered : current.spec().enterprises()) guard.require(c, session, covered, PermissionGuard.Access.WRITE);
            if (current.enabled() == enabled) return current;
            insert(c, id, current.version() + 1, current.spec(), enabled, session.userId());
            return current(c, id);
        });
    }

    /** The version in force for a turn, or refusal when it is disabled, missing or does not cover the enterprise. */
    public Agent resolve(PermissionGuard.Session session, String enterprise, String id) {
        return transaction(c -> {
            guard.require(c, session, enterprise, PermissionGuard.Access.READ);
            var agent = current(c, id == null ? DEFAULT_ID : id);
            if (agent == null) throw new DomainException(ErrorCode.NOT_FOUND, "Agent not found");
            if (!agent.enabled() || !agent.spec().enterprises().contains(enterprise))
                throw new DomainException(ErrorCode.PERMISSION_DENIED, "Agent is disabled or does not cover this enterprise");
            return agent;
        });
    }

    public void bindTask(PermissionGuard.Session session, String enterprise, String taskId, String agentId) {
        transaction(c -> {
            try (var s = c.prepareStatement("INSERT INTO task_agents VALUES (?,?,?,?)")) {
                s.setString(1, session.userId()); s.setString(2, enterprise); s.setString(3, taskId); s.setString(4, agentId);
                s.executeUpdate();
            }
            return null;
        });
    }
    /** Tasks keep the agent they were started with; tasks from before T30 use the default agent. */
    public String agentOf(PermissionGuard.Session session, String enterprise, String taskId) {
        return transaction(c -> {
            try (var s = c.prepareStatement("SELECT agent_id FROM task_agents WHERE user_id=? AND enterprise_id=? AND task_id=?")) {
                s.setString(1, session.userId()); s.setString(2, enterprise); s.setString(3, taskId);
                try (var r = s.executeQuery()) { return r.next() ? r.getString(1) : DEFAULT_ID; }
            }
        });
    }

    static Spec validate(String id, Spec spec) {
        if (id == null || !id.matches("[a-z][a-z0-9-]{2,39}")) throw invalid("Agent id: 3-40 lowercase letters, digits or '-'");
        if (spec == null) throw invalid("Agent definition required");
        var name = text(spec.name(), 60, "name");
        var responsibility = text(spec.responsibility(), 300, "responsibility");
        var instructions = spec.instructions() == null ? "" : spec.instructions().strip();
        if (instructions.length() > MAX_INSTRUCTIONS) throw invalid("instructions too long");
        var profile = spec.modelProfile() == null || spec.modelProfile().isBlank() ? "default" : spec.modelProfile().strip();
        if (!profile.matches("default|[A-Za-z0-9_]{1,30}")) throw invalid("Invalid model profile name");
        var model = spec.model() == null || spec.model().isBlank() ? null : spec.model().strip();
        if (model != null && !model.matches("[A-Za-z0-9._:/-]{1,80}")) throw invalid("Invalid model name");
        var tools = new TreeSet<>(spec.tools() == null ? List.of() : spec.tools());
        var skills = new TreeSet<String>();
        for (var skill : spec.skillIds()) {
            if (skill == null || !skill.matches("[a-z][a-z0-9-]{2,39}")) throw invalid("Invalid skill id");
            skills.add(skill);
        }
        if (tools.isEmpty() && skills.isEmpty()) throw invalid("At least one tool or skill is required");
        if (!TOOLS.containsAll(tools)) throw invalid("Unknown tool");
        if (tools.equals(Set.of(WRITE_TOOL)) && skills.isEmpty()) throw invalid("A write-only agent cannot read back its own save");
        if (spec.enterprises() == null || spec.enterprises().isEmpty()) throw invalid("At least one enterprise is required");
        var enterprises = new TreeSet<String>();
        for (var e : spec.enterprises()) { if (e == null || e.isBlank()) throw invalid("Blank enterprise"); enterprises.add(e.strip()); }
        return new Spec(name, responsibility, instructions, profile, model, List.copyOf(tools), List.copyOf(enterprises), List.copyOf(skills));
    }
    private static String text(String value, int max, String field) {
        if (value == null || value.isBlank()) throw invalid(field + " required");
        if (value.strip().length() > max) throw invalid(field + " too long");
        return value.strip();
    }

    private static Agent current(Connection c, String id) throws SQLException {
        try (var s = c.prepareStatement("SELECT * FROM agent_definitions WHERE id=? ORDER BY version DESC LIMIT 1")) {
            s.setString(1, id);
            try (var r = s.executeQuery()) { return r.next() ? agent(r) : null; }
        }
    }
    private static void insert(Connection c, String id, int version, Spec spec, boolean enabled, String by) throws SQLException {
        try (var s = c.prepareStatement("INSERT INTO agent_definitions VALUES (?,?,?,?,?,?)")) {
            s.setString(1, id); s.setInt(2, version); s.setString(3, JSON.writeValueAsString(spec));
            s.setInt(4, enabled ? 1 : 0); s.setString(5, by); s.setString(6, Instant.now().toString()); s.executeUpdate();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    static Agent agent(ResultSet r) throws SQLException {
        try {
            return new Agent(r.getString("id"), r.getInt("version"), JSON.readValue(r.getString("payload"), Spec.class),
                    r.getInt("enabled") == 1, r.getString("changed_by"), r.getString("changed_at"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new SQLException("Unreadable agent definition", e); }
    }
    private <T> T transaction(Operation<T> operation) {
        try (var c = Sqlite.open(url)) {
            try (var s = c.createStatement()) { s.execute("PRAGMA foreign_keys=ON"); s.execute("PRAGMA busy_timeout=5000"); }
            c.setAutoCommit(false);
            try { T result = operation.run(c); c.commit(); return result; }
            catch (Exception e) { c.rollback(); throw e; }
        } catch (SQLException e) { throw storage(e); }
    }
    private static DomainException invalid(String message) { return new DomainException(ErrorCode.INVALID_ARGUMENT, message); }
    private static DomainException storage(Exception e) { return new DomainException(ErrorCode.STORAGE_ERROR, "Agent storage failed", e); }
    @FunctionalInterface private interface Operation<T> { T run(Connection c) throws SQLException; }
}
