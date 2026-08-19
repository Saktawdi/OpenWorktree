-- V10__metrics_projection_expand.sql
-- Expand step for DEBT-010 / metrics-projection-design.md (L4 Approved)
-- Creates ticket_metrics_projection and review_metrics_projection tables.
-- Does NOT drop old columns from ticket or review_result (contract phase deferred to V11).

CREATE TABLE IF NOT EXISTS ticket_metrics_projection (
  ticket_no         TEXT PRIMARY KEY REFERENCES ticket(ticket_no),
  exec_token_total  INTEGER,
  exec_token_source TEXT,
  updated_at        TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS review_metrics_projection (
  review_result_id  INTEGER PRIMARY KEY REFERENCES review_result(id),
  presubmit_id      INTEGER NOT NULL,
  ticket_no         TEXT NOT NULL,
  review_round      INTEGER NOT NULL,
  prompt_tokens     INTEGER,
  completion_tokens INTEGER,
  total_tokens      INTEGER,
  token_source      TEXT,
  review_wall_ms    INTEGER,
  llm_wall_ms       INTEGER,
  diff_bytes        INTEGER,
  diff_lines        INTEGER,
  metric_basis      TEXT NOT NULL DEFAULT 'full',
  source_event_id   TEXT,
  updated_at        TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_review_metrics_ticket_round ON review_metrics_projection(ticket_no, review_round);
