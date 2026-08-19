# session 能力

能力名称：session  
能力目录：`gate-application/src/main/java/gate/application/session/`，`gate-ports/src/main/java/gate/ports/SessionRepository.java`，`gate-ports/src/main/java/gate/ports/AgentConfigRepository.java`，`gate-ports/src/main/java/gate/ports/AgentSessionPort.java`，`gate-web/src/main/java/gate/web/session/`  
业务 owner：Web owner  
技术 owner：Application owner  
最低实施等级：L2  
复核/批准等级：L3  
状态：Implementing

## 1. 边界

- 负责的业务不变量：Agent 配置（AgentConfig）生命周期与参数校验、Agent 交互会话（Session）与消息流（SessionMessage）记录、会话进程/端口绑定与资源释放、Token 消耗统计。
- 明确不负责的内容：工单生命周期（ticket）、审核判决（review）。
- 上游/下游能力：上游 `ticket`（绑定 clone_path 与目标 ticket_no）；下游 `event`（消息 SSE 推送）、`provider`（大模型调用转发）。

## 2. 端口与实现

- 驱动端口：`SessionRoutes` 暴露的 `/api/agent-configs` 与 `/api/sessions` 路由。
- 被动端口：`AgentSessionPort`（驱动外部 CLI/进程）、`AgentConfigRepository`、`SessionRepository`、`TicketLockManager`、`PortAllocator`。
- local 实现：`OpenCodeServeAdapter`、`ClaudeHeadlessAdapter`、SQLite 会话存储。
- production 实现：容器化 Agent Sandbox、PostgreSQL 会话记录。
- 超时、取消、错误码：`USAGE`（参数缺失或非法）、`GATE_ERROR_IO`（进程启动失败）、`abort` 接口支持安全终止。

## 3. 数据与事件

- owner 表/列：`agent_config`（`id, name, cli, provider_id, model, system_prompt, extra_flags_json, description, created_at, updated_at`）、`session`（`id, ticket_no, agent_config_id, cli, status, cli_session_id, clone_path, allocated_port, prompt_tokens, completion_tokens, total_tokens, started_at, finished_at`）、`session_message`（`id, session_id, role, content, tool_calls_json, prompt_tokens, completion_tokens, total_tokens, degraded, timestamp`）。
- 只读投影：`/api/sessions/{id}/messages` 历史消息流。
- 写入事务边界：每条消息插入及 Token 累计写入均为独立事务。
- outbox 事件及 sequence：`session.started`、`session.message`、`session.finished`、`session.aborted`。
- 幂等键和 request digest：`sessionId` 与 `messageId` 唯一。
- 对象存储引用及 GC：无。

## 4. 并发与恢复

- 资源锁/CAS：基于 `TicketLockManager` 确保同一 Clone 上会话与提审互斥。
- lease/fence：进程级 Port 占用与进程存活监控。
- UNKNOWN_OUTCOME 查询方式：查询 session 表中的 status 与 finished_at。
- reconcile：服务重启时孤儿会话标记为 ABORTED。
- 节点宕机行为：Agent 子进程随宿主崩溃退出，重启后释放残留端口。

## 5. 权限与运营

- RBAC 权限：`Developer` 发起会话与发送指令。
- 审计事件：`session.start`、`session.abort`。
- 指标、trace、日志：会话持续时长、Token 消耗统计、工具调用分布。
- 告警和 runbook：会话进程卡死时调用 `/api/sessions/{id}/abort`。
- 成本/配额：Token 预算超限熔断。

## 6. 验收

- 单元/契约/架构测试：`SessionOrchestrationTest`、`OpenCodeServeAdapterTest`、`ClaudeHeadlessAdapterTest`、`AgentConfigApiTest`。
- 故障测试：进程异常退出与端口冲突恢复。
- 容量测试：多会话并发执行与流式消息推送测试。
- API/事件兼容证据：`/api/sessions/**` 与 `/api/agent-configs/**` 契约稳定。
- 数据库迁移和回滚证据：`V3__agent_configs.sql`、`V4__sessions.sql`。
