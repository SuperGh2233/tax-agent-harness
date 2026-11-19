package com.taxagent.harness.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Builds the bounded view that is sent to the model. The full {@link TaskMemory.Context} remains
 * persisted and authoritative; projection never becomes evidence or write authority.
 */
public final class ContextProjector {
    /** T34: raised from 4000 to make room for the compressed conversation; the priority order below still applies. */
    public static final int MAX_CHARS = 6000;
    public static final int RECENT_TURNS = 4, MAX_CONSTRAINTS = 8, MIN_CONSTRAINTS = 3;
    public static final int MAX_PREFERENCES = 8;
    public static final int MAX_RECENT_TOOLS = 5;
    public static final int MAX_SUPPLIED = 8;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern MONTH = Pattern.compile("20[0-9]{2}-(0[1-9]|1[0-2])|(?:一|二|三|四|五|六|七|八|九|十|十一|十二|[1-9]|1[0-2])月");

    public record Projection(String memoryJson, int omittedPreferences, int omittedRecentTools, int omittedSupplied, int omittedTurns) {
        public Projection(String memoryJson, int omittedPreferences, int omittedRecentTools, int omittedSupplied) {
            this(memoryJson, omittedPreferences, omittedRecentTools, omittedSupplied, 0);
        }
        public boolean truncated() { return omittedPreferences + omittedRecentTools + omittedSupplied + omittedTurns > 0; }
    }

    // ---- T34: conversation compression (FINAL_ROUND_CONTRACT B7) ----

    /** User wording that states a limit, an exclusion, a standing instruction or a correction. */
    private static final Pattern CONSTRAINT = Pattern.compile("只|仅|不要|别|不用|不必|无需|必须|务必|一定要|以后|今后|请勿|除了|排除|不含|不包括|改为|改成|换成|更正|不对|错了|说错|而是");
    private static final Pattern NEGATION = Pattern.compile("不是|不要|别|非|而非|不查");
    private static final Pattern IDS = Pattern.compile("(?<![A-Za-z0-9])([CFTR])([0-9]{3})(?![0-9])");

