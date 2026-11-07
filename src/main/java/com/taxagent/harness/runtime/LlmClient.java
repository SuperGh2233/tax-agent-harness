package com.taxagent.harness.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.HashSet;

/** Provider-neutral decisions. No trusted identity is exposed to the model. */
@FunctionalInterface
public interface LlmClient {
    Decision next(Request request) throws Exception;
    /** The static system prompt is part of the exact model input trace; never contains secrets. */
    default String systemPrompt() { return ""; }

    record Tool(String name, JsonNode parameters) {}
    record Exchange(Call call, String resultJson, long durationMs) {
        public Exchange(Call call, String resultJson) { this(call, resultJson, 0); }
    }
    record Request(String userMessage, List<Tool> tools, List<Exchange> history) {
        public Request { tools = List.copyOf(tools); history = List.copyOf(history); }
    }
    sealed interface Decision permits Call, Batch, Ask, Finish {}
    /** A single assistant turn may request several distinct tools. Never infer approval from a batch. */
    record Batch(List<Call> calls) implements Decision {
        public Batch {
            calls = List.copyOf(calls);
            if (calls.size() < 2 || calls.size() > 8) throw new IllegalArgumentException("Invalid batch size");
            var ids = new HashSet<String>();
            for (var call : calls) {
                if (call == null || !ids.add(call.id())) throw new IllegalArgumentException("Duplicate or missing tool call");
            }
        }
    }
    record Call(String id, String name, String arguments,
                @com.fasterxml.jackson.annotation.JsonIgnore List<JsonNode> providerOutput) implements Decision {
        public Call(String id, String name, String arguments) { this(id, name, arguments, List.of()); }
        public Call {
            providerOutput = providerOutput.stream().<JsonNode>map(JsonNode::deepCopy).toList();
            if (id == null || id.isBlank() || name == null || name.isBlank() || arguments == null)
                throw new IllegalArgumentException("Invalid tool call");
        }
        @Override @com.fasterxml.jackson.annotation.JsonIgnore
        public List<JsonNode> providerOutput() { return providerOutput.stream().<JsonNode>map(JsonNode::deepCopy).toList(); }
        @Override public String toString() { return "Call[id=" + id + ", name=" + name + ", arguments=" + arguments + "]"; }
    }
    record Ask(String text) implements Decision {
        public Ask { if (text == null || text.isBlank()) throw new IllegalArgumentException("Empty question"); }
    }
    record Finish(String text) implements Decision {
        public Finish { if (text == null || text.isBlank()) throw new IllegalArgumentException("Empty answer"); }
    }
}
