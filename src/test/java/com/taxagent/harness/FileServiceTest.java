package com.taxagent.harness;

import com.taxagent.harness.tool.*;
import com.taxagent.harness.tool.FileService.*;
import com.taxagent.harness.tool.ToolContract.*;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static com.taxagent.harness.ToolContractTest.fails;

class FileServiceTest {
    static final Path OFFICIAL = Path.of("赛题二：财税Harness初赛材料包-v1.1.0", "02_示例数据.xlsx");
    @TempDir Path directory;
    Path db;
    FileService files;
    final PermissionGuard.Session operator = new PermissionGuard.Session("U001");
    final PermissionGuard.Session viewer = new PermissionGuard.Session("U002");

    @BeforeEach void setup() throws Exception {
        db = directory.resolve("files.db"); DataInitializer.initialize(db); files = new FileService(db);
    }
    void sql(String sql) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement()) { s.execute(sql); }
    }
    long count(String sql) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement(); var r = s.executeQuery(sql)) { return r.getLong(1); }
    }
    String totals(String enterprise, String month) {
        var tools = new ReadOnlyFinanceTools(db);
        var invoices = tools.queryInvoices(operator, new Query(enterprise, month));
        var bank = tools.queryBankEntries(operator, new Query(enterprise, month));
        return invoices.rows().size() + "/" + invoices.totalCents() + "/" + bank.rows().size() + "/" + bank.expenseCents() + "/" + bank.incomeCents();
    }
    /** Workbook in the official layout: title rows, then a header row, then records. */
    static byte[] workbook(boolean enterpriseColumn, Object[]... invoices) throws Exception {
        try (var wb = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("发票");
            sheet.createRow(0).createCell(0).setCellValue("发票明细");
            var headers = new ArrayList<>(List.of("发票编号", "开票日期", "销售方名称", "项目名称（票面）", "价税合计（元）", "用途", "备注"));
            if (enterpriseColumn) headers.addFirst("企业编号");
            var header = sheet.createRow(2);
            for (int i = 0; i < headers.size(); i++) header.createCell(i).setCellValue(headers.get(i));
            int r = 3;
            for (var values : invoices) {
                var row = sheet.createRow(r++);
                for (int i = 0; i < values.length; i++) {
                    if (values[i] instanceof Number n) row.createCell(i).setCellValue(n.doubleValue());
                    else row.createCell(i).setCellValue((String) values[i]);
                }
            }
            wb.write(out); return out.toByteArray();
        }
    }

    @Test void officialWorkbookReimportsExactlyAndNeverOverwrites() throws Exception {
        byte[] official = Files.readAllBytes(OFFICIAL);
        // Fresh database: every C001 key already exists, C002 rows belong to another enterprise.
        var first = files.upload(operator, "C001", "02_示例数据.xlsx", official);
        assertThat(first.status()).isEqualTo(Status.IMPORTED);
        assertThat(first.invoicesImported() + first.bankEntriesImported()).isZero();
        assertThat(first.conflicts()).hasSize(20 + 24).allSatisfy(i -> assertThat(i.reason()).isEqualTo("EXISTING_RECORD"));
        assertThat(first.skipped()).hasSize(20 + 24).allSatisfy(i -> assertThat(i.reason()).isEqualTo("ENTERPRISE_MISMATCH"));
        assertThat(first.chunks()).isPositive();

        // Remove C001's records, then import them back from the official Excel.
        var before = List.of(totals("C001", "2026-08"), totals("C001", "2026-09"));
        sql("DELETE FROM invoices WHERE enterprise_id='C001'"); sql("DELETE FROM bank_entries WHERE enterprise_id='C001'");
        files = new FileService(db);
        var renamed = files.upload(operator, "C001", "示例数据-重导.xlsx", official);
        assertThat(renamed.status()).isEqualTo(Status.DUPLICATE); // Same content is never stored twice, whatever the name.
        sql("DROP TRIGGER uploaded_files_no_delete");
        sql("DELETE FROM file_chunks"); sql("DELETE FROM uploaded_files");
        var reimport = files.upload(operator, "C001", "02_示例数据.xlsx", official);
        assertThat(reimport.invoicesImported()).isEqualTo(20);
        assertThat(reimport.bankEntriesImported()).isEqualTo(24);
        assertThat(List.of(totals("C001", "2026-08"), totals("C001", "2026-09"))).isEqualTo(before);
        assertThat(new RuleService(db).checkInvoicePurpose(operator, new PurposeCheck("C001", "2026-09")).missing())
                .extracting(InvoiceKey::invoiceId).containsExactlyInAnyOrder("F002", "F004", "F003", "F006", "F009");
        assertThat(count("SELECT count(*) FROM record_sources WHERE enterprise_id='C001' AND kind='INVOICE' AND record_id='F011' AND location='发票!第6行'")).isEqualTo(1);
    }

    @Test void newRowsImportExistingKeysConflictAndValuesStayUnchanged() throws Exception {
        var result = files.upload(operator, "C001", "十月发票.xlsx", workbook(true,
                new Object[]{"C001", "F101", "2026-10-08", "销售方", "项目", 1234.5, "办公", ""},
                new Object[]{"C001", "F002", "2026-09-08", "销售方", "项目", 1.0, "试图覆盖", ""}));
        assertThat(result.invoicesImported()).isEqualTo(1);
        assertThat(result.conflicts()).extracting(RowIssue::recordId).containsExactly("F002");
        var september = new ReadOnlyFinanceTools(db).queryInvoices(operator, new Query("C001", "2026-09"));
        var f002 = september.rows().stream().filter(i -> i.key().invoiceId().equals("F002")).findFirst().orElseThrow();
        assertThat(f002.purpose()).isEmpty();
        assertThat(f002.amountCents()).isNotEqualTo(100);
        var october = new ReadOnlyFinanceTools(db).queryInvoices(operator, new Query("C001", "2026-10"));
        assertThat(october.totalCents()).isEqualTo(123450);
    }

    @Test void formulaCellsUseExcelsSavedResult() throws Exception {
        byte[] bytes;
        try (var wb = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("发票");
            var header = sheet.createRow(0);
            var names = List.of("发票编号", "开票日期", "销售方名称", "项目名称", "价税合计", "用途", "备注");
            for (int i = 0; i < names.size(); i++) header.createCell(i).setCellValue(names.get(i));
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("F101"); row.createCell(1).setCellValue("2026-10-08");
            row.createCell(2).setCellValue("销售方"); row.createCell(3).setCellValue("项目");
            row.createCell(4).setCellFormula("1000+234.5");
            wb.getCreationHelper().createFormulaEvaluator().evaluateAll(); // Cache the result, as Excel does on save.
            wb.write(out); bytes = out.toByteArray();
        }
        assertThat(files.upload(operator, "C001", "公式.xlsx", bytes).invoicesImported()).isEqualTo(1);
        assertThat(new ReadOnlyFinanceTools(db).queryInvoices(operator, new Query("C001", "2026-10")).totalCents()).isEqualTo(123450);
    }

    @Test void otherEnterprisesRowsAreNeverStoredAsThisEnterprisesText() throws Exception {
        var result = files.upload(operator, "C001", "02_示例数据.xlsx", Files.readAllBytes(OFFICIAL));
        var chunks = files.detail(operator, "C001", result.file().id()).chunks();
        // No C002 record or totals row; descriptive text that merely names C002 (field notes) may remain.
        assertThat(chunks).noneMatch(c -> c.text().contains("企业编号=C002") || c.text().startsWith("C002；"));
        assertThat(chunks).anyMatch(c -> c.location().equals("发票!第6行") && c.text().startsWith("企业编号=C001；发票编号=F011；开票日期=2026-08-01"));
        assertThat(chunks).noneMatch(c -> c.text().contains("#VALUE!"));
        // Notes below the table are plain text, not mislabelled records.
        assertThat(chunks).anyMatch(c -> c.location().equals("发票!第47行") && c.text().startsWith("以企业编号和发票编号共同定位"));
    }

    @Test void fileWithoutEnterpriseColumnBelongsToUploaderEnterprise() throws Exception {
        var result = files.upload(operator, "C002", "乙十月.xlsx", workbook(false,
                new Object[]{"F101", "2026/10/09", "销售方", "项目", "2,000.00", "", ""}));
        assertThat(result.invoicesImported()).isEqualTo(1);
        assertThat(new ReadOnlyFinanceTools(db).queryInvoices(operator, new Query("C002", "2026-10")).totalCents()).isEqualTo(200000);
    }

    @Test void duplicateContentAndNewVersionsAreTracked() throws Exception {
        var v1 = workbook(true, new Object[]{"C001", "F101", "2026-10-08", "销售方", "项目", 10, "", ""});
        var v2 = workbook(true, new Object[]{"C001", "F101", "2026-10-08", "销售方", "项目", 10, "", ""},
                new Object[]{"C001", "F102", "2026-10-09", "销售方", "项目", 20, "", ""});
        var first = files.upload(operator, "C001", "十月.xlsx", v1);
        assertThat(files.upload(operator, "C001", "十月.xlsx", v1).status()).isEqualTo(Status.DUPLICATE);
        var second = files.upload(operator, "C001", "十月.xlsx", v2);
        assertThat(second.file().version()).isEqualTo(2);
        assertThat(second.invoicesImported()).isEqualTo(1);
        assertThat(second.conflicts()).extracting(RowIssue::recordId).containsExactly("F101");
        assertThat(files.list(operator, "C001")).extracting(StoredFile::version).containsExactly(1, 2);
        assertThat(files.list(operator, "C001").getFirst().id()).isEqualTo(first.file().id());
        // Each enterprise has its own store: the same bytes are a new file for C002.
        assertThat(files.upload(operator, "C002", "十月.xlsx", v1).status()).isEqualTo(Status.IMPORTED);
        assertThatThrownBy(() -> sql("UPDATE uploaded_files SET name='x'")).isInstanceOf(SQLException.class).hasMessageContaining("immutable");
        assertThatThrownBy(() -> sql("DELETE FROM uploaded_files")).isInstanceOf(SQLException.class).hasMessageContaining("retained");
    }

    @Test void invalidRowsRejectWholeFileAndStoreNothing() throws Exception {
        long invoices = count("SELECT count(*) FROM invoices");
        var result = files.upload(operator, "C001", "坏数据.xlsx", workbook(true,
                new Object[]{"C001", "F101", "2026-10-08", "销售方", "项目", 1.234, "", ""},
                new Object[]{"C001", "F102", "不是日期", "销售方", "项目", 10, "", ""},
                new Object[]{"C001", "F103", "2026-10-08", "销售方", "项目", 10, "", ""},
                new Object[]{"C001", "F103", "2026-10-09", "销售方", "项目", 10, "", ""}));
        assertThat(result.status()).isEqualTo(Status.REJECTED);
        assertThat(result.errors()).extracting(RowIssue::reason).containsExactly("INVALID_AMOUNT", "INVALID_DATE", "DUPLICATE_IN_FILE");
        assertThat(result.errors().getFirst().row()).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM invoices")).isEqualTo(invoices);
        assertThat(count("SELECT count(*) FROM uploaded_files")).isZero();
    }

    @Test void permissionsAndEnterpriseIsolation() throws Exception {
        var bytes = workbook(true, new Object[]{"C001", "F101", "2026-10-08", "销售方", "项目", 10, "", ""});
        fails(ErrorCode.PERMISSION_DENIED, () -> files.upload(viewer, "C001", "十月.xlsx", bytes));
        fails(ErrorCode.PERMISSION_DENIED, () -> files.upload(viewer, "C002", "十月.xlsx", bytes));
        fails(ErrorCode.AUTHENTICATION_REQUIRED, () -> files.upload(null, "C001", "十月.xlsx", bytes));
        var stored = files.upload(operator, "C001", "十月.xlsx", bytes).file();
        assertThat(files.list(viewer, "C001")).hasSize(1); // Viewers may read their enterprise's files.
        fails(ErrorCode.NOT_FOUND, () -> files.detail(operator, "C002", stored.id()));
        fails(ErrorCode.PERMISSION_DENIED, () -> files.list(viewer, "C002"));
        assertThat(count("SELECT count(*) FROM uploaded_files")).isEqualTo(1);
    }

    @Test void typeIsCheckedAgainstContentAndNameIsSanitized() throws Exception {
        var pdf = pdf("Purpose rule text");
        fails(ErrorCode.UNSUPPORTED_SCOPE, () -> files.upload(operator, "C001", "伪装.xlsx", pdf));
        fails(ErrorCode.UNSUPPORTED_SCOPE, () -> files.upload(operator, "C001", "说明.txt", "plain".getBytes()));
        fails(ErrorCode.INVALID_ARGUMENT, () -> files.upload(operator, "C001", "空.pdf", new byte[0]));
        fails(ErrorCode.INVALID_ARGUMENT, () -> files.upload(operator, "C001", "坏.xlsx", "PK-not-a-zip".getBytes()));
        assertThat(files.upload(operator, "C001", "../../etc/规则.pdf", pdf).file().name()).isEqualTo("规则.pdf");
    }

    @Test void wordAndPdfBecomeCitableSegments() throws Exception {
        byte[] docx;
        try (var doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("报销制度 1.0版");
            doc.createParagraph().createRun().setText("单张发票金额大于或等于 500.00 元时必须填写用途。");
            var table = doc.createTable(1, 2);
            table.getRow(0).getCell(0).setText("适用企业"); table.getRow(0).getCell(1).setText("C001");
            doc.write(out); docx = out.toByteArray();
        }
        var word = files.upload(operator, "C001", "报销制度.docx", docx);
        assertThat(files.detail(operator, "C001", word.file().id()).chunks())
                .extracting(Chunk::location, Chunk::text)
                .contains(tuple("第1段", "报销制度 1.0版"), tuple("第2段", "单张发票金额大于或等于 500.00 元时必须填写用途。"),
                        tuple("表1第1行", "适用企业；C001"));
        // Uploaded documents are knowledge only, never executable rules (FINAL_ROUND_CONTRACT 待确认 4).
        assertThat(new RuleService(db).queryRules(operator, "C001", null)).hasSize(4);

        var text = files.upload(operator, "C001", "说明.pdf", pdf("Bank records never prove payment."));
        assertThat(files.detail(operator, "C001", text.file().id()).chunks())
                .extracting(Chunk::location, Chunk::text).containsExactly(tuple("第1页第1行", "Bank records never prove payment."));
        var scanned = files.upload(operator, "C001", "扫描件.pdf", pdf(null));
        assertThat(scanned.status()).isEqualTo(Status.REJECTED);
        assertThat(scanned.errors()).extracting(RowIssue::reason).containsExactly("NOT_TEXT_PDF");
    }

    static byte[] pdf(String text) throws Exception {
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var page = new PDPage(); doc.addPage(page);
            if (text != null) try (var stream = new PDPageContentStream(doc, page)) {
                stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 700); stream.showText(text); stream.endText();
            }
            doc.save(out); return out.toByteArray();
        }
    }
}
