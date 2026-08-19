# task 能力

能力名称：task  
能力目录：`gate-application/src/main/java/gate/application/task/`，`gate-ports/src/main/java/gate/ports/TaskRegistry.java`，`gate-domain/src/main/java/gate/domain/task/`  
业务 owner：Web owner  
技术 owner：Application owner  
最低实施等级：L3  
复核/批准等级：L4  
状态：Baseline

## 1. 边界

- 负责的业务不变量：异步后台任务生命周期（SUBMITTED→RUNNING→SUCCEEDED/FAILED/CANCELLED）、任务租约（Lease）与分布式隔离（Fencing Token）、重试与幂等控制。
- 明确不负责的内容：具体用例业务逻辑（review、publish、agent execution 由对应 handler 执行）。
- 上游/下游能力：上游 `web`（提交 Review/Publish/AgentTask 并返回 202 + taskId）；下游 `event`（推送任务进度与终态事件）。

## 2. 端口与实现

- 驱动端口：`TaskRunner` 任务调度与提交接口。
- 被动端口：`TaskRegistry`（任务状态存取）。
- local 实现：`InMemoryTaskRegistry` / SQLite `gate_task` 表。
- production 实现：PostgreSQL 分布式任务队列表，支持 SKIP LOCKED 领券与 Lease 续期。
- 超时、取消、错误码：支持任务 Timeout 熔断与显式 Cancel。

## 3. 数据与事件

- owner 表/列：`gate_task` 事实表（`id, type, ticket_no, session_id, status, started_at, finished_at, result_json, error_json`；生产扩展字段 `lease_owner, lease_expires_at, attempt, max_attempts, fence_token` 见 DEBT-005）。
- 只读投影：`/api/tasks/{id}` 查询。
- 写入事务边界：任务状态转换独立提交。
- outbox 事件及 sequence：`task.submitted`、`task.running`、`task.finished`。
- 幂等键和 request digest：`taskId`。
- 对象存储引用及 GC：无。

## 4. 并发与恢复

- 资源锁/CAS：基于 `fence_token` 乐观并发更新。
- lease/fence：Worker 节点持有短租约，定期心跳续约。
- UNKNOWN_OUTCOME 查询方式：按 `taskId` 查表。
- reconcile：Reaper 线程定期扫描过期 Lease 任务并标记重试或超时失败。
- 节点宕机行为：宕机后 Lease 过期，由存活 Worker 接管。

## 5. 权限与运营

- RBAC 权限：系统内部 Worker 调度。
- 审计事件：`task.create`、`task.finish`。
- 指标、trace、日志：任务队列深度、执行耗时分布、重试次数。
- 告警和 runbook：队列积压与任务饥饿告警。
- 成本/配额：最大并发任务数限制。

## 6. 验收

- 单元/契约/架构测试：`TaskRegistryTest`。
- 故障测试：Worker 假死与 Lease 抢占测试。
- 容量测试：高并发任务入队与调度测试。
- API/事件兼容证据：`/api/tasks/{id}` 响应契约稳定。
- 数据库迁移和回滚证据：`V2__tasks.sql`，后续规划 V9 扩展迁移（DEBT-005）。
