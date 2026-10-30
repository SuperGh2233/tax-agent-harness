package com.taxagent.harness;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class DataInitializerTest {
    @TempDir Path directory;
    Path database;

    @BeforeEach void initialize() throws Exception {
        database = directory.resolve("runtime.db");
        DataInitializer.initialize(database);
    }

    Connection connect() throws Exception {
        var c = DriverManager.getConnection("jdbc:sqlite:" + database);
        c.createStatement().execute("PRAGMA foreign_keys=ON");
        return c;
    }

    long scalar(String sql) throws Exception {
        try (var c = connect(); var s = c.createStatement(); var r = s.executeQuery(sql)) {
            return r.getLong(1);
        }
    }

    @Test void originalSourcesMatchEverySeedField() throws Exception {
        var process = new ProcessBuilder("python3", "-B", "scripts/official_seed.py", "--check")
                .redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
        assertThat(output).contains("PASS");
    }

    @Test void countsAndEmptyFields() throws Exception {
        for (var pair : new String[][]{{"enterprises","2"},{"users","2"},{"permissions","4"},
                {"invoices","40"},{"bank_entries","48"},{"rules","5"},{"rule_enterprises","7"}}) {
            assertThat(scalar("SELECT count(*) FROM " + pair[0])).isEqualTo(Long.parseLong(pair[1]));
        }
        assertThat(scalar("SELECT count(DISTINCT id) FROM rules")).isEqualTo(4);
        assertThat(scalar("SELECT count(*) FROM invoices WHERE purpose IS NULL OR remarks IS NULL")).isZero();
        assertThat(scalar("SELECT count(*) FROM invoices WHERE purpose='' ")).isGreaterThan(0);
        assertThat(scalar("SELECT count(*) FROM invoices WHERE remarks='' ")).isGreaterThan(0);
        assertThat(scalar("SELECT count(*) FROM invoices WHERE typeof(amount_cents)<>'integer'")).isZero();
    }

    @ParameterizedTest
    @CsvSource({"C001,2026-07,0,0,0", "C001,2026-08,904635,572099,1485075",
            "C001,2026-09,962206,718074,1300050", "C002,2026-07,0,0,0",
            "C002,2026-08,1366705,934110,1880025", "C002,2026-09,1266724,808724,1370075"})
    void monthlyTotals(String enterprise, String month, long invoices, long expenses, long income) throws Exception {
        var filter = " WHERE enterprise_id='" + enterprise + "' AND ";
        assertThat(scalar("SELECT coalesce(sum(amount_cents),0) FROM invoices" + filter + "invoice_date LIKE '" + month + "%'")).isEqualTo(invoices);
        for (var direction : new String[]{"支出", "收入"}) {
            var where = filter + "transaction_date LIKE '" + month + "%' AND direction='" + direction + "'";
            assertThat(scalar("SELECT coalesce(sum(amount_cents),0) FROM bank_entries" + where)).isEqualTo(direction.equals("支出") ? expenses : income);
            assertThat(scalar("SELECT count(*) FROM bank_entries" + where)).isEqualTo(month.endsWith("07") ? 0 : direction.equals("支出") ? 8 : 4);
        }
        assertThat(scalar("SELECT count(*) FROM invoices" + filter + "invoice_date LIKE '" + month + "%'")).isEqualTo(month.endsWith("07") ? 0 : 10);
    }

    @Test void septemberExpectedPurposesOnlyInTest() throws Exception {
        for (var enterprise : new String[]{"C001", "C002"}) {
            var missing = new ArrayList<String>();
            try (var c = connect(); var s = c.prepareStatement("SELECT id,amount_cents,purpose FROM invoices WHERE enterprise_id=? AND invoice_date LIKE '2026-09%' ORDER BY rowid")) {
                s.setString(1, enterprise);
                try (var r = s.executeQuery()) {
                    while (r.next()) if (r.getString(3).isBlank() && (enterprise.equals("C001") || r.getLong(2) >= 100000)) missing.add(r.getString(1));
                }
            }
            assertThat(missing).containsExactly(enterprise.equals("C001") ? new String[]{"F002","F004","F003","F006","F009"} : new String[]{"F002","F005","F006"});
        }
        assertThat(scalar("SELECT amount_cents FROM invoices WHERE enterprise_id='C002' AND id='F002'")).isEqualTo(100000);
    }

    @Test void permissionsAndRuleMetadata() throws Exception {
        try (var c = connect(); var s = c.createStatement()) {
            var actual = new ArrayList<String>();
            try (var r = s.executeQuery("SELECT user_id||':'||enterprise_id||':'||permission FROM permissions ORDER BY user_id,enterprise_id")) {
                while (r.next()) actual.add(r.getString(1));
            }
            assertThat(actual).containsExactly("U001:C001:可查看并修改","U001:C002:可查看并修改","U002:C001:仅查看","U002:C002:无权访问");
            actual.clear();
            try (var r = s.executeQuery("SELECT id||':'||version||':'||name||':'||effective_from||':'||status FROM rules ORDER BY id,version")) {
                while (r.next()) actual.add(r.getString(1));
            }
            assertThat(actual).containsExactly("R001:1.0:金额统计说明:2026-01-01:启用", "R002:1.0:甲企业发票用途规则:2026-08-01:已停用", "R002:2.0:甲企业发票用途规则:2026-09-01:启用", "R003:1.0:乙企业发票用途规则:2026-08-01:启用", "R004:1.0:银行资料说明:2026-01-01:启用");
        }
    }

    @Test void replayPreservesUserChangesAndCompositeKeys() throws Exception {
        DataInitializer.initialize(database);
        assertThat(scalar("SELECT count(*) FROM invoices")).isEqualTo(40);
        try (var c = connect(); var s = c.createStatement()) {
            s.executeUpdate("UPDATE invoices SET purpose='用户后续填写',remarks='保留' WHERE enterprise_id='C001' AND id='F002'");
        }
        HarnessApplication.main(new String[]{"--initialize-data", "--database=" + database});
        assertThat(scalar("SELECT count(*) FROM invoices WHERE enterprise_id='C001' AND id='F002' AND purpose='用户后续填写' AND remarks='保留'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM invoices WHERE enterprise_id='C002' AND id='F002' AND purpose=''")).isEqualTo(1);
        countsAndEmptyFields();
    }

    @Test void constraintsAndFailureRollback() throws Exception {
        try (var c = connect(); var s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeUpdate("INSERT INTO invoices SELECT * FROM invoices LIMIT 1")).isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(() -> s.executeUpdate("UPDATE bank_entries SET amount_cents=-1")).isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(() -> s.executeUpdate("UPDATE permissions SET permission='任意权限'")).isInstanceOf(java.sql.SQLException.class);
            s.executeUpdate("DELETE FROM invoices WHERE enterprise_id='C001' AND id='F001'");
            s.execute("CREATE TRIGGER reject_seed BEFORE INSERT ON bank_entries BEGIN SELECT RAISE(ABORT,'test failure'); END");
        }
        assertThatThrownBy(() -> DataInitializer.initialize(database)).isInstanceOf(java.sql.SQLException.class);
        assertThat(scalar("SELECT count(*) FROM invoices")).isEqualTo(39);
    }
}
