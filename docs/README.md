# Gate 文档中心

本目录是项目唯一文档入口。仓库根目录不再并存 `doc/` 与 `docs/`；所有生效、评审中和需要长期保留的技术证据均归入这里。

## 建议阅读顺序

1. [产品愿景与用户体验规格](product/product-spec.md)：了解产品定位、角色和目标体验。
2. [企业级生产后端架构规范](architecture/production-architecture.md)：当前重构的架构边界、正确性协议、SLO 和分阶段验收标准。
3. [架构迭代评审记录](architecture/review-log.md)：查看架构文档每轮自评发现、修订和遗留决策。
4. [长期架构治理规范](architecture/governance.md)：防止模块、数据、复杂度和技术债在持续迭代中失控。
5. [能力注册表与所有权目录](architecture/capability-registry.md)：查看能力 owner、目录、API、表和事件边界。
6. [ADR 注册表](adr/README.md)：查看架构决策状态，不再依赖代码注释中的历史编号。
7. [能力实例目录](capabilities/README.md)：查看已登记能力的当前边界、owner 和实施状态。
8. [准入状态矩阵](architecture/admission-matrix.md)：查看当前可以推进什么、哪些事项仍被阻断。
9. [下一步行动清单](architecture/next-actions.md)：查看未关闭风险的 owner、截止日期和完成证据。
10. [L4 架构审批记录](architecture/l4-approval-record.md)：查看已批准范围、实施授权和明确未批准事项。
11. [治理状态快照](architecture/governance-status.json)：供自动检查读取当前状态和硬阻断项。

## 文档状态

| 文档 | 状态 | 作用 |
| --- | --- | --- |
| `product/product-spec.md` | 目标规格 | 描述目标用户体验；不单独证明能力已经实现 |
| `architecture/production-architecture.md` | Accepted | 唯一生效重构实施基线；不单独代表生产准入 |
| `architecture/review-log.md` | 持续更新 | 保存十一轮评审、L4 决策和遗留实施阻塞 |
| `architecture/governance.md` | Accepted | 架构适应度、能力模板、复杂度、豁免和技术债治理 |
| `architecture/ownership-catalog.md` | Accepted 基线 | 表、事件、API、对象和派生数据的唯一 owner |
| `architecture/capability-registry.md` | Accepted 基线 | 能力名称、owner、目录和交付状态；能力实现成熟度见各行 |
| `architecture/exemption-register.md` | 生效注册 | 开发期豁免、基线差量和到期动作 |
| `architecture/complexity-baseline.json` | 初始基线 | 复杂度规则的机器可读阈值和热点基线 |
| `architecture/admission-matrix.md` | 当前快照 | 架构、Phase 和生产准入状态 |
| `architecture/next-actions.md` | 执行中 | 当前行动、owner、日期、证据和阻断范围 |
| `architecture/governance-status.json` | 当前快照 | 机器可读的架构、Phase、ADR、豁免和债务状态 |
| `architecture/l4-approval-record.md` | 已批准 | L4 决策范围、实施授权和生产保留项 |
| `debt-register.md` | 初始台账 | P0/P1 架构与技术债及偿还证据 |
| `adr/README.md` | 8 项 Accepted | ADR 状态、责任人、批准人与验证计划 |
| `capabilities/README.md` | 初始实例目录 | 12 个业务能力的当前状态和边界 |
| `archive/prism-schema-validation.md` | 历史证据 | 保留代码仍依赖的 Prism 0.5.0 实测结论 |

## 事实来源与优先级

发生冲突时按以下顺序处理：

1. 可重复执行的测试和运行时外部事实。
2. 数据库迁移、公开 API/CLI 契约及已接受 ADR。
3. 生效架构规范。
4. 产品目标和历史证据。

发现冲突必须创建修复事项，不得通过选择性引用旧文档绕过当前基线。

## 文档治理规则

- 新文档必须放在 `docs/` 下，并从本入口或子目录入口可达。
- 架构约束进入主规范；重大技术取舍进入 ADR，禁止长期散落在执行报告中。
- 历史验证资料只有在代码、测试或决策仍引用时才保留到 `archive/`。
- 过期执行报告、原型和重复白皮书直接删除，由 Git 历史追溯。
- 文档不得用“生产级”“高可用”替代量化 SLO、故障测试和恢复证据。
- 新增能力必须同时提交 owner、能力模板、ADR/豁免（如适用）、数据/事件契约、测试和 runbook。