    /**
     * The model sees the last turns verbatim; from older turns only what must survive: the user's constraints and
     * corrections (with their turn number) and the key objects mentioned anywhere (months, invoice, bank and rule
     * ids), with the latest month marked. Older originals stay stored and can be fetched with recall_context.
     */
    public static Projection project(TaskMemory.Context context, String message, List<TaskMemory.TurnLog> turns) {
        var base = project(context, message);
        if (turns == null || turns.isEmpty()) return base;
        int recentFrom = Math.max(0, turns.size() - RECENT_TURNS);
        var older = turns.subList(0, recentFrom);
        var constraints = new ArrayList<Map<String, Object>>();
        for (var turn : older)
            if (CONSTRAINT.matcher(turn.userMessage()).find())
                constraints.add(Map.of("turn", turn.seq(), "user", clip(turn.userMessage(), 160)));
        if (constraints.size() > MAX_CONSTRAINTS) constraints = new ArrayList<>(constraints.subList(constraints.size() - MAX_CONSTRAINTS, constraints.size()));
        // Key objects: last mention wins, so a correction later in the conversation is what the model treats as current.
        var months = new LinkedHashMap<String, Integer>();
        var ids = new TreeMap<String, Integer>();
        Map<String, Object> latestMonth = null;
        var texts = new ArrayList<Map.Entry<Integer, String>>();
        texts.add(Map.entry(0, context.task().goal()));
        for (var turn : turns) texts.add(Map.entry(turn.seq(), turn.userMessage()));
        for (var entry : texts) {
            var m = MONTH.matcher(entry.getValue());
            while (m.find()) {
                months.remove(m.group()); months.put(m.group(), entry.getKey());
                // "不对，是八月，不是九月": a negated month is mentioned, but it is not the one in force.
                var before = entry.getValue().substring(Math.max(0, m.start() - 2), m.start());
                if (NEGATION.matcher(before).find()) continue;
                latestMonth = Map.of("value", m.group(), "turn", entry.getKey(), "said", clip(entry.getValue(), 120));
            }
            var id = IDS.matcher(entry.getValue());
            while (id.find()) ids.put(id.group(), entry.getKey());
        }
        var recent = new ArrayList<Map<String, Object>>();
        for (var turn : turns.subList(recentFrom, turns.size())) {
            var row = new LinkedHashMap<String, Object>();
            row.put("turn", turn.seq()); row.put("user", clip(turn.userMessage(), 300));
            if (turn.agentText() != null) row.put("agent", clip(turn.agentText(), 300));
            recent.add(row);
        }
        int dropped = 0, finalStage = 0;
        String json = null;
        for (int stage = 0; stage <= 4; stage++) {
            finalStage = stage;
            if (stage == 1) recent.forEach(r -> r.remove("agent"));
            if (stage == 3 && constraints.size() > MIN_CONSTRAINTS) constraints = new ArrayList<>(constraints.subList(constraints.size() - MIN_CONSTRAINTS, constraints.size()));
            if (stage == 4 && recent.size() > 1) recent = new ArrayList<>(recent.subList(recent.size() - 1, recent.size()));
            var conversation = new LinkedHashMap<String, Object>();
            conversation.put("turns_total", turns.size());
            conversation.put("key_objects", Map.of("months_mentioned", months.keySet(), "latest_month", latestMonth == null ? Map.of() : latestMonth,
                    "ids_mentioned", ids.keySet()));
            if (!constraints.isEmpty()) conversation.put("earlier_constraints", constraints);
            conversation.put("recent_turns", recent);
            int omitted = turns.size() - recent.size();
            if (omitted > 0) conversation.put("omitted_turns", Map.of("count", omitted,
                    "how_to_read", "需要原文时调用 recall_context {\"source\":\"TURN\",\"seq\":轮次}；原文不是当前数据库事实。"));
            json = withConversation(base.memoryJson(), conversation, stage >= 2);
            dropped = omitted;
            if (json.length() <= MAX_CHARS) break;
        }
        int prefsDropped = finalStage >= 2 ? context.preferences().size() : base.omittedPreferences();
        return new Projection(json, prefsDropped, base.omittedRecentTools(), base.omittedSupplied(), dropped);
    }
    /** Stage 2 and later: long-term preferences give way to the conversation's own constraints. */
    private static String withConversation(String memoryJson, Map<String, Object> conversation, boolean dropPreferences) {
        try {
            var root = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(memoryJson);
            if (dropPreferences && root.path("preferences").size() > 0) {
                root.put("omitted_preferences", root.path("omitted_preferences").asInt(0) + root.path("preferences").size());
                root.set("preferences", JSON.createObjectNode());
            }
            root.set("conversation", JSON.valueToTree(conversation));
            return JSON.writeValueAsString(root);
        } catch (Exception e) { throw new IllegalStateException("Cannot project conversation", e); }
    }

    private ContextProjector() {}

