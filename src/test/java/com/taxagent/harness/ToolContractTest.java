package com.taxagent.harness;

import com.taxagent.harness.tool.*;
import com.taxagent.harness.tool.ToolContract.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ToolContractTest {
    static void fails(ErrorCode code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{\"enterprise_id\":\"C001\"}",
            "{\"enterprise_id\":\"C001\",\"month\":\"2026-13\"}",
            "{\"enterprise_id\":\"C001\",\"month\":\"2026-9\"}",
            "{\"enterprise_id\":\" \",\"month\":\"2026-09\"}",
            "{\"enterprise_id\":1,\"month\":\"2026-09\"}",
            "{\"enterprise_id\":\"C001\",\"month\":\"2026-09\",\"user_id\":\"U001\"}",
            "{\"enterprise_id\":\"C001\",\"month\":\"2026-09\"} {}",
            "{\"enterprise_id\":\"C001\",\"month\":\"2026-09\",\"month\":\"2026-08\"}"})
    void rejectsInvalidQueries(String json) {
        fails(ErrorCode.INVALID_ARGUMENT, () -> ToolContract.decode(json, Query.class));
    }

    @Test void validQueryAndCompositeKeys() {
        assertThat(ToolContract.decode("{\"enterprise_id\":\"C001\",\"month\":\"2026-09\"}", Query.class))
                .isEqualTo(new Query("C001", "2026-09"));
        assertThat(new InvoiceKey("C001", "F001")).isNotEqualTo(new InvoiceKey("C002", "F001"));
        fails(ErrorCode.INVALID_ARGUMENT, () -> new InvoiceKey(null, "F001"));
        fails(ErrorCode.INVALID_ARGUMENT, () -> new BankKey("C001", ""));
        fails(ErrorCode.INVALID_ARGUMENT, () -> ToolContract.decode("{}", PermissionGuard.Session.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"amount_cents", "seller", "enterprise_id", "invoice_id", "invoice_date", "item", "user_id"})
    void rejectsFieldsOutsideWhitelist(String field) {
        fails(ErrorCode.FIELD_NOT_ALLOWED, () -> new InvoiceUpdate(new InvoiceKey("C001", "F001"), Map.of(field, "x")));
    }

    @Test void updateIsImmutableAndPreservesExplicitValues() {
        var fields = new HashMap<String, String>();
        fields.put("purpose", " "); fields.put("remarks", "");
        var update = new InvoiceUpdate(new InvoiceKey("C001", "F001"), fields);
        fields.put("purpose", "changed");
        assertThat(update.changes()).containsEntry("purpose", " ").containsEntry("remarks", "");
        assertThatThrownBy(() -> update.changes().put("purpose", "x")).isInstanceOf(UnsupportedOperationException.class);
        fails(ErrorCode.INVALID_ARGUMENT, () -> new InvoiceUpdate(update.key(), Map.of()));
        fields.put("purpose", null);
        fails(ErrorCode.INVALID_ARGUMENT, () -> new InvoiceUpdate(update.key(), fields));
        fails(ErrorCode.FIELD_NOT_ALLOWED, () -> ToolContract.decode(
                "{\"key\":{\"enterprise_id\":\"C001\",\"invoice_id\":\"F001\"},\"changes\":{\"seller\":\"x\"}}", InvoiceUpdate.class));
    }

    @Test void purposeScopeAndStateRuleValidation() {
        new PurposeCheck("C001", "2026-09");
        fails(ErrorCode.UNSUPPORTED_SCOPE, () -> new PurposeCheck("C001", "2026-08"));
        fails(ErrorCode.INVALID_ARGUMENT, () -> new StateRequest("C001", StateAction.PUT, "task1", null));
        fails(ErrorCode.INVALID_ARGUMENT, () -> new StateRequest("C001", StateAction.DELETE, null, null));
        fails(ErrorCode.INVALID_ARGUMENT, () -> new StateRequest("C001", StateAction.GET, null, "x"));
        fails(ErrorCode.INVALID_ARGUMENT, () -> new RuleRequest("C001", RuleAction.IMPORT, new RuleKey("R001", "1.0"), null));
        assertThat(ToolContract.decode("{\"enterprise_id\":\"C001\",\"action\":\"IMPORT\",\"key\":{\"rule_id\":\"R001\",\"version\":\"1.0\"},\"definition\":{\"name\":\"test\",\"effective_from\":\"2026-09-01\",\"status\":\"启用\",\"content\":\"test\"}}", RuleRequest.class).definition().effectiveFrom())
                .hasToString("2026-09-01");
    }

    @Test void resultSnapshotsAndErrorEnvelope() {
        var rows = new ArrayList<Invoice>();
        var result = new InvoiceResult(rows, 0);
        rows.add(null);
        assertThat(result.rows()).isEmpty();
        fails(ErrorCode.INVALID_ARGUMENT, () -> new Response<>(null, null));
        var error = new ToolContract.Error(ErrorCode.PERMISSION_DENIED, "Access denied");
        assertThat(new Response<>(null, error).error().code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
    }
}
