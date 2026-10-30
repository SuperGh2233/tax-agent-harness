package com.taxagent.harness;

import com.taxagent.harness.tool.*;
import com.taxagent.harness.tool.ToolContract.*;
import com.taxagent.harness.tool.InvoiceWriteService.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static com.taxagent.harness.ToolContractTest.fails;
import static com.taxagent.harness.tool.InvoiceWriteService.authorizeExplicitRequest;

class InvoiceWriteServiceTest {
    @TempDir Path directory;
    Path db;
    InvoiceWriteService service;
    final PermissionGuard.Session user = new PermissionGuard.Session("U001");
    @BeforeEach void setup() throws Exception {
        db = directory.resolve("写入 data.db"); DataInitializer.initialize(db); service = new InvoiceWriteService(db);
    }
    InvoiceUpdate update(String enterprise, String invoice, Map<String,String> changes) {
        return new InvoiceUpdate(new InvoiceKey(enterprise, invoice), changes);
    }
    InvoiceUpdate purpose(String enterprise, String value) { return update(enterprise,"F002",Map.of("purpose",value)); }
    Authorization auth(String id, InvoiceUpdate update) { return authorizeExplicitRequest(user,id,update,Intent.SAVE_NOW); }
    UpdateResult save(String id, InvoiceUpdate update) { return service.execute(user,id,update,auth(id,update)); }
    void sql(String sql) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement()) { s.execute(sql); }
    }
    long count() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement();
             var r = s.executeQuery("SELECT count(*) FROM invoice_operations")) { return r.getLong(1); }
    }
    Invoice invoice(String enterprise, String id) {
        return new ReadOnlyFinanceTools(db).queryInvoices(user,new Query(enterprise,"2026-09"))
                .rows().stream().filter(i -> i.key().invoiceId().equals(id)).findFirst().orElseThrow();
    }
    @Test void writesBothEnterprisesAndReadbackPreservesOtherFields() {
        for (String enterprise : List.of("C001","C002")) {
            var before = invoice(enterprise,"F002");
            var result = save(enterprise,purpose(enterprise,"实际采购用途 '中文'"));
            assertThat(result.verified()).isTrue();
            var actual = invoice(enterprise,"F002");
            assertThat(actual).isEqualTo(new Invoice(before.key(),before.invoiceDate(),before.seller(),before.item(),
                    before.amountCents(),result.purpose(),before.remarks()));
        }
    }
    @Test void companyCompositeKeyAndOptionalRemarks() {
        var other = invoice("C002","F002");
        save("a",purpose("C001","用户明确用途"));
        assertThat(invoice("C002","F002")).isEqualTo(other);
        var u = update("C001","F002",Map.of("remarks","仅备注")); save("b",u);
        assertThat(invoice("C001","F002").purpose()).isEqualTo("用户明确用途");
    }
    @Test void belowThresholdExplicitPurposeAllowed() {
        var u = update("C002","F001",Map.of("purpose","主动补充"));
        assertThat(invoice("C002","F001").amountCents()).isLessThan(100000);
        assertThat(save("low",u).verified()).isTrue();
    }
    @Test void permissionMatrixAndMissingIdentity() {
        for (String enterprise : List.of("C001","C002")) {
            var reader = new PermissionGuard.Session("U002"); var u = purpose(enterprise,"用途");
            var a = authorizeExplicitRequest(reader,"reader",u,Intent.SAVE_NOW);
            fails(ErrorCode.PERMISSION_DENIED, () -> service.execute(reader,"reader",u,a));
        }
        var u = purpose("C001","用途");
        fails(ErrorCode.AUTHENTICATION_REQUIRED, () -> authorizeExplicitRequest(null,"a",u,Intent.SAVE_NOW));
        var unknown = new PermissionGuard.Session("unknown");
        fails(ErrorCode.PERMISSION_DENIED, () -> service.execute(unknown,"a",u,authorizeExplicitRequest(unknown,"a",u,Intent.SAVE_NOW)));
    }
    @Test void unknownCompanyAndMissingInvoice() {
        fails(ErrorCode.PERMISSION_DENIED, () -> save("a",purpose("UNKNOWN","用途")));
        fails(ErrorCode.NOT_FOUND, () -> save("b",update("C001","missing",Map.of("purpose","用途"))));
    }
    @Test void whitelistInvalidInputsAndUnforgeableDecode() {
        for (String field : List.of("amount_cents","invoice_date","enterprise_id","id","seller","item","bank_entries"))
            fails(ErrorCode.FIELD_NOT_ALLOWED, () -> update("C001","F002",Map.of(field,"evil")));
        fails(ErrorCode.INVALID_ARGUMENT, () -> update("C001","F002",Map.of()));
        var nullValue = new HashMap<String,String>(); nullValue.put("purpose",null);
        fails(ErrorCode.INVALID_ARGUMENT, () -> update("C001","F002",nullValue));
        var nullKey = new HashMap<String,String>(); nullKey.put(null,"value");
        fails(ErrorCode.INVALID_ARGUMENT, () -> update("C001","F002",nullKey));
        fails(ErrorCode.INVALID_ARGUMENT, () -> auth(" ",purpose("C001","用途")));
        fails(ErrorCode.INVALID_ARGUMENT, () -> auth("x",purpose("C001","a\0b")));
        fails(ErrorCode.INVALID_ARGUMENT, () -> auth("x",purpose(" C001","用途")));
        fails(ErrorCode.INVALID_ARGUMENT, () -> ToolContract.decode("{}",Authorization.class));
        fails(ErrorCode.INVALID_ARGUMENT, () -> ToolContract.decode("{\"key\":{\"enterprise_id\":\"C001\",\"invoice_id\":\"F002\"},\"changes\":{\"purpose\":\"用途\"},\"confirmed\":true}",InvoiceUpdate.class));
    }
    @Test void explanationAndBindingCannotAuthorizeWrite() {
        var u = purpose("C001","说明用途");
        fails(ErrorCode.CONFIRMATION_REQUIRED, () -> authorizeExplicitRequest(user,"a",u,Intent.EXPLANATION));
        fails(ErrorCode.CONFIRMATION_REQUIRED, () -> service.execute(user,"a",u,null));
        var a = auth("a",u);
        fails(ErrorCode.CONFIRMATION_REQUIRED, () -> service.execute(user,"b",u,a));
        fails(ErrorCode.CONFIRMATION_REQUIRED, () -> service.execute(user,"a",purpose("C002","说明用途"),a));
        fails(ErrorCode.CONFIRMATION_REQUIRED, () -> service.execute(user,"a",purpose("C001","不同内容"),a));
        fails(ErrorCode.CONFIRMATION_REQUIRED, () -> service.execute(new PermissionGuard.Session("U002"),"a",u,a));
        assertThat(service.execute(user,"a",u,a).verified()).isTrue();
    }
    @Test void explicitClearingPreservesExactWhitespace() {
        var u = update("C001","F002",Map.of("purpose","  ","remarks",""));
        fails(ErrorCode.CONFIRMATION_REQUIRED, () -> auth("a",u));
        var result = service.execute(user,"a",u,authorizeExplicitRequest(user,"a",u,Intent.CLEAR_NOW));
        assertThat(result.purpose()).isEqualTo("  "); assertThat(result.remarks()).isEmpty();
    }
    @Test void sameValueDoesNotExecuteUpdateAndSameOperationNeverReexecutes() throws Exception {
        var u = purpose("C001","明确用途"); save("a",u);
        sql("CREATE TRIGGER no_update BEFORE UPDATE ON invoices BEGIN SELECT RAISE(ABORT,'must not update'); END");
        assertThat(save("a",u).verified()).isTrue();
        assertThat(save("b",u).verified()).isTrue();
        assertThat(count()).isEqualTo(2);
    }
    @Test void consumedAuthorizationAndIdPayloadConflicts() throws Exception {
        var u = purpose("C001","用途"); var a = auth("a",u); service.execute(user,"a",u,a);
        fails(ErrorCode.CONFLICT, () -> service.execute(user,"a",u,a));
        fails(ErrorCode.CONFLICT, () -> save("a",purpose("C001","不同用途")));
        fails(ErrorCode.CONFLICT, () -> save("a",purpose("C002","用途")));
        assertThat(count()).isEqualTo(1);
    }
    @Test void authorizationDoesNotCachePermissions() throws Exception {
        var u = purpose("C001","用途"); var a = auth("a",u);
        sql("UPDATE permissions SET permission='仅查看' WHERE user_id='U001' AND enterprise_id='C001'");
        fails(ErrorCode.PERMISSION_DENIED, () -> service.execute(user,"a",u,a));
        assertThat(count()).isZero();
    }
    @Test void currentRulesRecheckedAfterAuthorization() throws Exception {
        var u = purpose("C001","用途"); var a = auth("a",u);
        sql("UPDATE rules SET content='未知变更语义' WHERE id='R002' AND version='2.0'");
        fails(ErrorCode.UNSUPPORTED_SCOPE, () -> service.execute(user,"a",u,a));
        assertThat(count()).isZero();
    }
    @Test void disabledAndAmbiguousRulesRejected() throws Exception {
        sql("UPDATE rules SET status='启用' WHERE id='R002' AND version='1.0'");
        fails(ErrorCode.CONFLICT, () -> save("a",purpose("C001","用途")));
        sql("UPDATE rules SET status='已停用' WHERE id='R002'");
        fails(ErrorCode.UNSUPPORTED_SCOPE, () -> save("b",purpose("C001","用途")));
    }
    @Test void ledgerFailureRollsBackBusinessAndReadbackMismatchRollsBack() throws Exception {
        var old = invoice("C001","F002"); var u = purpose("C001","用途");
        sql("CREATE TRIGGER fail_log BEFORE INSERT ON invoice_operations BEGIN SELECT RAISE(ABORT,'failure'); END");
        fails(ErrorCode.STORAGE_ERROR, () -> save("a",u));
        assertThat(invoice("C001","F002")).isEqualTo(old); assertThat(count()).isZero();
        sql("DROP TRIGGER fail_log");
        sql("CREATE TRIGGER alter_saved AFTER UPDATE ON invoices BEGIN UPDATE invoices SET purpose='错误实际值' WHERE enterprise_id=NEW.enterprise_id AND id=NEW.id; END");
        fails(ErrorCode.CONFLICT, () -> save("b",u));
        assertThat(invoice("C001","F002")).isEqualTo(old); assertThat(count()).isZero();
    }
    @Test void lostResponseRecoveryAfterRestartOnlyReadsAndDetectsDrift() throws Exception {
        var u = purpose("C001","用途");
        save("lost-response",u); // Simulate caller timeout: discard response, then restart service.
        var restarted = new InvoiceWriteService(db);
        sql("CREATE TRIGGER no_update BEFORE UPDATE ON invoices BEGIN SELECT RAISE(ABORT,'must not retry'); END");
        assertThat(restarted.recover(user,"lost-response",u)).get().extracting(UpdateResult::verified).isEqualTo(true);
        assertThat(restarted.recover(user,"never-committed",u)).isEmpty();
        fails(ErrorCode.CONFLICT, () -> restarted.recover(user,"lost-response",purpose("C001","另一内容")));
        sql("DROP TRIGGER no_update"); sql("UPDATE invoices SET purpose='后续用户修改' WHERE enterprise_id='C001' AND id='F002'");
        fails(ErrorCode.CONFLICT, () -> restarted.recover(user,"lost-response",u));
        fails(ErrorCode.CONFLICT, () -> save("lost-response",u));
        assertThat(invoice("C001","F002").purpose()).isEqualTo("后续用户修改");
        assertThat(count()).isEqualTo(1);
    }
    @Test void storageFailureDoesNotCreateMissingDatabase() {
        var missing = directory.resolve("missing.db");
        fails(ErrorCode.STORAGE_ERROR, () -> new InvoiceWriteService(missing));
        assertThat(missing).doesNotExist();
    }
    @Test void callerTimeoutQueriesCommittedOperationInsteadOfRetrying() throws Exception {
        var committed = new java.util.concurrent.CountDownLatch(1);
        var deliver = new java.util.concurrent.CountDownLatch(1);
        var u = purpose("C001","超时保存"); var a = auth("timeout",u);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var response = executor.submit(() -> {
                var result = service.execute(user,"timeout",u,a);
                committed.countDown(); deliver.await(); return result;
            });
            try {
                assertThat(committed.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> response.get(10, java.util.concurrent.TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                sql("CREATE TRIGGER no_retry BEFORE UPDATE ON invoices BEGIN SELECT RAISE(ABORT,'no retry'); END");
                assertThat(service.recover(user,"timeout",u)).get().extracting(UpdateResult::verified).isEqualTo(true);
                fails(ErrorCode.CONFLICT, () -> service.execute(user,"timeout",u,a));
                assertThat(count()).isEqualTo(1);
            } finally { deliver.countDown(); }
            assertThat(response.get(5, java.util.concurrent.TimeUnit.SECONDS).verified()).isTrue();
        }
    }
    @Test void ledgerTriggerCannotChangeSavedInvoiceAfterVerification() throws Exception {
        var old = invoice("C001","F002");
        sql("CREATE TRIGGER alter_after_log AFTER INSERT ON invoice_operations BEGIN UPDATE invoices SET remarks='篡改' WHERE enterprise_id=NEW.enterprise_id AND id=NEW.invoice_id; END");
        fails(ErrorCode.CONFLICT, () -> save("a",purpose("C001","用途")));
        assertThat(invoice("C001","F002")).isEqualTo(old); assertThat(count()).isZero();
    }
}
