package com.taxagent.harness.tool;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;

/** Transport types only: no query, rule evaluation or persistence executor. */
public final class ToolContract {
    private ToolContract() {}

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules()
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    static {
        var text = MAPPER.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual);
        for (var shape : List.of(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
                com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,
                com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean)) {
            text.setCoercion(shape, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
        }
    }

    /** Canonical provider-neutral JSON Schema. A fresh tree prevents mutation of shared state. */
    public static JsonNode schema() {
        try (var input = ToolContract.class.getResourceAsStream("/tool-contract.schema.json")) {
            if (input == null) throw new IllegalStateException("Missing tool schema");
            return MAPPER.readTree(input);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read tool schema", e);
        }
    }

    public static <T> T decode(String json, Class<T> type) {
        // An allowlist prevents callers from deserializing trusted server context.
        if (!Set.of(Query.class, PurposeCheck.class, RuleQuery.class, KnowledgeQuery.class, RecallQuery.class, InvoiceUpdate.class,
                StateRequest.class, RuleRequest.class).contains(type)) throw invalid("Unsupported tool input");
        try {
            T value = MAPPER.readValue(json, type);
            if (value == null) throw invalid("Input is required");
            return value;
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) {
            Throwable cause = e;
            while (cause != null) {
                if (cause instanceof DomainException domain) throw domain;
                cause = cause.getCause();
            }
            throw new DomainException(ErrorCode.INVALID_ARGUMENT, "Invalid tool input", e);
        }
    }

