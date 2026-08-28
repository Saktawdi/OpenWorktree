-- Flyway V17: ticket restart history (重启已取消/已完成的工单).
--
-- Every restart of a terminal (DONE/CANCELLED) ticket records one row: the reason the
-- operator gave (mandatory — the PATCH stage transition refuses a restart without one),
-- the stage the ticket restarted from, and the round the restart opens (the next presubmit
-- round at restart time; presubmit keeps allocating MAX(review_round)+1, so the numbering
-- continues seamlessly across restarts).

CREATE TABLE ticket_restart (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  ticket_no  TEXT NOT NULL REFERENCES ticket(ticket_no),
  round      INTEGER NOT NULL,
  from_stage TEXT NOT NULL,
  reason     TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE INDEX idx_ticket_restart_ticket ON ticket_restart(ticket_no, id);
