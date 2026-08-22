-- Flyway V15: agent_config.inject_context — 会话启动时注入项目/工单上下文作为系统提示词。
--
-- SQLite 限制：ALTER TABLE ADD COLUMN 只能追加可空 TEXT 列（新列默认值必须 NULL），
-- 因此这里不写 DEFAULT；语义由读取端解释：
--   NULL / '1' → 注入开启（缺省行为）
--   '0'        → 关闭

ALTER TABLE agent_config ADD COLUMN inject_context TEXT;
