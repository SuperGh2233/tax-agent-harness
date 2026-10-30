package com.taxagent.harness;

import com.taxagent.harness.tool.*;
import com.taxagent.harness.tool.PermissionGuard.Session;
import com.taxagent.harness.tool.ToolContract.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.HashSet;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;
import static com.taxagent.harness.ToolContractTest.fails;

class ReadOnlyFinanceToolsTest {
    @TempDir Path directory;
    Path database;
    ReadOnlyFinanceTools tools;
    final Session user = new Session("U001");

    @BeforeEach void initialize() throws Exception {
        database = directory.resolve("运行 data.db");
        DataInitializer.initialize(database);
        tools = new ReadOnlyFinanceTools(database);
    }

    void execute(String sql) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + database); var s = c.createStatement()) {
            s.execute(sql);
        }
    }

    @ParameterizedTest
    @CsvSource({"C001,2026-07,0,0,0", "C001,2026-08,904635,572099,1485075",
            "C001,2026-09,962206,718074,1300050", "C002,2026-07,0,0,0",
            "C002,2026-08,1366705,934110,1880025", "C002,2026-09,1266724,808724,1370075"})
    void monthlyRecordsAndTotals(String enterprise, String month, long total, long expense, long income) {
        var query = new Query(enterprise, month);
        var invoices = tools.queryInvoices(user, query);
        var bank = tools.queryBankEntries(user, query);
        boolean empty = month.endsWith("07");
        assertThat(invoices.totalCents()).isEqualTo(total);
        assertThat(bank.incomeCents()).isEqualTo(income);
        assertThat(bank.expenseCents()).isEqualTo(expense);
        assertThat(invoices.rows()).hasSize(empty ? 0 : 10).allSatisfy(row -> {
            assertThat(row.key().enterpriseId()).isEqualTo(enterprise);
            assertThat(YearMonth.from(row.invoiceDate()).toString()).isEqualTo(month);
            assertThat(row.seller()).isNotBlank();
            assertThat(row.item()).isNotBlank();
            assertThat(row.purpose()).isNotNull();
            assertThat(row.remarks()).isNotNull();
        });
        assertThat(bank.rows()).hasSize(empty ? 0 : 12).allSatisfy(row -> {
            assertThat(row.key().enterpriseId()).isEqualTo(enterprise);
            assertThat(YearMonth.from(row.transactionDate()).toString()).isEqualTo(month);
            assertThat(row.counterparty()).isNotBlank();
        });
        assertThat(bank.rows().stream().filter(r -> r.direction() == Direction.支出)).hasSize(empty ? 0 : 8);
        assertThat(bank.rows().stream().filter(r -> r.direction() == Direction.收入)).hasSize(empty ? 0 : 4);
        assertThat(invoices.rows().stream().mapToLong(Invoice::amountCents).sum()).isEqualTo(total);
    }

    @ParameterizedTest
    @CsvSource({"U001,C001,true", "U001,C002,true", "U002,C001,true", "U002,C002,false",
            "UNKNOWN,C001,false", "U001,UNKNOWN,false", "U001,C001' OR 1=1 --,false"})
    void permissionsForBothQueries(String identity, String enterprise, boolean allowed) {
        var session = new Session(identity);
        var query = new Query(enterprise, "2026-09");
        if (allowed) {
            assertThat(tools.queryInvoices(session, query).rows()).hasSize(10);
            assertThat(tools.queryBankEntries(session, query).rows()).hasSize(12);
        } else {
            fails(ErrorCode.PERMISSION_DENIED, () -> tools.queryInvoices(session, query));
            fails(ErrorCode.PERMISSION_DENIED, () -> tools.queryBankEntries(session, query));
        }
    }

    @Test void rechecksPermissionsAndRejectsUntrustedInput() throws Exception {
        var query = new Query("C001", "2026-07");
        tools.queryInvoices(user, query);
        tools.queryBankEntries(user, query);
        execute("DELETE FROM permissions WHERE user_id='U001' AND enterprise_id='C001'");
        fails(ErrorCode.PERMISSION_DENIED, () -> tools.queryInvoices(user, query));
        fails(ErrorCode.PERMISSION_DENIED, () -> tools.queryBankEntries(user, query));
        fails(ErrorCode.AUTHENTICATION_REQUIRED, () -> tools.queryInvoices(null, query));
        fails(ErrorCode.AUTHENTICATION_REQUIRED, () -> tools.queryBankEntries(null, query));
        fails(ErrorCode.INVALID_ARGUMENT, () -> tools.queryInvoices(user, null));
        fails(ErrorCode.INVALID_ARGUMENT, () -> tools.queryBankEntries(user, null));
        fails(ErrorCode.INVALID_ARGUMENT, () -> ToolContract.decode(
                "{\"enterprise_id\":\"C002\",\"month\":\"2026-09\",\"user_id\":\"U001\"}", Query.class));
    }

    @Test void compositeKeysRemainDistinct() {
        var invoices = new HashSet<InvoiceKey>();
        var bank = new HashSet<BankKey>();
        for (var enterprise : new String[]{"C001", "C002"}) {
            invoices.addAll(tools.queryInvoices(user, new Query(enterprise, "2026-09")).rows().stream().map(Invoice::key).toList());
            bank.addAll(tools.queryBankEntries(user, new Query(enterprise, "2026-09")).rows().stream().map(BankEntry::key).toList());
        }
        assertThat(invoices).hasSize(20).contains(new InvoiceKey("C001", "F001"), new InvoiceKey("C002", "F001"));
        assertThat(bank).hasSize(24).contains(new BankKey("C001", "T001"), new BankKey("C002", "T001"));
    }

    @Test void readsCurrentDataAndExactMonthBoundariesWithoutWriting() throws Exception {
        execute("UPDATE invoices SET amount_cents=123,purpose='当前用途',remarks='当前备注',invoice_date='2026-07-31' WHERE enterprise_id='C001' AND id='F001'");
        execute("UPDATE bank_entries SET amount_cents=456,transaction_date='2026-07-01' WHERE enterprise_id='C001' AND id='T001'");
        var before = Files.readAllBytes(database);
        var query = new Query("C001", "2026-07");
        var invoice = tools.queryInvoices(user, query);
        assertThat(invoice.totalCents()).isEqualTo(123);
        assertThat(invoice.rows()).singleElement().satisfies(row -> {
            assertThat(row.invoiceDate()).isEqualTo(LocalDate.of(2026, 7, 31));
            assertThat(row.purpose()).isEqualTo("当前用途");
            assertThat(row.remarks()).isEqualTo("当前备注");
        });
        var bank = tools.queryBankEntries(user, query);
        assertThat(bank.rows()).hasSize(1);
        assertThat(bank.incomeCents() + bank.expenseCents()).isEqualTo(456);
        assertThat(tools.queryInvoices(user, new Query("C001", "2026-08")).rows()).hasSize(10);
        assertThat(tools.queryInvoices(user, new Query("C002", "2026-07")).rows()).isEmpty();
        assertThat(Files.readAllBytes(database)).isEqualTo(before);
    }

    @Test void storageFailuresDoNotBecomeEmptyResults() throws Exception {
        execute("DROP TABLE invoices");
        storageFailure(() -> tools.queryInvoices(user, new Query("C001", "2026-07")));
        execute("DROP TABLE bank_entries");
        storageFailure(() -> tools.queryBankEntries(user, new Query("C001", "2026-07")));
        var missing = directory.resolve("missing.db");
        var absent = new ReadOnlyFinanceTools(missing);
        storageFailure(() -> absent.queryInvoices(user, new Query("C001", "2026-07")));
        storageFailure(() -> absent.queryBankEntries(user, new Query("C001", "2026-07")));
        assertThat(missing).doesNotExist();
        execute("DROP TABLE permissions");
        storageFailure(() -> tools.queryInvoices(user, new Query("C001", "2026-07")));
    }

    void storageFailure(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.STORAGE_ERROR);
            assertThat(e.getCause()).isInstanceOf(SQLException.class);
            assertThat(e.getMessage()).doesNotContain(database.toString(), "SELECT", "jdbc:");
        });
    }
}
