package com.taxagent.harness.runtime;

import com.fasterxml.jackson.databind.*;
import com.taxagent.harness.tool.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * T31 capability registry (FINAL_ROUND_CONTRACT B5). Tools are code with a built-in contract version and a global
 * on/off switch; skills are reusable instructions plus the read tools they need, shared by any number of agents.
 * An agent's usable tools for a turn are (agent tools ∪ its enabled skills' tools) ∩ switched-on tools. A skill
 * can never grant update_invoice: saving must be granted to the agent itself. Global changes need modify rights
 * on every enterprise, because they affect every enterprise.
 */
public final class CapabilityRegistry {
    /** Contract version of each tool, bumped when its arguments or semantics change. */
    public static final Map<String, String> TOOL_VERSIONS = Map.of(
            "query_invoices", "1.0", "query_bank_entries", "1.0", "query_rules", "1.0",
            "check_invoice_purpose", "2.0",   // T28: any month, rule in force per invoice date.
            "search_knowledge", "1.0",        // T32.
            "recall_context", "1.0",          // T34.
            AgentDirectory.WRITE_TOOL, "2.0"); // T26: no arguments, the approval is the payload.
    public static final Map<String, String> TOOL_DESCRIPTIONS = Map.of(
            "query_invoices", "按企业与月份查询发票明细与价税合计", "query_bank_entries", "按企业与月份查询银行流水，收入与支出分开统计",
            "query_rules", "按企业与日期查询启用规则的正文与版本", "check_invoice_purpose", "按生效规则列出缺用途的发票",
            "search_knowledge", "检索启用规则与上传资料，返回来源与位置", "recall_context", "取回被压缩的对话原文、大结果的更多行或资料原文段落",
            AgentDirectory.WRITE_TOOL, "保存经用户逐项确认的用途或备注，并回读核验");
    static final int MAX_SKILL_INSTRUCTIONS = 2000;
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final String url;
    private final PermissionGuard guard = new PermissionGuard();

    public record SkillSpec(String name, String description, String instructions, List<String> tools) {}
    public record Skill(String id, int version, SkillSpec spec, boolean enabled, String changedBy, String changedAt) {
        String block() { return "\n[Skill " + id + " v" + version + " · " + spec.name() + "]\n" + spec.instructions(); }
    }
    public record ToolInfo(String name, String version, String description, boolean enabled, String changedBy,
                           String changedAt, List<String> usedBy) {}
    public record SkillInfo(Skill skill, List<String> usedBy) {}
    /** What one agent version may use right now, and what was dropped because it is switched off. */
    public record Effective(Set<String> tools, Set<String> disabledTools, List<Skill> skills, List<String> droppedSkills) {
        public Effective {
            tools = Set.copyOf(tools); disabledTools = Set.copyOf(disabledTools);
            skills = List.copyOf(skills); droppedSkills = List.copyOf(droppedSkills);
        }
        /** Appended after the agent role in the system prompt; the trace shows exactly which skill versions ran. */
        public String prompt() {
            var out = new StringBuilder();
            for (var skill : skills) out.append(skill.block());
            return out.toString();
        }
    }

    public CapabilityRegistry(Path database) {
        url = "jdbc:sqlite:" + database.toAbsolutePath().toUri().toASCIIString() + "?mode=rw";
        Migrations.once(url, "agent-config", c -> Migrations.script(c, CapabilityRegistry.class, "/agent-config.sql"));
        Migrations.once(url, "skill-seed", CapabilityRegistry::seedSkills);
    }

    /** Two example skills, inert until an agent includes them. Seeded once; never overwritten. */
    private static void seedSkills(Connection c) throws Exception {
        seed(c, "purpose-gap-check", new SkillSpec("用途缺口检查",
                "按当前生效规则检查发票用途缺口并引用规则版本",
                "检查用途缺口时：先用 query_rules 确认该企业该日期生效的用途规则及版本，再调用 check_invoice_purpose；"
                        + "逐条列出缺用途的发票编号，注明依据的规则名称与版本；用户未明确要求保存时只说明，不保存。",
                List.of("query_rules", "check_invoice_purpose", "query_invoices")));
        seed(c, "bank-evidence", new SkillSpec("银行资料解释",
                "解释银行流水能说明什么、不能说明什么",
                "涉及银行流水时：收入与支出分别统计，不相互抵减；银行记录与发票没有逐条对应关系，不能据对方名称、金额或备注推断发票已付款；"
                        + "需要依据时用 search_knowledge 或 query_rules 引用《银行资料说明》并注明版本。",
                List.of("query_bank_entries", "query_rules", "search_knowledge")));
    }
    private static void seed(Connection c, String id, SkillSpec spec) throws Exception {
        try (var s = c.prepareStatement("SELECT 1 FROM skill_definitions WHERE id=?")) {
            s.setString(1, id);
            try (var r = s.executeQuery()) { if (r.next()) return; }
        }
        insertSkill(c, id, 1, spec, true, "system");
    }

