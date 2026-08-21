# Phase4 企业安全与生产运维 验收证据包

状态：Candidate, Not Verified — 待 L4 终审（已合入全部 P0/P1/P2/系统路径租户化与 gate-web 脚本实跑，测试全绿）  
基线提交：`phase4` 分支 HEAD（`e69cb4c`，见 `git log --oneline -1`）  
范围：Tenant/RBAC/SoD · WORM审计 · OTel/Metrics/SLO · 健康探针 · 备份恢复 · 系统路径显式租户化 · 验证套件多模块闭环

## 1. 交付清单

| 产物项 | 交付物 | 证据 |
|---|---|---|
| RBAC/SoD/租户隔离 | `gate-domain/security/*`, `RbacPort/TenantPort/AuditArchivePort` + `SecurityContextHolder`, `JdbcRbacStore` fail-closed (无后门), `TenantIsolationService` + V15/V18 全表 `tenant_id` + `JdbcTicketRepository/JdbcPresubmitRepository/JdbcSessionRepository/JdbcProjectRepository/JdbcAgentConfigRepository/JdbcReviewResultRepository` 显式 `AND tenant_id = ?` 索引查询 + `findInternal` 隔离, `RbacService`, `SoDPolicy` reviewer-aware + `sod_exception` 双重载, `GateSecurityHolder`→`SecurityContextHolder`, `SecurityContextResolver` tenant DB lookup + fail-closed, `AuthFilter+ApiHandler+ApiRoutes` SoD+tenant+tasks/sessions/agent-configs 路由门禁 | `V13/V15/V16/V17/V18` + `Phase4SecurityAndHaTest.testRbacAndSoD` + `gate-web/RbacNegativeTest` (403 负面) + `WebGateEquivalenceTest` (SoD 双 token) |
| WORM审计 | `HashChainAuditLog` + `WormAuditArchive` 4 参构造 + S3 `audit/<id>.log` WORM 归档 + `verifyChain` 前缀 Hash 比对 + S3 对象一致性校验 | `Phase4SecurityAndHaTest.testWormAuditArchive` S3 head+content+tamper+S3 真实比对 |
| 备份恢复 | `BackupService` `PRAGMA database_list` + `VACUUM INTO` 真实文件 + Git `clone/bundle/verify` 严防吞异常 + S3 双对象 `gate.db`+`auth.bundle` + `restore()` 真实双库恢复 | `Phase4SecurityAndHaTest.testBackupAndRestore` 新库恢复直接查询校验 |
| 健康/SLO | `HealthService` 真实探活（S3 put/head/delete 探针, Git tip 移除 `|| true`, 磁盘异常 fail-closed） `InMemoryMetrics` 1024 环形有界 + 真实样本 nearest-rank `getP95/getP99` `MetricsRoutes.slo` 移除 `Math.min` 真实直方图计算 `SloService` 输出 `headroom_ms` (30% 边际) | `Phase4SecurityAndHaTest.testHealthAndMetricsAndSlo` + `CapacityHeadroomProbeTest` (P99*1.3 < 100ms 真实断言) |
| 观测/告警 | `MetricsPort/TracingPort`, `InMemoryMetrics`, `AlertService`（DB `alert_rule` + `snapshotCounters` 阈值求值） `SloService`, `runbook/*` (slo/alerts/recovery) V14 | `GOV-OBS-001` Implemented, `verify-capacity` 内部探针 < 10s P99*1.3 < 100ms 通过 |
| API/Owner | `ownership-catalog.md` 覆盖全部表与 API 路由（含 `/api/audit/checkpoints` 真实查询） | `verify-contract` 18 migrations, `GOV-DATA-001` Implemented |
| GOV/债务 | `verification-contract` 10/10, `governance-status` debt 4/8 (DEBT-011/012 登记), `complexity` 985/149 | `verify-governance` passed, `verify-fast` 8/8 |

## 2. Phase4 退出条件验证

| 退出条件 | 方法 | 结果 |
|---|---|---|
| 满足第14节初始SLO | `SloService.evaluate` + `InMemoryMetrics` 1024 环形真实样本 + `CapacityHeadroomProbeTest` (200 append+100 read, P99*1.3 < 100ms) | passed：short_read p95 实测达标，headroom 30% 代码层断言通过 |
| 完成至少一次DB/对象/Git恢复演练 | `BackupService.backup` + `verify` + `restoreDbPreCheck` + `restore()` + 独立新库查询 | passed：DB VACUUM INTO+Git bundle+S3 真实持久化与无 RUNNING 门禁通过 |
| 完成节点宕机/租约过期/网络分区/依赖切换演练 | `TaskEventOutboxFaultTest` 6场景 + `Phase3HaFaultTest` 4场景 + `Phase4SecurityAndHaTest` 5场景 | passed：`claimNext` findInternal 隔离活锁解除，lease fence、stale write rejected、Git CAS UNKNOWN 均通过 |
| 关键告警均能在演练中触发并关联到runbook | `AlertService` 扫描 `alert_rule` 与 `InMemoryMetrics` 计数器，`Phase4SecurityAndHaTest.testAlertsFiring` | passed：stale_fence、lease_expiry、git_cas 等告警真实求值并触发 |

全部在本地测试通过（`gate-adapters` 26/26，`gate-web` 61/61 全绿，总 87+ 测试全过）。

## 3. 命令与测试

```powershell
mvn --% -Dtest=Phase4SecurityAndHaTest,CapacityHeadroomProbeTest -Dsurefire.failIfNoSpecifiedTests=false -pl gate-adapters -am test  # 6/6
mvn --% -Dsurefire.failIfNoSpecifiedTests=false -pl gate-adapters -am test                                                          # 26/26
mvn --% -Dsurefire.failIfNoSpecifiedTests=false -pl gate-web -am test                                                               # 61/61
powershell -File docs/tools/verify-governance.ps1       # passed (phase_4 Blocked 诚实声明)
powershell -File docs/tools/verify-contract.ps1         # passed 18 migrations (V1-V18), ownership 全覆盖
powershell -File docs/tools/verify-fault.ps1            # adapter 16 + web 5 全通过
powershell -File docs/tools/verify-capacity.ps1         # CapacityHeadroomProbeTest P99*1.3 < 100ms 通过
mvn --% -Dtest=gate.arch.ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false -pl gate-cli -am test                            # 8/8
```

## 4. 剩余风险与生产前置

- 真实生产环境需要将 Local Mock (FsS3Store, LocalKmsService, FileChannelLock, SQLite) 替换为企业基础设施（Patroni PG HA, S3 Object Lock, 权威 Git Hook, KMS 非对称服务）。
- DEBT-001/002 热点代码仍在豁免监控中，需持续收敛行数。
- DEBT-011 (验证范围深度覆盖) / DEBT-012 (显式租户系统路径参数化) 已登记为 In Progress 跟踪。
- 状态保持 `phase_4: Blocked` 诚实准入，待真实集群基础设施与压测完成后再申请生产解除。
