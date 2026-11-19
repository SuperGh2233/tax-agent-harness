CREATE TABLE IF NOT EXISTS agent_tasks (user_id TEXT NOT NULL REFERENCES users(id), enterprise_id TEXT NOT NULL REFERENCES enterprises(id), id TEXT NOT NULL, status TEXT NOT NULL CHECK(status IN ('ACTIVE','WAITING_USER','COMPLETED','CANCELLED')), version INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(user_id,enterprise_id,id));
CREATE UNIQUE INDEX IF NOT EXISTS one_open_agent_task ON agent_tasks(user_id,enterprise_id) WHERE status IN ('ACTIVE','WAITING_USER');
CREATE TABLE IF NOT EXISTS agent_preferences (user_id TEXT NOT NULL REFERENCES users(id), enterprise_id TEXT NOT NULL REFERENCES enterprises(id), name TEXT NOT NULL, value TEXT NOT NULL, user_request TEXT NOT NULL, PRIMARY KEY(user_id,enterprise_id,name));
;
-- T33 personal memory: one user, every enterprise. Never holds enterprise, invoice or bank identifiers.
CREATE TABLE IF NOT EXISTS personal_preferences (user_id TEXT NOT NULL REFERENCES users(id), name TEXT NOT NULL, value TEXT NOT NULL, user_request TEXT NOT NULL, expires_on TEXT, updated_at TEXT NOT NULL, PRIMARY KEY(user_id,name));
-- T33 enterprise memory: shared by the users of one enterprise, changed only by users who may modify it (no semicolons in comments: this file is split on them).
CREATE TABLE IF NOT EXISTS enterprise_preferences (enterprise_id TEXT NOT NULL REFERENCES enterprises(id), name TEXT NOT NULL, value TEXT NOT NULL, user_request TEXT NOT NULL, expires_on TEXT, updated_by TEXT NOT NULL REFERENCES users(id), updated_at TEXT NOT NULL, PRIMARY KEY(enterprise_id,name))
;
-- T34 conversation log per task: the original text behind the compressed model view (no semicolons in comments)
CREATE TABLE IF NOT EXISTS task_turns (user_id TEXT NOT NULL, enterprise_id TEXT NOT NULL, task_id TEXT NOT NULL, seq INTEGER NOT NULL, user_message TEXT NOT NULL, agent_text TEXT, status TEXT NOT NULL, created_at TEXT NOT NULL, PRIMARY KEY(user_id,enterprise_id,task_id,seq));
-- T34 full tool results per turn, for recall when the model only saw a compacted view
CREATE TABLE IF NOT EXISTS task_tool_results (user_id TEXT NOT NULL, enterprise_id TEXT NOT NULL, task_id TEXT NOT NULL, turn_seq INTEGER NOT NULL, call_id TEXT NOT NULL, tool TEXT NOT NULL, arguments TEXT NOT NULL, result_json TEXT NOT NULL, PRIMARY KEY(user_id,enterprise_id,task_id,turn_seq,call_id))
