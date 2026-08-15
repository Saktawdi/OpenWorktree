-- Flyway V4: agent session orchestration (执行文档-后端-web §6.1, S2 基础层).
--
-- Session = multica Task 等价物: one execution of an AgentConfig against a ticket's clone.
-- Messages: 数量可能很大, 内容落 blob store (与 review_result 一致), DB 存路径.
-- Gate task: 异步任务元数据 (review/publish/session-send), 幂等键 id.
--
-- SQLite-compatible: no column types beyond TEXT/INTEGER, FK clauses on REFERENCES columns
-- referencing existing PK columns, and every ALTER TABLE ... ADD COLUMN appends only a single
-- nullable TEXT column (SQLite requires the new column default to be NULL). Column names follow
-- the existing V1/V3 conventions (ticket.ticket_no, provider.id, presubmit, etc.).

CREATE TABLE agent_config (
  id            TEXT PRIMARY KEY,
  name          TEXT NOT NULL,
  cli           TEXT NOT NULL,           -- OPENCODE | CLAUDE
  provider_id   TEXT NOT NULL REFERENCES provider(id),
  model         TEXT NOT NULL,
  system_prompt TEXT,
  extra_flags   TEXT,                   -- JSON array of strings
  description   TEXT,
  created_at    TEXT NOT NULL,
  updated_at    TEXT NOT NULL
);

CREATE TABLE agent_session (
  id              TEXT PRIMARY KEY,
  ticket_no       TEXT NOT NULL REFERENCES ticket(ticket_no),
  agent_config_id TEXT NOT NULL REFERENCES agent_config(id),
  cli             TEXT NOT NULL,
  status          TEXT NOT NULL,        -- ACTIVE | ABORTED | CLOSED
  cli_session_id  TEXT,                 -- claude session-id / opencode session id
  clone_path      TEXT NOT NULL,
  allocated_port  INTEGER,              -- opencode serve port; claude NULL
  context_file    TEXT,                 -- 工单上下文文件路径 (审计)
  prompt_tokens   INTEGER,              -- cumulative usage
  completion_tokens INTEGER,
  total_tokens    INTEGER,
  started_at      TEXT NOT NULL,
  finished_at     TEXT
);
CREATE INDEX idx_session_ticket ON agent_session(ticket_no);
CREATE INDEX idx_session_status ON agent_session(status);

CREATE TABLE session_message (
  id              TEXT PRIMARY KEY,
  session_id      TEXT NOT NULL REFERENCES agent_session(id),
  role            TEXT NOT NULL,        -- USER | ASSISTANT | TOOL | ERROR
  content_blob    TEXT NOT NULL,        -- blob store 路径
  content_bytes   INTEGER NOT NULL,
  tool_calls_blob TEXT,                 -- JSON array, 可空
  prompt_tokens   INTEGER,
  completion_tokens INTEGER,
  total_tokens    INTEGER,
  degraded        INTEGER NOT NULL DEFAULT 0,  -- usage 解析失败标记
  created_at      TEXT NOT NULL
);
CREATE INDEX idx_msg_session ON session_message(session_id, created_at);

CREATE TABLE gate_task (
  id           TEXT PRIMARY KEY,
  type         TEXT NOT NULL,          -- review | publish | session-send
  ticket_no    TEXT,
  session_id   TEXT,
  status       TEXT NOT NULL,          -- RUNNING | SUCCEEDED | FAILED
  result_json  TEXT,
  error_json   TEXT,
  started_at   TEXT NOT NULL,
  finished_at  TEXT
);
CREATE INDEX idx_task_status ON gate_task(status);

-- ticket 加可选 agent_config_id 关联 (工单创建时指定, 可空)
ALTER TABLE ticket ADD COLUMN agent_config_id TEXT REFERENCES agent_config(id);
