package com.taxagent.harness.tool;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xwpf.usermodel.*;

/**
 * Uploaded finance files (FINAL_ROUND_CONTRACT B1–B3): every file keeps its uploader, enterprise, hash and
 * version. Identical content is never stored twice. Excel invoice/bank rows only fill missing keys of the
 * uploader's current enterprise; existing records are reported as conflicts, never overwritten.
 */
public final class FileService {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    static final int MAX_CHUNKS = 20_000;
    static final int MAX_CHUNK_CHARS = 1_500;
    static final int MAX_REPORTED_ISSUES = 200;
    private final String url;
    private final PermissionGuard guard = new PermissionGuard();

    public enum Status { IMPORTED, DUPLICATE, REJECTED }
    /** latest: highest version of this name; only latest, knowledge-enabled files are searchable (T32). */
    public record StoredFile(String id, String enterpriseId, String name, int version, String sha256, String kind,
                             long sizeBytes, String uploadedBy, String uploadedAt, boolean knowledgeEnabled, boolean latest) {}
    /** A row that was not imported, with its sheet and 1-based row number. */
    public record RowIssue(String sheet, int row, String recordId, String reason) {}
    public record UploadResult(Status status, StoredFile file, int invoicesImported, int bankEntriesImported,
                               List<RowIssue> conflicts, List<RowIssue> skipped, List<RowIssue> errors, int chunks) {}
    public record Chunk(int seq, String location, String text) {}
    public record FileDetail(StoredFile file, List<Chunk> chunks) {}

    public FileService(Path database) {
        url = "jdbc:sqlite:" + database.toAbsolutePath().toUri().toASCIIString() + "?mode=rw";
        Migrations.once(url, "file-store", c -> Migrations.script(c, FileService.class, "/file-store.sql"));
    }

