# 架构文档

- [企业级生产后端架构规范](production-architecture.md)：唯一候选架构基线。
- [迭代评审记录](review-log.md)：本轮自我迭代的发现、修订与结论。
- [长期架构治理规范](governance.md)：架构适应度、能力模板、复杂度预算、豁免和技术债。
- [能力注册表](capability-registry.md)：统一业务能力名称、owner、目录和交付状态。
- [数据与契约所有权目录](ownership-catalog.md)：事实表、事件、API、对象和派生数据的 owner。
- [架构豁免注册表](exemption-register.md)：开发期临时豁免、基线差量和到期规则。
- [复杂度基线](complexity-baseline.json)：复杂度规则的机器可读初始基线。
- [架构验证命令契约](verification-contract.md)：规则编号、目标命令、基线、输出和当前实现状态。
- [L4 架构审批记录](l4-approval-record.md)：主规范、ADR、豁免和条件设计的正式批准范围及未批准事项。

实施人员等级、工单分派和审批边界统一见主规范 [17.1 节](production-architecture.md#171-实施人员等级与审批边界)；不得以人员资历替代 ADR、测试和 Phase 准入证据。
- [准入状态矩阵](admission-matrix.md)：统一回答当前能否合并、进入 Phase 和部署生产。
- [下一步行动清单](next-actions.md)：未关闭风险的 owner、日期、证据和阻断范围。
- [治理状态快照](governance-status.json)：供 CI/工具读取的当前架构、Phase、ADR、豁免和债务状态。

架构主文档已完成十一轮评审并由项目负责人/L4 批准为 `Accepted` 实施基线。该状态只批准设计与受控重构；实现完成情况仍以每个 Phase 的退出条件和自动化证据为准，文档批准不等于生产准入。
