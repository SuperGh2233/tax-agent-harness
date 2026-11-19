-- T30 configurable agents (FINAL_ROUND_CONTRACT A5/B4). Versions are immutable; the highest version is in force.
CREATE TABLE IF NOT EXISTS agent_definitions (
 id TEXT NOT NULL,
 version INTEGER NOT NULL CHECK(version >= 1),
 payload TEXT NOT NULL,
 enabled INTEGER NOT NULL CHECK(enabled IN (0,1)),
 changed_by TEXT NOT NULL,
 changed_at TEXT NOT NULL,
 PRIMARY KEY(id, version)
);
-- Which agent a task was started with. Tasks created before T30 have no row and use the default agent.
CREATE TABLE IF NOT EXISTS task_agents (
 user_id TEXT NOT NULL,
 enterprise_id TEXT NOT NULL,
 task_id TEXT NOT NULL,
 agent_id TEXT NOT NULL,
 PRIMARY KEY(user_id, enterprise_id, task_id)
);
-- T31 global tool switches: append-only history; the latest row per tool is in force, no row = enabled.
CREATE TABLE IF NOT EXISTS tool_status (
 seq INTEGER PRIMARY KEY AUTOINCREMENT,
 tool TEXT NOT NULL,
 enabled INTEGER NOT NULL CHECK(enabled IN (0,1)),
 changed_by TEXT NOT NULL,
 changed_at TEXT NOT NULL
);
-- T31 skills: reusable instructions plus the read tools they need. Versions immutable, highest in force.
CREATE TABLE IF NOT EXISTS skill_definitions (
 id TEXT NOT NULL,
 version INTEGER NOT NULL CHECK(version >= 1),
 payload TEXT NOT NULL,
 enabled INTEGER NOT NULL CHECK(enabled IN (0,1)),
 changed_by TEXT NOT NULL,
 changed_at TEXT NOT NULL,
 PRIMARY KEY(id, version)
);
CREATE TRIGGER IF NOT EXISTS tool_status_append_only BEFORE UPDATE ON tool_status
BEGIN SELECT RAISE(ABORT, 'tool status history is append-only'); END;
CREATE TRIGGER IF NOT EXISTS tool_status_no_delete BEFORE DELETE ON tool_status
BEGIN SELECT RAISE(ABORT, 'tool status history is append-only'); END;
CREATE TRIGGER IF NOT EXISTS skill_definitions_immutable BEFORE UPDATE ON skill_definitions
BEGIN SELECT RAISE(ABORT, 'skill versions are immutable; save a new version'); END;
CREATE TRIGGER IF NOT EXISTS skill_definitions_no_delete BEFORE DELETE ON skill_definitions
BEGIN SELECT RAISE(ABORT, 'skill versions are retained for traceability'); END;
CREATE TRIGGER IF NOT EXISTS agent_definitions_immutable BEFORE UPDATE ON agent_definitions
BEGIN SELECT RAISE(ABORT, 'agent versions are immutable; save a new version'); END;
CREATE TRIGGER IF NOT EXISTS agent_definitions_no_delete BEFORE DELETE ON agent_definitions
BEGIN SELECT RAISE(ABORT, 'agent versions are retained for traceability'); END
