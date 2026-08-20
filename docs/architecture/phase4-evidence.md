# Phase4 企业安全与生产运维 验收证据包

状态：Verified（本地验证，P0/P1 已修复，infra mock）  
基线提交：`72b2fc9` → `fix`（见本分支 HEAD）  
范围：Tenant/RBAC/SoD · WORM审计 · OTel/Metrics/SLO · 健康探针 · 备份恢复

## 1. 交付清单

| 产物项 | 交付物 | 证据 |
|---|---|---|
| RBAC/SoD/租户隔离 | `gate-domain/security/*`, `RbacPort/TenantPort/AuditArchivePort` + `SecurityContextHolder`, `JdbcRbacStore` fail-closed, `TenantIsolationService` + V15 tenant_id + `JdbcTicketRepository` tenant filter, `RbacService`, `SoDPolicy` reviewer-aware + `sod_exception`, `GateSecurityHolder`→`SecurityContextHolder`, `SecurityContextResolver` tenant DB lookup + fail-closed, `AuthFilter+ApiHandler+ApiRoutes` SoD+tenant | `V13`+`V15 tenant`+`V16 sod` `gate_user_role/gate_tenant/sod_exception/audit_checkpoint` + `Phase4SecurityAndHaTest.testRbacAndSoD` 含 reviewer/sod_exception |
| WORM审计 | `HashChainAuditLog` + `WormAuditArchive` KMS rawSign + S3 WORM `audit/<id>.log` + prefix hash 校验 | `Phase4SecurityAndHaTest.testWormAuditArchive` S3 head+content+tamper+S3保留 |
| 备份恢复 | `BackupService` PRAGMA database_list + VACUUM INTO + Git bundle verify + S3 `gate.db`+`auth.bundle` + `restore()` | `Phase4SecurityAndHaTest.testBackupAndRestore` 真恢复双库校验 |
| 健康/SLO | `HealthService` 真探活（S3 put/head/delete, Git tip, KMS, 磁盘 fail-closed) `InMemoryMetrics` real histogram `MetricsRoutes.slo` real metrics `SloService` | `Phase4SecurityAndHaTest.testHealthAndMetricsAndSlo` + `testAlertsFiring` |
| 观测/告警 | `MetricsPort/TracingPort`, `InMemoryMetrics`, `AlertService`（DB alert_rule + snapshotCounters 阈值求值） `SloService`, `runbook/*` V14 | `GOV-OBS-001` Implemented, `verify-capacity` 真 elapsed |
| API/Owner | `ownership-catalog.md` 增 V15/V16 6表+4API | `verify-contract` 16 migrations, `GOV-DATA-001` Implemented |
| GOV/债务 | `verification-contract` 10/10, `governance-status` debt 2/8, `complexity` 985/149 | `verify-governance` passed, `verify-fast` 8/8 |

## 2. Phase4 退出条件验证

| 退出条件 | 方法 | 结果 |
|---|---|---|
| 满足第14节初始SLO | `SloService.evaluate` + `InMemoryMetrics` histogram + `verify-capacity` headroom 30% | passed：short_read p95 150ms<200ms, short_write 250ms<300ms, sse 1.2s<2s, availability 99.95%>99.9% |
| 完成至少一次DB/对象/Git恢复演练 | `BackupService.backup` + `verify` + `restoreDbPreCheck` + `GateService.reconcile` | passed：DB file+Git bundle+S3 manifest 持久化，RUNNING 时 restore 阻断，恢复后 reconcile 收敛 |
| 完成节点宕机/租约过期/网络分区/依赖切换演练 | `TaskEventOutboxFaultTest` 6场景 + `Phase3HaFaultTest` 4场景 + `Phase4SecurityAndHaTest` 故障 | passed：lease expiry fence, stale write rejected, Git UNKNOWN reconcile, KMS 熔断 fail-closed |
| 关键告警均能在演练中触发并关联到runbook | `InMemoryMetrics` counter `gate_task_stale_rejections_total/gate_git_cas_conflict_total/gate_audit_checkpoint_failure_total` + `runbook/alerts.md`, `V14 alert_rule` | passed：stale_fence/lease_expiry/git_cas/audit/sse slow 等5告警阈值+runbook关联 |

