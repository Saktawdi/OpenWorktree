# event 能力

能力名称：event  
能力目录：`gate-application/src/main/java/gate/application/event/`，`gate-ports/src/main/java/gate/ports/TaskEventPort.java`，`gate-web/src/main/java/gate/web/SseHandler.java`  
业务 owner：Web owner  
技术 owner：Application owner  
最低实施等级：L3  
复核/批准等级：L4  
状态：Planned

## 1. 边界

- 负责的业务不变量：Transactional Outbox 投递保证、单任务内部严格单调递增 Sequence 编号、持久化 Event Log、SSE 客户端游标回放（Cursor Replay）与流式背压控制。
- 明确不负责的内容：业务事件的业务语义定义（各领域事件 payload 语义由 ticket/review/publish/session/task 等能力所有）。
- 上游/下游能力：上游 各业务能力（发布业务事件）；下游 Web 前端 / 订阅者（通过 SSE `/api/tasks/{id}/events` 消费流式事件）。

## 2. 端口与实现

- 驱动端口：`SseHandler` 与 `SessionSseHandler`。
- 被动端口：`TaskEventPort`、`OutboxRepository`。
- local 实现：内存广播通道 / SQLite event 表。
- production 实现：PostgreSQL Outbox 表 + CDC / LISTEN NOTIFY + Redis Pub/Sub。
- 超时、取消、错误码：客户端断开连接自动清理 Session，支持 `Last-Event-ID` 断线重连。

## 3. 数据与事件

- owner 表/列：`task_event` 表（`id, task_id, seq, event_type, payload_json, created_at`；设计见 DEBT-006 / ADR-004）。
- 只读投影：`/api/tasks/{id}/events` SSE 协议流。
- 写入事务边界：业务操作与对应 outbox event 必须在同一 DB 事务内写入（保证 Exactly-Once 产生）。
- outbox 事件及 sequence：由 event 框架自动分配自增 `seq`。
- 幂等键和 request digest：`task_id + seq` 联合唯一。
- 对象存储引用及 GC：无。

## 4. 并发与恢复

- 资源锁/CAS：基于 sequence 递增保证局部有序。
- lease/fence：无。
- UNKNOWN_OUTCOME 查询方式：通过游标查询未送达事件。
- reconcile：Outbox Relay 轮询线程确保未推送事件补偿推送。
- 节点宕机行为：基于数据库持久化事件表进行回放。

## 5. 权限与运营

- RBAC 权限：`Developer` 订阅对应任务/会话事件。
- 审计事件：`event.relay`。
- 指标、trace、日志：SSE 连接数、Outbox 延迟、事件回放量。
- 告警和 runbook：Outbox 堆积报警。
- 成本/配额：单客户端最大连接数限制。

## 6. 验收

- 单元/契约/架构测试：`WebSseTest`。
- 故障测试：客户端断线重连与事件补发测试。
- 容量测试：高并发 SSE 广播长连接稳定性测试。
- API/事件兼容证据：标准 W3C SSE 格式（`id: ...\nevent: ...\ndata: ...\n\n`）。
- 数据库迁移和回滚证据：待随 DEBT-006 接入 V9/V10 migration。
