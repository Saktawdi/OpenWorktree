# review 能力

能力名称：review
能力目录：`gate-application/src/main/java/gate/application/review/`，`gate-ports/src/main/java/gate/ports/ReviewEngine.java`，`gate-web/src/main/java/gate/web/review/`，`gate-adapters/src/main/java/gate/adapters/engine/`
业务 owner：Review owner
技术 owner：Application owner
最低实施等级：L2
复核/批准等级：L3
状态：Implementing

## 1. 边界

- 负责的业务不变量：
  - 对 `presubmit` 锁定的 `tree_hash/baseCommit/diff` 调用 `ReviewEngine` 产生 `ReviewEvidence`，经 `GatePolicy` 判决 `PASS/REJECT/REQUIRES_HUMAN`
  - `review_result` 仅包含事实证据 `verdict/evidenceJson/coveredOk/degraded/rawBlob`，不混入 metrics 派生（见 `metrics-projection-design.md`）
  - Fail-Closed：证据缺失、引擎超时、策略版本未知均判 `NEEDS_HUMAN`/`REJECT`
- 明确不负责的内容：
  - 快照捕获（presubmit）、Git CAS 发布（publish）、工单字段编辑（ticket）、成本派生计算（metrics 投影）
- 上游/下游能力：
  - 上游 `presubmit`（`presubmit_id` + `Snapshot` 输入）
  - 下游 `publish`（`PublishAuthorization` 复用 `GatePolicy` 决策）、`metrics`（消费 `review.*` 事件投影）

## 2. 端口与实现

- 驱动端口：`ReviewEngine.review(ReviewRequest)`、`ReviewEngineFactory.forPrism/forManualVerdict`、`GatePolicy.decide`
- 被动端口：`ReviewResultRepository.insert/findLatestForPresubmit`，`BlobStore.put/get`（evidence/raw）
- local 实现：`PrismReviewEngine`（子进程调用，同步执行，超时可配置）、`ManualReviewEngineFactory`
- production 实现：同 local，team 模式复用临时工作区重建（`ADR-001`），S3 Blob 适配器替换 `FsBlobStore`
- 超时、取消、错误码：
  - 超时：引擎超时 → `EngineFailure` → `degraded=true`，不抛异常，策略判 `REQUIRES_HUMAN`
  - 取消：review 为同步短任务，无 lease；agent 会话取消由 `session` 能力处理
  - 错误码：`GATE_ERROR_CONFIG`（engine 未配置但 humanPass 缺失）、`REJECT_FINDINGS`

## 3. 数据与事件

- owner 表/列：`review_result`（`id, presubmit_id, engine_id, engine_version, provider_id, model_name, verdict, findings_blob, covered_ok, degraded, raw_blob, created_at`）— review 事实；`provider/model` 仅引用
- 只读投影：无（metrics 消费事件后投影）
- 写入事务边界：`reviewResults.insert + tickets.updateStage` 同事务（≤100ms，不跨 Git/Engine 调用；Engine 调用在事务外）
- outbox 事件及 sequence：`review.succeeded`/`review.rejected`/`review.needs_human`（review 事务 + outbox，`event` 存储，语义归 review，`sequence` 原子分配）
- 幂等键和 request digest：`presubmit_id` + `engine_version` 为幂等键；同 `presubmit_id` 重放返回已存 `review_result`
- 对象存储引用及 GC：`review/{ticket_no}/{round}/evidence.json` 与 `rawBlob` 经 `BlobStore`，GC 由 `review.owner` 按审计保留策略清理

## 4. 并发与恢复

- 资源锁/CAS：`review_result` 以 `presubmit_id` 唯一（同轮次不并发）；`TicketLockManager` 保证同 ticket 不并发 presubmit/review
- lease/fence：无（同步任务）
- UNKNOWN_OUTCOME 查询方式：`EngineReport` 为值对象，可安全重算；`UNKNOWN_OUTCOME` 时创建新 attempt 保留旧证据
- reconcile：无（review 无外部副作用，不涉及 publish intent）
- 节点宕机行为：同步执行，节点宕机则客户端收 `GATE_ERROR_IO`，重试为新 attempt

## 5. 权限与运营

- RBAC 权限：`Reviewer` 可触发 review；`Developer` 仅可查看 `review_result` 只读
- 审计事件：`review.PASS/REJECT/REQUIRES_HUMAN` 记录 `engine_id/reason/detail/tree/dangling`
- 指标、trace、日志：`review.verdict` 计数、`review.degraded` 计数、`engine.duration`；trace 关联 `ticket_no/round/engine_id`
- 告警和 runbook：`review degraded` 激增告警，runbook 检查 `provider/model` 配置与 `engine.cmd` 可用性
- 成本/配额：无（成本由 `CostHint` 提取，见 metrics）

## 6. 验收

- 单元/契约/架构测试：
  - `GateServiceImpl.review` 单元测试：PASS/REJECT/NEEDS_HUMAN 分支、Fail-Closed、evidence JSON 编解码
  - `ReviewEngine` 契约测试：`ManualVerdict` 与 `Prism` 双实现
  - ArchUnit：`gate-application/review` 仅依赖 `gate.ports`/`gate.domain`，不依赖 `gate.adapters.engine` 实现
- 故障测试：引擎超时→ `degraded` + `REQUIRES_HUMAN`；`engineConfigured=false` 且缺 `humanPass` → `USAGE`
- 容量测试：无
- API/事件兼容证据：`review.succeeded` payload 版本化，`engine_id` 变更需 ADR
- 数据库迁移和回滚证据：`V1__init.sql` review_result；metrics 派生列冻结见 `metrics-projection-design.md`，`expand` 新增投影表，`contract` 删旧列
