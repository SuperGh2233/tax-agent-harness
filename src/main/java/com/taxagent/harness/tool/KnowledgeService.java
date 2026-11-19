package com.taxagent.harness.tool;

import com.taxagent.harness.tool.ToolContract.*;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

/**
 * T32 knowledge search: simple BM25 over character bigrams (the brief allows plain text retrieval).
 * Searches only evidence that is currently in force for the trusted enterprise:
 * enabled rules effective on the date, and the latest, knowledge-enabled version of each uploaded file.
 * Disabled rules, disabled files and superseded file versions are never searched, and never fall back.
 */
public final class KnowledgeService {
    static final int MAX_SNIPPET_CHARS = 400;
    private static final double K1 = 1.2, B = 0.75;
    private final String url;
    private final PermissionGuard guard = new PermissionGuard();

    public KnowledgeService(Path database) {
        new FileService(database); // Ensures the additive file-store migration ran.
        url = "jdbc:sqlite:" + database.toAbsolutePath().toUri().toASCIIString() + "?mode=ro";
    }

    private record Segment(String kind, String id, String name, String version, String location, String text, List<String> tokens) {}

    public KnowledgeResult search(PermissionGuard.Session session, KnowledgeQuery query) {
        if (query == null) throw new DomainException(ErrorCode.INVALID_ARGUMENT, "Knowledge query required");
        var queryTokens = new LinkedHashSet<>(tokens(query.query()));
        if (queryTokens.isEmpty()) throw new DomainException(ErrorCode.INVALID_ARGUMENT, "Query has no searchable text");
        var date = query.date() == null ? LocalDate.now() : query.date();
        try (var c = DriverManager.getConnection(url)) {
            guard.require(c, session, query.enterpriseId(), PermissionGuard.Access.READ);
            var segments = new ArrayList<Segment>();
            rules(c, query.enterpriseId(), date, segments);
            files(c, query.enterpriseId(), segments);
            return new KnowledgeResult(rank(segments, queryTokens, query.effectiveLimit()), segments.size());
        } catch (SQLException e) {
            throw new DomainException(ErrorCode.STORAGE_ERROR, "Knowledge search failed", e);
        }
    }

