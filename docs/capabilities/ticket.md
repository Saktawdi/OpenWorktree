# ticket 能力

能力名称：ticket  
能力目录：`gate-application/src/main/java/gate/application/ticket/`，`gate-ports/src/main/java/gate/ports/TicketRepository.java`，`gate-web/src/main/java/gate/web/ticket/`  
业务 owner：Web owner  
技术 owner：Application owner  
最低实施等级：L2  
复核/批准等级：L3  
状态：Implementing

## 1. 边界

- 负责的业务不变量：工单 `ticket_no` 唯一、stage 单向流转（PENDING→IN_PROGRESS→PRESUBMITTED→IN_REVIEW→READY_TO_PUBLISH/DONE）、title/priority/labels 合法性
- 明确不负责的内容：快照/tree/diff（presubmit）、审核判决（review）、Git CAS 发布（publish）、会话（session）
- 上游/下游能力：上游 `project`（project_id 归属）；下游 `presubmit`、`review`、`publish` 仅通过 `TicketRepository` 只读或 `updateStage` 受控迁移

## 2. 端口与实现

- 驱动端口：`TicketRepository.find/findAll/updateStage/updateEditable/updateAgentConfig`；`GateService` 透出 ticket 查询
- 被动端口：`TicketRepository` JDBC 实现 `JdbcTicketRepository`
- local 实现：SQLite `ticket` 表，`target_ref`/`clone_path` 存储
- production 实现：PostgreSQL 同 schema（当前 SQLite 为 local，team 需 PG 适配器）
- 超时、取消、错误码：`USAGE`（非法 stage 越权）、`GATE_ERROR_IO`（clone 丢失）；无长任务超时

## 3. 数据与事件

- owner 表/列：`ticket` 事实表（`ticket_no, title, target_ref, clone_path, stage, priority, labels, project_id, description, note, agent_config_id`）
- 只读投影：`TicketStatus` 经 `status` 能力聚合
- 写入事务边界：`updateStage` 单独事务；`presubmit` 中 `insert presubmit + updateStage` 同事务（由 presubmit owner 发起，ticket 仅暴露受控方法）
- outbox 事件及 sequence：`ticket.created`/`ticket.updated`/`ticket.stage_changed` 由 ticket 事务 + outbox（`event` 能力存储，ticket 语义）
- 幂等键和 request digest：`ticket_no` 为天然幂等键；重复创建同 `ticket_no` 返回 `USAGE`
- 对象存储引用及 GC：无 Blob，仅 clone 路径（project 管理）

## 4. 并发与恢复

- 资源锁/CAS：单 ticket `ticket_no` 版本号隐式 CAS（`updated_at`）；`TicketLockManager` 文件锁用于 clone 互斥（local）
- lease/fence：无（非长任务）
- UNKNOWN_OUTCOME 查询方式：无
- reconcile：无
- 节点宕机行为：无持久任务，重启后读库即可

## 5. 权限与运营

- RBAC 权限：`Developer` 创建/编辑；`ProjectAdmin` 跨项目越权校验由 `projectId` 归属检查保障（当前 `GOV-DATA-001` 单租户简化）
- 审计事件：`ticket.created`/`ticket.stage_changed` 经 `AuditLog`
- 指标、trace、日志：`ticket.stage` 计数；trace 关联 `ticket_no`
- 告警和 runbook：`ticket not found` 归 `USAGE`；runbook 见 `docs/archive/legacy-adr-mapping.md`
- 成本/配额：无

## 6. 验收

- 单元/契约/架构测试：`TicketRepository` CRUD 单元测试；`ApiRoutes` ticket 路由契约测试；ArchUnit `DEBT-003` 已全绿
- 故障测试：无
- 容量测试：无
- API/事件兼容证据：`/api/projects/{id}/tickets/**` 与 `/api/tickets/**` 双路径兼容，旧路径保留至 contract 冻结
- 数据库迁移和回滚证据：`V1__init.sql` ticket 表；`V5__ticket_meta` 扩展，回滚仅删列（expand/contract 规划见 `metrics-projection-design.md`）
