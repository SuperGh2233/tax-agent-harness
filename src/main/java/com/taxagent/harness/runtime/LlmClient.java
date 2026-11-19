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
    /**
     * The same client for one configured agent (T30): its role text is appended after the fixed system prompt and
     * can never replace it; model, when given, replaces this profile's model name. Fakes only record the prompt.
     */
    default LlmClient forAgent(String role, String model) {
        var base = this;
        return new LlmClient() {
            public Decision next(Request request) throws Exception { return base.next(request); }
            public String systemPrompt() { return base.systemPrompt() + role; }
        };
    }

    /**
     * T36: every provider adapter gives a malformed reply exactly one corrective retry with this note, then fails
     * with MODEL_RESPONSE_INVALID. The runtime itself never retries the model.
     */
    String CORRECTION = "上一次回复无法被解析。请严格遵守：没有工具调用时只输出 JSON "
            + "{\"kind\":\"ask\" 或 \"finish\",\"text\":\"...\"}；"
            + "单轮工具调用不得超过 8 个；需要更多月份时先追问，不要一次并行发起。";
    /** T36: the same sanitized code for the same HTTP failure, whichever provider protocol is in use. */
    static String httpFailure(int status) {
        if (status == 401 || status == 403) return "MODEL_AUTH_FAILED";
        if (status == 429) return "MODEL_RATE_LIMITED";
        return "MODEL_HTTP_ERROR";
    }

    record Tool(String name, JsonNode parameters) {}
    /**
     * resultJson is the full tool result: evidence for the UI, task memory and verification. modelJson (T34), when
     * set, is the compacted view actually sent to the model for a result too large to replay whole.
     */
    record Exchange(Call call, String resultJson, long durationMs, String modelJson) {
        public Exchange(Call call, String resultJson) { this(call, resultJson, 0, null); }
        public Exchange(Call call, String resultJson, long durationMs) { this(call, resultJson, durationMs, null); }
        public String forModel() { return modelJson == null ? resultJson : modelJson; }
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
