-- Flyway V1: initial schema.
--
-- Verbatim from 架构落地执行文档 §7.2. Table order respects the foreign keys
-- (provider before review_result, ticket before everything referencing it).
--
-- Connection PRAGMAs are applied by the DataSource, not here:
--   journal_mode=WAL, synchronous=FULL, foreign_keys=ON, busy_timeout=5000  (§7.1)
-- synchronous=FULL is load-bearing: publish_intent must be durable BEFORE commit-tree runs,
-- otherwise crash point C1/C2 loses its anchor and recovery has to guess (§7.4 I1).

CREATE TABLE ticket (
  ticket_no    TEXT PRIMARY KEY,
  title        TEXT NOT NULL,
  target_ref   TEXT NOT NULL,
  clone_path   TEXT NOT NULL,
  executor_provider_id TEXT,
  executor_model TEXT,
  reviewer_provider_id TEXT,
  reviewer_model TEXT,
  stage        TEXT NOT NULL,
  created_at   TEXT NOT NULL,
  updated_at   TEXT NOT NULL
);

-- LLM providers (OpenAI-compatible endpoints; ADR-9 routes everything through the newapi gateway).
-- api_key_ref holds a reference or ciphertext ONLY: the plaintext key must never appear in any
-- other table, in the audit log, in a commit trailer, or in argv (§6.1, §10.1.1).
-- P1 seeds a single row id='manual' so a human verdict can satisfy review_result.provider_id
-- without weakening the NOT NULL constraint.
CREATE TABLE provider (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL,
  base_url    TEXT NOT NULL,
  api_key_ref TEXT NOT NULL,
  type        TEXT NOT NULL,
  created_at  TEXT NOT NULL,
  updated_at  TEXT NOT NULL
);

-- Model list pulled from a provider by an explicit action, never implicitly during a review.
CREATE TABLE model (
  provider_id TEXT NOT NULL REFERENCES provider(id),
  model_name  TEXT NOT NULL,
  pulled_at   TEXT NOT NULL,
  PRIMARY KEY (provider_id, model_name)
);

CREATE TABLE presubmit (
  id           INTEGER PRIMARY KEY,
  ticket_no    TEXT NOT NULL REFERENCES ticket(ticket_no),
  review_round INTEGER NOT NULL,
  tree_hash    TEXT NOT NULL,
  base_commit  TEXT NOT NULL,
  target_ref   TEXT NOT NULL,
  diff_blob    TEXT NOT NULL,
  diff_bytes   INTEGER NOT NULL,
  diff_sha256  TEXT NOT NULL,
  created_at   TEXT NOT NULL,
  UNIQUE(ticket_no, review_round, tree_hash)
);

CREATE TABLE review_result (
  id           INTEGER PRIMARY KEY,
  presubmit_id INTEGER NOT NULL REFERENCES presubmit(id),
  engine_id    TEXT NOT NULL,
  engine_version TEXT NOT NULL,
  provider_id  TEXT NOT NULL REFERENCES provider(id),
  model_name   TEXT NOT NULL,
  verdict      TEXT NOT NULL,
  findings_blob TEXT NOT NULL,
  covered_ok   INTEGER NOT NULL,
  degraded     INTEGER NOT NULL,
  raw_blob     TEXT NOT NULL,
  created_at   TEXT NOT NULL
);

-- Write-ahead record of an intended publish.
-- UNIQUE(ticket_no, review_round, tree_hash) makes the intent unique;
-- UNIQUE(commit_sha) makes the EFFECT unique, which is what turns a replayed publish into a no-op
-- instead of a second commit (§7.3).
CREATE TABLE publish_intent (
  id            INTEGER PRIMARY KEY,
  ticket_no     TEXT NOT NULL REFERENCES ticket(ticket_no),
  review_round  INTEGER NOT NULL,
  tree_hash     TEXT NOT NULL,
  base_commit   TEXT NOT NULL,
  target_ref    TEXT NOT NULL,
  commit_message TEXT NOT NULL,
  author_name TEXT NOT NULL, author_email TEXT NOT NULL, author_date TEXT NOT NULL,
  committer_name TEXT NOT NULL, committer_email TEXT NOT NULL, committer_date TEXT NOT NULL,
  approval_id   TEXT NOT NULL,
  commit_sha    TEXT,
  status        TEXT NOT NULL,
  observed_ref_before TEXT, observed_ref_after TEXT,
  created_at    TEXT NOT NULL, finished_at TEXT,
  UNIQUE(ticket_no, review_round, tree_hash),
  UNIQUE(commit_sha)
);
