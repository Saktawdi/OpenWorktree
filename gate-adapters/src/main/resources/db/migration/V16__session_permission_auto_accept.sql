-- Flyway V16: per-session permission auto-accept switch (权限卡片自动允许).
--
-- When on, the opencode adapter answers each permission.asked with "once" on the
-- user's behalf instead of waiting for a card click. Boolean columns follow the V4
-- `degraded` INTEGER 0/1 style and ALTER ... NOT NULL DEFAULT follows the V13 style.

ALTER TABLE agent_session ADD COLUMN permission_auto_accept BOOLEAN NOT NULL DEFAULT 0;