    public UploadResult upload(PermissionGuard.Session session, String enterprise, String originalName, byte[] content) {
        ToolContract.required(enterprise);
        String name = baseName(originalName);
        if (content == null || content.length == 0) throw invalid("Empty file");
        if (content.length > MAX_BYTES) throw invalid("File too large");
        String kind = kind(name, content);
        // Unauthorised uploads are refused before any parsing work.
        var otherEnterprises = transaction(c -> {
            guard.require(c, session, enterprise, PermissionGuard.Access.WRITE);
            var others = new HashSet<String>();
            try (var s = c.prepareStatement("SELECT id FROM enterprises WHERE id<>?")) {
                s.setString(1, enterprise);
                try (var r = s.executeQuery()) { while (r.next()) others.add(r.getString(1)); }
            }
            return Set.copyOf(others);
        });
        // Parse before touching storage: a malformed file stores nothing.
        var parsed = parse(kind, content, otherEnterprises);
        String sha = sha256(content);
        return transaction(c -> {
            guard.require(c, session, enterprise, PermissionGuard.Access.WRITE);
            if (!parsed.errors.isEmpty())
                return new UploadResult(Status.REJECTED, null, 0, 0, List.of(), List.of(), cap(parsed.errors), 0);
            var existing = findBySha(c, enterprise, sha);
            if (existing != null) return new UploadResult(Status.DUPLICATE, existing, 0, 0, List.of(), List.of(), List.of(), 0);
            var file = new StoredFile(UUID.randomUUID().toString(), enterprise, name, nextVersion(c, enterprise, name), sha,
                    kind, content.length, session.userId(), Instant.now().toString(), true, true);
            try (var s = c.prepareStatement("INSERT INTO uploaded_files VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                s.setString(1, file.id()); s.setString(2, enterprise); s.setString(3, name); s.setInt(4, file.version());
                s.setString(5, sha); s.setString(6, kind); s.setLong(7, content.length); s.setString(8, session.userId());
                s.setString(9, file.uploadedAt()); s.setBytes(10, content); s.executeUpdate();
            }
            try (var s = c.prepareStatement("INSERT INTO file_chunks VALUES (?,?,?,?)")) {
                for (var chunk : parsed.chunks) {
                    s.setString(1, file.id()); s.setInt(2, chunk.seq()); s.setString(3, chunk.location()); s.setString(4, chunk.text());
                    s.addBatch();
                }
                s.executeBatch();
            }
            var conflicts = new ArrayList<RowIssue>(); var skipped = new ArrayList<RowIssue>();
            int invoices = 0, bank = 0;
            for (var row : parsed.rows) {
                // A file without an enterprise column belongs to the uploader's current enterprise.
                if (!row.enterprise.isEmpty() && !row.enterprise.equals(enterprise)) {
                    skipped.add(row.issue("ENTERPRISE_MISMATCH")); continue;
                }
                if (exists(c, row.invoice ? "invoices" : "bank_entries", enterprise, row.id)) {
                    conflicts.add(row.issue("EXISTING_RECORD")); continue;
                }
                try (var s = c.prepareStatement(row.invoice ? "INSERT INTO invoices VALUES (?,?,?,?,?,?,?,?)"
                        : "INSERT INTO bank_entries VALUES (?,?,?,?,?,?,?)")) {
                    var values = row.values.clone(); values[0] = enterprise;
                    for (int i = 0; i < values.length; i++) {
                        if (values[i] instanceof Long cents) s.setLong(i + 1, cents); else s.setString(i + 1, (String) values[i]);
                    }
                    s.executeUpdate();
                }
                try (var s = c.prepareStatement("INSERT INTO record_sources VALUES (?,?,?,?,?)")) {
                    s.setString(1, enterprise); s.setString(2, row.invoice ? "INVOICE" : "BANK_ENTRY"); s.setString(3, row.id);
                    s.setString(4, file.id()); s.setString(5, row.location()); s.executeUpdate();
                }
                if (row.invoice) invoices++; else bank++;
            }
            return new UploadResult(Status.IMPORTED, file, invoices, bank, cap(conflicts), cap(skipped), List.of(), parsed.chunks.size());
        });
    }

    public List<StoredFile> list(PermissionGuard.Session session, String enterprise) {
        ToolContract.required(enterprise);
        return transaction(c -> {
            guard.require(c, session, enterprise, PermissionGuard.Access.READ);
            var files = new ArrayList<StoredFile>();
            try (var s = c.prepareStatement(SELECT_FILE + " WHERE f.enterprise_id=? ORDER BY f.name, f.version")) {
                s.setString(1, enterprise);
                try (var r = s.executeQuery()) { while (r.next()) files.add(file(r)); }
            }
            return List.copyOf(files);
        });
    }

    public FileDetail detail(PermissionGuard.Session session, String enterprise, String fileId) {
        ToolContract.required(enterprise); ToolContract.required(fileId);
        return transaction(c -> {
            guard.require(c, session, enterprise, PermissionGuard.Access.READ);
            StoredFile file = null;
            try (var s = c.prepareStatement(SELECT_FILE + " WHERE f.enterprise_id=? AND f.id=?")) {
                s.setString(1, enterprise); s.setString(2, fileId);
                try (var r = s.executeQuery()) { if (r.next()) file = file(r); }
            }
            // Another enterprise's file is indistinguishable from a missing one.
            if (file == null) throw new DomainException(ErrorCode.NOT_FOUND, "File not found");
            var chunks = new ArrayList<Chunk>();
            try (var s = c.prepareStatement("SELECT seq,location,text FROM file_chunks WHERE file_id=? ORDER BY seq")) {
                s.setString(1, fileId);
                try (var r = s.executeQuery()) { while (r.next()) chunks.add(new Chunk(r.getInt(1), r.getString(2), r.getString(3))); }
            }
            return new FileDetail(file, List.copyOf(chunks));
        });
    }

    /**
     * T34: one segment by location plus up to {@code neighbours} segments on each side, for citing the original.
     * Only the latest, knowledge-enabled version is readable this way, the same rule as search_knowledge.
     */
    public FileDetail segment(PermissionGuard.Session session, String enterprise, String fileId, String location, int neighbours) {
        ToolContract.required(enterprise); ToolContract.required(fileId); ToolContract.required(location);
        return transaction(c -> {
            guard.require(c, session, enterprise, PermissionGuard.Access.READ);
            StoredFile file = null;
            try (var s = c.prepareStatement(SELECT_FILE + " WHERE f.enterprise_id=? AND f.id=?")) {
                s.setString(1, enterprise); s.setString(2, fileId);
                try (var r = s.executeQuery()) { if (r.next()) file = file(r); }
            }
            if (file == null || !file.latest() || !file.knowledgeEnabled())
                throw new DomainException(ErrorCode.NOT_FOUND, "No current, enabled file with this id");
            Integer seq = null;
            try (var s = c.prepareStatement("SELECT seq FROM file_chunks WHERE file_id=? AND location=? ORDER BY seq LIMIT 1")) {
                s.setString(1, fileId); s.setString(2, location);
                try (var r = s.executeQuery()) { if (r.next()) seq = r.getInt(1); }
            }
            if (seq == null) throw new DomainException(ErrorCode.NOT_FOUND, "No segment at this location");
            var chunks = new ArrayList<Chunk>();
            try (var s = c.prepareStatement("SELECT seq,location,text FROM file_chunks WHERE file_id=? AND seq BETWEEN ? AND ? ORDER BY seq")) {
                s.setString(1, fileId); s.setInt(2, seq - neighbours); s.setInt(3, seq + neighbours);
                try (var r = s.executeQuery()) { while (r.next()) chunks.add(new Chunk(r.getInt(1), r.getString(2), r.getString(3))); }
            }
            return new FileDetail(file, List.copyOf(chunks));
        });
    }

    /** Enable or disable a file as knowledge. Takes effect on the next search; the file itself never changes. */
    public StoredFile setKnowledgeEnabled(PermissionGuard.Session session, String enterprise, String fileId, boolean enabled) {
        ToolContract.required(enterprise); ToolContract.required(fileId);
        return transaction(c -> {
            guard.require(c, session, enterprise, PermissionGuard.Access.WRITE);
            try (var s = c.prepareStatement(SELECT_FILE + " WHERE f.enterprise_id=? AND f.id=?")) {
                s.setString(1, enterprise); s.setString(2, fileId);
                try (var r = s.executeQuery()) { if (!r.next()) throw new DomainException(ErrorCode.NOT_FOUND, "File not found"); }
            }
            try (var s = c.prepareStatement("""
                    INSERT INTO file_knowledge_status VALUES (?,?,?,?)
                    ON CONFLICT(file_id) DO UPDATE SET enabled=excluded.enabled, changed_by=excluded.changed_by, changed_at=excluded.changed_at
                    """)) {
                s.setString(1, fileId); s.setInt(2, enabled ? 1 : 0); s.setString(3, session.userId());
                s.setString(4, Instant.now().toString()); s.executeUpdate();
            }
            try (var s = c.prepareStatement(SELECT_FILE + " WHERE f.id=?")) {
                s.setString(1, fileId);
                try (var r = s.executeQuery()) { r.next(); return file(r); }
            }
        });
    }

    // ---- parsing (pure; no storage) ----

    private record Row(boolean invoice, String sheet, int rowNumber, String enterprise, String id, Object[] values) {
        RowIssue issue(String reason) { return new RowIssue(sheet, rowNumber, id, reason); }
        String location() { return sheet + "!第" + rowNumber + "行"; }
    }
    private static final class Parsed {
        final List<Chunk> chunks = new ArrayList<>();
        final List<Row> rows = new ArrayList<>();
        final List<RowIssue> errors = new ArrayList<>();
        void chunk(String location, String text) {
            var clean = text.strip();
            for (int start = 0; start < clean.length(); start += MAX_CHUNK_CHARS) {
                if (chunks.size() >= MAX_CHUNKS) { errors.add(new RowIssue(null, 0, null, "TOO_MANY_SEGMENTS")); return; }
                chunks.add(new Chunk(chunks.size() + 1, location, clean.substring(start, Math.min(clean.length(), start + MAX_CHUNK_CHARS))));
            }
        }
    }

    static Parsed parse(String kind, byte[] content, Set<String> otherEnterprises) {
        try {
            return switch (kind) {
                case "XLSX", "XLS" -> parseWorkbook(content, otherEnterprises);
                case "DOCX" -> parseDocx(content);
                case "PDF" -> parsePdf(content);
                default -> throw unsupported("Unsupported file type");
            };
        } catch (DomainException e) { throw e;
        } catch (Exception e) { throw new DomainException(ErrorCode.INVALID_ARGUMENT, "File could not be parsed", e); }
    }

    private enum Field { ENTERPRISE, ID, DATE, SELLER, ITEM, AMOUNT, PURPOSE, REMARKS, DIRECTION, COUNTERPARTY }
    private static final Map<String, Field> INVOICE_HEADERS = Map.ofEntries(
            Map.entry("企业编号", Field.ENTERPRISE), Map.entry("发票编号", Field.ID), Map.entry("开票日期", Field.DATE),
            Map.entry("销售方名称", Field.SELLER), Map.entry("销售方", Field.SELLER), Map.entry("项目名称", Field.ITEM),
            Map.entry("项目", Field.ITEM), Map.entry("价税合计", Field.AMOUNT), Map.entry("金额", Field.AMOUNT),
            Map.entry("用途", Field.PURPOSE), Map.entry("备注", Field.REMARKS));
    private static final Map<String, Field> BANK_HEADERS = Map.ofEntries(
            Map.entry("企业编号", Field.ENTERPRISE), Map.entry("流水编号", Field.ID), Map.entry("交易日期", Field.DATE),
            Map.entry("收支方向", Field.DIRECTION), Map.entry("方向", Field.DIRECTION), Map.entry("金额", Field.AMOUNT),
            Map.entry("对方名称", Field.COUNTERPARTY), Map.entry("对方", Field.COUNTERPARTY), Map.entry("备注", Field.REMARKS));
    private static final List<Field> INVOICE_REQUIRED = List.of(Field.ID, Field.DATE, Field.SELLER, Field.ITEM, Field.AMOUNT);
    private static final List<Field> BANK_REQUIRED = List.of(Field.ID, Field.DATE, Field.DIRECTION, Field.AMOUNT, Field.COUNTERPARTY);
    private static final int HEADER_SCAN_ROWS = 30;

    /** "价税合计（元）" and "金额 (元)" both normalise to their bare name. */
    static String header(String text) {
        return text == null ? "" : text.replaceAll("[（(][^）)]*[）)]", "").replaceAll("\\s+", "");
    }

    static final long MAX_UNZIPPED_BYTES = 200L * 1024 * 1024;
    private static final java.util.regex.Pattern ISO_DATE_CELL =
            java.util.regex.Pattern.compile("(<(?:\\w+:)?c\\b[^>]*?\\s)t=\"d\"");

    /**
     * POI's schema rejects ISO 8601 date cells (t="d"), which the official workbook uses. Rewrite only that
     * attribute to a plain string cell; the ISO text is then parsed by {@link #date}. Bounded, because this
     * pass runs before POI's own zip-bomb checks.
     */
    static byte[] normalizeIsoDateCells(byte[] content) throws java.io.IOException {
        var out = new ByteArrayOutputStream();
        boolean changed = false; long total = 0;
        try (var in = new java.util.zip.ZipInputStream(new ByteArrayInputStream(content));
             var zip = new java.util.zip.ZipOutputStream(out)) {
            for (java.util.zip.ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                var buffer = new ByteArrayOutputStream(); var chunk = new byte[8192];
                for (int n; (n = in.read(chunk)) > 0; ) {
                    total += n;
                    if (total > MAX_UNZIPPED_BYTES) throw invalid("Workbook expands beyond the size limit");
                    buffer.write(chunk, 0, n);
                }
                byte[] data = buffer.toByteArray();
                if (entry.getName().startsWith("xl/worksheets/") && entry.getName().endsWith(".xml")) {
                    var xml = new String(data, StandardCharsets.UTF_8);
                    var rewritten = ISO_DATE_CELL.matcher(xml).replaceAll("$1t=\"str\"");
                    if (!rewritten.equals(xml)) { data = rewritten.getBytes(StandardCharsets.UTF_8); changed = true; }
                }
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getName())); zip.write(data); zip.closeEntry();
            }
        }
        return changed ? out.toByteArray() : content;
    }

    /** "2026-08-01T00:00:00.000Z" (official ISO date cells) reads as the plain date it is. */
    private static String display(String text) {
        return text.matches("\\d{4}-\\d{2}-\\d{2}T00:00(:00(\\.0+)?)?Z?") ? text.substring(0, 10) : text;
    }

    /**
     * Rows of another existing enterprise never become this enterprise's knowledge: neither table rows whose
     * enterprise column names it, nor any row with that enterprise id as a cell value. Otherwise anyone who
     * can read this enterprise could read the other enterprise's records through search.
     */
    private static Parsed parseWorkbook(byte[] content, Set<String> otherEnterprises) throws Exception {
        var parsed = new Parsed();
        var formatter = new DataFormatter(Locale.ROOT);
        // Excel's saved results are what the user saw; re-evaluating can fail (#VALUE!) or differ.
        formatter.setUseCachedValuesForFormulaCells(true);
        boolean ooxml = content.length > 1 && content[0] == 'P' && content[1] == 'K';
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(ooxml ? normalizeIsoDateCells(content) : content))) {
            FormulaEvaluator evaluator = null;
            var keys = new HashSet<String>();
            for (var sheet : workbook) {
                String name = sheet.getSheetName();
                Boolean invoice = null; Map<Field, Integer> columns = Map.of(); Map<Integer, String> headers = Map.of();
                int headerRow = -1;
                for (var row : sheet) {
                    int number = row.getRowNum() + 1;
                    var cells = new TreeMap<Integer, String>();
                    for (var cell : row) {
                        var text = display(formatter.formatCellValue(cell, evaluator).strip());
                        if (!text.isEmpty()) cells.put(cell.getColumnIndex(), text);
                    }
                    if (cells.isEmpty()) continue;
                    if (invoice == null && row.getRowNum() < HEADER_SCAN_ROWS) {
                        var names = new HashMap<String, Integer>();
                        cells.forEach((column, text) -> names.putIfAbsent(header(text), column));
                        // A header row names both the record id and its date; a lone field name (e.g. in a
                        // field-description sheet) is not a table header.
                        boolean invoiceHeader = names.containsKey("发票编号") && names.containsKey("开票日期");
                        if (invoiceHeader || names.containsKey("流水编号") && names.containsKey("交易日期")) {
                            invoice = invoiceHeader;
                            var map = invoice ? INVOICE_HEADERS : BANK_HEADERS;
                            var found = new EnumMap<Field, Integer>(Field.class);
                            names.forEach((text, column) -> { var field = map.get(text); if (field != null) found.putIfAbsent(field, column); });
                            var missing = (invoice ? INVOICE_REQUIRED : BANK_REQUIRED).stream().filter(f -> !found.containsKey(f)).toList();
                            if (!missing.isEmpty()) {
                                parsed.errors.add(new RowIssue(name, number, null, "MISSING_COLUMNS:" + missing));
                                invoice = null; continue;
                            }
                            columns = found; headerRow = number; headers = new HashMap<>();
                            for (var e : cells.entrySet()) headers.put(e.getKey(), e.getValue());
                        }
                    }
                    Row parsedRow = null;
                    if (invoice != null && number > headerRow) {
                        parsedRow = record(invoice, name, number, row, columns, formatter, evaluator, parsed.errors);
                        if (parsedRow != null) {
                            if (!keys.add((invoice ? "I:" : "B:") + parsedRow.enterprise + ":" + parsedRow.id))
                                parsed.errors.add(parsedRow.issue("DUPLICATE_IN_FILE"));
                            else parsed.rows.add(parsedRow);
                        }
                    }
                    boolean foreign = parsedRow != null && otherEnterprises.contains(parsedRow.enterprise)
                            || cells.values().stream().anyMatch(otherEnterprises::contains);
                    if (foreign) continue;
                    // Every other non-empty row is kept as a citable segment; only record rows get column labels.
                    var segment = new StringBuilder();
                    for (var e : cells.entrySet()) {
                        if (!segment.isEmpty()) segment.append("；");
                        var label = parsedRow != null ? headers.get(e.getKey()) : null;
                        segment.append(label == null ? e.getValue() : label + "=" + e.getValue());
                    }
                    parsed.chunk(name + "!第" + number + "行", segment.toString());
                }
            }
        }
        return parsed;
    }

    private static Row record(boolean invoice, String sheet, int number, org.apache.poi.ss.usermodel.Row row,
                              Map<Field, Integer> columns, DataFormatter formatter, FormulaEvaluator evaluator, List<RowIssue> errors) {
        java.util.function.Function<Field, Cell> cell = field -> columns.containsKey(field) ? row.getCell(columns.get(field)) : null;
        java.util.function.Function<Field, String> text = field -> {
            var c = cell.apply(field);
            return c == null ? "" : formatter.formatCellValue(c, evaluator).strip();
        };
        String id = text.apply(Field.ID);
        // Rows with no record id (totals, notes, blank spacing) are not records.
        if (id.isEmpty()) return null;
        String enterprise = text.apply(Field.ENTERPRISE);
        var issue = (java.util.function.Function<String, Row>) reason -> { errors.add(new RowIssue(sheet, number, id, reason)); return null; };
        if (columns.containsKey(Field.ENTERPRISE) && enterprise.isEmpty()) return issue.apply("MISSING_ENTERPRISE");
        LocalDate date = date(cell.apply(Field.DATE), evaluator);
        if (date == null) return issue.apply("INVALID_DATE");
        Long cents = cents(cell.apply(Field.AMOUNT), evaluator);
        if (cents == null) return issue.apply("INVALID_AMOUNT");
        if (invoice) {
            String seller = text.apply(Field.SELLER), item = text.apply(Field.ITEM);
            if (seller.isEmpty() || item.isEmpty()) return issue.apply("MISSING_REQUIRED_FIELD");
            return new Row(true, sheet, number, enterprise, id, new Object[]{null, id, date.toString(), seller, item, cents,
                    text.apply(Field.PURPOSE), text.apply(Field.REMARKS)});
        }
        String direction = text.apply(Field.DIRECTION), counterparty = text.apply(Field.COUNTERPARTY);
        if (!direction.equals("收入") && !direction.equals("支出")) return issue.apply("INVALID_DIRECTION");
        if (cents <= 0) return issue.apply("INVALID_AMOUNT");
        if (counterparty.isEmpty()) return issue.apply("MISSING_REQUIRED_FIELD");
        return new Row(false, sheet, number, enterprise, id, new Object[]{null, id, date.toString(), direction, cents,
                counterparty, text.apply(Field.REMARKS)});
    }

    /** Formula cells use the result Excel saved, never a re-evaluation. */
    private static CellType type(Cell cell, FormulaEvaluator ignored) {
        return cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
    }
    private static LocalDate date(Cell cell, FormulaEvaluator evaluator) {
        if (cell == null) return null;
        try {
            var type = type(cell, evaluator);
            if (type == CellType.NUMERIC) return DateUtil.isCellDateFormatted(cell) ? cell.getLocalDateTimeCellValue().toLocalDate() : null;
            if (type == CellType.STRING) {
                var value = cell.getStringCellValue().strip().replace('/', '-').replace('.', '-');
                var m = java.util.regex.Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})").matcher(value);
                if (m.find()) return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
                var cn = java.util.regex.Pattern.compile("^(\\d{4})年(\\d{1,2})月(\\d{1,2})日").matcher(value);
                if (cn.find()) return LocalDate.of(Integer.parseInt(cn.group(1)), Integer.parseInt(cn.group(2)), Integer.parseInt(cn.group(3)));
            }
        } catch (Exception ignored) { }
        return null;
    }
    /** Exact integer cents; more than two decimals is rejected, never rounded. */
    static Long cents(Cell cell, FormulaEvaluator evaluator) {
        if (cell == null) return null;
        try {
            var type = type(cell, evaluator);
            BigDecimal value;
            if (type == CellType.NUMERIC) value = new BigDecimal(Double.toString(cell.getNumericCellValue()));
            else if (type == CellType.STRING) value = new BigDecimal(cell.getStringCellValue().strip().replace(",", "").replace("¥", "").replace("￥", ""));
            else return null;
            return value.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (Exception e) { return null; }
    }

    private static Parsed parseDocx(byte[] content) throws Exception {
        var parsed = new Parsed();
        try (var document = new XWPFDocument(new ByteArrayInputStream(content))) {
            int paragraph = 0, table = 0;
            for (var element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph p) {
                    paragraph++;
                    if (!p.getText().isBlank()) parsed.chunk("第" + paragraph + "段", p.getText());
                } else if (element instanceof XWPFTable t) {
                    table++; int r = 0;
                    for (var row : t.getRows()) {
                        r++;
                        var cells = row.getTableCells().stream().map(c -> c.getText().strip()).filter(s -> !s.isEmpty()).toList();
                        if (!cells.isEmpty()) parsed.chunk("表" + table + "第" + r + "行", String.join("；", cells));
                    }
                }
            }
        }
        if (parsed.chunks.isEmpty()) parsed.errors.add(new RowIssue(null, 0, null, "NO_TEXT"));
        return parsed;
    }

    private static Parsed parsePdf(byte[] content) throws Exception {
        var parsed = new Parsed();
        try (var document = Loader.loadPDF(content)) {
            if (document.isEncrypted()) { parsed.errors.add(new RowIssue(null, 0, null, "ENCRYPTED_PDF")); return parsed; }
            var stripper = new PDFTextStripper();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page); stripper.setEndPage(page);
                var lines = stripper.getText(document).split("\\R");
                var block = new StringBuilder(); int first = 0;
                for (int i = 0; i < lines.length; i++) {
                    var line = lines[i].strip();
                    if (!line.isEmpty()) { if (block.isEmpty()) first = i + 1; block.append(block.isEmpty() ? "" : "\n").append(line); }
                    boolean end = line.isEmpty() || i == lines.length - 1 || block.length() >= MAX_CHUNK_CHARS / 2;
                    if (end && !block.isEmpty()) {
                        parsed.chunk("第" + page + "页第" + first + (first == i + 1 ? "" : "–" + (i + 1)) + "行", block.toString());
                        block.setLength(0);
                    }
                }
            }
        }
        // Scanned PDFs have no text layer; OCR is out of scope.
        if (parsed.chunks.isEmpty()) parsed.errors.add(new RowIssue(null, 0, null, "NOT_TEXT_PDF"));
        return parsed;
    }

    // ---- helpers ----

    static String baseName(String original) {
        if (original == null) throw invalid("File name required");
        var name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.isEmpty() || name.length() > 200 || name.chars().anyMatch(ch -> ch < 0x20)) throw invalid("Invalid file name");
        return name;
    }
    /** Extension and content signature must agree; a renamed file is rejected rather than guessed. */
    static String kind(String name, byte[] content) {
        var lower = name.toLowerCase(Locale.ROOT);
        boolean zip = content.length > 4 && content[0] == 'P' && content[1] == 'K';
        boolean ole = content.length > 8 && (content[0] & 0xff) == 0xD0 && (content[1] & 0xff) == 0xCF;
        boolean pdf = content.length > 5 && new String(content, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-");
        if (lower.endsWith(".xlsx") && zip) return "XLSX";
        if (lower.endsWith(".xls") && ole) return "XLS";
        if (lower.endsWith(".docx") && zip) return "DOCX";
        if (lower.endsWith(".pdf") && pdf) return "PDF";
        throw unsupported("Only xlsx, xls, docx and text PDF files are supported");
    }
    private static String sha256(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static List<RowIssue> cap(List<RowIssue> issues) {
        return List.copyOf(issues.size() > MAX_REPORTED_ISSUES ? issues.subList(0, MAX_REPORTED_ISSUES) : issues);
    }
    static final String SELECT_FILE = """
            SELECT f.id,f.enterprise_id,f.name,f.version,f.sha256,f.kind,f.size_bytes,f.uploaded_by,f.uploaded_at,
                   COALESCE(k.enabled,1),
                   f.version=(SELECT MAX(g.version) FROM uploaded_files g WHERE g.enterprise_id=f.enterprise_id AND g.name=f.name)
            FROM uploaded_files f LEFT JOIN file_knowledge_status k ON k.file_id=f.id""";
    private static StoredFile file(ResultSet r) throws SQLException {
        return new StoredFile(r.getString(1), r.getString(2), r.getString(3), r.getInt(4), r.getString(5), r.getString(6),
                r.getLong(7), r.getString(8), r.getString(9), r.getInt(10) == 1, r.getInt(11) == 1);
    }
    private static StoredFile findBySha(Connection c, String enterprise, String sha) throws SQLException {
        try (var s = c.prepareStatement(SELECT_FILE + " WHERE f.enterprise_id=? AND f.sha256=?")) {
            s.setString(1, enterprise); s.setString(2, sha);
            try (var r = s.executeQuery()) { return r.next() ? file(r) : null; }
        }
    }
    private static int nextVersion(Connection c, String enterprise, String name) throws SQLException {
        try (var s = c.prepareStatement("SELECT COALESCE(MAX(version),0)+1 FROM uploaded_files WHERE enterprise_id=? AND name=?")) {
            s.setString(1, enterprise); s.setString(2, name);
            try (var r = s.executeQuery()) { return r.getInt(1); }
        }
    }
    private static boolean exists(Connection c, String table, String enterprise, String id) throws SQLException {
        try (var s = c.prepareStatement("SELECT 1 FROM " + table + " WHERE enterprise_id=? AND id=?")) {
            s.setString(1, enterprise); s.setString(2, id);
            try (var r = s.executeQuery()) { return r.next(); }
        }
    }
    private <T> T transaction(Operation<T> operation) {
        try (var c = Sqlite.open(url)) {
            try (var s = c.createStatement()) { s.execute("PRAGMA foreign_keys=ON"); s.execute("PRAGMA busy_timeout=5000"); }
            c.setAutoCommit(false);
            try { T result = operation.run(c); c.commit(); return result; }
            catch (Exception e) { c.rollback(); throw e; }
        } catch (SQLException e) { throw storage(e); }
    }
    private static DomainException invalid(String message) { return new DomainException(ErrorCode.INVALID_ARGUMENT, message); }
    private static DomainException unsupported(String message) { return new DomainException(ErrorCode.UNSUPPORTED_SCOPE, message); }
    private static DomainException storage(Exception e) { return new DomainException(ErrorCode.STORAGE_ERROR, "File storage operation failed", e); }
    @FunctionalInterface private interface Operation<T> { T run(Connection c) throws SQLException; }
}