    static DomainException invalid(String message) { return new DomainException(ErrorCode.INVALID_ARGUMENT, message); }
    static String required(String value) {
        if (value == null || value.isBlank()) throw invalid("Required text is missing");
        return value;
    }
    static void month(String value) {
        if (value == null || !value.matches("[0-9]{4}-(0[1-9]|1[0-2])")) throw invalid("month must be yyyy-MM");
        try { YearMonth.parse(value); } catch (DateTimeException e) { throw invalid("Invalid month"); }
    }
    public record Query(String enterpriseId, String month) {
        public Query { required(enterpriseId); ToolContract.month(month); }
    }
    public record PurposeCheck(String enterpriseId, String month) {
        public PurposeCheck {
            // Applicability is decided by the stored rules in force, not by a fixed month.
            required(enterpriseId); ToolContract.month(month);
        }
    }
    public record InvoiceKey(String enterpriseId, String invoiceId) {
        public InvoiceKey { required(enterpriseId); required(invoiceId); }
    }
    public record BankKey(String enterpriseId, String entryId) {
        public BankKey { required(enterpriseId); required(entryId); }
    }
    public record InvoiceUpdate(InvoiceKey key, Map<String, String> changes) {
        public InvoiceUpdate {
            if (key == null || changes == null || changes.isEmpty()) throw invalid("Key and changes are required");
            if (changes.keySet().stream().anyMatch(Objects::isNull)) throw invalid("Field name required");
            if (!Set.of("purpose", "remarks").containsAll(changes.keySet()))
                throw new DomainException(ErrorCode.FIELD_NOT_ALLOWED, "Only purpose and remarks may change");
            if (changes.values().stream().anyMatch(Objects::isNull)) throw invalid("Changes cannot contain null");
            changes = Map.copyOf(changes);
        }
    }
    public enum StateAction { GET, PUT, DELETE }
    public record StateRequest(String enterpriseId, StateAction action, String id, String content) {
        public StateRequest {
            required(enterpriseId);
            if (action == null) throw invalid("Action is required");
            if (id != null || action != StateAction.GET) required(id);
            if (action == StateAction.PUT) required(content);
            else if (content != null) throw invalid("Content only allowed for PUT");
        }
    }
    public enum RuleAction { GET, IMPORT, UPDATE, DISABLE }
    public enum RuleStatus { 启用, 已停用 }
    public record RuleKey(String ruleId, String version) {
        public RuleKey { required(ruleId); required(version); }
    }
    public record RuleDefinition(String name, LocalDate effectiveFrom, RuleStatus status, String content) {
        public RuleDefinition {
            required(name); required(content);
            if (effectiveFrom == null || status == null) throw invalid("Rule date and status required");
        }
    }
    public record RuleRequest(String enterpriseId, RuleAction action, RuleKey key, RuleDefinition definition) {
        public RuleRequest {
            required(enterpriseId);
            if (action == null || (action != RuleAction.GET && key == null)) throw invalid("Action/key required");
            boolean needsDefinition = action == RuleAction.IMPORT || action == RuleAction.UPDATE;
            if (needsDefinition != (definition != null)) throw invalid("Invalid rule definition combination");
        }
    }
    public record Invoice(InvoiceKey key, LocalDate invoiceDate, String seller, String item,
                          long amountCents, String purpose, String remarks) {}
    public enum Direction { 收入, 支出 }
    public record BankEntry(BankKey key, LocalDate transactionDate, Direction direction,
                            long amountCents, String counterparty, String remarks) {}
    public record InvoiceResult(List<Invoice> rows, long totalCents) {
        public InvoiceResult { rows = List.copyOf(rows); }
    }
    public record BankResult(List<BankEntry> rows, long incomeCents, long expenseCents) {
        public BankResult { rows = List.copyOf(rows); }
    }
    public record RuleReference(RuleKey key, String name) {}
    public record RuleQuery(String enterpriseId, LocalDate date) {
        public RuleQuery {
            required(enterpriseId);
            if (date == null) throw invalid("Rule date required");
        }
    }
    public record RuleSummary(RuleKey key, String name, LocalDate effectiveFrom, RuleStatus status, String content) {}
    public record RuleQueryResult(List<RuleSummary> rules) {
        public RuleQueryResult { rules = List.copyOf(rules); }
    }
    /** T32: text search over enabled rules and current uploaded files of the trusted enterprise. */
    public record KnowledgeQuery(String enterpriseId, String query, LocalDate date, Integer limit) {
        public static final int DEFAULT_LIMIT = 5, MAX_LIMIT = 10, MAX_QUERY_CHARS = 200;
        public KnowledgeQuery {
            required(enterpriseId); required(query);
            if (query.length() > MAX_QUERY_CHARS) throw invalid("Query too long");
            if (limit != null && (limit < 1 || limit > MAX_LIMIT)) throw invalid("limit must be 1-10");
        }
        public int effectiveLimit() { return limit == null ? DEFAULT_LIMIT : limit; }
    }
    /** One cited passage: source kind RULE (id@version) or FILE (name, vN), with its location in the source. */
    public record KnowledgeHit(String sourceKind, String sourceId, String sourceName, String version, String location,
                               String text, double score) {}
    public record KnowledgeResult(List<KnowledgeHit> hits, int searchedSegments) {
        public KnowledgeResult { hits = List.copyOf(hits); }
    }
    /**
     * T34: read the original behind the compressed view, within the current task and enterprise only.
     * TURN needs seq; TOOL_RESULT needs call_id (offset/limit page its rows); FILE needs file_id and location
     * (limit = neighbouring segments on each side).
     */
    public record RecallQuery(String source, Integer seq, String callId, String fileId, String location, Integer offset, Integer limit) {
        public static final int MAX_ROWS = 50, MAX_NEIGHBOURS = 3;
        public RecallQuery {
            if (source == null || !Set.of("TURN", "TOOL_RESULT", "FILE").contains(source)) throw invalid("source must be TURN, TOOL_RESULT or FILE");
            switch (source) {
                case "TURN" -> { if (seq == null || seq < 1 || callId != null || fileId != null || location != null) throw invalid("TURN needs seq only"); }
                case "TOOL_RESULT" -> { required(callId); if (seq != null || fileId != null || location != null) throw invalid("TOOL_RESULT needs call_id"); }
                default -> { required(fileId); required(location); if (seq != null || callId != null) throw invalid("FILE needs file_id and location"); }
            }
            if (offset != null && offset < 0) throw invalid("offset must be >= 0");
            if (limit != null && (limit < 1 || limit > ("FILE".equals(source) ? MAX_NEIGHBOURS : MAX_ROWS))) throw invalid("limit out of range");
        }
    }
    public record RecallResult(String source, String reference, String content, Integer total, Integer offset, Integer returned, String note) {}
    public record PurposeResult(List<InvoiceKey> missing, List<RuleReference> rules) {
        public PurposeResult { missing = List.copyOf(missing); rules = List.copyOf(rules); }
    }
    public record UpdateResult(InvoiceKey key, String purpose, String remarks, boolean verified) {}
    public record StateResult(String id, String content) {}
    public record Error(ErrorCode code, String message) {}
    public record Response<T>(T data, Error error) {
        public Response { if ((data == null) == (error == null)) throw invalid("Exactly one of data/error required"); }
    }
}
