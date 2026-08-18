-- Flyway V5: ticket metadata + project registry (web console integration).
--
-- The web UI needs two things the core schema did not model (执行文档-后端-web §4.1 extension):
--   1. a per-ticket priority (P0..P3) driving the kanban/home board ordering and grouping;
--   2. a project registry so the console can adopt a workspace (codex-style: pick a folder →
--      register it as a project) and tag tickets with it. Registration is an organisational
--      layer only — the gate topology (auth repo / clones) still comes from gate.toml (ADR-14).
--
-- SQLite-compatible, mirroring V4's constraints: TEXT/INTEGER columns only and every
-- ALTER TABLE ... ADD COLUMN appends a single nullable column.

CREATE TABLE project (
  id             TEXT PRIMARY KEY,
  name           TEXT NOT NULL,
  workspace_path TEXT NOT NULL UNIQUE,   -- normalized absolute path (no symlink resolution guarantees)
  target_ref     TEXT,                   -- informational; the authoritative target_ref stays on ticket
  auth_repo      TEXT,                   -- informational mirror of the gate topology
  created_at     TEXT NOT NULL,
  updated_at     TEXT NOT NULL
);

-- Queue priority. NULL = unset (the UI groups those under 未设置).
ALTER TABLE ticket ADD COLUMN priority TEXT;                 -- P0 | P1 | P2 | P3 | NULL

-- Optional project tag. Detached (set NULL) when the project row is deleted.
ALTER TABLE ticket ADD COLUMN project_id TEXT REFERENCES project(id);
