# 数据与契约所有权目录

状态：生效目录（Accepted；后续 owner 变更仍须 L4 批准）
规则来源：[`governance.md`](governance.md) 第 4 节  
能力名称来源：[`capability-registry.md`](capability-registry.md)

## 1. 所有权模型

- **目标 owner**：最终唯一负责事实写入、状态迁移、兼容、保留和恢复的能力。
- **当前写入者**：当前代码实际执行写入的类/模块，可以与目标 owner 不一致；差距必须进入债务台账。
- **派生 owner**：只负责可重建投影，不拥有来源事实，不得回写来源字段。
- **事件语义 owner**：定义事件何时产生、payload 和兼容策略。
- **事件存储 owner**：`event` 能力，负责 outbox、sequence、回放、保留和传输。

一个事实对象只能有一个目标 owner。物理表中混有不同能力字段属于待拆分债务，不得通过“列级双 owner”永久维持。

## 2. 表与配置所有权

| 数据对象 | 当前写入者 | 目标 owner | 访问协议 | 当前/目标说明 |
| --- | --- | --- | --- | --- |
| `project` | Project repository/routes | `project` | Project use/query ports | 当前已存在，需收敛项目配置写入口 |
| `ticket` 核心字段与 stage | GateService/JdbcTicketRepository/routes | `ticket` | Ticket use/query ports | publish/review 不能直接更新 ticket；目标通过 ticket transition port |
| `ticket.exec_token_*`（当前物理列） | Metrics/session 路径 | `ticket`（迁移期物理写入控制） | Ticket owner 接收 projector command | 目标删除这些列并写入 `ticket_metrics_projection`，其 owner 为 `metrics` |
| `presubmit` | GateService/JdbcPresubmitRepository | `presubmit` | Presubmit ports | tree/base/diff 为 presubmit 事实 |
| `review_result` 核心证据字段 | GateService/JdbcReviewResultRepository | `review` | Review ports | verdict/evidence 为 review 事实 |
| `review_result.*tokens/*wall_ms/*diff_*`（当前物理列） | Review/metrics 路径 | `review`（迁移期物理写入控制） | Review owner 接收 projector command | 目标删除这些列并写入 `review_metrics_projection`，其 owner 为 `metrics` |
| `ticket_metrics_projection` | Metrics projector/Worm | `metrics` | Metrics projector/query port | Phase4 V10 已落地，独立投影 |
| `review_metrics_projection` | Metrics projector | `metrics` | Metrics projector/query port | Phase4 V10 已落地，独立投影 |
| `publish_intent` | GateService/JdbcPublishIntentRepository | `publish` | Publish ports/reconcile | publish 是唯一状态 owner |
| `gate_nonce` | LocalAuthoritativeGitService/JdbcNonceStore | `publish` | Nonce CAS port | Phase3 V11: nonce 单次消费，与 ref 原子 |
| `s3_object` | FsS3Store/JdbcS3Adapter | `presubmit`（Blob） | S3Store port | Phase3 V12: S3 digest 条件写，版本化 |
| `kms_key` | LocalKmsService | `security` | KmsService port | Phase3 V12: HMAC 本地 mock，KMS 轮换 |
| `gate_task` | TaskRunner/session adapters/JdbcGateTaskRepository | `task` | Task command/query ports | Phase3 V11 PG兼容 claim 索引 |
| `task_event` | JdbcGateTaskRepository | `event`（存储） | Event append/cursor ports | Phase2 已落地，W3C SSE cursor |
| `outbox` | JdbcGateTaskRepository | `event`（存储） | Outbox append/relay ports | Phase2 已落地，at-least-once relay |
| `gate_tenant` | TenantIsolationService | `security` | TenantPort | Phase4 V13: tenant registry, default backfill |
| `gate_user_role` | JdbcRbacStore | `security` | RbacPort | Phase4 V13: RBAC, SoD, tenant/project scoped |
| `audit_checkpoint` | WormAuditArchive | `security` | AuditArchivePort | Phase4 V13: WORM KMS checkpoint |
| `sod_exception` | RbacService | `security` | SoDPort | Phase4 V13: dual-approval exception |
| `slo_history` | SloService | `metrics` | MetricsPort | Phase4 V14: SLO evaluate |
| `backup_manifest` | BackupService | `status` | BackupPort | Phase4 V14: backup/restore manifest WORM |
| `health_probe` | HealthService | `status` | HealthPort | Phase4 V14: /livez /readyz probe |
| `alert_rule` | Metrics/InMemoryMetrics | `metrics` | AlertPort | Phase4 V14: alert thresholds (§13.4) |
| `agent_config` | Web routes/Jdbc repositories | `session` | Agent config ports | Provider/Secret 只存引用 |
| `agent_session` | Session adapters/repository | `session` | Session lifecycle ports | 状态与恢复等级归 session |
| `session_message` | Session adapters/repository | `session` | Message ports | 大消息使用 Blob 引用 |
| `provider`、`model` | Provider routes/repositories | `provider` | Provider command/query ports | Secret 明文不属于 provider |
| `credential` | CLI/Web credential paths | `security` | Credential validation/issuance ports | token hash、域和撤销归 security |
| GatePolicy 配置 | TOML/config classes | `security`（安全策略） | Versioned policy port | 必须版本化并进入授权 evidence |
| Worker/任务类型配置 | 进程配置 | `task` | Task type registry | 超时、attempt、重试、恢复等级 |
| Provider endpoint/model 配置 | provider 表/config | `provider` | Provider config port | Secret 引用由 security 提供 |

