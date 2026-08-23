# publish 能力

能力名称：publish  
能力目录：`gate-application/src/main/java/gate/application/publish/`，`gate-ports/src/main/java/gate/ports/PublishIntentRepository.java`，`gate-ports/src/main/java/gate/ports/CommitPublisher.java`，`gate-ports/src/main/java/gate/ports/RefObserver.java`  
业务 owner：Web owner  
技术 owner：Application owner  
最低实施等级：L3  
复核/批准等级：L4  
状态：Implementing

## 1. 边界

- 负责的业务不变量：PublishIntent 持久化先于任何不可逆操作（§7.4 C0-C5 锚点）、确定性 Commit 构建（I1: 相同输入产生相同 SHA）、严格单次授权（ApprovalGrant）、Git Ref CAS 推送与结果观测、状态一致性 Reconcile（I4: 最终一致性以 Git 权威仓库为准）。
- 明确不负责的内容：审核策略判断（review 产出）、快照捕获（presubmit）。
- 上游/下游能力：上游 `review`（提供审核凭据与 authorization）、`presubmit`（提供待发布快照）；下游 `status`（提供发布结果投影）、`ticket`（流转至 DONE 终态）。

## 2. 端口与实现

- 驱动端口：`PublishHandler.handle(PublishCommand)`，`PublishHandler.reconcile(ReconcileCommand)`；`GateService.publish/reconcile`。
- 被动端口：`PublishIntentRepository`、`CommitPublisher`、`RefObserver`、`ApprovalStore`、`GatePolicy`、`LockManager`、`DbTransactionRunner`、`AuditLog`、`WorkspaceSyncer`。
- local 实现：`GitCli`、`GitCliWorkspaceSyncer`、`JdbcPublishIntentRepository`、`FileLockManager`、`InMemoryApprovalStore`。
- production 实现：权威 Git 仓库 CAS 推送、PostgreSQL PublishIntent 表、分布式 Lock/CAS。
- 超时、取消、错误码：`REJECT_TOCTOU`（工作区在审核后发生变更）、`REJECT_FINDINGS`（未通过审核）、`GATE_ERROR_IO`（推送失败转 PENDING）。

## 3. 数据与事件

- owner 表/列：`publish_intent` 事实表（`id, ticket_no, review_round, tree_hash, base_commit, target_ref, commit_message, author_identity, committer_identity, approval_id, commit_sha, status, observed_ref_before, observed_ref_after, created_at, finished_at, clone_path, auth_repo`）。
- 只读投影：`/api/reconcile` 结果，`/api/status` 中的 `published_in_auth`。
- 写入事务边界：严格遵守 I3（DB 事务绝不跨越 Git 推送网络调用），写 intent、更新 commitSha、更新 outcome 均为独立事务。
- outbox 事件及 sequence：`publish.pending`、`publish.done`、`publish.idempotent`、`publish.toctou`、`publish.workspace_sync`。
- 幂等键和 request digest：`(ticket_no, review_round, tree_hash)` 三元组；同三元组重复调用触发 tryResume 幂等快路径。
- 对象存储引用及 GC：无，关联的 Diff 来自 Presubmit Blob。

## 4. 并发与恢复

- 资源锁/CAS：基于 `LockManager.acquire(project, targetRef)` 进行目标分支并发互斥；推送采用 Git Ref CAS。
- lease/fence：PublishIntent 状态机驱动。
- UNKNOWN_OUTCOME 查询方式：调用 `reconcile` 检查权威 Git 仓库 tip 是否已包含目标 Commit。
- reconcile：自动/手动触发 reconcile，对 PENDING 状态依据 Git 真实 Ref 进行收敛（PUBLISHED 或 ABANDONED）。
- 节点宕机行为：C0-C5 各阶段崩溃均由 durable intent 锚定，重启后调用 reconcile 无缝恢复。

### 4.1 发布后工作区同步（workspace sync）

- **机制与语义**：发布收敛权威库（auth repo）后，PublishHandler 执行 best-effort 尾步调用 `WorkspaceSyncer`，回写关联项目的 `workspace_path`。
- **快进保护（ff-only）**：同步操作严格仅允许快进（未检出分支通过 `update-ref` CAS 前移；已检出当前分支且工作区干净时执行 `merge --ff-only`）。
- **非阻塞降级（DEFERRED）**：若工作区有未提交改动、分支分叉（diverged）或非 Git 目录等无法快进的情况，返回 `DEFERRED` 并附带原因 note，绝不影响发布本身的成败语义与终态流转。
- **远端不干预**：绝不执行 force/reset，绝不修改用户配置的 remote。GitHub 等外部远端始终由用户自行 push。
- **审计与结果透传**：触发审计事件 `publish.workspace_sync`；TaskRunner 的 `result_json` 透传 `workspace_sync_status`（`SYNCED` / `ALREADY` / `DEFERRED` / `null`）与 `workspace_sync_note` 字段。
- **手动补同步入口**：提供手动补同步与重试端点 `POST /api/projects/{id}/workspace-sync`，用于发布时未配置工作区、或解决本地工作区冲突后的人工推进。

## 5. 权限与运营

- RBAC 权限：`Developer` 触发发布；系统内部要求 `GatePolicy` 实时校验签发 `PublishAuthorization`。
- 审计事件：`publish.done`、`publish.pending`、`publish.toctou`、`publish.idempotent`、`publish.workspace_sync`、`reconcile`。
- 指标、trace、日志：发布耗时、CAS 冲突率、Reconcile 收敛量。
- 告警和 runbook：推送网络超时报警，提示执行 reconcile 自动收敛。
- 成本/配额：无。

## 6. 验收

- 单元/契约/架构测试：`AcceptanceTest`、`A5CrashRecoveryTest`、`BypassMatrixTest`、`ConcurrentApprovalTest`。
- 故障测试：C0~C5 崩溃注入演练全覆盖。
- 容量测试：高频并发发布互斥与排队测试。
- API/事件兼容证据：`/api/tickets/{no}/publish` 异步与同步接口契约保持兼容。
- 数据库迁移和回滚证据：`V1__init.sql`、`V7__publish_topology`。
