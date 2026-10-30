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
        if (!Set.of(Query.class, PurposeCheck.class, InvoiceUpdate.class,
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
            required(enterpriseId); ToolContract.month(month);
            if (!month.equals("2026-09")) throw new DomainException(ErrorCode.UNSUPPORTED_SCOPE, "Purpose check supports 2026-09 only");
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
