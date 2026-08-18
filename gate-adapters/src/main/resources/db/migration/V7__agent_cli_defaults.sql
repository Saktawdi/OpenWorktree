-- Flyway V7: mark agent profiles as CLI-owned runtimes.
--
-- V4 made provider_id/model NOT NULL because AgentConfig originally represented an API-shaped
-- runtime. SQLite cannot ALTER COLUMN safely while agent_session and ticket reference this table.
-- The repository therefore stores a private cli-default sentinel in those legacy columns for a
-- local CLI profile, maps it to null at the domain boundary, and keeps this source marker ready for
-- a future API-backed runtime without weakening existing foreign-key guarantees.

ALTER TABLE agent_config ADD COLUMN runtime_source TEXT NOT NULL DEFAULT 'cli';
