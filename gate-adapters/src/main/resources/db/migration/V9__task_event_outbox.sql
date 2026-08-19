-- Flyway V9: Task Event, Outbox, and GateTask concurrency & fencing extensions.
-- Production Architecture §6, §8, §9 & ADR-002, ADR-004.

-- 1. Extend gate_task with lease, fencing, attempt, and idempotency fields
ALTER TABLE gate_task ADD COLUMN tenant_id TEXT NOT NULL DEFAULT 'default';
ALTER TABLE gate_task ADD COLUMN project_id TEXT;
ALTER TABLE gate_task ADD COLUMN idempotency_key TEXT;
ALTER TABLE gate_task ADD COLUMN request_digest TEXT;
ALTER TABLE gate_task ADD COLUMN priority INTEGER NOT NULL DEFAULT 0;
ALTER TABLE gate_task ADD COLUMN available_at TEXT;
ALTER TABLE gate_task ADD COLUMN lease_owner TEXT;
ALTER TABLE gate_task ADD COLUMN lease_until TEXT;
ALTER TABLE gate_task ADD COLUMN attempt INTEGER NOT NULL DEFAULT 0;
ALTER TABLE gate_task ADD COLUMN max_attempts INTEGER NOT NULL DEFAULT 3;
ALTER TABLE gate_task ADD COLUMN fence_token INTEGER NOT NULL DEFAULT 0;
ALTER TABLE gate_task ADD COLUMN next_event_sequence INTEGER NOT NULL DEFAULT 0;
ALTER TABLE gate_task ADD COLUMN timeout_at TEXT;
ALTER TABLE gate_task ADD COLUMN cancel_requested_at TEXT;
ALTER TABLE gate_task ADD COLUMN result_ref TEXT;
ALTER TABLE gate_task ADD COLUMN error_code TEXT;

-- 2. Persistent Task Event Table (W3C SSE Cursor & Replay backbone)
CREATE TABLE IF NOT EXISTS task_event (
  event_id     TEXT PRIMARY KEY,
  task_id      TEXT NOT NULL REFERENCES gate_task(id),
  sequence     INTEGER NOT NULL,
  event_type   TEXT NOT NULL,
  payload_ref  TEXT,
  payload_json TEXT,
  created_at   TEXT NOT NULL,
  expires_at   TEXT,
  UNIQUE(task_id, sequence)
);

CREATE INDEX IF NOT EXISTS ix_task_event_task_seq ON task_event(task_id, sequence);

-- 3. Transactional Outbox Table
CREATE TABLE IF NOT EXISTS outbox (
  outbox_id      TEXT PRIMARY KEY,
  aggregate_type TEXT NOT NULL,
  aggregate_id   TEXT NOT NULL,
  event_type     TEXT NOT NULL,
  payload_json   TEXT NOT NULL,
  created_at     TEXT NOT NULL,
  available_at   TEXT NOT NULL DEFAULT (datetime('now')),
  relayed_at     TEXT,
  lease_owner    TEXT,
  lease_until    TEXT,
  attempt        INTEGER NOT NULL DEFAULT 0,
  last_error     TEXT
);
