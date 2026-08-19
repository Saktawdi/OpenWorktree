# presubmit 能力

能力名称：presubmit  
能力目录：`gate-application/src/main/java/gate/application/presubmit/`，`gate-ports/src/main/java/gate/ports/SnapshotCapture.java`，`gate-ports/src/main/java/gate/ports/PresubmitRepository.java`，`gate-web/src/main/java/gate/web/presubmit/`  
业务 owner：Web owner  
技术 owner：Application owner  
最低实施等级：L2  
复核/批准等级：L3  
状态：Implementing

## 1. 边界

- 负责的业务不变量：工作区快照捕获（clone, auth, targetRef）、Tree/Base OID 生成、Diff Blob 存储、Presubmit 递增轮次分配（round 1..N）、空 Diff 拦截与快照完整性检查（Integrity Rules）。
- 明确不负责的内容：审核策略仲裁与判决（review）、Git CAS 提交与推送（publish）、工单元数据维护（ticket）。
- 上游/下游能力：上游 `ticket`（提供 clone 路径与 targetRef）；下游 `review`（基于 presubmit 生成的 tree/diff 进行审核）。

## 2. 端口与实现

- 驱动端口：`PresubmitHandler.handle(PresubmitCommand)`；`GateService.presubmit`。
- 被动端口：`SnapshotCapture`（Git 真实命令行快照）、`PresubmitRepository`（JDBC 存储）、`BlobStore`（Diff 内容持久化）、`TicketRepository`（更新 stage 为 PRESUBMITTED）、`AuditLog`。
- local 实现：`GitCli` + SQLite `presubmit` 表与文件系统 Blob。
- production 实现：PostgreSQL 同 schema 存储，对象存储 Blob（S3/MinIO）。
- 超时、取消、错误码：`REJECT_PRECONDITION`（空 diff 或 integrity 阻断）、`GATE_ERROR_IO`（Git 捕获异常）、`USAGE`（工单不存在）。

## 3. 数据与事件

- owner 表/列：`presubmit` 事实表（`id, ticket_no, review_round, tree_hash, base_commit, target_ref, diff_blob_path, diff_bytes, diff_sha256, changed_paths_json, integrity_blockers_json, integrity_warnings_json, created_at`）。
- 只读投影：`/api/tickets/{no}/presubmit/{round}/diff`，`/api/tickets/{no}/status` 轮次展示。
- 写入事务边界：快照捕获在事务外完成（I3 约束，不跨 Git）；`insert presubmit + update ticket stage` 在单一 DB 短事务内提交。
- outbox 事件及 sequence：`presubmit.created`，包含 round、treeHash 与 changedPaths。
- 幂等键和 request digest：每次提审基于当前工作区生成确定性 Tree Hash。
- 对象存储引用及 GC：`diffBlobPath` 指向 Diff 补丁内容，由 BlobStore 维护生命周期。

## 4. 并发与恢复

- 资源锁/CAS：提审时通过 `TicketLockManager` 获取 Clone 文件锁，防止并发写冲突。
- lease/fence：无（同步操作）。
- UNKNOWN_OUTCOME 查询方式：无。
- reconcile：无。
- 节点宕机行为：纯同步短操作，未提交事务自动回滚，无残留脏状态。

## 5. 权限与运营

- RBAC 权限：`Developer` 具备提审权限。
- 审计事件：`presubmit.ok`、`presubmit.blocked`、`presubmit.emptyDiff`。
- 指标、trace、日志：提审耗时、Diff 大小、变更文件数。
- 告警和 runbook：Integrity 阻断时提示修复本地 Git 状态。
- 成本/配额：Diff 大小超限保护。

## 6. 验收

- 单元/契约/架构测试：`PresubmitHandlerTest`、`ConcurrentPresubmitAndSessionTest`、`WebGateEquivalenceTest`。
- 故障测试：Git 进程崩溃与 IO 错误处理。
- 容量测试：大 Diff 与多文件变更测试。
- API/事件兼容证据：`/api/tickets/{no}/presubmit` POST 接口契约稳定。
- 数据库迁移和回滚证据：`V1__init.sql` 基础表，支持字段扩展。
