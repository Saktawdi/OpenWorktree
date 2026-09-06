-- V20: 会话回合时间线（ZCode 式分段渲染）。
-- assistant 行在 parts_blob 里保存整回合按真实到达序排列的分段：
--   {"type":"text","text":...} | {"type":"thinking","text":...} |
--   {"type":"tool","name":...,"arguments_json":...,"result_json":...}
-- 可空：旧行（以及 USER/ERROR 行）没有 parts，读取端按 NULL 回退旧的
-- content + tool_calls 两段式渲染。content/tool_calls 依旧照写，作为
-- 兼容视图（旧前端、检索、成本统计不感知本列）。
ALTER TABLE session_message ADD COLUMN parts_blob TEXT;
