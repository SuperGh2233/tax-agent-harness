package com.taxagent.harness;

import com.fasterxml.jackson.databind.*;
import com.taxagent.harness.tool.ToolContract;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class ToolSchemaTest {
    final JsonNode root = ToolContract.schema();
    final ObjectMapper json = new ObjectMapper();

    @Test void canonicalDefinitionsMatchRecordsAndAreClosed() {
        for (var type : List.of(ToolContract.Query.class, ToolContract.PurposeCheck.class,
                ToolContract.InvoiceKey.class, ToolContract.RuleKey.class, ToolContract.InvoiceUpdate.class,
                ToolContract.StateRequest.class, ToolContract.RuleDefinition.class, ToolContract.RuleRequest.class)) {
            var definition = root.path("$defs").path(type.getSimpleName());
            var fields = new HashSet<String>();
            for (var field : type.getRecordComponents())
                fields.add(field.getName().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT));
            var actual = new HashSet<String>();
            definition.path("properties").fieldNames().forEachRemaining(actual::add);
            assertThat(actual).isEqualTo(fields).doesNotContain("user_id");
            assertThat(definition.path("additionalProperties").asBoolean(true)).isFalse();
            var required = new HashSet<String>();
            definition.path("required").forEach(n -> required.add(n.asText()));
            assertThat(required).isEqualTo(type == ToolContract.StateRequest.class || type == ToolContract.RuleRequest.class
                    ? Set.of("enterprise_id", "action") : fields);
        }
        assertThat(root.at("/$defs/InvoiceUpdate/properties/changes/additionalProperties").asBoolean(true)).isFalse();
        assertThat(root.at("/$defs/StateRequest/properties/action/enum").toString()).isEqualTo("[\"GET\",\"PUT\",\"DELETE\"]");
        assertThat(root.at("/$defs/RuleRequest/properties/action/enum").toString()).isEqualTo("[\"GET\",\"IMPORT\",\"UPDATE\",\"DISABLE\"]");
        ((com.fasterxml.jackson.databind.node.ObjectNode) root).remove("$defs");
        assertThat(ToolContract.schema().has("$defs")).isTrue();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Query|{\"enterprise_id\":\"C001\",\"month\":\"2026-09\"}|true",
            "Query|{\"enterprise_id\":\"C001\"}|false",
            "Query|{\"enterprise_id\":1,\"month\":\"2026-09\"}|false",
            "Query|{\"enterprise_id\":\"C001\",\"month\":\"2026-13\"}|false",
            "Query|{\"enterprise_id\":\"C001\",\"month\":\"2026-09\",\"user_id\":\"U001\"}|false",
            "PurposeCheck|{\"enterprise_id\":\"C001\",\"month\":\"2026-08\"}|false",
            "InvoiceUpdate|{\"key\":{\"enterprise_id\":\"C001\",\"invoice_id\":\"F001\"},\"changes\":{\"purpose\":\"\"}}|true",
            "InvoiceUpdate|{\"key\":{\"invoice_id\":\"F001\"},\"changes\":{\"purpose\":\"x\"}}|false",
            "InvoiceUpdate|{\"key\":{\"enterprise_id\":\"C001\",\"invoice_id\":\"F001\"},\"changes\":{\"seller\":\"x\"}}|false",
            "InvoiceUpdate|{\"key\":{\"enterprise_id\":\"C001\",\"invoice_id\":\"F001\"},\"changes\":{}}|false",
            "StateRequest|{\"enterprise_id\":\"C001\",\"action\":\"GET\"}|true",
            "StateRequest|{\"enterprise_id\":\"C001\",\"action\":\"PUT\",\"id\":\"t1\",\"content\":\"pending\"}|true",
            "StateRequest|{\"enterprise_id\":\"C001\",\"action\":\"PUT\"}|false",
            "StateRequest|{\"enterprise_id\":\"C001\",\"action\":\"DELETE\",\"id\":null}|false",
            "StateRequest|{\"enterprise_id\":\"C001\",\"action\":\"GET\",\"content\":\"x\"}|false",
            "RuleRequest|{\"enterprise_id\":\"C001\",\"action\":\"GET\"}|true",
            "RuleRequest|{\"enterprise_id\":\"C001\",\"action\":\"IMPORT\"}|false",
            "RuleRequest|{\"enterprise_id\":\"C001\",\"action\":\"DISABLE\",\"key\":{\"rule_id\":\"R001\",\"version\":\"1.0\"}}|true",
            "RuleRequest|{\"enterprise_id\":\"C001\",\"action\":\"WRITE\"}|false"
    })
    void canonicalSchemaAndDecoderAgree(String definition, String input, boolean valid) throws Exception {
        assertThat(matches(root.path("$defs").path(definition), json.readTree(input))).isEqualTo(valid);
        var type = Class.forName(ToolContract.class.getName() + "$" + definition);
        if (valid) assertThatCode(() -> ToolContract.decode(input, type)).doesNotThrowAnyException();
        else assertThatThrownBy(() -> ToolContract.decode(input, type)).isInstanceOf(com.taxagent.harness.tool.DomainException.class);
    }

    // Test-only evaluator for the keywords used by this canonical file, not a runtime validator.
    boolean matches(JsonNode schema, JsonNode value) {
        if (schema.has("$ref")) return matches(root.at(schema.get("$ref").asText().substring(1)), value);
        if (schema.has("anyOf")) {
            boolean found = false;
            for (var alternative : schema.get("anyOf")) found |= matches(alternative, value);
            if (!found) return false;
        }
        if (schema.has("type")) {
            boolean correct = switch (schema.get("type").asText()) {
                case "object" -> value.isObject(); case "string" -> value.isTextual();
                case "null" -> value.isNull(); default -> throw new AssertionError("Unsupported test keyword type");
            };
            if (!correct) return false;
        }
        if (schema.has("const") && !schema.get("const").equals(value)) return false;
        if (schema.has("enum")) {
            boolean found = false;
            for (var choice : schema.get("enum")) found |= choice.equals(value);
            if (!found) return false;
        }
        if (schema.has("pattern") && !java.util.regex.Pattern.compile(schema.get("pattern").asText()).matcher(value.asText()).find()) return false;
        if (schema.has("format")) {
            try { java.time.LocalDate.parse(value.asText()); } catch (java.time.DateTimeException e) { return false; }
        }
        if (value.isObject()) {
            for (var required : schema.path("required")) if (!value.has(required.asText())) return false;
            if (schema.has("minProperties") && value.size() < schema.get("minProperties").asInt()) return false;
            var names = value.fieldNames();
            while (names.hasNext()) {
                var name = names.next();
                var property = schema.path("properties").get(name);
                if (property != null && !matches(property, value.get(name))) return false;
                if (property == null && schema.has("additionalProperties") && !schema.get("additionalProperties").asBoolean()) return false;
            }
        }
        for (var clause : schema.path("allOf")) if (!matches(clause, value)) return false;
        if (schema.has("if")) {
            var branch = schema.get(matches(schema.get("if"), value) ? "then" : "else");
            if (branch != null && !matches(branch, value)) return false;
        }
        return true;
    }
}