    private static void rules(Connection c, String enterprise, LocalDate date, List<Segment> out) throws SQLException {
        try (var s = c.prepareStatement("""
                SELECT r.id,r.version,r.name,r.content FROM rules r
                JOIN rule_enterprises e ON r.id=e.rule_id AND r.version=e.version
                WHERE e.enterprise_id=? AND r.status='启用' AND r.effective_from<=? ORDER BY r.id,r.version
                """)) {
            s.setString(1, enterprise); s.setString(2, date.toString());
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    var lines = r.getString(4).split("\\R");
                    for (int i = 0; i < lines.length; i++) {
                        if (lines[i].isBlank()) continue;
                        // The rule title line is prefixed so every passage still names its rule.
                        String text = lines[i].strip();
                        out.add(new Segment("RULE", r.getString(1), r.getString(3), r.getString(2), "第" + (i + 1) + "行", text,
                                tokens(r.getString(3) + " " + text)));
                    }
                }
            }
        }
    }

    private static void files(Connection c, String enterprise, List<Segment> out) throws SQLException {
        try (var s = c.prepareStatement("""
                SELECT f.id,f.name,f.version,k.location,k.text FROM uploaded_files f
                JOIN file_chunks k ON k.file_id=f.id
                LEFT JOIN file_knowledge_status st ON st.file_id=f.id
                WHERE f.enterprise_id=? AND COALESCE(st.enabled,1)=1
                  AND f.version=(SELECT MAX(g.version) FROM uploaded_files g WHERE g.enterprise_id=f.enterprise_id AND g.name=f.name)
                ORDER BY f.name,k.seq
                """)) {
            s.setString(1, enterprise);
            try (var r = s.executeQuery()) {
                while (r.next())
                    out.add(new Segment("FILE", r.getString(1), r.getString(2), "v" + r.getInt(3), r.getString(4), r.getString(5),
                            tokens(r.getString(5))));
            }
        }
    }

    private static List<KnowledgeHit> rank(List<Segment> segments, Set<String> query, int limit) {
        if (segments.isEmpty()) return List.of();
        var df = new HashMap<String, Integer>();
        double total = 0;
        for (var segment : segments) {
            total += segment.tokens.size();
            for (var token : new HashSet<>(segment.tokens)) if (query.contains(token)) df.merge(token, 1, Integer::sum);
        }
        double avg = Math.max(1, total / segments.size());
        int n = segments.size();
        var scored = new ArrayList<Map.Entry<Segment, Double>>();
        for (var segment : segments) {
            var tf = new HashMap<String, Integer>();
            for (var token : segment.tokens) if (query.contains(token)) tf.merge(token, 1, Integer::sum);
            if (tf.isEmpty()) continue;
            double score = 0;
            for (var e : tf.entrySet()) {
                double idf = Math.log(1 + (n - df.get(e.getKey()) + 0.5) / (df.get(e.getKey()) + 0.5));
                score += idf * e.getValue() * (K1 + 1) / (e.getValue() + K1 * (1 - B + B * segment.tokens.size() / avg));
            }
            scored.add(Map.entry(segment, score));
        }
        // Ties keep source order, so equal questions give equal answers.
        scored.sort(Map.Entry.<Segment, Double>comparingByValue().reversed());
        return scored.stream().limit(limit).map(e -> {
            var s = e.getKey();
            var text = s.text.length() > MAX_SNIPPET_CHARS ? s.text.substring(0, MAX_SNIPPET_CHARS) + "…" : s.text;
            return new KnowledgeHit(s.kind, s.id, s.name, s.version, s.location, text, Math.round(e.getValue() * 1000) / 1000.0);
        }).toList();
    }

    /**
     * Finance wording that means the same thing is folded to one form on both sides, so different phrasings
     * of a question reach the same passage. Longest phrases first; kept short and reviewable on purpose.
     */
    private static final String[][] SYNONYMS = {
            {"住酒店", "住宿"}, {"酒店", "住宿"}, {"宾馆", "住宿"}, {"住宿费", "住宿"},
            {"出差", "差旅"}, {"报账", "报销"}, {"票据", "发票"}, {"开票", "发票"},
            {"价税合计", "金额"}, {"支付", "付款"}, {"付钱", "付款"}, {"已付", "付款"}, {"流水", "银行"},
            {"不超过", "上限"}, {"最多", "上限"}, {"最高", "上限"}, {"封顶", "上限"},
            {"必须填写", "必填"}, {"必须填", "必填"}, {"要填", "必填"}, {"一定要写", "必填"},
            {"每晚", "每天"}, {"每日", "每天"}, {"一天", "每天"}, {"块钱", "元"},
    };
    private static final Set<String> STOP = Set.of("的", "了", "吗", "呢", "是", "在", "和", "与", "或", "请", "问", "我", "们", "多少",
            "什么", "怎么", "如何", "哪些", "是否", "可以", "能否", "需要");

    static List<String> tokens(String text) {
        var normal = text.toLowerCase(Locale.ROOT);
        for (var pair : SYNONYMS) normal = normal.replace(pair[0], pair[1]);
        for (var stop : STOP) if (stop.length() > 1) normal = normal.replace(stop, " ");
        var out = new ArrayList<String>();
        var cjk = new StringBuilder();
        var word = new StringBuilder();
        for (int i = 0; i <= normal.length(); i++) {
            int ch = i < normal.length() ? normal.charAt(i) : ' ';
            boolean han = Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN;
            boolean alnum = !han && Character.isLetterOrDigit(ch);
            if (han && !STOP.contains(String.valueOf((char) ch))) cjk.append((char) ch);
            else flushCjk(cjk, out);
            if (alnum || ch == '.' && !word.isEmpty() && i + 1 < normal.length() && Character.isDigit(normal.charAt(i + 1))) word.append((char) ch);
            else if (!word.isEmpty()) { out.add(word.toString()); word.setLength(0); }
        }
        return out;
    }
    private static void flushCjk(StringBuilder cjk, List<String> out) {
        if (cjk.length() == 1) out.add(cjk.toString());
        for (int i = 0; i + 1 < cjk.length(); i++) out.add(cjk.substring(i, i + 2));
        cjk.setLength(0);
    }
}
