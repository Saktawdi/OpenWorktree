# ADR-002：PostgreSQL 任务领取、租约、Fencing 与重试

状态：Accepted  
负责人：Task owner  
批准人：Codex（项目负责人授权 L4）  
创建日期：2026-08-19  
批准日期：2026-08-19  
复审日期：2026-10-18  
验证证据计划：claim/renew/complete 契约测试、节点暂停、数据库切换和 stale fence negative test

## 背景与约束

at-least-once 调度允许任务重复领取；租约过期不代表旧 Worker 已停止。最终正确性必须由数据库条件写与外部 CAS 保证。

## 选项与取舍

- 进程队列或内存 owner：节点故障即丢失，拒绝。
- 分布式锁作为最终正确性：无法阻止过期进程写入，拒绝。
- PostgreSQL 原子 claim + lease + 单调 fence：需要更严格 schema 与测试，采用。

## 决策

- PostgreSQL 使用 `READ COMMITTED` 短事务和 `FOR UPDATE SKIP LOCKED` CTE/等价条件 `UPDATE ... RETURNING` 原子领取。
- 排序固定为 `priority DESC, available_at ASC, id ASC`；时间比较统一使用数据库服务器时间。
- 默认 lease 60 秒、每 20 秒续租；任务类型可收紧但续租周期不得超过 lease 的 1/2。
- 每次领取增加 `attempt` 与 `fence_token`；renew/progress/complete 必须匹配 `id + owner + attempt + fence + non-terminal status`。
- reaper 是唯一宣布租约过期的组件；旧 fence 更新 0 行并记录指标。
- 错误分为 transient、rebuild、input、integrity、unknown outcome；不可逆副作用遇到 unknown 必须先 reconcile。
- local SQLite 实现相同端口契约但不宣称多节点能力。

## 数据与迁移

采用 expand/backfill/contract，历史任务的 idempotency key 使用 `legacy:<task-id>`，项目归属不能为 NULL。PostgreSQL 与 SQLite 使用独立迁移文件。详细条件见 [`../architecture/task-event-outbox-design.md`](../architecture/task-event-outbox-design.md)。

## 负面后果、回滚与验证

任务表和 Worker 协议复杂度增加。迁移可在切换 claim 前回滚应用读取，但一旦产生 production fence，不得回退到不校验 fence 的写路径。验证必须覆盖暂停超过租约、双 Worker 竞争、重复终态、取消竞争、数据库切换和时钟漂移。
