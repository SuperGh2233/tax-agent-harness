-- T29 uploaded files (FINAL_ROUND_CONTRACT B1–B3). Additive; never alters business tables.
CREATE TABLE IF NOT EXISTS uploaded_files (
 id TEXT PRIMARY KEY NOT NULL,
 enterprise_id TEXT NOT NULL REFERENCES enterprises(id),
 name TEXT NOT NULL,
 version INTEGER NOT NULL CHECK(version >= 1),
 sha256 TEXT NOT NULL,
 kind TEXT NOT NULL CHECK(kind IN ('XLSX','XLS','DOCX','PDF')),
 size_bytes INTEGER NOT NULL,
 uploaded_by TEXT NOT NULL REFERENCES users(id),
 uploaded_at TEXT NOT NULL,
 content BLOB NOT NULL,
 UNIQUE(enterprise_id, sha256),
 UNIQUE(enterprise_id, name, version)
);
-- Text segments with their location in the source file, for answers that cite where a fact came from.
CREATE TABLE IF NOT EXISTS file_chunks (
 file_id TEXT NOT NULL REFERENCES uploaded_files(id),
 seq INTEGER NOT NULL,
 location TEXT NOT NULL,
 text TEXT NOT NULL,
 PRIMARY KEY(file_id, seq)
);
-- Which uploaded file and row a business record was imported from. Seed records have no row here.
CREATE TABLE IF NOT EXISTS record_sources (
 enterprise_id TEXT NOT NULL,
 kind TEXT NOT NULL CHECK(kind IN ('INVOICE','BANK_ENTRY')),
 record_id TEXT NOT NULL,
 file_id TEXT NOT NULL REFERENCES uploaded_files(id),
 location TEXT NOT NULL,
 PRIMARY KEY(enterprise_id, kind, record_id)
);
-- T32: whether a file's segments may serve as knowledge. No row = enabled. Files stay immutable.
CREATE TABLE IF NOT EXISTS file_knowledge_status (
 file_id TEXT PRIMARY KEY NOT NULL REFERENCES uploaded_files(id),
 enabled INTEGER NOT NULL CHECK(enabled IN (0,1)),
 changed_by TEXT NOT NULL REFERENCES users(id),
 changed_at TEXT NOT NULL
);
CREATE TRIGGER IF NOT EXISTS uploaded_files_immutable BEFORE UPDATE ON uploaded_files
BEGIN SELECT RAISE(ABORT, 'uploaded files are immutable; upload a new version'); END;
CREATE TRIGGER IF NOT EXISTS uploaded_files_no_delete BEFORE DELETE ON uploaded_files
BEGIN SELECT RAISE(ABORT, 'uploaded files are retained for traceability'); END
