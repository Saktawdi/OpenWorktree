# Metrics Projection 迁移设计（DEBT-010，L4 有条件批准）

状态：L4 有条件批准进入 expand/backfill，contract 删除仍需迁移证据  
批准记录：项目负责人授权 Codex 作为 L4 技术审批人，2026-08-19  
关联：`ownership-catalog.md` review_result.*tokens / ticket.exec_token_* 混表列；`debt-register.md` DEBT-010

## 1. 问题

- `review_result` 混入 `tokens/*wall_ms/diff_*` 派生列，`ticket` 混入 `exec_token_*`
- 目标 owner：`review`/`ticket` 仅管事实，`metrics` 派生投影可重建，不得回写事实表

## 2. 方案（expand/contract）

### 新表（metrics 投影）

```sql
CREATE TABLE ticket_metrics_projection (
  ticket_no TEXT PRIMARY KEY REFERENCES ticket(ticket_no),
  exec_token_total INTEGER,
  exec_token_source TEXT,
  updated_at TEXT NOT NULL
);

CREATE TABLE review_metrics_projection (
  review_result_id INTEGER PRIMARY KEY REFERENCES review_result(id),
  presubmit_id INTEGER NOT NULL,
  ticket_no TEXT NOT NULL,
  review_round INTEGER NOT NULL,
  prompt_tokens INTEGER, completion_tokens INTEGER, total_tokens INTEGER,
  token_source TEXT, review_wall_ms INTEGER, llm_wall_ms INTEGER,
  diff_bytes INTEGER, diff_lines INTEGER,
  metric_basis TEXT NOT NULL, -- 'full' or 'degraded'
  source_event_id TEXT,
  updated_at TEXT NOT NULL
);
```

### 迁移期

- 双写：review 事实与 outbox 在同一事务提交；MetricsProjector 幂等消费事件写 projection。禁止在 review 事务提交后做无恢复记录的“尽力双写”
- 读路径兼容：metrics 查询优先读 projection，回退读旧列
- 冻结：新 PR 禁止新增 `review_result.*tokens` 等列（verify-governance 增加列检查，待实现 GOV-DATA-001）

### 切换

- 回填历史数据后，切换 MetricsService 至 projection
- 观察一个发布窗口后，V10 删除旧列（contract）

## 3. L4 批准条件

- `source_event_id` 或等价来源摘要必须建立唯一约束，保证 projector 重放幂等。
- 回填需要记录总数、缺失数、摘要和可重跑检查点；读路径切换前新旧结果必须对账一致。
- 删除旧列前至少观察一个发布窗口，并提供 rollback/read fallback。
- 本 PR 起冻结事实表新增 metrics 列；任何新增列需 owner 审核和 ADR。

## 4. 后续实施决策

- 默认投影陈旧度目标 p95 ≤ 30 秒；超过 5 分钟告警。
- 投影可重建，不单独作为不可恢复事实；来源事件按 event 能力保留策略管理。
- contract 删除旧列必须由 Review/Metrics owners 共同签署迁移证据。
