# 架构治理下一步行动清单

状态：架构已 Accepted，进入实施与验证
更新时间：2026-08-19

| 优先级 | 行动 | Owner | 截止/检查点 | 完成证据 | 阻断范围 |
| --- | --- | --- | --- | --- | --- |
| P1 | 将 `verify-fast` 接入仓库必跑 CI 并保存首个 CI 证据包 | Platform owner | 2026-09-18 | `docs/tools/verify-fast.ps1`、CI 运行链接 | Phase 0/1 |
| P1 | 实现 `verify-contract` 最小入口：API/owner/迁移检查 | Platform owner | 2026-10-18 | 规则报告和失败样例 | Phase 1/2 |
| P1 | 实现 `verify-fault` lease/SSE/Git UNKNOWN 场景 | Task/Event/Publish owners | 2026-11-17 | 故障演练原始结果 | team/enterprise |
| P1 | 实现 `verify-capacity`：API、SSE、Worker、DB/Git 容量与 30% 余量 | Platform/Web owners | 2026-11-17 | 压测配置、原始结果和容量结论 | enterprise/生产 |
| P1 | 完成 12 个能力实例的端口、数据、权限、测试和 runbook 回填 | Capability owners | 2026-10-18 | 每个 `docs/capabilities/*.md` 的验收链接 | Phase 1 |
| P1 | 实施 `review_result`/`ticket` metrics projection expand/backfill 和对账 | Review/Metrics owners | 2026-10-18 | migration、回填摘要、新旧对账和回滚证据 | Phase 1/2 |
| P1 | 清理 Legacy ADR/旧路径引用并建立映射 | Documentation owner | 2026-09-18 | `GOV-DOC-001` 报告为 0 | Phase 0 |

执行规则：行动项未达到截止日期时，必须更新债务台账状态和逾期动作；不得只修改本表日期而不补证据。

已关闭的 L4 行动：ADR-001~008 已 `Accepted`，EX-001/002 已 `Active`，三份高风险设计已条件批准，主架构已批准为实施基线；详见 [`l4-approval-record.md`](l4-approval-record.md)。
