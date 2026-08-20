# 架构与生产准入状态矩阵

状态：架构已 `Accepted`；生产仍 `Blocked`
更新时间：2026-08-19  
规则：任何一项硬阻断条件未满足，都不得把系统或对应 Phase 标记为已准入。

## 1. 总体状态

| 对象 | 当前状态 | 可以做什么 | 禁止做什么 | 转下一状态的证据 |
| --- | --- | --- | --- | --- |
| 主架构规范 | `Accepted` | 作为强制实施与评审基线 | 用文档批准冒充实现或生产完成 | 后续只通过 ADR/正式修订变更；实现成熟度另行验证 |
| local profile | `Development-only` | 单机开发和离线契约测试 | 多节点 HA、企业生产承诺 | local 契约和故障测试 |
| team profile | `Blocked` | 设计和集成测试 | 生产部署 | PostgreSQL 任务 lease/fence、持久化事件、Git 服务 CAS |
| enterprise profile | `Blocked` | 架构设计和灾备演练 | 生产部署 | team 全部条件 + RBAC/审计/HA/RPO/RTO/SLO |
| Phase 0 | `Verified` | 基线、ADR、规则和契约已冻结 | 以旧文档作为新决策依据 | 已验证：构建自包含、ADR 8 Accepted、规则基线可复现 |
| Phase 1 | `Verified` | 模块边界与统一组合根已完成（EX-001/002 有界豁免） | 新增无 owner 能力、扩大热点 | 已验证：组合根/能力目录/ArchUnit 8/8/能力实例 12/12/热点 139/902 已收敛 |
| Phase 2 | `Verified` | 任务和事件底座已完成（lease/fence/idempotency/outbox/SSE 含慢消费者有界 100，已通过） | 多节点长任务生产（Phase2 已 Verified，可进入 Phase3 设计） | 已验证：fence/幂等/SSE重连/慢消费者有界（TaskEventOutboxFaultTest 6场景 passed，SseHandler 有界 100） |
| Phase 3 | `Blocked` | Worker/Git/S3/KMS 集成 | 以本地文件系统模拟 HA | 任意 Web/Worker 接管和 Git CAS 演练 |
| Phase 4 | `Blocked` | 企业安全和运维演练 | 未达 SLO 上线 | RBAC、审计、备份恢复、SLO 和灾备证据 |

## 2. 规则状态语义

| 状态 | 含义 | 合并 | Phase 准入 | 生产 |
| --- | --- | --- | --- | --- |
| `Draft` | 文档/决策已建立但未完成评审 | 可以讨论，不作为强制依据 | 否 | 否 |
| `Proposed` | 已提交批准但未批准 | 仅在其他规则允许时 | 否 | 否 |
| `Accepted` | 负责人/批准人已批准 | 是 | 视依赖项 | 否，仍需 Implemented/Verified |
| `Implemented` | 代码/基础设施已落地 | 是 | 需测试证据 | 否 |
| `Verified` | 有自动化、故障、容量或演练证据 | 是 | 是 | 仍需总闸门 |
| `Active` | 仅用于已批准且未过期的开发豁免 | 受限 | 不解除准入 | 不解除准入 |
| `Expired`/`Rejected`/`Blocked` | 不能作为有效依据 | 否 | 否 | 否 |

## 3. 主规范 Accepted 决策条件

以下条件必须全部为真：

1. ADR-001 至 ADR-008 状态至少为 `Accepted`，并填写批准人、复审日期和验证证据计划。
2. 治理规则、`verify-fast`、`verify-contract`、`verify-fault` 和 `verify-capacity` 的命令契约、输入及输出已定义；已实现项必须通过，未实现项保持 `Planned` 并阻断对应 Phase/生产，而不冒充已落地。
3. 能力注册表、所有权目录覆盖当前所有表、列、API、事件、对象前缀和派生指标；未登记对象为阻断项。
4. 所有 P1 债务有 owner、日历 SLA、目标版本、逾期动作和证据；逾期项不能被静默忽略。
5. 所有超预算热点都有有效批准的开发豁免或已经下降到预算内；`Proposed`/无批准豁免不算有效。
6. 能力实例文档覆盖全部注册能力；尚未完成的端口、数据、权限、测试和 runbook 如实保持 `Baseline/Implementing/Planned` 并进入债务台账。
7. 项目负责人批准主规范状态变更，并保存评审会议/批准记录。

2026-08-19 复核结果：以上决策条件均已满足，批准记录见 [`l4-approval-record.md`](l4-approval-record.md)。当前等待 L4 的架构决策项为 0；未实现治理命令和能力证据继续作为 Phase/生产阻断项。

## 4. 生产发布硬条件

生产发布还必须满足主规范第 19 节总验收清单。任何 `Draft`、`Proposed`、`Missing`、`Planned`、`Blocked` 或过期豁免均不得作为生产准入依据。

## 5. 人员等级准入

实施人员等级和审批边界以主规范 [17.1 节](production-architecture.md#171-实施人员等级与审批边界) 为准：

- L1/L2 可以执行有明确协议、范围、测试和回滚方式的文档、测试、单能力和行为保持型重构。
- L3 必须负责跨能力、并发恢复、数据库迁移、Worker/Git/Provider 和故障测试实施。
- L4 必须批准 ADR、数据 owner、核心协议、豁免、Phase 准入和生产准入建议。
- 未批准的 ADR、`Proposed` 豁免和 `Blocked` profile 不得因为人员等级较高而被视为已准入。
