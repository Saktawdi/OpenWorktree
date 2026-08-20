# 架构治理下一步行动清单

状态：架构已 Accepted，Phase4 本地 Verified 进入生产准入等待
更新时间：2026-08-20

| 优先级 | 行动 | Owner | 截止/检查点 | 完成证据 | 阻断范围 |
| --- | --- | --- | --- | --- | --- |
| P1 | 将 `verify-fast` 接入仓库必跑 CI 并保存首个 CI 证据包 | Platform owner | 2026-09-18 | `docs/tools/verify-fast.ps1` 8/8, `verify-governance` passed (non-strict) | Phase 0/1 |
| P1 | 实现 `verify-contract` 最小入口：API/owner/迁移检查 | Platform owner | 2026-08-20 已完成 | `verify-contract` 14 migrations, ownership 全覆盖, GOV-DATA-001/GOV-API-001 已实现 | Phase 1/2 |
| P1 | 实现 `verify-fault` lease/SSE/Git UNKNOWN 场景 | Task/Event/Publish owners | 2026-08-20 已完成 | `TaskEventOutboxFaultTest` 6/6 + `Phase3HaFaultTest` 4/4 + `Phase4SecurityAndHaTest` 4/4, `verify-fault` passed | team/enterprise |
| P1 | 实现 `verify-capacity`：API、SSE、Worker、DB/Git 容量与 30% 余量 | Platform/Web owners | 2026-08-20 已完成 | `verify-capacity` passed (SLO headroom 30%, MAX_BUFFERED_EVENTS 100, InMemoryMetrics/SloService), `Phase4SecurityAndHaTest` health/SLO | enterprise/生产 |
| P1 | 完成 12 个能力实例的端口、数据、权限、测试和 runbook 回填 | Capability owners | 2026-08-20 已完成 | `capability-registry` 11 Implementing + 1 Baseline, `security` Implementing, `phase4-evidence` | Phase 1 |
| P1 | 实施 `review_result`/`ticket` metrics projection expand/backfill 和对账 | Review/Metrics owners | 2026-08-20 已完成 | `V10`+`V14` + `SloService` + `InMemoryMetrics` + `runbook/slo.md`, `debt-register` DEBT-010 Resolved | Phase 1/2 |
| P1 | 清理 Legacy ADR/旧路径引用并建立映射 | Documentation owner | 2026-08-20 已完成 | `GOV-DOC-001` 0, `legacy-adr-mapping.md` | Phase 0 |
| P1 | Phase4 企业安全与运维：RBAC/SoD/租户/WORM、SLO/告警/健康、备份恢复 | Security/Platform owners | 2026-08-20 已完成 | `phase4-evidence.md` + `Phase4SecurityAndHaTest` 4/4 + `runbook/*` + V13/V14 | Phase4 |

执行规则：行动项未达到截止日期时，必须更新债务台账状态和逾期动作；不得只修改本表日期而不补证据。

已关闭的 L4 行动：ADR-001~008 已 `Accepted`，EX-001/002 已 `Active`，三份高风险设计已条件批准，主架构已批准为实施基线；Phase4 本地 Verified 已完成，详见 [`phase4-evidence.md`](phase4-evidence.md) 与 [`../debt-register.md`](../debt-register.md)。
