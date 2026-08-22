-- Per-session runtime model override (会话内实时切换模型与推理强度).
-- NULL columns mean "use the AgentConfig defaults"; the web UI writes these when
-- the user switches model / reasoning-effort (variant) mid-session. The next
-- prompt_async send picks them up, mirroring OpenChamber's per-session picker.
ALTER TABLE agent_session ADD COLUMN override_provider TEXT;
ALTER TABLE agent_session ADD COLUMN override_model TEXT;
ALTER TABLE agent_session ADD COLUMN override_variant TEXT;