    // ---- tools ----

    public List<ToolInfo> tools(PermissionGuard.Session session, String enterprise) {
        return transaction(c -> {
            guard.require(c, session, enterprise, PermissionGuard.Access.READ);
            var agents = currentAgents(c);
            var out = new ArrayList<ToolInfo>();
            for (var name : new TreeSet<>(TOOL_VERSIONS.keySet())) {
                var status = toolStatus(c, name);
                var usedBy = agents.stream().filter(a -> grants(c, a, name)).map(AgentDirectory.Agent::id).toList();
                out.add(new ToolInfo(name, TOOL_VERSIONS.get(name), TOOL_DESCRIPTIONS.get(name), status == null || status.enabled,
                        status == null ? null : status.by, status == null ? null : status.at, usedBy));
            }
            return List.copyOf(out);
        });
    }

    public ToolInfo setToolEnabled(PermissionGuard.Session session, String enterprise, String tool, boolean enabled) {
        if (!TOOL_VERSIONS.containsKey(tool)) throw new DomainException(ErrorCode.NOT_FOUND, "Unknown tool");
        transaction(c -> {
            requireGlobal(c, session, enterprise);
            var status = toolStatus(c, tool);
            if ((status == null || status.enabled) == enabled) return null;
            try (var s = c.prepareStatement("INSERT INTO tool_status(tool,enabled,changed_by,changed_at) VALUES (?,?,?,?)")) {
                s.setString(1, tool); s.setInt(2, enabled ? 1 : 0); s.setString(3, session.userId());
                s.setString(4, Instant.now().toString()); s.executeUpdate();
            }
            return null;
        });
        return tools(session, enterprise).stream().filter(t -> t.name().equals(tool)).findFirst().orElseThrow();
    }

    // ---- skills ----

    public List<SkillInfo> skills(PermissionGuard.Session session, String enterprise) {
        return transaction(c -> {
            guard.require(c, session, enterprise, PermissionGuard.Access.READ);
            var agents = currentAgents(c);
            var out = new ArrayList<SkillInfo>();
            try (var s = c.prepareStatement("""
                    SELECT * FROM skill_definitions a WHERE a.version=(SELECT MAX(b.version) FROM skill_definitions b WHERE b.id=a.id) ORDER BY a.id
                    """); var r = s.executeQuery()) {
                while (r.next()) {
                    var skill = skill(r);
                    out.add(new SkillInfo(skill, agents.stream().filter(a -> a.spec().skillIds().contains(skill.id()))
                            .map(AgentDirectory.Agent::id).toList()));
                }
            }
            return List.copyOf(out);
        });
    }

    public List<Skill> skillHistory(PermissionGuard.Session session, String enterprise, String id) {
        return transaction(c -> {
            guard.require(c, session, enterprise, PermissionGuard.Access.READ);
            var out = new ArrayList<Skill>();
            try (var s = c.prepareStatement("SELECT * FROM skill_definitions WHERE id=? ORDER BY version DESC")) {
                s.setString(1, id);
                try (var r = s.executeQuery()) { while (r.next()) out.add(skill(r)); }
            }
            if (out.isEmpty()) throw new DomainException(ErrorCode.NOT_FOUND, "Skill not found");
            return List.copyOf(out);
        });
    }

    public Skill saveSkill(PermissionGuard.Session session, String enterprise, String id, SkillSpec spec) {
        var clean = validate(id, spec);
        return transaction(c -> {
            requireGlobal(c, session, enterprise);
            var current = currentSkill(c, id);
            insertSkill(c, id, current == null ? 1 : current.version() + 1, clean, current == null || current.enabled(), session.userId());
            return currentSkill(c, id);
        });
    }

    public Skill setSkillEnabled(PermissionGuard.Session session, String enterprise, String id, boolean enabled) {
        return transaction(c -> {
            requireGlobal(c, session, enterprise);
            var current = currentSkill(c, id);
            if (current == null) throw new DomainException(ErrorCode.NOT_FOUND, "Skill not found");
            if (current.enabled() == enabled) return current;
            insertSkill(c, id, current.version() + 1, current.spec(), enabled, session.userId());
            return currentSkill(c, id);
        });
    }

    // ---- effective capabilities of one agent ----

    public Effective effective(AgentDirectory.Agent agent) {
        return transaction(c -> effective(c, agent));
    }
    static Effective effective(Connection c, AgentDirectory.Agent agent) throws SQLException {
        var wanted = new TreeSet<>(agent.spec().tools());
        var skills = new ArrayList<Skill>();
        var dropped = new ArrayList<String>();
        for (var id : agent.spec().skillIds()) {
            var skill = currentSkill(c, id);
            if (skill == null || !skill.enabled()) { dropped.add(id); continue; }
            skills.add(skill);
            wanted.addAll(skill.spec().tools()); // Read tools only: validated on save.
        }
        var disabled = new TreeSet<String>();
        for (var tool : TOOL_VERSIONS.keySet()) { var st = toolStatus(c, tool); if (st != null && !st.enabled) disabled.add(tool); }
        var usable = new TreeSet<>(wanted);
        usable.removeAll(disabled);
        return new Effective(usable, disabled, skills, dropped);
    }

