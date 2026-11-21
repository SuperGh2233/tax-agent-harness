package com.taxagent.harness;

import com.taxagent.harness.runtime.VisionClient;
import com.taxagent.harness.tool.*;
import com.taxagent.harness.tool.FileService.*;
import com.taxagent.harness.tool.ToolContract.*;
import java.net.URI;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static com.taxagent.harness.ToolContractTest.fails;

/** T48: a scanned invoice is a model draft until the accountant confirms it; the program checks the amount words. */
class ScanInvoiceTest {
    @TempDir Path directory;
    Path db;
    FileService files;
    final PermissionGuard.Session operator = new PermissionGuard.Session("U001");
    final PermissionGuard.Session viewer = new PermissionGuard.Session("U002");
    static final String READING = "```json\n{\"invoice_id\":\"F301\",\"invoice_date\":\"2026年10月12日\",\"seller\":\"合成供应商\","
            + "\"item\":\"打印服务\",\"amount\":\"1,234.50\",\"amount_in_words\":\"壹仟贰佰叁拾肆元伍角\",\"remarks\":\"\",\"unreadable\":[]}\n```";

    @BeforeAll static void headless() { System.setProperty("java.awt.headless", "true"); }
    @BeforeEach void setup() throws Exception {
        db = directory.resolve("scan.db"); DataInitializer.initialize(db); files = new FileService(db);
    }
    long count(String sql) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement(); var r = s.executeQuery(sql)) { return r.getLong(1); }
    }
    static ScanReader reader(String text) {
        return pages -> {
            assertThat(pages).isNotEmpty();
            assertThat(pages.getFirst()).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
            return new Reading("fake-vision", text);
        };
    }

    @Test void draftBecomesAnInvoiceOnlyAfterFieldByFieldConfirmation() throws Exception {
        byte[] scan = FileServiceTest.pdf(null);
        var draft = files.recognize(operator, "C001", "发票扫描件.pdf", scan, reader(READING));
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(draft.fields()).containsEntry("invoice_id", "F301").containsEntry("invoice_date", "2026-10-12")
                .containsEntry("amount", "1234.50");
        assertThat(draft.checks()).containsExactly("AMOUNT_WORDS_MATCH");
        assertThat(draft.pageImage()).startsWith("data:image/png;base64,");
        assertThat(count("SELECT count(*) FROM invoices WHERE id='F301'")).isZero(); // A draft is not a record.
        assertThat(count("SELECT count(*) FROM scan_drafts WHERE model='fake-vision'")).isEqualTo(1);

        fails(ErrorCode.CONFIRMATION_REQUIRED, () -> files.confirm(operator, "C001", draft.draftId(),
                new ScanConfirmation("F301", "2026-10-12", "合成供应商", "打印服务", "1234.50", "", "1243.50")));
        fails(ErrorCode.INVALID_ARGUMENT, () -> files.confirm(operator, "C001", draft.draftId(),
                new ScanConfirmation("F301", "2026-10-12", "合成供应商", "打印服务", "1234.505", "", "1234.505")));
        fails(ErrorCode.PERMISSION_DENIED, () -> files.confirm(viewer, "C001", draft.draftId(),
                new ScanConfirmation("F301", "2026-10-12", "合成供应商", "打印服务", "1234.50", "", "1234.50")));
        var imported = files.confirm(operator, "C001", draft.draftId(),
                new ScanConfirmation("F301", "2026-10-12", "合成供应商", "打印服务", "1234.50", "手工更正", "1234.5"));
        assertThat(imported.status()).isEqualTo("IMPORTED");
        assertThat(imported.amountCents()).isEqualTo(123450);
        assertThat(imported.differences()).containsExactly("remarks: （未识别） → 手工更正");
        assertThat(new ReadOnlyFinanceTools(db).queryInvoices(operator, new Query("C001", "2026-10")).totalCents()).isEqualTo(123450);
        assertThat(count("SELECT count(*) FROM record_sources WHERE record_id='F301' AND location='第1页（扫描件，人工确认）'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM file_chunks WHERE text LIKE '%发票编号=F301%'")).isEqualTo(1);
        fails(ErrorCode.CONFLICT, () -> files.confirm(operator, "C001", draft.draftId(),
                new ScanConfirmation("F301", "2026-10-12", "合成供应商", "打印服务", "1234.50", "", "1234.50")));

        // The same scan again reuses the stored file version; a new draft never re-imports by itself.
        var again = files.recognize(operator, "C001", "发票扫描件.pdf", scan, reader(READING));
        assertThat(again.file().id()).isEqualTo(draft.file().id());
        assertThat(again.checks()).contains("AMOUNT_WORDS_MATCH", "EXISTING_INVOICE");
        assertThat(count("SELECT count(*) FROM uploaded_files")).isEqualTo(1);
    }

    @Test void mismatchedWordsAndModelOutagesAreFlaggedNeverGuessed() throws Exception {
        byte[] scan = FileServiceTest.pdf(null);
        var mismatch = files.recognize(operator, "C001", "a.pdf", scan, reader(READING.replace("壹仟贰佰叁拾肆元伍角", "壹仟贰佰叁拾伍元整")));
        assertThat(mismatch.checks()).containsExactly("AMOUNT_WORDS_MISMATCH");

        var outage = files.recognize(operator, "C001", "a.pdf", scan, pages -> { throw new ScanReadFailure("MODEL_NOT_CONFIGURED"); });
        assertThat(outage.status()).isEqualTo("MANUAL");
        assertThat(outage.errorCode()).isEqualTo("MODEL_NOT_CONFIGURED");
        assertThat(outage.fields().values()).allMatch(String::isEmpty);
        assertThat(outage.pageImage()).startsWith("data:image/png;base64,"); // Enough to type the values by hand.
        var garbage = files.recognize(operator, "C001", "a.pdf", scan, reader("看不清"));
        assertThat(garbage.status()).isEqualTo("MANUAL");
        assertThat(garbage.errorCode()).isEqualTo("MODEL_RESPONSE_INVALID");
        assertThat(files.recognize(operator, "C001", "a.pdf", scan, VisionClient.NOT_CONFIGURED).errorCode()).isEqualTo("MODEL_NOT_CONFIGURED");

        // Typed by hand onto an existing key: reported, never overwritten.
        var conflict = files.confirm(operator, "C001", outage.draftId(),
                new ScanConfirmation("F002", "2026-09-08", "合成", "合成", "1.00", "", "1.00"));
        assertThat(conflict.status()).isEqualTo("CONFLICT");
        assertThat(count("SELECT amount_cents FROM invoices WHERE enterprise_id='C001' AND id='F002'")).isEqualTo(80000);

        fails(ErrorCode.PERMISSION_DENIED, () -> files.recognize(viewer, "C001", "a.pdf", scan, reader(READING)));
        fails(ErrorCode.UNSUPPORTED_SCOPE, () -> files.recognize(operator, "C001", "a.xlsx", scan, reader(READING)));
        fails(ErrorCode.NOT_FOUND, () -> files.confirm(operator, "C002", outage.draftId(),
                new ScanConfirmation("F302", "2026-10-01", "合成", "合成", "1.00", "", "1.00")));
    }

    @Test void capitalAmountWordsParseExactlyOrNotAtAll() {
        assertThat(FileService.wordsToCents("壹万贰仟叁佰肆拾伍元陆角柒分")).isEqualTo(1234567L);
        assertThat(FileService.wordsToCents("人民币壹仟元整")).isEqualTo(100000L);
        assertThat(FileService.wordsToCents("⊗壹仟元整")).isNull();
        assertThat(FileService.wordsToCents("伍角")).isEqualTo(50L);
        assertThat(FileService.wordsToCents("拾贰元零伍分")).isEqualTo(1205L);
        assertThat(FileService.wordsToCents("贰仟零捌拾圆整")).isEqualTo(208000L);
        assertThat(FileService.wordsToCents("壹亿零伍万元整")).isEqualTo(10_005_000_000L);
        assertThat(FileService.wordsToCents("壹仟贰")).isNull();
        assertThat(FileService.wordsToCents("壹仟元伍")).isNull();
        assertThat(FileService.wordsToCents("")).isNull();
        assertThat(FileService.wordsToCents(null)).isNull();
    }

    @Test void visionRequestCarriesTheImageAndNeverTheKeyInTheBody() throws Exception {
        var openai = new VisionClient(URI.create("https://example.invalid/compatible-mode/v1"), "secret-key", "vision-model", false, Duration.ofSeconds(5));
        assertThat(VisionClient.isJsonObject("```json\n{\"a\":\"b\"}\n```")).isTrue();
        assertThat(VisionClient.isJsonObject("不是 JSON")).isFalse();
        var method = VisionClient.class.getDeclaredMethod("body", List.class, String.class);
        method.setAccessible(true);
        var body = (String) method.invoke(openai, List.of(new byte[]{1, 2, 3}), null);
        assertThat(body).contains("\"image_url\"", "data:image/png;base64,AQID", "vision-model").doesNotContain("secret-key");
        var anthropic = new VisionClient(URI.create("https://example.invalid/apps/anthropic"), "secret-key", "vision-model", true, Duration.ofSeconds(5));
        var messages = (String) method.invoke(anthropic, List.of(new byte[]{1, 2, 3}), "坏回复");
        assertThat(messages).contains("\"media_type\":\"image/png\"", "\"data\":\"AQID\"", "坏回复").doesNotContain("secret-key");
        assertThatThrownBy(() -> new VisionClient(URI.create("http://example.invalid/v1"), "k", "m", false, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