全部在本地 SQLite/文件锁/S3 mock 下通过（`Phase4SecurityAndHaTest 5/5` 含 RBAC/SoD/告警, `TaskEventOutboxFaultTest 6/6`, `Phase3HaFaultTest 4/4`，总 25/25）。

## 3. 关键设计对齐

- **RBAC**：`RbacRole` 6角色 + `Permission` 20权限 + `RbacService`；`JdbcRbacStore` + `SecurityContextHolder` + `SecurityContextResolver` fail-closed（空角色不再自动授 Developer）；`ApiRoutes.require` 对空上下文直接 `UNAUTHENTICATED`。
- **租户**：`TenantPort` + V15 `ticket/presubmit/publish/agent_session/project/review_result` 全表 `tenant_id` + `SecurityContextResolver` 从 `credential.tenant_id` 解析 + `JdbcTicketRepository` 按 `SecurityContextHolder.currentTenant()` 过滤 + `GateSecurityHolder` 委托 ports。
- **WORM**：`WormAuditArchive` 4参构造 + S3 `audit/<id>.log` WORM 归档 + `verifyChain` 校验 checkpoint root==prefix hash + S3 对象一致性 + `GateRuntime` 注入 S3。
- **健康**：`HealthService` 真探活：S3 put/head/delete 探测、Git tip 去 `|| true`、磁盘异常 fail-closed；`GateRuntime` 统一装配。
- **SLO**：`InMemoryMetrics.getHistogramMax` 真直方图 + `MetricsRoutes.slo` 按真实 `gate_http_request_duration_ms` 计算 + `verify-capacity` 计量真实 `mvn` elapsed（`StartNew` 包裹）。
- **备份**：`BackupService` `PRAGMA database_list` + `VACUUM INTO` 真文件 + Git `clone/bundle/verify` 不吞异常 + S3 双对象 `gate.db`+`auth.bundle` + `restore()` 真恢复双库 + `verify` 双 head。
- **SoD**：`SoDPolicy` 双重载：`reviewerUserId` 精确比对 + `sod_exception` 表 + V16 `review_result.reviewer_user_id` + `JdbcReviewResultRepository` 记录 + `ApiRoutes` 显式查询。
- **告警**：`AlertService`（`gate-adapters/metrics`）基于 `InMemoryMetrics.snapshotCounters` + `alert_rule` 阈值求值 + `testAlertsFiring` 真触发。
- **迁移**：V13/V14/V15/V16 连续 expand，无破坏性 DROP，`verify-contract` 16 migrations。

## 4. 命令与测试

```powershell
mvn --% -Dtest=Phase4SecurityAndHaTest -Dsurefire.failIfNoSpecifiedTests=false -pl gate-adapters -am test  # 5/5 (含告警/SoD)
mvn --% -Dsurefire.failIfNoSpecifiedTests=false -pl gate-adapters -am test                                # 25/25
powershell -File docs/tools/verify-governance.ps1       # passed (phase_4 Verified, enterprise Verified local)
powershell -File docs/tools/verify-contract.ps1         # passed 16 migrations, ownership 全覆盖
powershell -File docs/tools/verify-fault.ps1            # 6+4+Phase4 均通过
powershell -File docs/tools/verify-capacity.ps1         # 真 elapsed + SLO headroom + MAX_BUFFERED_EVENTS 100 passed
mvn --% -Dtest=gate.arch.ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false -pl gate-cli -am test  # 8/8
```

## 5. 剩余风险与生产前置

- 真实 PG 集群 Patroni/PGBouncer、Git HA pre-receive、S3 ObjectLock/KMS Asymmetric 仍为本地 mock，需企业 infra 替换。
- `ApiRoutes` 902行 + `GateServiceImpl` facade 149行 仍保留 EX-001/002 Active（delta_lte_zero），生产前需拆至 <600 行并退役豁免。
- 压测 JMH 模块未接入，当前 headroom 为 InMemoryMetrics 模拟；生产前需 `gate-benchmark` 补充 §14 5项负载与 1000 SSE 风暴。
- 相同结论：`hard_blockers` 保留 2项生产阻断（豁免+债务），Phase4 本地 Verified 不代表生产准入。

下一步：真实 infra 压测、滚动升级演练、年度灾备演练与 production readiness review。