    static SkillSpec validate(String id, SkillSpec spec) {
        if (id == null || !id.matches("[a-z][a-z0-9-]{2,39}")) throw invalid("Skill id: 3-40 lowercase letters, digits or '-'");
        if (spec == null || spec.name() == null || spec.name().isBlank() || spec.name().strip().length() > 60) throw invalid("Skill name required");
        var description = spec.description() == null ? "" : spec.description().strip();
        if (description.length() > 300) throw invalid("description too long");
        if (spec.instructions() == null || spec.instructions().isBlank()) throw invalid("Skill instructions required");
        if (spec.instructions().strip().length() > MAX_SKILL_INSTRUCTIONS) throw invalid("instructions too long");
        if (spec.tools() == null || spec.tools().isEmpty()) throw invalid("A skill needs at least one tool");
        var tools = new TreeSet<>(spec.tools());
        if (!TOOL_VERSIONS.keySet().containsAll(tools)) throw invalid("Unknown tool");
        if (tools.contains(AgentDirectory.WRITE_TOOL)) throw invalid("A skill cannot grant saving; give update_invoice to the agent itself");
        return new SkillSpec(spec.name().strip(), description, spec.instructions().strip(), List.copyOf(tools));
    }
    static boolean skillExists(Connection c, String id) throws SQLException { return currentSkill(c, id) != null; }

    private record Status(boolean enabled, String by, String at) {}
    private static Status toolStatus(Connection c, String tool) throws SQLException {
        try (var s = c.prepareStatement("SELECT enabled,changed_by,changed_at FROM tool_status WHERE tool=? ORDER BY seq DESC LIMIT 1")) {
            s.setString(1, tool);
            try (var r = s.executeQuery()) { return r.next() ? new Status(r.getInt(1) == 1, r.getString(2), r.getString(3)) : null; }
        }
    }
    private static boolean grants(Connection c, AgentDirectory.Agent agent, String tool) {
        if (agent.spec().tools().contains(tool)) return true;
        try {
            for (var id : agent.spec().skillIds()) {
                var skill = currentSkill(c, id);
                if (skill != null && skill.spec().tools().contains(tool)) return true;
            }
            return false;
        } catch (SQLException e) { throw storage(e); }
    }
    private static List<AgentDirectory.Agent> currentAgents(Connection c) throws SQLException {
        var out = new ArrayList<AgentDirectory.Agent>();
        try (var s = c.prepareStatement("""
                SELECT * FROM agent_definitions a WHERE a.version=(SELECT MAX(b.version) FROM agent_definitions b WHERE b.id=a.id) ORDER BY a.id
                """); var r = s.executeQuery()) { while (r.next()) out.add(AgentDirectory.agent(r)); }
        return out;
    }
    /** Global switches and skills affect every enterprise, so they need modify rights on all of them. */
    private void requireGlobal(Connection c, PermissionGuard.Session session, String enterprise) throws SQLException {
        guard.require(c, session, enterprise, PermissionGuard.Access.WRITE);
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT id FROM enterprises ORDER BY id")) {
            while (r.next()) guard.require(c, session, r.getString(1), PermissionGuard.Access.WRITE);
        }
    }
    static Skill currentSkill(Connection c, String id) throws SQLException {
        try (var s = c.prepareStatement("SELECT * FROM skill_definitions WHERE id=? ORDER BY version DESC LIMIT 1")) {
            s.setString(1, id);
            try (var r = s.executeQuery()) { return r.next() ? skill(r) : null; }
        }
    }
    private static void insertSkill(Connection c, String id, int version, SkillSpec spec, boolean enabled, String by) throws SQLException {
        try (var s = c.prepareStatement("INSERT INTO skill_definitions VALUES (?,?,?,?,?,?)")) {
            s.setString(1, id); s.setInt(2, version); s.setString(3, JSON.writeValueAsString(spec));
            s.setInt(4, enabled ? 1 : 0); s.setString(5, by); s.setString(6, Instant.now().toString()); s.executeUpdate();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    private static Skill skill(ResultSet r) throws SQLException {
        try {
            return new Skill(r.getString("id"), r.getInt("version"), JSON.readValue(r.getString("payload"), SkillSpec.class),
                    r.getInt("enabled") == 1, r.getString("changed_by"), r.getString("changed_at"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new SQLException("Unreadable skill", e); }
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
    private static DomainException storage(Exception e) {
        return e instanceof DomainException d ? d : new DomainException(ErrorCode.STORAGE_ERROR, "Capability storage failed", e);
    }
    @FunctionalInterface private interface Operation<T> { T run(Connection c) throws SQLException; }
}
