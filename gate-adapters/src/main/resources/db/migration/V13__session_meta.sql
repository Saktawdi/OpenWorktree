-- Flyway V13: session metadata for the workbench session list (title / archived).
--
-- title is nullable: sessions may be created idle (empty initial_prompt) and titled later
-- via PATCH /api/sessions/{id}. archived is a list-only soft flag, orthogonal to the runtime
-- status (ACTIVE/ABORTED/CLOSED); boolean columns follow the V4 `degraded` INTEGER 0/1 style
-- and ALTER ... NOT NULL DEFAULT follows the V9 outbox style (SQLite allows a non-null default).

ALTER TABLE agent_session ADD COLUMN title TEXT;
ALTER TABLE agent_session ADD COLUMN archived INTEGER NOT NULL DEFAULT 0;
