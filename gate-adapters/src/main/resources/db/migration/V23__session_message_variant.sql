-- V23: 逐消息精确的推理强度标注（与 V22 逐消息模型同一口径的 variant 补全）。
-- assistant 行落库时记录本回合钉住的推理强度（reasoning-effort）。上游（opencode/
-- claude CLI）均不回传实际生效的 effort，实际来源即发送端请求值：opencode prompt
-- body 的 variant 字段、claude 的 --effort argv。可空：未选档位、存量行与 USER/ERROR
-- 行为 NULL，读取端回退会话当前档位的近似标注。
ALTER TABLE session_message ADD COLUMN reasoning_variant TEXT;
