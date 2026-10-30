package com.taxagent.harness;

import com.taxagent.harness.tool.*;
import com.taxagent.harness.tool.ToolContract.*;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static com.taxagent.harness.ToolContractTest.fails;

class RuleServiceTest {
    @TempDir Path directory;
    Path db;
    RuleService service;
    final PermissionGuard.Session user = new PermissionGuard.Session("U001");
    final RuleService.ManagementContext admin = RuleService.trustedLocalManagement();
    final LocalDate september = LocalDate.of(2026, 9, 1);
    @BeforeEach void setup() throws Exception {
        db = directory.resolve("规则 data.db"); DataInitializer.initialize(db); service = new RuleService(db);
    }
    void sql(String sql) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement()) { s.execute(sql); }
    }
    PurposeResult check(String enterprise) { return service.checkInvoicePurpose(user, new PurposeCheck(enterprise, "2026-09")); }
    RuleDefinition definition(String text, LocalDate date) { return new RuleDefinition("测试规则", date, RuleStatus.启用, text); }

    @Test void officialBaselineAndTraceableContent() {
        assertThat(check("C001").missing()).extracting(InvoiceKey::invoiceId).containsExactlyInAnyOrder("F002","F004","F003","F006","F009");
        assertThat(check("C002").missing()).extracting(InvoiceKey::invoiceId).containsExactlyInAnyOrder("F002","F005","F006");
        assertThat(check("C001").rules()).containsExactly(new RuleReference(new RuleKey("R002","2.0"), "甲企业发票用途规则"));
        var all = service.queryRules(user,"C001",null);
        assertThat(all).hasSize(4).allSatisfy(r -> assertThat(r.definition().content()).isNotBlank());
        assertThat(service.queryRules(user,"C001",september)).extracting(RuleService.StoredRule::key)
                .contains(new RuleKey("R002","2.0")).doesNotContain(new RuleKey("R002","1.0"));
        assertThat(service.queryRules(user,"C001",september.minusDays(1))).extracting(RuleService.StoredRule::key)
                .doesNotContain(new RuleKey("R002","2.0"),new RuleKey("R002","1.0"));
        fails(ErrorCode.UNSUPPORTED_SCOPE, () -> new PurposeCheck("C001","2026-08"));
    }
    @Test void thresholdCurrentDataWhitespaceAndRemarks() throws Exception {
        sql("UPDATE invoices SET amount_cents=100000,purpose='   ',remarks='已有备注' WHERE enterprise_id='C002' AND id='F001'");
        assertThat(check("C002").missing()).contains(new InvoiceKey("C002","F001"));
        sql("UPDATE invoices SET amount_cents=99999 WHERE enterprise_id='C002' AND id='F001'");
        assertThat(check("C002").missing()).doesNotContain(new InvoiceKey("C002","F001"));
        sql("UPDATE invoices SET purpose='用户补充' WHERE enterprise_id='C001' AND id='F002'");
        assertThat(check("C001").missing()).hasSize(4).doesNotContain(new InvoiceKey("C001","F002"));
        assertThat(check("C002").missing()).contains(new InvoiceKey("C002","F002"));
    }
    @Test void permissionsAndNoSessionManagement() {
        var reader = new PermissionGuard.Session("U002");
        assertThat(service.checkInvoicePurpose(reader,new PurposeCheck("C001","2026-09")).missing()).hasSize(5);
        assertThat(service.queryRules(reader,"C001",null)).isNotEmpty();
        fails(ErrorCode.PERMISSION_DENIED, () -> service.queryRules(reader,"C002",null));
        fails(ErrorCode.PERMISSION_DENIED, () -> service.checkInvoicePurpose(reader,new PurposeCheck("C002","2026-09")));
        fails(ErrorCode.AUTHENTICATION_REQUIRED, () -> service.queryRules(null,"C001",null));
        for (String id : new String[]{"U001","U002","admin"})
            for (var action : new RuleAction[]{RuleAction.IMPORT,RuleAction.UPDATE,RuleAction.DISABLE})
                fails(ErrorCode.PERMISSION_DENIED, () -> service.manageRules(new PermissionGuard.Session(id),
                        new RuleRequest("C001",action,new RuleKey("R002","3.0"),
                                action == RuleAction.DISABLE ? null : definition("正文",september))));
        fails(ErrorCode.PERMISSION_DENIED, () -> service.importVersion(null,new RuleKey("X","1"),definition("正文",september),Set.of("C001")));
        fails(ErrorCode.PERMISSION_DENIED, () -> service.disableVersion(null,new RuleKey("R002","2.0")));
    }
    @Test void importEffectiveDateDisableAndPreserveVersionRecords() {
        var key = new RuleKey("NEW","1.0");
        var rule = definition("可检索的完整出处正文",september.plusDays(1));
        service.importVersion(admin,key,rule,Set.of("C001","C002"));
        assertThat(service.queryRules(user,"C001",september)).extracting(RuleService.StoredRule::key).doesNotContain(key);
        for (String enterprise : Set.of("C001","C002"))
            assertThat(service.queryRules(user,enterprise,september.plusDays(1))).contains(new RuleService.StoredRule(key,rule));
        fails(ErrorCode.CONFLICT, () -> service.importVersion(admin,key,rule,Set.of("C001")));
        service.disableVersion(admin,key);
        assertThat(service.queryRules(user,"C002",september.plusDays(1))).extracting(RuleService.StoredRule::key).doesNotContain(key);
        assertThat(service.queryRules(user,"C001",null)).filteredOn(r -> r.key().equals(key)).singleElement()
                .satisfies(r -> { assertThat(r.definition().status()).isEqualTo(RuleStatus.已停用); assertThat(r.definition().content()).isEqualTo(rule.content()); });
        fails(ErrorCode.NOT_FOUND, () -> service.disableVersion(admin,new RuleKey("MISSING","1")));
    }
    @Test void unknownEnterpriseInvalidStatusAndRollback() throws Exception {
        var key = new RuleKey("NEW","1");
        fails(ErrorCode.INVALID_ARGUMENT, () -> service.importVersion(admin,key,definition("正文",september),Set.of("C001","UNKNOWN")));
        assertThat(service.queryRules(user,"C001",null)).extracting(RuleService.StoredRule::key).doesNotContain(key);
        fails(ErrorCode.INVALID_ARGUMENT, () -> new RuleDefinition("名称",september,null,"正文"));
        fails(ErrorCode.INVALID_ARGUMENT, () -> ToolContract.decode("{\"enterprise_id\":\"C001\",\"action\":\"IMPORT\",\"key\":{\"rule_id\":\"NEW\",\"version\":\"1\"},\"definition\":{\"name\":\"名称\",\"effective_from\":\"2026-09-01\",\"status\":\"无效\",\"content\":\"正文\"}}",RuleRequest.class));
        sql("CREATE TRIGGER fail_association BEFORE INSERT ON rule_enterprises WHEN NEW.rule_id='NEW' BEGIN SELECT RAISE(ABORT,'injected'); END");
        fails(ErrorCode.STORAGE_ERROR, () -> service.importVersion(admin,key,definition("正文",september),Set.of("C001")));
        sql("DROP TRIGGER fail_association");
        // Successful retry proves both version and associations were rolled back.
        service.importVersion(admin,key,definition("正文",september),Set.of("C001"));
    }
    @Test void disabledRulesNeverFallbackAndUnknownSemanticsNeverExecute() throws Exception {
        service.disableVersion(admin,new RuleKey("R002","2.0"));
        fails(ErrorCode.UNSUPPORTED_SCOPE, () -> check("C001"));
        assertThat(service.queryRules(user,"C001",null)).hasSize(4);
        sql("UPDATE rules SET status='启用' WHERE id='R002' AND version='2.0'");
        sql("UPDATE rules SET content='改为所有发票不必填用途' WHERE id='R002' AND version='2.0'");
        fails(ErrorCode.UNSUPPORTED_SCOPE, () -> check("C001"));
        service.importVersion(admin,new RuleKey("R003","2.0"),definition("新正文",september),Set.of("C002"));
        fails(ErrorCode.CONFLICT, () -> check("C002"));
        service.disableVersion(admin,new RuleKey("R003","1.0"));
        fails(ErrorCode.UNSUPPORTED_SCOPE, () -> check("C002"));
    }
    @Test void readDoesNotMutateAndStorageFailureIsNotEmpty() throws Exception {
        byte[] before = Files.readAllBytes(db); check("C001"); service.queryRules(user,"C001",null);
        assertThat(Files.readAllBytes(db)).isEqualTo(before);
        var absent = directory.resolve("absent.db");
        fails(ErrorCode.STORAGE_ERROR, () -> new RuleService(absent).queryRules(user,"C001",null));
        assertThat(absent).doesNotExist();
        sql("DROP TABLE invoices");
        fails(ErrorCode.STORAGE_ERROR, () -> check("C001"));
    }

    @Test void missingEnterpriseRuleDoesNotFallbackOrAffectOtherEnterprise() throws Exception {
        sql("DELETE FROM rule_enterprises WHERE rule_id='R002' AND version='2.0' AND enterprise_id='C001'");
        fails(ErrorCode.UNSUPPORTED_SCOPE, () -> check("C001"));
        assertThat(check("C002").rules()).containsExactly(
                new RuleReference(new RuleKey("R003", "1.0"), "乙企业发票用途规则"));
        assertThat(check("C002").missing()).hasSize(3);
    }
}
