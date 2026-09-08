-- V22: 逐消息精确的模型标注。
-- assistant 行在落库时记录本回合实际使用的模型（providerID + modelID），前端
-- 气泡 footer 的「当时请求的模型」标注从近似回退升级为逐消息精确。
-- 来源口径：上游实际值优先（opencode message.updated/backfill 的 info、claude
-- stream-json 的 message.model），上游未给出时回退发送端的请求值（--model /
-- prompt body 的 model 字段）。可空：存量行与 USER/ERROR 行没有该值，读取端
-- 按 NULL 回退既有的「会话当前模型」近似标注。
ALTER TABLE session_message ADD COLUMN model_provider TEXT;
ALTER TABLE session_message ADD COLUMN model_id TEXT;
