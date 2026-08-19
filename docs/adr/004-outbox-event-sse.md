# ADR-004：Transactional Outbox、事件序号与 SSE 游标

状态：Accepted  
负责人：Event owner  
批准人：Codex（项目负责人授权 L4）  
创建日期：2026-08-19  
批准日期：2026-08-19  
复审日期：2026-10-18  
验证证据计划：事务原子性、通知丢失、Relay 重放、cursor 过期、慢消费者和跨节点 SSE 测试

## 背景与约束

进程内 listener 无法跨节点回放，数据库提交后的尽力通知会永久丢失终态。事件投递允许重复但不得丢失可观察终态。

## 选项与取舍

- 内存事件总线：不可恢复，拒绝。
- 数据库提交后直接发消息：双写不一致，拒绝。
- 业务状态、事件与 outbox 同事务，Relay at-least-once：采用。

## 决策

- 业务状态、严格递增 sequence、`task_event` 和 outbox 在同一 owner 事务提交。
- sequence 使用任务行 `next_event_sequence` 原子递增并 `RETURNING`，禁止无锁 `MAX+1`。
- Event 能力拥有存储、Relay、游标和保留；业务能力拥有事件语义与兼容。
- Relay 使用 lease/attempt、available_at、指数退避；消费者按 event/outbox id 幂等。
- PostgreSQL `LISTEN/NOTIFY` 仅唤醒，数据库游标轮询是恢复事实来源；默认 fallback poll 2 秒。
- SSE 使用 `Last-Event-ID`，允许重复并由客户端去重；cursor 过期转快照，非法未来 cursor 拒绝。
- 默认事件在线保留 90 天；不得短于任务最大运行时间、最大重连窗口与灾备窗口之和。审计事件使用独立合规保留。
- 背压和配额采用 [`../architecture/sse-cursor-design.md`](../architecture/sse-cursor-design.md) 的批准基线。

## 负面后果

数据库写放大、Relay/归档运维和 schema 兼容成本增加；事件不是 exactly-once 投递，所有消费者必须幂等。

## 迁移、回滚与验证

Phase 2 先双写旧 listener 与持久化事件，再切换读取，最后删除内存事实来源。切换后不得回退为只依赖 listener。验证覆盖终态原子提交、通知丢失、重复 Relay、多 Web 节点、游标边界、连接风暴和慢消费者。
