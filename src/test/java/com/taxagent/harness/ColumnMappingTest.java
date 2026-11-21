package com.taxagent.harness;

import com.taxagent.harness.tool.*;
import com.taxagent.harness.tool.FileService.*;
import com.taxagent.harness.tool.ToolContract.*;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static com.taxagent.harness.ToolContractTest.fails;

/** T46: unfamiliar Excel columns are offered back for an explicit mapping; nothing is imported by guessing. */
class ColumnMappingTest {
    @TempDir Path directory;
    Path db;
    FileService files;
    final PermissionGuard.Session operator = new PermissionGuard.Session("U001");
    final PermissionGuard.Session viewer = new PermissionGuard.Session("U002");

    @BeforeEach void setup() throws Exception {
        db = directory.resolve("mapping.db"); DataInitializer.initialize(db); files = new FileService(db);
    }
    long count(String sql) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement(); var r = s.executeQuery(sql)) { return r.getLong(1); }
    }
    /** One sheet: the header in the first row, then the rows. */
    static byte[] sheet(String name, List<String> headers, Object[]... rows) throws Exception {
        try (var wb = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet(name);
            var header = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) header.createCell(i).setCellValue(headers.get(i));
            int r = 1;
            for (var values : rows) {
                var row = sheet.createRow(r++);
                for (int i = 0; i < values.length; i++) {
                    if (values[i] instanceof Number n) row.createCell(i).setCellValue(n.doubleValue());
                    else row.createCell(i).setCellValue((String) values[i]);
                }
            }
            wb.write(out); return out.toByteArray();
        }
    }
    static final List<String> UNFAMILIAR = List.of("票号", "日期", "开票单位", "品名", "含税金额", "附注");
    static Map<String, Integer> invoiceColumns() {
        return Map.of("ID", 0, "DATE", 1, "SELLER", 2, "ITEM", 3, "AMOUNT", 4, "REMARKS", 5);
    }

    @Test void unfamiliarHeaderIsOfferedThenImportedOnlyWithTheAccountantsMapping() throws Exception {
        byte[] book = sheet("票据", UNFAMILIAR,
                new Object[]{"F201", "2026-10-09", "合成供应商", "办公用品", 88.5, ""},
                new Object[]{"F002", "2026-09-08", "合成供应商", "测试", 1.0, "试图覆盖"});
        var first = files.upload(operator, "C001", "陌生表头.xlsx", book);
        assertThat(first.status()).isEqualTo(Status.IMPORTED);
        assertThat(first.invoicesImported()).isZero();
        assertThat(first.tables()).singleElement().satisfies(t -> {
            assertThat(t.sheet()).isEqualTo("票据");
            assertThat(t.headerRow()).isEqualTo(1);
            assertThat(t.reason()).isEqualTo("UNRECOGNIZED_HEADER");
            assertThat(t.suggestedKind()).isEqualTo("INVOICE");
            assertThat(t.suggested()).containsEntry("ID", 0).containsEntry("DATE", 1).containsEntry("SELLER", 2)
                    .containsEntry("ITEM", 3).containsEntry("AMOUNT", 4);
            assertThat(t.headers()).containsExactlyElementsOf(UNFAMILIAR);
            assertThat(t.samples()).hasSize(2);
        });

        var mapped = files.upload(operator, "C001", "陌生表头.xlsx", book,
                List.of(new SheetMapping("票据", "INVOICE", 1, invoiceColumns())));
        assertThat(mapped.status()).isEqualTo(Status.IMPORTED);
        assertThat(mapped.file().id()).isEqualTo(first.file().id()); // Same stored version, no duplicate file.
        assertThat(mapped.invoicesImported()).isEqualTo(1);
        assertThat(mapped.conflicts()).extracting(RowIssue::recordId).containsExactly("F002");
        assertThat(count("SELECT count(*) FROM uploaded_files")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM file_column_mappings WHERE sheet='票据' AND kind='INVOICE' AND mapped_by='U001'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM record_sources WHERE record_id='F201' AND location='票据!第2行'")).isEqualTo(1);
        var october = new ReadOnlyFinanceTools(db).queryInvoices(operator, new Query("C001", "2026-10"));
        assertThat(october.totalCents()).isEqualTo(8850);
        var f002 = new ReadOnlyFinanceTools(db).queryInvoices(operator, new Query("C001", "2026-09")).rows().stream()
                .filter(i -> i.key().invoiceId().equals("F002")).findFirst().orElseThrow();
        assertThat(f002.remarks()).isEmpty(); // An existing record is never overwritten by a mapped import.

        // The same mapping again changes nothing: the row now exists and is reported as a conflict.
        var again = files.upload(operator, "C001", "陌生表头.xlsx", book, List.of(new SheetMapping("票据", "INVOICE", 1, invoiceColumns())));
        assertThat(again.invoicesImported()).isZero();
        assertThat(again.conflicts()).extracting(RowIssue::recordId).containsExactlyInAnyOrder("F201", "F002");
    }

    @Test void incompleteKnownHeaderIsRejectedButOfferedForMapping() throws Exception {
        byte[] book = sheet("发票", List.of("发票编号", "开票日期", "销售方名称", "项目名称", "总价"),
                new Object[]{"F202", "2026-10-10", "合成供应商", "快递服务", 30.0});
        var rejected = files.upload(operator, "C001", "缺列.xlsx", book);
        assertThat(rejected.status()).isEqualTo(Status.REJECTED);
        assertThat(rejected.errors()).extracting(RowIssue::reason).singleElement().asString().startsWith("MISSING_COLUMNS");
        assertThat(rejected.tables()).singleElement().satisfies(t -> {
            assertThat(t.reason()).isEqualTo("MISSING_COLUMNS");
            assertThat(t.suggestedKind()).isEqualTo("INVOICE");
            assertThat(t.suggested()).containsEntry("ID", 0).doesNotContainKey("AMOUNT");
        });
        assertThat(count("SELECT count(*) FROM uploaded_files")).isZero();

        var mapped = files.upload(operator, "C001", "缺列.xlsx", book,
                List.of(new SheetMapping("发票", "INVOICE", 1, Map.of("ID", 0, "DATE", 1, "SELLER", 2, "ITEM", 3, "AMOUNT", 4))));
        assertThat(mapped.status()).isEqualTo(Status.IMPORTED);
        assertThat(mapped.invoicesImported()).isEqualTo(1);
    }

    @Test void bankSheetsMapToo() throws Exception {
        byte[] book = sheet("对账单", List.of("交易流水号", "记账日期", "借贷方向", "发生额", "对方户名", "摘要"),
                new Object[]{"B901", "2026-10-11", "支出", 12.34, "合成快递", "寄件"});
        var first = files.upload(operator, "C001", "对账单.xlsx", book);
        assertThat(first.tables()).singleElement().satisfies(t -> {
            assertThat(t.suggestedKind()).isEqualTo("BANK");
            assertThat(t.suggested()).containsEntry("ID", 0).containsEntry("DATE", 1).containsEntry("DIRECTION", 2)
                    .containsEntry("AMOUNT", 3).containsEntry("COUNTERPARTY", 4).containsEntry("REMARKS", 5);
        });
        var mapped = files.upload(operator, "C001", "对账单.xlsx", book, List.of(new SheetMapping("对账单", "BANK", 1, first.tables().getFirst().suggested())));
        assertThat(mapped.bankEntriesImported()).isEqualTo(1);
        assertThat(new ReadOnlyFinanceTools(db).queryBankEntries(operator, new Query("C001", "2026-10")).expenseCents()).isEqualTo(1234);
    }

    @Test void unknownWordingIsOfferedWhenRowsAreRecordsButNeverForSummaries() throws Exception {
        // No known wording at all, but every row has its own id, a date and an amount: offered for mapping.
        byte[] records = sheet("台账", List.of("单号", "时刻", "往来单位", "明细", "合计"),
                new Object[]{"F401", "2026-10-03", "合成供应商", "快递", 12.5},
                new Object[]{"F402", "2026-10-04", "合成供应商", "打印", 30.25}); // 30.0 would display as "30", not an amount
        assertThat(files.upload(operator, "C001", "台账.xlsx", records).tables()).singleElement()
                .satisfies(t -> assertThat(t.reason()).isEqualTo("UNRECOGNIZED_HEADER"));
        // A monthly summary has dates and amounts too, but repeats its enterprise id: not a table to import.
        byte[] summary = sheet("汇总", List.of("企业", "月份", "张数", "合计"),
                new Object[]{"C001", "2026-08-01", 10, 9046.35},
                new Object[]{"C001", "2026-09-01", 12, 100.5});
        assertThat(files.upload(operator, "C001", "汇总.xlsx", summary).tables()).isEmpty();
    }

    @Test void invalidMappingsAreRefusedBeforeAnythingIsRead() throws Exception {
        byte[] book = sheet("票据", UNFAMILIAR, new Object[]{"F203", "2026-10-09", "合成供应商", "办公用品", 1.0, ""});
        var twice = new HashMap<>(invoiceColumns()); twice.put("REMARKS", 0);
        fails(ErrorCode.INVALID_ARGUMENT, () -> files.upload(operator, "C001", "x.xlsx", book, List.of(new SheetMapping("票据", "INVOICE", 1, twice))));
        var missing = new HashMap<>(invoiceColumns()); missing.remove("AMOUNT");
        fails(ErrorCode.INVALID_ARGUMENT, () -> files.upload(operator, "C001", "x.xlsx", book, List.of(new SheetMapping("票据", "INVOICE", 1, missing))));
        fails(ErrorCode.INVALID_ARGUMENT, () -> files.upload(operator, "C001", "x.xlsx", book, List.of(new SheetMapping("票据", "BANK", 1, invoiceColumns()))));
        fails(ErrorCode.INVALID_ARGUMENT, () -> files.upload(operator, "C001", "x.xlsx", book, List.of(new SheetMapping("票据", "INVOICE", 0, invoiceColumns()))));
        byte[] pdf = FileServiceTest.pdf("text");
        fails(ErrorCode.INVALID_ARGUMENT, () -> files.upload(operator, "C001", "规则.pdf", pdf, List.of(new SheetMapping("票据", "INVOICE", 1, invoiceColumns()))));
        fails(ErrorCode.PERMISSION_DENIED, () -> files.upload(viewer, "C001", "x.xlsx", book, List.of(new SheetMapping("票据", "INVOICE", 1, invoiceColumns()))));
        var noSheet = files.upload(operator, "C001", "x.xlsx", book, List.of(new SheetMapping("不存在", "INVOICE", 1, invoiceColumns())));
        assertThat(noSheet.status()).isEqualTo(Status.REJECTED);
        assertThat(noSheet.errors()).extracting(RowIssue::reason).contains("MAPPED_SHEET_NOT_FOUND");
        assertThat(count("SELECT count(*) FROM uploaded_files")).isZero();
        assertThat(count("SELECT count(*) FROM invoices WHERE id='F203'")).isZero();
    }
}
