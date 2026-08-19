# Task Event / Outbox 设计（Phase 2，L4 有条件批准）

状态：L4 有条件批准；满足本文件迁移前置条件后进入 `V9__task_event_outbox.sql` 正式迁移  
批准记录：项目负责人授权 Codex 作为 L4 技术审批人，2026-08-19  
关联：`production-architecture.md` §6 / §8，`debt-register.md` DEBT-005/006，`capability-registry.md` task/event

## 1. 目标

- 按 §6.2 最小任务模型补全 `gate_task` lease/fence/idempotency 字段
- 按 §8.2 实现 Transactional Outbox 与 `task_event` sequence 原子分配
- 按 §9 实现 SSE 游标回放与背压（持久化事件事实来源为 PostgreSQL/SQLite，通知仅唤醒）

## 2. 表设计

### gate_task 增量

SQLite 与 PostgreSQL 必须使用各自迁移文件，不得在同一 SQL 中混用 `datetime('now')`、`SKIP LOCKED` 或方言相关约束。迁移采用 expand/backfill/contract：先增加可空列，按现有任务 ID 回填唯一 legacy 幂等键和项目归属，再建立非空与唯一约束。禁止把所有历史记录回填为相同的空字符串。

```sql
ALTER TABLE gate_task ADD COLUMN tenant_id TEXT;
ALTER TABLE gate_task ADD COLUMN project_id TEXT;
ALTER TABLE gate_task ADD COLUMN idempotency_key TEXT;
ALTER TABLE gate_task ADD COLUMN request_digest TEXT;
ALTER TABLE gate_task ADD COLUMN priority INTEGER NOT NULL DEFAULT 0;
ALTER TABLE gate_task ADD COLUMN available_at TEXT;
ALTER TABLE gate_task ADD COLUMN lease_owner TEXT;
ALTER TABLE gate_task ADD COLUMN lease_until TEXT;
ALTER TABLE gate_task ADD COLUMN attempt INTEGER NOT NULL DEFAULT 0;
ALTER TABLE gate_task ADD COLUMN max_attempts INTEGER NOT NULL DEFAULT 3;
ALTER TABLE gate_task ADD COLUMN fence_token INTEGER NOT NULL DEFAULT 0;
ALTER TABLE gate_task ADD COLUMN next_event_sequence INTEGER NOT NULL DEFAULT 0;
ALTER TABLE gate_task ADD COLUMN timeout_at TEXT;
ALTER TABLE gate_task ADD COLUMN cancel_requested_at TEXT;
ALTER TABLE gate_task ADD COLUMN result_ref TEXT;
ALTER TABLE gate_task ADD COLUMN error_code TEXT;

-- backfill 示例语义：
-- tenant_id='default'; project_id 从 ticket/project 关系回填；
-- idempotency_key='legacy:' || id；available_at=created_at。
-- 回填完成并验证无 NULL/重复后，PostgreSQL SET NOT NULL；SQLite 重建表。
CREATE UNIQUE INDEX ux_gate_task_idempotency
  ON gate_task(tenant_id, project_id, type, idempotency_key);
```

`project_id` 在目标 schema 中必须非空。无法关联项目的历史任务必须进入隔离报告，禁止用 `NULL` 绕过 PostgreSQL 唯一约束。

### task_event（持久化事件）

```sql
CREATE TABLE task_event (
  event_id   TEXT PRIMARY KEY,
  task_id    TEXT NOT NULL REFERENCES gate_task(id),
  sequence   INTEGER NOT NULL,
  event_type TEXT NOT NULL, -- task.created / task.claimed / task.progress / task.succeeded etc.
  payload_ref TEXT,
  payload_json TEXT,
  created_at TEXT NOT NULL,
  expires_at TEXT,
  UNIQUE(task_id, sequence)
);
CREATE INDEX ix_task_event_task_seq ON task_event(task_id, sequence);
```

### outbox

```sql
CREATE TABLE outbox (
  outbox_id  TEXT PRIMARY KEY,
  aggregate_type TEXT NOT NULL,
  aggregate_id TEXT NOT NULL,
  event_type TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  created_at TEXT NOT NULL,
  available_at TEXT NOT NULL,
  relayed_at TEXT,
  lease_owner TEXT,
  lease_until TEXT,
  attempt INTEGER NOT NULL DEFAULT 0,
  last_error TEXT
);
```

## 3. 原子性协议

- 任务创建 + 幂等登记 + `task.created` 事件 + outbox 写入在同一事务（§6.3）
- PostgreSQL Worker 领取使用一个短事务内的 CTE：候选查询 `FOR UPDATE SKIP LOCKED`，随后条件 `UPDATE ... RETURNING`；排序固定为 `priority DESC, available_at ASC, id ASC`。SQLite local 使用单写者条件更新实现同一契约。
- 所有 lease/available/timeout 比较使用数据库服务器时间。默认 lease 为 60 秒、每 20 秒续租；任务类型可以在注册表中收紧，但续租周期不得超过 lease 的 1/2。
- 续租/完成：`WHERE id=? AND lease_owner=? AND attempt=? AND fence_token=? AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED')`
- 事件 sequence：原子执行 `UPDATE gate_task SET next_event_sequence=next_event_sequence+1 ... RETURNING next_event_sequence`，禁止任何形式的 `SELECT MAX(sequence)+1`
- 任务状态更新、sequence 分配、`task_event` 和 outbox 写入必须位于同一事务；Relay 只投递，不得修改事件语义

## 4. 端口

- `TaskEventPort.append(taskId, eventType, payload, attempt, fenceToken)` 在 owner 事务内校验 fence、分配 sequence 并写 task_event + outbox
- `TaskEventPort.replay(taskId, afterSequence)` 游标查询 `sequence > cursor`
- `OutboxPort.relay(limit)` 使用 SKIP LOCKED 领取并投递，标记 relayed_at 幂等

## 5. L4 批准条件与验收

本批准允许进入实现，不代表 Phase 2 或生产准入。实现合并前必须：

- 提供 PostgreSQL 与 SQLite 独立迁移及回填/回滚验证。
- 对 NULL 项目、legacy 幂等键重复、迁移中断和重复执行提供 negative test。
- Outbox Relay 使用 lease/attempt、指数退避和幂等消费者，不能无限热循环。
- `UNKNOWN_OUTCOME` 外部副作用必须先 reconcile，不能由任务重试直接重复执行。

- 节点暂停超租约 → 旧 Worker 写入被 `stale_worker_write_rejected` 拒绝
- 重复请求同 idempotency_key → 单任务
- SSE 重连带 Last-Event-ID 不丢终态，重复事件可去重
- 慢消费者背压：单连接字节水位 + 写超时，无无界队列