当前混表列必须在 Phase 1/2 通过 expand/contract 迁出，或由 ADR 证明长期共表仍不破坏唯一写入权。在迁移完成前，物理表 owner 对写事务负责，派生能力只能通过 owner 用例写入。

## 3. API/SSE 契约所有权

| 路由/资源 | 语义 owner | 驱动实现 | 目标访问协议 |
| --- | --- | --- | --- |
| `/api/health`, `/api/runtime`, `/api/status`, `/api/config` | `status` | gate-web | Read-only status ports；配置敏感字段脱敏 |
| `/livez`, `/readyz` | `status` | gate-web | HealthService livez/readyz (§13.3) |
| `/status/dependencies`, `/status/slo`, `/metrics` | `status`/`metrics` | gate-web | HealthService + SloService + InMemoryMetrics (§13-14) |
| `/api/auth/**` | `security` | gate-web | Auth command/query ports |
| `/api/audit/checkpoints` | `security` | gate-web | WormAuditArchive checkpoints (KMS, WORM) |
| `/api/agent-runtimes` | `status`（运行环境只读视图） | gate-web | Runtime/status query port；不得写入 Agent 配置事实 |
| `/api/projects/**`, `/api/workspaces/**` | `project` | gate-web | Project ports |
| `/api/tickets/**` | `ticket`；子资源按 `presubmit/review/publish` | gate-web | 各能力 command/query ports，不由巨型 router 实现业务 |
| `/api/tasks/**` | `task` | gate-web | Task query/cancel ports |
| `/api/tasks/{id}/events` | `task` 语义 + `event` 传输 | SSE handler | Event cursor port |
| `/api/providers/**`, `/api/models/**` | `provider` | gate-web | Provider ports |
| `/api/agent-configs/**` | `session` | gate-web | Agent config ports |
| `/api/sessions/**` | `session` | gate-web | Session command/query ports |
| `/api/sessions/{id}/events` | `session` 语义 + `event` 传输 | SSE handler | Event cursor/session stream port |
| `/api/metrics/**`、成本/容量视图 | `metrics` | gate-web | Metrics query ports，只读投影 |
| `/api/reconcile` | `publish` | gate-web | Reconcile command port；只触发可恢复收敛，不直接修改状态 |
| `/api/backup` | `status` | gate-web | BackupService backup/restore (§15) |

API owner 决定契约和授权；Web 只是驱动适配器。新增路由必须先登记本表，未知 `/api/*` 路径触发 `GOV-DATA-001/GOV-DOC-001`。

## 4. 事件、对象和派生数据所有权

| 对象 | 语义/事实 owner | 存储/传输 owner | 写入协议 | 保留/删除 |
| --- | --- | --- | --- | --- |
| `ticket.*` | `ticket` | `event` | ticket 事务 + outbox | ticket owner 定义语义保留 |
| `presubmit.*` | `presubmit` | `event` | presubmit 事务 + outbox | 与快照证据一致 |
| `review.*` | `review` | `event` | review 事务 + outbox | 审计策略 |
| `publish.*` | `publish` | `event` | publish/reconcile 事务 + outbox | 长期审计 |
| `task.*` | `task` | `event` | task 状态事务 + task_event/outbox | 按事件水位归档 |
| `session.*` | `session` | `event` | session 事务 + outbox | 会话保留策略 |
| `provider.*` | `provider` | `event` | provider 事务 + outbox | 配置审计 |
| `security.*` | `security` | `event` | security 事务 + WORM 审计 | 合规保留 |
| `diff/*` Blob | `presubmit` | Blob adapter | digest + 临时上传 + DB 引用 | owner GC |
| `review/*` Blob | `review` | Blob adapter | evidence digest | 审计保留 |
| `session/*` Blob | `session` | Blob adapter | message digest | 会话/租户策略 |
| `audit/*` WORM | `security` | Audit adapter | KMS checkpoint | 只增不改 |
| 成本/容量/SLO 投影 | `metrics` | metrics store | 订阅来源事件重建 | 可重建，声明陈旧度 |
| 健康/运行状态投影 | `status` | status store/cache | 依赖探测与 metrics 聚合 | 短期、可丢弃 |

## 5. 新增对象准入

新增表、列、事件、API、配置项、对象前缀或派生指标时，PR 必须同时更新：

- 能力注册表和目标 owner。
- 当前写入者、唯一写入事务和访问端口。
- 租户/项目隔离字段。
- 幂等、版本、事件语义和存储 owner。
- 审计、指标、保留、删除和重建策略。
- 迁移、回滚和债务/豁免（如适用）。

没有 owner 的对象不得合并；Owner 变更必须有迁移期双读/兼容计划和 ADR。
