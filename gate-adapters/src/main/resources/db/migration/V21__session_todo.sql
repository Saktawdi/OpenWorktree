-- V21: 会话任务清单快照（todolist 渲染重构）。
--
-- 此前 todos 只内嵌在 session_message.tool_calls_blob 里，前端切换会话/刷新后要全量
-- 扫描消息历史反解最后一条 todowrite 才能重建侧栏清单（慢、且流式中未落库的历史
-- 看不到本回合已写的 todo，催生一堆时序防御分支）。本表把「每会话一份任务清单」
-- 提升为一等实体：CLI 适配器在 tool 事件到达时即 upsert（不等整回合 idle 落库），
-- 读取端 messages/todos 响应直接附带；删除会话由 FK 级联清理。
--
-- 语义：行存在 = 有清单（todos_json 可为 "[]" = 已清空）；无行 = 从未有清单。
-- todos_json 为规范 TodoItem[] 数组（content/status/priority/id?），由
-- TodoSnapshots.canonicalJson 在写入前统一归一。
--
-- SQLite-compatible：FK 引用既有 PK 列（agent_session.id），ON DELETE CASCADE 依赖
-- SqliteDataSourceFactory 强制开启的 PRAGMA foreign_keys=ON。

CREATE TABLE session_todo (
  session_id TEXT PRIMARY KEY REFERENCES agent_session(id) ON DELETE CASCADE,
  todos_json TEXT NOT NULL,              -- 规范 TodoItem[] JSON（含 "[]" 清空态）
  updated_at TEXT NOT NULL               -- 诊断用（写入时刻，ISO-8601）
);
