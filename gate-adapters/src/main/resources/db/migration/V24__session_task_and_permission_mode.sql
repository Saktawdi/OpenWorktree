-- V24: claude 会话任务清单（TaskCreate/TaskUpdate 平行链）+ 权限模式。
--
-- session_task 与 session_todo（V21）同构但语义不同：todowrite 每次调用都是全量快照
-- （last-write-wins 覆盖），claude 的 TaskCreate/TaskUpdate 则是增量事件——journal 是
-- 事件流的累积结果（TaskCreate 追加、TaskUpdate 按 id 改字段）。live 阶段 TaskCreate
-- 的 id 由 claude 服务端分配、result 未到，按 max+1 乐观入行；回合终态后从消息历史
-- 的 parts（result_json 携带 "Task #N created successfully"）全量重放整表覆盖，
-- journal 因此是消息历史的纯投影，任何 live 阶段的脏数据（幽灵任务/乐观 id 错位）
-- 在下一个成功回合自愈。
--
-- 语义：行存在 = 有任务列表（tasks_json 可为 "[]" = 全部已清）；无行 = 从未写过。
-- tasks_json 为规范 ClaudeTaskItem[] 数组（id/subject/description?/activeForm?/status）。
-- 删除会话由 FK 级联清理（PRAGMA foreign_keys=ON 由 SqliteDataSourceFactory 强制）。
--
-- permission_mode：claude headless 专属语义（--permission-mode 传参），opencode 不读。
-- NULL = 未设置，buildArgv 回退 acceptEdits（= 引入本列之前的硬编码默认，行为不变）。

CREATE TABLE session_task (
  session_id TEXT PRIMARY KEY REFERENCES agent_session(id) ON DELETE CASCADE,
  tasks_json TEXT NOT NULL,              -- 规范 ClaudeTaskItem[] JSON（含 "[]" 空态）
  updated_at TEXT NOT NULL               -- 诊断用（写入时刻，ISO-8601）
);

ALTER TABLE agent_session ADD COLUMN permission_mode TEXT;
