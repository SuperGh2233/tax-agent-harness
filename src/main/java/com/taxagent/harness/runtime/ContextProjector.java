package com.taxagent.harness.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Builds the bounded view that is sent to the model. The full {@link TaskMemory.Context} remains
 * persisted and authoritative; projection never becomes evidence or write authority.
 */
public final class ContextProjector {
    public static final int MAX_CHARS = 4000;
    public static final int MAX_PREFERENCES = 8;
    public static final int MAX_RECENT_TOOLS = 5;
    public static final int MAX_SUPPLIED = 8;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern MONTH = Pattern.compile("20[0-9]{2}-(0[1-9]|1[0-2])|(?:一|二|三|四|五|六|七|八|九|十|十一|十二|[1-9]|1[0-2])月");

    public record Projection(String memoryJson, int omittedPreferences, int omittedRecentTools, int omittedSupplied) {
        public boolean truncated() { return omittedPreferences + omittedRecentTools + omittedSupplied > 0; }
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
                task.pending(), supplied, clip(task.question(), 500), recent);
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
                    clip(projected.goal(), 200), projected.pending(), projected.supplied(), projected.question(), recent);
            json = render(projected, preferences, omittedPreferences, omittedRecent, omittedSupplied);
        }
        if (json.length() > MAX_CHARS && !supplied.isEmpty()) {
            omittedSupplied += supplied.size();
            supplied = Map.of();
            projected = new TaskMemory.Task(projected.id(), projected.status(), projected.version(),
                    clip(projected.goal(), 200), projected.pending(), supplied, projected.question(), recent);
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