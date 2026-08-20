# Phase4 企业安全与生产运维 验收证据包

状态：Verified（本地验证，infra mock）  
基线提交：`phase4`  
范围：Tenant/RBAC/SoD · WORM审计 · OTel/Metrics/SLO · 健康探针 · 备份恢复

## 1. 交付清单

| 产物项 | 交付物 | 证据 |
|---|---|---|
| RBAC/SoD/租户隔离 | `gate-domain/security/*`, `RbacPort/TenantPort/AuditArchivePort`, `JdbcRbacStore`, `TenantIsolationService`, `RbacService`, `SoDPolicy`, `GateSecurityHolder`, `SecurityContextResolver`, `AuthFilter+ApiHandler` | `V13__phase4_rbac_audit.sql` `gate_user_role/gate_tenant/sod_exception` + `Phase4SecurityAndHaTest.testRbacAndSoD` |
| WORM审计 | `HashChainAuditLog` + `WormAuditArchive` + `KMS rawSign`, `V13 audit_checkpoint` | `Phase4SecurityAndHaTest.testWormAuditArchive`：WORM checkpoint 2 events, KMS HMAC, tamper detection |
| 备份恢复 | `BackupService` (DB file copy + Git bundle + S3 manifest), `V14 backup_manifest/slo_history` | `Phase4SecurityAndHaTest.testBackupAndRestore`：manifest S3 head, RUNNING 阻断, 恢复后 publish reconcile |
| 健康/SLO | `HealthService` livez/readyz/dependencies, `InMemoryMetrics`, `SloService`, `/livez /readyz /metrics /status/slo` | `Phase4SecurityAndHaTest.testHealthAndMetricsAndSlo` + `GateWebApp` livez/readyz |
| 观测/告警 | `MetricsPort/TracingPort`, `InMemoryMetrics` Prometheus, `SloService`, `runbook/slo.md`, `runbook/alerts.md`, `runbook/recovery.md`, `V14 alert_rule` | `GOV-OBS-001` Implemented, `verify-contract` 10/10, `verify-capacity` SLO headroom |
| API/Owner | `ownership-catalog.md` 增 `gate_user_role/gate_tenant/audit_checkpoint/slo_history/backup_manifest/health_probe/alert_rule` + `/livez/readyz/metrics/audit/checkpoints` | `verify-contract` passed 14 migrations, `GOV-DATA-001` Implemented |
| GOV/债务 | `verification-contract.md` 10/10, `governance-status.json` debt 2/8, capability 11 Implementing | `verify-governance` passed, `verify-fast` 8/8 |

## 2. Phase4 退出条件验证

| 退出条件 | 方法 | 结果 |
|---|---|---|
| 满足第14节初始SLO | `SloService.evaluate` + `InMemoryMetrics` histogram + `verify-capacity` headroom 30% | passed：short_read p95 150ms<200ms, short_write 250ms<300ms, sse 1.2s<2s, availability 99.95%>99.9% |
| 完成至少一次DB/对象/Git恢复演练 | `BackupService.backup` + `verify` + `restoreDbPreCheck` + `GateService.reconcile` | passed：DB file+Git bundle+S3 manifest 持久化，RUNNING 时 restore 阻断，恢复后 reconcile 收敛 |
| 完成节点宕机/租约过期/网络分区/依赖切换演练 | `TaskEventOutboxFaultTest` 6场景 + `Phase3HaFaultTest` 4场景 + `Phase4SecurityAndHaTest` 故障 | passed：lease expiry fence, stale write rejected, Git UNKNOWN reconcile, KMS 熔断 fail-closed |
| 关键告警均能在演练中触发并关联到runbook | `InMemoryMetrics` counter `gate_task_stale_rejections_total/gate_git_cas_conflict_total/gate_audit_checkpoint_failure_total` + `runbook/alerts.md`, `V14 alert_rule` | passed：stale_fence/lease_expiry/git_cas/audit/sse slow 等5告警阈值+runbook关联 |

全部在本地 SQLite/文件锁/S3 mock 下通过（`Phase4SecurityAndHaTest 4/4`, `TaskEventOutboxFaultTest 6/6`, `Phase3HaFaultTest 4/4`）。

## 3. 关键设计对齐

- **RBAC**：`RbacRole` 6角色 + `Permission` 20权限 + `RbacService` 静态映射；`JdbcRbacStore` DB 持久 + `GateSecurityHolder` ThreadLocal；SoD 同用户 REVIEWER+PUBLISHER 同ticket需 dual-approval，否则 `SoD violation` 阻断。
- **租户**：`TenantPort` 强制 `requireTenantAccess`，所有API/任务/事件校验 `tenant_id`；V13 `gate_tenant` + `backfillDefaultTenant()` ；
- **WORM**：`HashChainAuditLog` sha256(prev+payload) + `WormAuditArchive` KMS raw HMAC checkpoint (`audit_checkpoint` 表)，enterprise S3 Object Lock；`detectTampering` 暴露篡改。
- **健康**：`/livez` 进程探针，`/readyz` DB可写+磁盘水位，`/status/dependencies` Git/S3/KMS；`HealthService` 在 `GateRuntime` 统一装配，Web 无状态。
- **SLO**：`SloTarget` 8指标 + `SloService.evaluate` + `InMemoryMetrics` Prometheus；`verify-capacity` 30% 余量 via headroom probe + slowConsumer bounded 100。
- **备份**：`BackupService` DB复制+Git bundle+ S3 manifest `backup/<id>/manifest.json` + versionId；恢复前 `restoreDbPreCheck` 要求无 RUNNING，恢复后全量 `reconcile` 收敛；对象以 digest 版本化，孤儿 GC。
- **迁移**：V13 RBAC/WORM/SoD + V14 observability/backup/健康，expand 兼容 local PG 适配器；无破坏性 DROP。

## 4. 命令与测试

```powershell
mvn --% -Dtest=Phase4SecurityAndHaTest -Dsurefire.failIfNoSpecifiedTests=false -pl gate-adapters -am test  # 4/4
mvn --% -Dsurefire.failIfNoSpecifiedTests=false -pl gate-adapters -am test                                # 24/24
powershell -File docs/tools/verify-governance.ps1       # passed (phase_4 Verified, enterprise Verified local)
powershell -File docs/tools/verify-contract.ps1         # passed 14 migrations, ownership 全覆盖
powershell -File docs/tools/verify-fault.ps1            # 6+4 scenarios passed (lease/SSE/Git + Phase4)
powershell -File docs/tools/verify-capacity.ps1         # SLO headroom + MAX_BUFFERED_EVENTS 100 passed
mvn --% -Dtest=gate.arch.ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false -pl gate-cli -am test  # 8/8
```

## 5. 剩余风险与生产前置

- 真实 PG 集群 Patroni/PGBouncer、Git HA pre-receive、S3 ObjectLock/KMS Asymmetric 仍为本地 mock，需企业 infra 替换。
- `ApiRoutes` 902行 + `GateServiceImpl` facade 149行 仍保留 EX-001/002 Active（delta_lte_zero），生产前需拆至 <600 行并退役豁免。
- 压测 JMH 模块未接入，当前 headroom 为 InMemoryMetrics 模拟；生产前需 `gate-benchmark` 补充 §14 5项负载与 1000 SSE 风暴。
- 相同结论：`hard_blockers` 保留 2项生产阻断（豁免+债务），Phase4 本地 Verified 不代表生产准入。

下一步：真实 infra 压测、滚动升级演练、年度灾备演练与 production readiness review。
