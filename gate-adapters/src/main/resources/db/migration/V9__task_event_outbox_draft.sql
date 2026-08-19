-- V9 draft: task_event + outbox + gate_task lease/fence extensions
-- STATUS: DRAFT - pending L4 approval (ADR-002/ADR-004) and expand/contract plan
-- Do not apply until design reviewed; gated by Phase 2 entry.

-- gate_task extensions (see task-event-outbox-design.md)
-- SQLite does not support IF NOT EXISTS for ADD COLUMN in older versions; guard via application migration runner
-- ALTER TABLE gate_task ADD COLUMN tenant_id TEXT NOT NULL DEFAULT 'default';
-- ALTER TABLE gate_task ADD COLUMN idempotency_key TEXT NOT NULL DEFAULT '';
-- Additional columns deferred to V10 expand step

CREATE TABLE IF NOT EXISTS task_event (
  event_id   TEXT PRIMARY KEY,
  task_id    TEXT NOT NULL,
  sequence   INTEGER NOT NULL,
  event_type TEXT NOT NULL,
  payload_json TEXT,
  created_at TEXT NOT NULL,
  expires_at TEXT,
  UNIQUE(task_id, sequence)
);

CREATE INDEX IF NOT EXISTS ix_task_event_task_seq ON task_event(task_id, sequence);

CREATE TABLE IF NOT EXISTS outbox (
  outbox_id      TEXT PRIMARY KEY,
  aggregate_type TEXT NOT NULL,
  aggregate_id   TEXT NOT NULL,
  event_type     TEXT NOT NULL,
  payload_json   TEXT NOT NULL,
  created_at     TEXT NOT NULL,
  relayed_at     TEXT
);