    public static Projection project(TaskMemory.Context context, String message) {
        if (context == null) throw new IllegalArgumentException("context required");
        var task = context.task();
        var supplied = tailMap(task.supplied(), MAX_SUPPLIED);
        var recent = tailList(task.recentTools(), MAX_RECENT_TOOLS);
        var preferences = selectPreferences(context.preferences(), message, task);
        int omittedPreferences = context.preferences().size() - preferences.size();
        int omittedRecent = task.recentTools().size() - recent.size();
        int omittedSupplied = task.supplied().size() - supplied.size();
        var projected = new TaskMemory.Task(task.id(), task.status(), task.version(), clip(task.goal(), 500),
                task.pending(), supplied, clip(task.question(), 500), recent, null, task.awaitingSave());
        var json = render(projected, preferences, omittedPreferences, omittedRecent, omittedSupplied);
        if (json.length() > MAX_CHARS && !preferences.isEmpty()) {
            omittedPreferences += preferences.size();
            preferences = Map.of();
            json = render(projected, preferences, omittedPreferences, omittedRecent, omittedSupplied);
        }
        if (json.length() > MAX_CHARS && !recent.isEmpty()) {
            omittedRecent += recent.size();
            recent = List.of();
            projected = new TaskMemory.Task(projected.id(), projected.status(), projected.version(),
                    clip(projected.goal(), 200), projected.pending(), projected.supplied(), projected.question(), recent, null,
                    projected.awaitingSave());
            json = render(projected, preferences, omittedPreferences, omittedRecent, omittedSupplied);
        }
        if (json.length() > MAX_CHARS && !supplied.isEmpty()) {
            omittedSupplied += supplied.size();
            supplied = Map.of();
            projected = new TaskMemory.Task(projected.id(), projected.status(), projected.version(),
                    clip(projected.goal(), 200), projected.pending(), supplied, projected.question(), recent, null,
                    projected.awaitingSave());
            json = render(projected, preferences, omittedPreferences, omittedRecent, omittedSupplied);
        }
        return new Projection(json, omittedPreferences, omittedRecent, omittedSupplied);
    }

    private static Map<String, TaskMemory.Input> selectPreferences(Map<String, TaskMemory.Input> all,
                                                                  String message, TaskMemory.Task task) {
        if (all.size() <= MAX_PREFERENCES) return all;
        var text = ((message == null ? "" : message) + " " + task.goal() + " " + String.join(" ", task.pending()))
                .toLowerCase(Locale.ROOT);
        var cues = new HashSet<String>();
        var matcher = MONTH.matcher(text);
        while (matcher.find()) cues.add(matcher.group());
        var selected = new LinkedHashMap<String, TaskMemory.Input>();
        for (var entry : all.entrySet()) {
            if (relevant(entry.getKey(), entry.getValue(), text, cues)) selected.put(entry.getKey(), entry.getValue());
            if (selected.size() >= MAX_PREFERENCES) break;
        }
        return Map.copyOf(selected);
    }

    private static boolean relevant(String name, TaskMemory.Input value, String text, Set<String> cues) {
        var blob = (name + " " + value.value() + " " + value.userRequest()).toLowerCase(Locale.ROOT);
        for (var cue : cues) if (blob.contains(cue)) return true;
        for (var token : text.split("[\\s,，。；;:：、]+"))
            if (token.length() >= 2 && blob.contains(token)) return true;
        return false;
    }

    private static String render(TaskMemory.Task task, Map<String, TaskMemory.Input> preferences,
                                 int omittedPreferences, int omittedRecent, int omittedSupplied) {
        var root = new LinkedHashMap<String, Object>();
        root.put("task", task);
        root.put("preferences", preferences);
        if (omittedPreferences > 0) root.put("omitted_preferences", omittedPreferences);
        if (omittedRecent > 0) root.put("omitted_recent_tools", omittedRecent);
        if (omittedSupplied > 0) root.put("omitted_supplied", omittedSupplied);
        if (omittedPreferences + omittedRecent + omittedSupplied > 0)
            root.put("note", "部分无关偏好或旧工具摘要已省略；需要时重新查询或让用户补充。");
        try { return JSON.writeValueAsString(root); }
        catch (Exception e) { throw new IllegalStateException("Cannot project context", e); }
    }

    private static <K, V> Map<K, V> tailMap(Map<K, V> source, int max) {
        var result = new LinkedHashMap<K, V>();
        int skip = Math.max(0, source.size() - max);
        int index = 0;
        for (var entry : source.entrySet()) if (index++ >= skip) result.put(entry.getKey(), entry.getValue());
        return Map.copyOf(result);
    }

    private static <T> List<T> tailList(List<T> source, int max) {
        return List.copyOf(source.subList(Math.max(0, source.size() - max), source.size()));
    }

    private static String clip(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}