# L4 架构审批记录

审批日期：2026-08-19

审批人：Codex（项目负责人授权 L4）

最终负责人确认：项目负责人已在本轮评审中明确其 L4 与项目负责人身份，并授权 Codex 共同执行 L4 技术审批

审批范围：架构决策与受控重构实施；不包含 Phase 退出或生产发布批准

## 1. 批准结论

1. [`production-architecture.md`](production-architecture.md) 批准为 `Accepted` 重构实施基线。
2. ADR-001 至 ADR-008 批准为 `Accepted`，可以作为 L1/L2/L3 的强制实现依据。
3. [`ownership-catalog.md`](ownership-catalog.md) 的当前数据、API、事件、对象与投影 owner 基线获批准；任何 owner 变更仍须单独 L4 审批。
4. EX-001、EX-002 批准为 `Active` 开发期豁免，只允许按 [`EX-001-002-split-plan.md`](EX-001-002-split-plan.md) 执行 `delta <= 0` 的拆分；不解除生产阻断，到期自动失效。
5. [`task-event-outbox-design.md`](task-event-outbox-design.md)、[`sse-cursor-design.md`](sse-cursor-design.md) 和 [`metrics-projection-design.md`](metrics-projection-design.md) 有条件批准进入实现；各文件列出的迁移、幂等、故障测试和回滚条件仍是硬条件。

## 2. 当前授权实施范围

- L1：文档/Legacy 引用清理、测试补充、negative fixture、明确边界内的机械性提取。
- L2：单能力行为保持型拆分、已接受 ADR 的局部实现、契约测试；由 L3 复核。
- L3：热点拆分、数据库 expand/backfill、lease/fence、Outbox/SSE、Git CAS、Worker/安全/HA 实现与故障测试；必须遵循已接受 ADR 和条件批准设计。
- Phase 1 的开发期重构可以开始；Phase 2 至 Phase 4 可以开展设计、分支实现和测试建设，但未取得退出证据前不得声明 Phase 完成。

## 3. 明确未批准事项

- 未批准 team/enterprise profile 生产部署。
- 未批准任何 Phase 退出；Phase 状态必须由对应证据包单独升级。
- 未批准以 `Active` 豁免替代复杂度偿还。
- 未批准 metrics contract 删除旧列；必须先完成对账、观察窗口和回滚证据。
- 未批准跳过 `verify-contract`、`verify-fault`、`verify-capacity`、安全、恢复、容量或 SLO 验收。

因此，本记录关闭全部“等待 L4 架构决策”的阻塞；剩余阻塞均应归类为实施、测试、迁移、演练或生产验收证据不足。
