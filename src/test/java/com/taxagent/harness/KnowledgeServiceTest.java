package com.taxagent.harness;

import com.taxagent.harness.tool.*;
import com.taxagent.harness.tool.ToolContract.*;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static com.taxagent.harness.ToolContractTest.fails;

class KnowledgeServiceTest {
    @TempDir Path directory;
    Path db;
    FileService files;
    KnowledgeService knowledge;
    final PermissionGuard.Session operator = new PermissionGuard.Session("U001");
    final PermissionGuard.Session viewer = new PermissionGuard.Session("U002");
    final LocalDate september = LocalDate.of(2026, 9, 15);

    @BeforeEach void setup() throws Exception {
        db = directory.resolve("knowledge.db"); DataInitializer.initialize(db);
        files = new FileService(db); knowledge = new KnowledgeService(db);
    }
    List<KnowledgeHit> search(String enterprise, String query) {
        return knowledge.search(operator, new KnowledgeQuery(enterprise, query, september, null)).hits();
    }
    static byte[] docx(String... paragraphs) throws Exception {
        try (var doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            for (var p : paragraphs) doc.createParagraph().createRun().setText(p);
            doc.write(out); return out.toByteArray();
        }
    }
    static final String[] POLICY_V1 = {"差旅报销制度", "差旅住宿费每晚不超过 500 元。", "交通费按实际发生报销。",
            "餐补每天 100 元。", "报销须在 30 天内提交发票。"};

    @Test void rulesInForceAreCitedAndDisabledOrForeignRulesNeverAppear() {
        var hits = search("C001", "银行流水能证明发票已经付款吗");
        assertThat(hits.getFirst()).extracting(KnowledgeHit::sourceKind, KnowledgeHit::sourceId, KnowledgeHit::version)
                .containsExactly("RULE", "R004", "1.0");
        assertThat(hits.getFirst().text()).contains("不能据此推断某张发票已经付款");
        // R002 1.0 (disabled) and R003 (C002 only) share wording with the query but are not evidence for C001.
        var threshold = search("C001", "大于或等于 1000.00 元时必须填写用途");
        assertThat(threshold).isNotEmpty().noneMatch(h -> h.sourceId().equals("R002") && h.version().equals("1.0"))
                .noneMatch(h -> h.sourceId().equals("R003"));
        // R002 2.0 starts 2026-09-01: an August date must not see it.
        assertThat(knowledge.search(operator, new KnowledgeQuery("C001", "每张发票都必须填写用途", LocalDate.of(2026, 8, 15), null)).hits())
                .noneMatch(h -> h.sourceId().equals("R002"));
    }

    @Test void differentPhrasingsReachTheSamePassage() throws Exception {
        files.upload(operator, "C001", "差旅报销制度.docx", docx(POLICY_V1));
        var phrasings = List.of("出差住酒店每晚最多报多少钱", "住宿费报销上限是多少", "差旅住宿标准", "出差住宾馆一天能报多少", "酒店费用最高多少");
        for (var question : phrasings) {
            var top = search("C001", question).getFirst();
            assertThat(top.sourceName()).as(question).isEqualTo("差旅报销制度.docx");
            assertThat(top.location()).as(question).isEqualTo("第2段");
            assertThat(top.version()).isEqualTo("v1");
        }
    }

    @Test void newVersionSwitchesImmediatelyAndDisabledFilesAreFullyExcluded() throws Exception {
        var v1 = files.upload(operator, "C001", "差旅报销制度.docx", docx(POLICY_V1)).file();
        var policy = POLICY_V1.clone(); policy[1] = "差旅住宿费每晚不超过 600 元。";
        var v2 = files.upload(operator, "C001", "差旅报销制度.docx", docx(policy)).file();
        var hits = search("C001", "住宿费上限");
        assertThat(hits.getFirst().text()).contains("600");
        assertThat(hits).noneMatch(h -> h.sourceId().equals(v1.id()));
        // Disabling the current version removes the document; the older version never comes back.
        files.setKnowledgeEnabled(operator, "C001", v2.id(), false);
        assertThat(search("C001", "住宿费上限")).noneMatch(h -> h.sourceKind().equals("FILE"));
        assertThat(files.list(operator, "C001")).extracting(FileService.StoredFile::version, FileService.StoredFile::knowledgeEnabled,
                FileService.StoredFile::latest).containsExactly(tuple(1, true, false), tuple(2, false, true));
        files.setKnowledgeEnabled(operator, "C001", v2.id(), true);
        assertThat(search("C001", "住宿费上限").getFirst().sourceId()).isEqualTo(v2.id());
        fails(ErrorCode.PERMISSION_DENIED, () -> files.setKnowledgeEnabled(viewer, "C001", v2.id(), false));
        fails(ErrorCode.NOT_FOUND, () -> files.setKnowledgeEnabled(operator, "C002", v2.id(), false));
    }

    @Test void spreadsheetRowsAreCitableAndEnterprisesAreIsolated() throws Exception {
        files.upload(operator, "C001", "02_示例数据.xlsx", Files.readAllBytes(FileServiceTest.OFFICIAL));
        var hit = search("C001", "F011 的开票日期和金额").getFirst();
        assertThat(hit).extracting(KnowledgeHit::sourceKind, KnowledgeHit::sourceName, KnowledgeHit::location)
                .containsExactly("FILE", "02_示例数据.xlsx", "发票!第6行");
        assertThat(hit.text()).contains("开票日期=2026-08-01", "价税合计（元）=460.50");
        assertThat(search("C002", "F011 发票")).noneMatch(h -> h.sourceKind().equals("FILE"));
        // C002's rows in the same workbook are not C001 knowledge: the viewer U002 has no C002 access.
        assertThat(knowledge.search(viewer, new KnowledgeQuery("C001", "C002 乙企业 发票 住宿", september, 10)).hits())
                .noneMatch(h -> h.text().contains("企业编号=C002") || h.text().startsWith("C002；"));
        assertThat(knowledge.search(viewer, new KnowledgeQuery("C001", "F011", september, 3)).hits()).hasSizeLessThanOrEqualTo(3);
        fails(ErrorCode.PERMISSION_DENIED, () -> knowledge.search(viewer, new KnowledgeQuery("C002", "F011", september, null)));
    }

    @Test void unrelatedQuestionsReturnNothingAndInputIsValidated() {
        assertThat(search("C001", "股票期权行权")).isEmpty();
        fails(ErrorCode.INVALID_ARGUMENT, () -> new KnowledgeQuery("C001", " ", null, null));
        fails(ErrorCode.INVALID_ARGUMENT, () -> new KnowledgeQuery("C001", "x".repeat(201), null, null));
        fails(ErrorCode.INVALID_ARGUMENT, () -> new KnowledgeQuery("C001", "发票", null, 11));
        fails(ErrorCode.INVALID_ARGUMENT, () -> knowledge.search(operator, new KnowledgeQuery("C001", "的吗？", null, null)));
    }
}
