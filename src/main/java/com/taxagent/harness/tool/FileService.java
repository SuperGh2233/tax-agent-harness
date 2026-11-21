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
    /**
     * tables (T46): sheets that look like a data table whose header the program could not map on its own. They are
     * offered back to the accountant for an explicit column mapping; nothing is imported from them by guessing.
     */
    public record UploadResult(Status status, StoredFile file, int invoicesImported, int bankEntriesImported,
                               List<RowIssue> conflicts, List<RowIssue> skipped, List<RowIssue> errors, int chunks,
                               List<TableCandidate> tables) {
        public UploadResult {
            tables = tables == null ? List.of() : List.copyOf(tables);
        }
        public UploadResult(Status status, StoredFile file, int invoicesImported, int bankEntriesImported,
                            List<RowIssue> conflicts, List<RowIssue> skipped, List<RowIssue> errors, int chunks) {
            this(status, file, invoicesImported, bankEntriesImported, conflicts, skipped, errors, chunks, List.of());
        }
    }
    public record Chunk(int seq, String location, String text) {}
    public record FileDetail(StoredFile file, List<Chunk> chunks) {}
    /**
     * T46: the accountant's explicit column assignment for one sheet. kind is INVOICE or BANK; headerRow is 1-based;
     * columns maps a field name (ID, DATE, SELLER, ITEM, AMOUNT, PURPOSE, REMARKS, DIRECTION, COUNTERPARTY,
     * ENTERPRISE) to a 0-based column index. Data rows are the rows below headerRow.
     */
    public record SheetMapping(String sheet, String kind, int headerRow, Map<String, Integer> columns) {}
    /**
     * T46: a sheet that looks like a table but was not imported. headers and samples are indexed by column;
     * suggested is a hint from known header wordings and is never applied without the accountant's confirmation.
     * reason is UNRECOGNIZED_HEADER or MISSING_COLUMNS.
     */
    public record TableCandidate(String sheet, int headerRow, List<String> headers, List<List<String>> samples,
                                 String suggestedKind, Map<String, Integer> suggested, String reason) {}
    static final int MAX_MAPPED_SHEETS = 20, MAX_SAMPLES = 3, MAX_COLUMNS = 200;

    public FileService(Path database) {
        url = "jdbc:sqlite:" + database.toAbsolutePath().toUri().toASCIIString() + "?mode=rw";
        Migrations.once(url, "file-store", c -> Migrations.script(c, FileService.class, "/file-store.sql"));
    }

    public UploadResult upload(PermissionGuard.Session session, String enterprise, String originalName, byte[] content) {
        return upload(session, enterprise, originalName, content, List.of());
    }

    /**
     * T46: with mappings, the named sheets are read with the accountant's column assignment instead of header
     * detection. Re-sending a file that is already stored (same content) together with mappings imports the mapped
     * sheets' rows into that same stored file version; rows whose key already exists are still reported as conflicts.
     */
    public UploadResult upload(PermissionGuard.Session session, String enterprise, String originalName, byte[] content,
                               List<SheetMapping> mappings) {
        ToolContract.required(enterprise);
        var byName = mappings(mappings);
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
        if (!byName.isEmpty() && !kind.startsWith("XLS")) throw invalid("Column mappings apply to Excel files only");
        var parsed = parse(kind, content, otherEnterprises, byName);
        String sha = sha256(content);
        return transaction(c -> {
            guard.require(c, session, enterprise, PermissionGuard.Access.WRITE);
            if (!parsed.errors.isEmpty())
                return new UploadResult(Status.REJECTED, null, 0, 0, List.of(), List.of(), cap(parsed.errors), 0, parsed.tables);
            var existing = findBySha(c, enterprise, sha);
            if (existing != null) {
                if (byName.isEmpty()) return new UploadResult(Status.DUPLICATE, existing, 0, 0, List.of(), List.of(), List.of(), 0);
                // T46: the same stored file, now read with the accountant's mapping. Only the mapped sheets are
                // imported here; the rest of the file was handled when it was first stored.
                var mapped = parsed.rows.stream().filter(row -> byName.containsKey(row.sheet)).toList();
                recordMappings(c, existing.id(), byName.values(), session.userId());
                var imported = importRows(c, enterprise, existing.id(), mapped);
                return new UploadResult(Status.IMPORTED, existing, imported.invoices, imported.bank, cap(imported.conflicts),
                        cap(imported.skipped), List.of(), 0, parsed.tables);
            }
            var file = new StoredFile(UUID.randomUUID().toString(), enterprise, name, nextVersion(c, enterprise, name), sha,
                    kind, content.length, session.userId(), Instant.now().toString(), true, true);
            insertFile(c, file, content);
            insertChunks(c, file.id(), parsed.chunks, 1);
            recordMappings(c, file.id(), byName.values(), session.userId());
            var imported = importRows(c, enterprise, file.id(), parsed.rows);
            return new UploadResult(Status.IMPORTED, file, imported.invoices, imported.bank, cap(imported.conflicts),
                    cap(imported.skipped), List.of(), parsed.chunks.size(), parsed.tables);
        });
    }

    private record Imported(int invoices, int bank, List<RowIssue> conflicts, List<RowIssue> skipped) {}
    /** Missing keys only: rows of another enterprise are skipped and existing records are conflicts, never overwritten. */
    private static Imported importRows(Connection c, String enterprise, String fileId, List<Row> rows) throws SQLException {
        var conflicts = new ArrayList<RowIssue>(); var skipped = new ArrayList<RowIssue>();
        int invoices = 0, bank = 0;
        for (var row : rows) {
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
                s.setString(4, fileId); s.setString(5, row.location()); s.executeUpdate();
            }
            if (row.invoice) invoices++; else bank++;
        }
        return new Imported(invoices, bank, conflicts, skipped);
    }
    private static void insertFile(Connection c, StoredFile file, byte[] content) throws SQLException {
        try (var s = c.prepareStatement("INSERT INTO uploaded_files VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            s.setString(1, file.id()); s.setString(2, file.enterpriseId()); s.setString(3, file.name()); s.setInt(4, file.version());
            s.setString(5, file.sha256()); s.setString(6, file.kind()); s.setLong(7, file.sizeBytes()); s.setString(8, file.uploadedBy());
            s.setString(9, file.uploadedAt()); s.setBytes(10, content); s.executeUpdate();
        }
    }
    /** Segments are numbered from firstSeq; a later append (a confirmed scan) continues after the existing ones. */
    private static void insertChunks(Connection c, String fileId, List<Chunk> chunks, int firstSeq) throws SQLException {
        try (var s = c.prepareStatement("INSERT INTO file_chunks VALUES (?,?,?,?)")) {
            int seq = firstSeq;
            for (var chunk : chunks) {
                s.setString(1, fileId); s.setInt(2, seq++); s.setString(3, chunk.location()); s.setString(4, chunk.text());
                s.addBatch();
            }
            s.executeBatch();
        }
    }
    /** T46: every applied mapping is kept with who applied it, so an imported row can be traced to its reading. */
    private static void recordMappings(Connection c, String fileId, Collection<SheetMapping> mappings, String user) throws SQLException {
        if (mappings.isEmpty()) return;
        try (var s = c.prepareStatement("INSERT INTO file_column_mappings(file_id,sheet,kind,header_row,columns_json,mapped_by,mapped_at) VALUES (?,?,?,?,?,?,?)")) {
            for (var m : mappings) {
                s.setString(1, fileId); s.setString(2, m.sheet()); s.setString(3, m.kind()); s.setInt(4, m.headerRow());
                s.setString(5, new TreeMap<>(m.columns()).toString()); s.setString(6, user); s.setString(7, Instant.now().toString());
                s.addBatch();
            }
            s.executeBatch();
        }
    }
    /** Field names a mapping may use for each record kind. */
    private static final Map<String, Set<Field>> MAPPABLE = Map.of(
            "INVOICE", EnumSet.of(Field.ENTERPRISE, Field.ID, Field.DATE, Field.SELLER, Field.ITEM, Field.AMOUNT, Field.PURPOSE, Field.REMARKS),
            "BANK", EnumSet.of(Field.ENTERPRISE, Field.ID, Field.DATE, Field.DIRECTION, Field.AMOUNT, Field.COUNTERPARTY, Field.REMARKS));
    /** Strict validation of explicit mappings; any doubt is a refusal, never a partial reading. */
    static Map<String, SheetMapping> mappings(List<SheetMapping> mappings) {
        if (mappings == null || mappings.isEmpty()) return Map.of();
        if (mappings.size() > MAX_MAPPED_SHEETS) throw invalid("Too many sheet mappings");
        var out = new LinkedHashMap<String, SheetMapping>();
        for (var m : mappings) {
            if (m == null || m.sheet() == null || m.sheet().isBlank() || m.columns() == null) throw invalid("Incomplete sheet mapping");
            var allowed = MAPPABLE.get(m.kind());
            if (allowed == null) throw invalid("kind must be INVOICE or BANK");
            if (m.headerRow() < 1 || m.headerRow() > 1_000_000) throw invalid("Invalid header row");
            var columns = new HashSet<Integer>();
            for (var e : m.columns().entrySet()) {
                Field field;
                try { field = Field.valueOf(e.getKey()); } catch (Exception bad) { throw invalid("Unknown field: " + e.getKey()); }
                if (!allowed.contains(field)) throw invalid("Field " + field + " does not belong to " + m.kind());
                if (e.getValue() == null || e.getValue() < 0 || e.getValue() >= MAX_COLUMNS || !columns.add(e.getValue()))
                    throw invalid("Each field needs its own valid column");
            }
            var missing = ("INVOICE".equals(m.kind()) ? INVOICE_REQUIRED : BANK_REQUIRED).stream()
                    .filter(f -> !m.columns().containsKey(f.name())).toList();
            if (!missing.isEmpty()) throw invalid("Mapping lacks required fields " + missing);
            if (out.put(m.sheet(), new SheetMapping(m.sheet(), m.kind(), m.headerRow(), Map.copyOf(m.columns()))) != null)
                throw invalid("Sheet mapped twice");
        }
        return Collections.unmodifiableMap(out);
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

    // ---- T48: scanned invoices (a model reads, the accountant confirms, the program checks) ----

    /** Reads rendered scan pages (PNG). The answer is untrusted text that only ever becomes a draft. */
    @FunctionalInterface public interface ScanReader { Reading read(List<byte[]> pngPages) throws Exception; }
    public record Reading(String model, String text) {}
    /** A reader failure with a sanitized code (MODEL_NOT_CONFIGURED, MODEL_TIMEOUT, ...); never carries payloads. */
    public static final class ScanReadFailure extends RuntimeException {
        private final String code;
        public ScanReadFailure(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
    /**
     * status DRAFT: the model's reading, to be confirmed field by field. MANUAL: the model was unavailable or its
     * answer unusable (errorCode says why); the accountant types the values from the page image instead.
     * checks are the program's findings, e.g. AMOUNT_WORDS_MATCH / AMOUNT_WORDS_MISMATCH / EXISTING_INVOICE.
     */
    public record ScanDraft(String status, String errorCode, String draftId, StoredFile file, Map<String, String> fields,
                            List<String> unreadable, List<String> checks, String pageImage) {}
    /** What the accountant confirms; amountConfirmation is the amount typed a second time from the paper. */
    public record ScanConfirmation(String invoiceId, String invoiceDate, String seller, String item, String amount,
                                   String remarks, String amountConfirmation) {}
    /** IMPORTED: a new invoice; CONFLICT: the key exists and was not overwritten. differences: draft vs confirmed. */
    public record ScanImport(String status, StoredFile file, String invoiceId, long amountCents, List<String> differences) {}
    static final List<String> SCAN_FIELDS = List.of("invoice_id", "invoice_date", "seller", "item", "amount", "amount_in_words", "remarks");
    static final int SCAN_PAGES = 2, SCAN_DPI = 150, MAX_SCAN_PIXELS = 2400;

    /**
     * Stores the PDF like any upload (same hash/version rules; nothing is imported), renders its first pages and asks
     * the reader for a draft. The draft and the program's checks are kept; no invoice exists until confirm().
     */
    public ScanDraft recognize(PermissionGuard.Session session, String enterprise, String originalName, byte[] content,
                               ScanReader reader) {
        ToolContract.required(enterprise);
        String name = baseName(originalName);
        if (content == null || content.length == 0) throw invalid("Empty file");
        if (content.length > MAX_BYTES) throw invalid("File too large");
        if (!"PDF".equals(kind(name, content))) throw unsupported("Scanned invoices must be PDF files");
        transaction(c -> { guard.require(c, session, enterprise, PermissionGuard.Access.WRITE); return null; });
        List<byte[]> pages;
        try { pages = render(content); }
        catch (DomainException e) { throw e; }
        catch (Exception e) { throw new DomainException(ErrorCode.INVALID_ARGUMENT, "PDF could not be rendered", e); }
        String sha = sha256(content);
        var file = transaction(c -> {
            guard.require(c, session, enterprise, PermissionGuard.Access.WRITE);
            var existing = findBySha(c, enterprise, sha);
            if (existing != null) return existing;
            var stored = new StoredFile(UUID.randomUUID().toString(), enterprise, name, nextVersion(c, enterprise, name), sha,
                    "PDF", content.length, session.userId(), Instant.now().toString(), true, true);
            insertFile(c, stored, content);
            return stored;
        });
        // The model call runs outside any transaction; its failure leaves a manual draft, never an import.
        String errorCode = null, model = "MANUAL"; Map<String, String> fields = new LinkedHashMap<>(); var unreadable = new ArrayList<String>();
        try {
            var reading = reader.read(pages);
            model = reading.model() == null || reading.model().isBlank() ? "vision" : reading.model();
            if (!draftFields(reading.text(), fields, unreadable)) { errorCode = "MODEL_RESPONSE_INVALID"; fields.clear(); unreadable.clear(); }
        } catch (ScanReadFailure e) { errorCode = e.code();
        } catch (Exception e) { errorCode = "MODEL_FAILURE"; }
        for (var field : SCAN_FIELDS) fields.putIfAbsent(field, "");
        var checks = scanChecks(fields);
        String draftId = UUID.randomUUID().toString();
        var status = errorCode == null ? "DRAFT" : "MANUAL";
        var draftModel = errorCode == null ? model : "MANUAL:" + errorCode;
        var unreadableList = List.copyOf(unreadable);
        var stored = transaction(c -> {
            guard.require(c, session, enterprise, PermissionGuard.Access.WRITE);
            var check = new ArrayList<>(checks);
            if (!fields.get("invoice_id").isBlank() && exists(c, "invoices", enterprise, fields.get("invoice_id"))) check.add("EXISTING_INVOICE");
            try (var s = c.prepareStatement("INSERT INTO scan_drafts VALUES (?,?,?,?,?,?,?)")) {
                s.setString(1, draftId); s.setString(2, file.id()); s.setString(3, enterprise); s.setString(4, session.userId());
                s.setString(5, Instant.now().toString()); s.setString(6, draftModel);
                s.setString(7, draftJson(fields, unreadableList, check)); s.executeUpdate();
            }
            return List.copyOf(check);
        });
        String image = "data:image/png;base64," + Base64.getEncoder().encodeToString(pages.getFirst());
        return new ScanDraft(status, errorCode, draftId, file, Collections.unmodifiableMap(fields), unreadableList, stored, image);
    }

    /**
     * Creates the invoice only from the accountant's confirmed values, and only for a missing key. The amount must be
     * typed twice identically; every field is validated as an Excel row would be. Each draft is confirmed once.
     */
    public ScanImport confirm(PermissionGuard.Session session, String enterprise, String draftId, ScanConfirmation confirmation) {
        ToolContract.required(enterprise); ToolContract.required(draftId);
        if (confirmation == null) throw invalid("Confirmation required");
        String id = scanText(confirmation.invoiceId(), 64, true), seller = scanText(confirmation.seller(), 200, true),
                item = scanText(confirmation.item(), 200, true), remarks = scanText(confirmation.remarks(), 500, false);
        if (!id.matches("[A-Za-z0-9_-]{1,64}")) throw invalid("Invoice id may contain letters, digits, - and _ only");
        LocalDate date = isoDate(confirmation.invoiceDate());
        if (date == null) throw invalid("Invoice date must be yyyy-MM-dd");
        Long cents = amountCents(confirmation.amount());
        if (cents == null || cents <= 0) throw invalid("Amount must be a positive number with at most two decimals");
        if (!cents.equals(amountCents(confirmation.amountConfirmation())))
            throw new DomainException(ErrorCode.CONFIRMATION_REQUIRED, "The amount typed again does not match");
        var confirmed = new LinkedHashMap<String, String>();
        confirmed.put("invoice_id", id); confirmed.put("invoice_date", date.toString()); confirmed.put("seller", seller);
        confirmed.put("item", item); confirmed.put("amount", BigDecimal.valueOf(cents, 2).toPlainString()); confirmed.put("remarks", remarks);
        return transaction(c -> {
            guard.require(c, session, enterprise, PermissionGuard.Access.WRITE);
            String fileId, draftJson, createdBy;
            try (var s = c.prepareStatement("SELECT file_id,draft_json,created_by FROM scan_drafts WHERE id=? AND enterprise_id=?")) {
                s.setString(1, draftId); s.setString(2, enterprise);
                try (var r = s.executeQuery()) {
                    if (!r.next()) throw new DomainException(ErrorCode.NOT_FOUND, "Scan draft not found");
                    fileId = r.getString(1); draftJson = r.getString(2); createdBy = r.getString(3);
                }
            }
            if (!createdBy.equals(session.userId())) throw new DomainException(ErrorCode.NOT_FOUND, "Scan draft not found");
            try (var s = c.prepareStatement("SELECT 1 FROM scan_confirmations WHERE draft_id=?")) {
                s.setString(1, draftId);
                try (var r = s.executeQuery()) { if (r.next()) throw new DomainException(ErrorCode.CONFLICT, "This scan was already confirmed"); }
            }
            StoredFile file;
            try (var s = c.prepareStatement(SELECT_FILE + " WHERE f.id=?")) {
                s.setString(1, fileId);
                try (var r = s.executeQuery()) { r.next(); file = file(r); }
            }
            var differences = differences(draftJson, confirmed);
            boolean conflict = exists(c, "invoices", enterprise, id);
            if (!conflict) {
                try (var s = c.prepareStatement("INSERT INTO invoices VALUES (?,?,?,?,?,?,?,?)")) {
                    s.setString(1, enterprise); s.setString(2, id); s.setString(3, date.toString()); s.setString(4, seller);
                    s.setString(5, item); s.setLong(6, cents); s.setString(7, ""); s.setString(8, remarks); s.executeUpdate();
                }
                String location = "第1页（扫描件，人工确认）";
                try (var s = c.prepareStatement("INSERT INTO record_sources VALUES (?,?,?,?,?)")) {
                    s.setString(1, enterprise); s.setString(2, "INVOICE"); s.setString(3, id); s.setString(4, fileId);
                    s.setString(5, location); s.executeUpdate();
                }
                int next;
                try (var s = c.prepareStatement("SELECT COALESCE(MAX(seq),0)+1 FROM file_chunks WHERE file_id=?")) {
                    s.setString(1, fileId);
                    try (var r = s.executeQuery()) { next = r.getInt(1); }
                }
                insertChunks(c, fileId, List.of(new Chunk(next, location, "发票编号=" + id + "；开票日期=" + date + "；销售方名称=" + seller
                        + "；项目名称=" + item + "；价税合计=" + confirmed.get("amount") + "元" + (remarks.isEmpty() ? "" : "；备注=" + remarks)
                        + "（扫描件经模型识别后由会计逐项确认）")), next);
            }
            try (var s = c.prepareStatement("INSERT INTO scan_confirmations VALUES (?,?,?,?,?,?,?)")) {
                s.setString(1, draftId); s.setString(2, id); s.setString(3, conflict ? "CONFLICT" : "IMPORTED");
                s.setString(4, draftJson(confirmed, List.of(), List.of())); s.setString(5, String.join("\n", differences));
                s.setString(6, session.userId()); s.setString(7, Instant.now().toString()); s.executeUpdate();
            }
            return new ScanImport(conflict ? "CONFLICT" : "IMPORTED", file, id, cents, differences);
        });
    }

    /** First pages as PNG, scaled so the longer side stays within MAX_SCAN_PIXELS. */
    static List<byte[]> render(byte[] content) throws Exception {
        try (var document = Loader.loadPDF(content)) {
            if (document.isEncrypted()) throw invalid("Encrypted PDF");
            if (document.getNumberOfPages() == 0) throw invalid("PDF has no pages");
            var renderer = new org.apache.pdfbox.rendering.PDFRenderer(document);
            var pages = new ArrayList<byte[]>();
            for (int i = 0; i < Math.min(SCAN_PAGES, document.getNumberOfPages()); i++) {
                var box = document.getPage(i).getMediaBox();
                float longest = Math.max(box.getWidth(), box.getHeight());
                float scale = Math.min(SCAN_DPI / 72f, MAX_SCAN_PIXELS / Math.max(longest, 1f));
                var image = renderer.renderImage(i, scale, org.apache.pdfbox.rendering.ImageType.RGB);
                var out = new ByteArrayOutputStream();
                javax.imageio.ImageIO.write(image, "png", out);
                pages.add(out.toByteArray());
            }
            return List.copyOf(pages);
        }
    }
    private static final com.fasterxml.jackson.databind.ObjectMapper SCAN_JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    /** Only the known keys with string values are taken; a missing or non-string value counts as unreadable. */
    static boolean draftFields(String text, Map<String, String> fields, List<String> unreadable) {
        if (text == null) return false;
        var body = text.strip().replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
        com.fasterxml.jackson.databind.JsonNode node;
        try { node = SCAN_JSON.readTree(body); } catch (Exception e) { return false; }
        if (node == null || !node.isObject()) return false;
        for (var field : SCAN_FIELDS) {
            var value = node.get(field);
            String clean = value != null && value.isTextual() ? value.asText().strip() : "";
            if (clean.length() > 200 || clean.chars().anyMatch(ch -> Character.isISOControl(ch))) clean = "";
            fields.put(field, clean);
            if (clean.isEmpty() && !field.equals("remarks")) unreadable.add(field);
        }
        var said = node.get("unreadable");
        if (said != null && said.isArray()) said.forEach(v -> { if (v.isTextual() && SCAN_FIELDS.contains(v.asText()) && !unreadable.contains(v.asText())) unreadable.add(v.asText()); });
        // A date or amount the program cannot read exactly is left for the accountant rather than guessed.
        var date = isoDate(fields.get("invoice_date"));
        if (date != null) fields.put("invoice_date", date.toString());
        var cents = amountCents(fields.get("amount"));
        if (cents != null) fields.put("amount", BigDecimal.valueOf(cents, 2).toPlainString());
        return true;
    }
    /** The program's own checks on a draft. The 大写 amount is parsed and compared with the 小写 amount. */
    static List<String> scanChecks(Map<String, String> fields) {
        var checks = new ArrayList<String>();
        var cents = amountCents(fields.get("amount"));
        if (!fields.get("amount").isEmpty() && cents == null) checks.add("AMOUNT_INVALID");
        if (!fields.get("invoice_date").isEmpty() && isoDate(fields.get("invoice_date")) == null) checks.add("DATE_INVALID");
        var words = wordsToCents(fields.get("amount_in_words"));
        if (fields.get("amount_in_words").isEmpty() || words == null) checks.add("AMOUNT_WORDS_UNREADABLE");
        else if (cents != null) checks.add(words.equals(cents) ? "AMOUNT_WORDS_MATCH" : "AMOUNT_WORDS_MISMATCH");
        return List.copyOf(checks);
    }
    private static String draftJson(Map<String, String> fields, List<String> unreadable, List<String> checks) {
        var node = SCAN_JSON.createObjectNode();
        var f = node.putObject("fields"); fields.forEach(f::put);
        var u = node.putArray("unreadable"); unreadable.forEach(u::add);
        var k = node.putArray("checks"); checks.forEach(k::add);
        return node.toString();
    }
    private static List<String> differences(String draftJson, Map<String, String> confirmed) {
        var out = new ArrayList<String>();
        try {
            var fields = SCAN_JSON.readTree(draftJson).path("fields");
            confirmed.forEach((field, value) -> {
                var read = fields.path(field).asText("");
                if (!read.equals(value)) out.add(field + ": " + (read.isEmpty() ? "（未识别）" : read) + " → " + (value.isEmpty() ? "（空）" : value));
            });
        } catch (Exception e) { out.add("draft unreadable"); }
        return List.copyOf(out);
    }
    private static String scanText(String value, int max, boolean required) {
        var clean = value == null ? "" : value.strip();
        if (required && clean.isEmpty()) throw invalid("Required field missing");
        if (clean.length() > max || clean.chars().anyMatch(ch -> Character.isISOControl(ch))) throw invalid("Invalid field value");
        return clean;
    }
    static LocalDate isoDate(String text) {
        if (text == null) return null;
        var m = java.util.regex.Pattern.compile("^(\\d{4})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?$").matcher(text.strip());
        if (!m.matches()) return null;
        try { return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))); }
        catch (Exception e) { return null; }
    }
    /** Exact integer cents from "1,234.50" / "¥1234.5"; more than two decimals is rejected, never rounded. */
    static Long amountCents(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            var value = new BigDecimal(text.strip().replace(",", "").replace("¥", "").replace("￥", "").replace("元", ""));
            return value.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (Exception e) { return null; }
    }
    private static final String DIGITS = "零壹贰叁肆伍陆柒捌玖";
    private static final String SMALL_DIGITS = "〇一二三四五六七八九";
    /** Chinese capital amount (壹万贰仟叁佰肆拾伍元陆角柒分 / …元整) as cents; null when not strictly readable. */
    public static Long wordsToCents(String words) {
        if (words == null) return null;
        var text = words.replaceAll("[\\s:：()（）]|人民币|大写|[¥￥]", "").replace('圆', '元').replace('两', '二');
        text = text.replaceAll("[整正]$", "");
        if (text.isEmpty() || text.length() > 40) return null;
        int yuan = text.indexOf('元');
        String integer = yuan >= 0 ? text.substring(0, yuan) : "", fraction = yuan >= 0 ? text.substring(yuan + 1) : text;
        if (yuan < 0 && !fraction.matches(".*[角分]$")) return null;
        long total = 0, section = 0, number = 0;
        for (char ch : integer.toCharArray()) {
            int d = digit(ch);
            if (d >= 0) { number = d; continue; }
            switch (ch) {
                case '拾', '十' -> { section += (number == 0 ? 1 : number) * 10; number = 0; }
                case '佰', '百' -> { section += number * 100; number = 0; }
                case '仟', '千' -> { section += number * 1000; number = 0; }
                case '万' -> { section += number; total += section * 10_000; section = 0; number = 0; }
                case '亿' -> { section += number; total = (total + section) * 100_000_000; section = 0; number = 0; }
                default -> { return null; }
            }
            if (total > 1_000_000_000_000L) return null;
        }
        total += section + number;
        long jiao = 0, fen = 0; number = -1;
        for (char ch : fraction.toCharArray()) {
            int d = digit(ch);
            if (d >= 0) { number = d; continue; }
            if (ch == '角' && number >= 0) { jiao = number; number = -1; }
            else if (ch == '分' && number >= 0) { fen = number; number = -1; }
            else return null;
        }
        if (number > 0) return null; // A trailing digit without 角/分 is not a readable amount ("零" alone is fine).
        return total * 100 + jiao * 10 + fen;
    }
    private static int digit(char ch) {
        int d = DIGITS.indexOf(ch);
        return d >= 0 ? d : SMALL_DIGITS.indexOf(ch);
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
        final List<TableCandidate> tables = new ArrayList<>();
        void chunk(String location, String text) {
            var clean = text.strip();
            for (int start = 0; start < clean.length(); start += MAX_CHUNK_CHARS) {
                if (chunks.size() >= MAX_CHUNKS) { errors.add(new RowIssue(null, 0, null, "TOO_MANY_SEGMENTS")); return; }
                chunks.add(new Chunk(chunks.size() + 1, location, clean.substring(start, Math.min(clean.length(), start + MAX_CHUNK_CHARS))));
            }
        }
    }

    static Parsed parse(String kind, byte[] content, Set<String> otherEnterprises) {
        return parse(kind, content, otherEnterprises, Map.of());
    }
    static Parsed parse(String kind, byte[] content, Set<String> otherEnterprises, Map<String, SheetMapping> mappings) {
        try {
            return switch (kind) {
                case "XLSX", "XLS" -> parseWorkbook(content, otherEnterprises, mappings);
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
    private static Parsed parseWorkbook(byte[] content, Set<String> otherEnterprises, Map<String, SheetMapping> mappings) throws Exception {
        var parsed = new Parsed();
        var formatter = new DataFormatter(Locale.ROOT);
        // Excel's saved results are what the user saw; re-evaluating can fail (#VALUE!) or differ.
        formatter.setUseCachedValuesForFormulaCells(true);
        boolean ooxml = content.length > 1 && content[0] == 'P' && content[1] == 'K';
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(ooxml ? normalizeIsoDateCells(content) : content))) {
            FormulaEvaluator evaluator = null;
            var keys = new HashSet<String>();
            var seenSheets = new HashSet<String>();
            for (var sheet : workbook) {
                String name = sheet.getSheetName();
                seenSheets.add(name);
                Boolean invoice = null; Map<Field, Integer> columns = Map.of(); Map<Integer, String> headers = Map.of();
                int headerRow = -1;
                // T46: an explicit mapping replaces header detection for its sheet.
                var mapping = mappings.get(name);
                if (mapping != null) {
                    invoice = "INVOICE".equals(mapping.kind());
                    var mapped = new EnumMap<Field, Integer>(Field.class);
                    mapping.columns().forEach((field, column) -> mapped.put(Field.valueOf(field), column));
                    columns = mapped; headerRow = mapping.headerRow(); headers = new HashMap<>();
                }
                Candidate candidate = null;
                for (var row : sheet) {
                    int number = row.getRowNum() + 1;
                    var cells = new TreeMap<Integer, String>();
                    for (var cell : row) {
                        var text = display(formatter.formatCellValue(cell, evaluator).strip());
                        if (!text.isEmpty()) cells.put(cell.getColumnIndex(), text);
                    }
                    if (cells.isEmpty()) continue;
                    if (mapping != null && number == headerRow) headers.putAll(cells);
                    if (invoice == null && candidate != null && number > candidate.headerRow && candidate.samples.size() < MAX_SAMPLES)
                        candidate.samples.add(cells);
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
                                // T46: offered back for an explicit mapping instead of only being refused.
                                candidate = new Candidate(number, cells, "MISSING_COLUMNS", invoice ? "INVOICE" : "BANK");
                                invoice = null; continue;
                            }
                            columns = found; headerRow = number; headers = new HashMap<>();
                            for (var e : cells.entrySet()) headers.put(e.getKey(), e.getValue());
                        }
                        // T46: a row of several text cells may be a header the program does not know.
                        if (invoice == null && candidate == null && looksLikeHeader(cells))
                            candidate = new Candidate(number, cells, "UNRECOGNIZED_HEADER", null);
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
                // A sheet that never became a table but has a header-like row with data below it is offered back.
                if (mapping == null && invoice == null && candidate != null && candidate.worthOffering())
                    parsed.tables.add(candidate.table(name));
            }
            for (var mapped : mappings.keySet())
                if (!seenSheets.contains(mapped)) parsed.errors.add(new RowIssue(mapped, 0, null, "MAPPED_SHEET_NOT_FOUND"));
        }
        return parsed;
    }

    /** A header-like row: at least three cells that are words rather than numbers or dates. */
    static boolean looksLikeHeader(Map<Integer, String> cells) {
        return cells.values().stream().filter(text -> !text.matches("[-+¥￥]?[0-9][0-9,]*(\\.[0-9]+)?%?")
                && !text.matches("\\d{4}[-/.年]\\d{1,2}.*")).count() >= 3;
    }
    private static final class Candidate {
        final int headerRow; final TreeMap<Integer, String> cells; final String reason, kind;
        final List<TreeMap<Integer, String>> samples = new ArrayList<>();
        Candidate(int headerRow, TreeMap<Integer, String> cells, String reason, String kind) {
            this.headerRow = headerRow; this.cells = cells; this.reason = reason; this.kind = kind;
        }
        /**
         * Offered when the header was found but incomplete, when known wordings cover at least two required fields,
         * or when the rows below it read like records. Lists of names, field descriptions and summaries (the official
         * workbook's 企业与用户, 字段说明 and the monthly totals in 说明) are not tables to import.
         */
        boolean worthOffering() {
            if (samples.isEmpty()) return false;
            if ("MISSING_COLUMNS".equals(reason)) return true;
            var suggestion = suggest(cells, kind);
            if (score(suggestion.columns(), "BANK".equals(suggestion.kind()) ? BANK_REQUIRED : INVOICE_REQUIRED) >= 2) return true;
            return looksLikeRecords(samples);
        }
        private static final String DATE_TEXT = "\\d{4}[-/.年]\\d{1,2}([-/.月]\\d{1,2}日?)?.*";
        private static final String NUMBER_TEXT = "[-+¥￥]?[0-9][0-9,]*(\\.[0-9]+)?%?";
        /**
         * Record rows, not a summary: at least two rows, each with a date and an amount, and an identifier column
         * (text containing a digit, neither a number nor a date) whose value differs on every row. A monthly summary
         * repeats its enterprise id on every row and so has no such column.
         */
        static boolean looksLikeRecords(List<TreeMap<Integer, String>> rows) {
            if (rows.size() < 2) return false;
            boolean datesAndAmounts = rows.stream().allMatch(row -> row.values().stream().anyMatch(t -> t.matches(DATE_TEXT))
                    && row.values().stream().anyMatch(t -> t.matches("[-+¥￥]?[0-9][0-9,]*\\.[0-9]{1,2}|[-+¥￥]?[0-9][0-9,]{2,}")));
            if (!datesAndAmounts) return false;
            var columns = new TreeSet<Integer>();
            rows.forEach(row -> columns.addAll(row.keySet()));
            for (int column : columns) {
                var values = rows.stream().map(row -> row.getOrDefault(column, "")).toList();
                boolean identifiers = values.stream().allMatch(v -> v.matches(".*[0-9].*") && !v.matches(NUMBER_TEXT) && !v.matches(DATE_TEXT));
                if (identifiers && new HashSet<>(values).size() == values.size()) return true;
            }
            return false;
        }
        TableCandidate table(String sheet) {
            int width = cells.lastKey() + 1;
            for (var sample : samples) width = Math.max(width, sample.lastKey() + 1);
            width = Math.min(width, MAX_COLUMNS);
            var headers = row(cells, width);
            var rows = new ArrayList<List<String>>();
            for (var sample : samples) rows.add(row(sample, width));
            var suggestion = suggest(cells, kind);
            return new TableCandidate(sheet, headerRow, headers, List.copyOf(rows), suggestion.kind(), suggestion.columns(), reason);
        }
        private static List<String> row(TreeMap<Integer, String> cells, int width) {
            var out = new ArrayList<String>();
            for (int i = 0; i < width; i++) out.add(cells.getOrDefault(i, ""));
            return List.copyOf(out);
        }
    }

    /**
     * T46 hints only: common wordings for each field, longest first. A suggestion is shown to the accountant and is
     * applied only when they submit it as their mapping; the importer itself never reads these.
     */
    private static final Map<Field, List<String>> INVOICE_HINTS = hints(
            Field.ID, "发票编号,发票号码,发票号,票号,票据号码,票据号,invoiceno,invoicenumber,invoiceid",
            Field.DATE, "开票日期,发票日期,开票时间,日期,invoicedate,date",
            Field.SELLER, "销售方名称,销方名称,销售单位,开票单位,开票方,供应商,销售方,销方,seller,vendor,supplier",
            Field.ITEM, "货物或应税劳务名称,项目名称,商品名称,服务名称,货物名称,品名,项目,内容,item,description,goods",
            Field.AMOUNT, "价税合计,含税金额,发票金额,合计金额,总金额,金额,totalamount,amount,total",
            Field.PURPOSE, "费用用途,用途,事由,purpose",
            Field.REMARKS, "备注,说明,remarks,note,memo",
            Field.ENTERPRISE, "企业编号");
    private static final Map<Field, List<String>> BANK_HINTS = hints(
            Field.ID, "交易流水号,流水编号,流水号,交易编号,凭证号,transactionid,reference",
            Field.DATE, "交易日期,记账日期,交易时间,日期,transactiondate,date",
            Field.DIRECTION, "收支方向,借贷方向,收支类型,收/支,收支,方向,direction",
            Field.AMOUNT, "交易金额,发生额,金额,amount",
            Field.COUNTERPARTY, "对方账户名称,对方户名,对方名称,交易对方,对方单位,对方,counterparty,payee,payer",
            Field.REMARKS, "备注,摘要,用途,说明,memo,remarks",
            Field.ENTERPRISE, "企业编号");
    private static Map<Field, List<String>> hints(Object... pairs) {
        var out = new EnumMap<Field, List<String>>(Field.class);
        for (int i = 0; i < pairs.length; i += 2) out.put((Field) pairs[i], List.of(((String) pairs[i + 1]).split(",")));
        return Collections.unmodifiableMap(out);
    }
    record Suggestion(String kind, Map<String, Integer> columns) {}
    /** Exact wording first, then a header that contains a known wording; each column and field is used once. */
    static Suggestion suggest(Map<Integer, String> cells, String kindHint) {
        var invoice = suggestFor(cells, INVOICE_HINTS);
        var bank = suggestFor(cells, BANK_HINTS);
        String kind = kindHint != null ? kindHint
                : score(bank, BANK_REQUIRED) > score(invoice, INVOICE_REQUIRED) ? "BANK" : "INVOICE";
        return new Suggestion(kind, "BANK".equals(kind) ? bank : invoice);
    }
    private static long score(Map<String, Integer> columns, List<Field> required) {
        return required.stream().filter(f -> columns.containsKey(f.name())).count();
    }
    private static Map<String, Integer> suggestFor(Map<Integer, String> cells, Map<Field, List<String>> hints) {
        var out = new LinkedHashMap<String, Integer>();
        var used = new HashSet<Integer>();
        for (boolean exact : new boolean[]{true, false}) {
            for (var hint : hints.entrySet()) {
                if (out.containsKey(hint.getKey().name())) continue;
                search:
                for (var word : hint.getValue()) {
                    for (var cell : cells.entrySet()) {
                        if (used.contains(cell.getKey())) continue;
                        var text = header(cell.getValue()).toLowerCase(Locale.ROOT);
                        if (exact ? text.equals(word) : word.length() >= 2 && text.contains(word)) {
                            out.put(hint.getKey().name(), cell.getKey()); used.add(cell.getKey()); break search;
                        }
                    }
                }
            }
        }
        return Collections.unmodifiableMap(out);
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
