# 架构治理下一步行动清单

状态：执行中（Reviewing）  
更新时间：2026-08-19

| 优先级 | 行动 | Owner | 截止/检查点 | 完成证据 | 阻断范围 |
| --- | --- | --- | --- | --- | --- |
| P1 | 评审并批准 ADR-001~004 | Architecture/Task/Publish/Event owners | 2026-09-02 | ADR 状态、批准人、会议记录 | 主规范 Accepted、Phase 0 |
| P1 | 评审并批准 ADR-005~008 | Web/Security/Platform owners | 2026-09-16 | ADR 状态、批准人、验证计划 | 主规范 Accepted、enterprise |
| P1 | 为 EX-001/002 指定批准人并决定 Active 或删除 | Architecture owner + Web/Application owners | 2026-09-02 | 豁免记录批准字段或删除 diff | 相关热点合并 |
| P1 | 将已实现的文档/复杂度最小检查接入 `verify-fast` CI，并补过期豁免 negative fixture | Platform owner | 2026-09-18 | `docs/tools/verify-governance.ps1`、negative fixture、CI 输出 | Phase 0/1 |
| P1 | 实现 `verify-contract` 最小入口：API/owner/迁移检查 | Platform owner | 2026-10-18 | 规则报告和失败样例 | Phase 1/2 |
| P1 | 实现 `verify-fault` lease/SSE/Git UNKNOWN 场景 | Task/Event/Publish owners | 2026-11-17 | 故障演练原始结果 | team/enterprise |
| P1 | 完成 12 个能力实例的端口、数据、权限、测试和 runbook 回填 | Capability owners | 2026-10-18 | 每个 `docs/capabilities/*.md` 的验收链接 | Phase 1 |
| P1 | 完成 `review_result`/`ticket` metrics projection 迁移设计 | Review/Metrics owners | 2026-10-18 | projection ADR/schema/兼容读路径 | Phase 1/2 |
| P1 | 清理 Legacy ADR/旧路径引用并建立映射 | Documentation owner | 2026-09-18 | `GOV-DOC-001` 报告为 0 | Phase 0 |

执行规则：行动项未达到截止日期时，必须更新债务台账状态和逾期动作；不得只修改本表日期而不补证据。
