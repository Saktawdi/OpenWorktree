# 架构与技术债台账

状态：生效台账（架构 Accepted；债务按各行独立治理）
维护规则：[`architecture/governance.md`](architecture/governance.md) 第 6 节  
日期口径：所有 SLA 使用明确日历日期；“Phase 1/2”只能作为关联目标，不能替代到期日。

本台账记录会提高未来修改成本、故障风险或架构漂移风险的事项。普通待办事项不替代本台账。

## 1. 台账字段

| 字段 | 含义 |
| --- | --- |
| `interest/risk` | 技术债利息：继续拖延会增加什么故障概率、修改成本或迁移成本 |
| `SLA-30/60/90` | 创建后 30/60/90 天对应的具体日历日期和检查点；P1 至少填写 |
| `target_version` | 计划偿还的版本/Phase |
| `overdue_action` | 到期未完成时的动作：阻断、升级、降级发布或重新批准 |
| `evidence` | 代码、测试、指标、迁移或演练证据链接 |
| `exemption` | 关联的 `EX-NNN`；没有豁免不能绕过阻断规则 |

## 2. 当前债务

| ID | 级别 | 债务 | 影响能力 | `interest/risk` | Owner | 创建日 | SLA-30 | SLA-60 | SLA-90 | `target_version` | `overdue_action` | 状态 | 豁免/证据 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| DEBT-001 | P1 | `ApiRoutes` 约 1,538 行，按资源/能力拆分 | `ticket/project/status` | 路由分支耦合、修改半径大、契约回归风险高 | Web owner | 2026-08-19 | 2026-09-18：完成路由 owner 盘点 | 2026-10-18：拆出 2 个资源 handler（已完成 TicketRoutes/SessionRoutes 拆解，降至 902 行） | 2026-11-17：facade 只转发且降至预算内 | Phase 1 | 超过基线 delta 阻断合并并升级 | In Progress | EX-001 Active / `gate-web/src/main/java/gate/web/` |
| DEBT-002 | P1 | `GateServiceImpl` 约 663 行，按 use case 拆分 | `presubmit/review/publish` | 事务、Git、策略和审计职责混杂 | Application owner | 2026-08-19 | 2026-09-18：列出 use case 边界 | 2026-10-18：拆出 presubmit/review/publish handler（已完成拆解，降至 139 行 Facade） | 2026-11-17：publish/reconcile 独立且 facade 只转发 | Phase 1 | 阻断新增业务分支 | In Progress | EX-002 Active / `gate-application/src/main/java/gate/application/` |
| DEBT-003 | P1 | ArchUnit 规则与 gate-bootstrap/Spring 边界未完全同步 | `architecture` | 规则失败或错误放行，依赖方向失去自动守护 | Architecture owner | 2026-08-19 | 2026-09-18：修订规则矩阵（已完成） | 2026-10-18：增加 bootstrap 边界测试（已完成） | 2026-11-17：CI 规则全绿且无基线误报 | Phase 0/1 | 回归时阻断架构合并 | Resolved | `gate-cli/.../ArchitectureTest.java`；`docs/tools/verify-fast.ps1` 8/8 通过 |
| DEBT-004 | P1 | 旧 ADR 编号和旧文档路径仍散落在代码注释/构建说明 | `documentation` | 知识漂移导致实现依据不可追溯 | Documentation owner | 2026-08-19 | 2026-09-18：建立 Legacy 映射（已完成） | 2026-10-18：清理旧路径引用并建立映射消歧 | 2026-11-17：关键 ADR 引用指向新注册表/文档 | Phase 0 | 阻断文档治理检查 | Resolved | `docs/archive/legacy-adr-mapping.md` |
| DEBT-005 | P1 | `gate_task` 尚无生产所需 lease/fence/idempotency/event 完整字段 | `task` | 多节点接管可能双写，阻断 team 模式 | Task owner | 2026-08-19 | 2026-09-18：完成 schema/状态机（已完成 V9 迁移与字段扩展） | 2026-10-18：完成 claim/renew/complete 契约（已完成 updateWithFence / registerWithKey） | 2026-11-17：完成节点暂停和 stale fence 故障测试（TaskEventOutboxFaultTest 已通过） | Phase 2 | 禁止进入 team/enterprise | Resolved | `gate-adapters/src/main/resources/db/migration/V9__task_event_outbox.sql`；`TaskEventOutboxFaultTest` |
| DEBT-006 | P1 | 当前 SSE 仍有进程内监听语义，未完成跨节点持久化事件 | `event/session` | 跨节点丢事件、重连不可证明 | Event owner | 2026-08-19 | 2026-09-18：定义 event/outbox schema（已完成 task_event / outbox 表定义） | 2026-10-18：完成 cursor/replay（已完成 TaskEventPort.replay / SseHandler Last-Event-ID 游标） | 2026-11-17：完成慢消费者和节点切换测试（已完成 SseHandler MAX_BUFFERED_EVENTS=100 有界缓冲/drop-oldest + TaskEventOutboxFaultTest 慢消费者/节点切换 2 场景通过） | Phase 2 | 禁止多 Web 节点生产 | Resolved | `gate-ports/src/main/java/gate/ports/TaskEventPort.java`；`JdbcGateTaskRepository`；`SseHandler`；`TaskEventOutboxFaultTest:6 scenarios passed` |
| DEBT-007 | P1 | 8 项生产 ADR 已 Accepted，尚未完成 Implemented/Verified | `architecture` | 决策已可实施，但关键协议未经故障和容量证据证明，仍阻断生产 | Architecture owner | 2026-08-19 | 2026-09-18：8 项 Accepted（已完成） | 2026-10-18：关键协议进入 Implemented | 2026-11-17：关键项达到 Verified | Phase 0/2/3/4 | 阻断对应 Phase/生产，不回退已接受决策 | In Progress | `adr/README.md`；`architecture/l4-approval-record.md` |
| DEBT-008 | P1 | 部分 GOV 规则已有本地自动检查，CI 与剩余规则尚未全部落地 | `governance` | 未自动化部分仍依赖人工，可能回归 | Platform owner | 2026-08-19 | 2026-09-18：规则编号、基线、verify-fast 和 negative fixture（已完成） | 2026-10-18：verify-contract、verify-fault、verify-capacity 本地工具均已实现并全部通过 | 2026-11-17：fault/capacity 与全 owner 自动阻断接入 CI | Phase 0/1/2 | 阻断对应治理/生产准入 | In Progress | `tools/verify-fast.ps1`；`tools/verify-contract.ps1`；`tools/verify-fault.ps1`；`tools/verify-capacity.ps1` |
| DEBT-009 | P1 | 12 个能力实例已建立，但详细端口、权限、测试和 runbook 尚未完全回填 | `all capabilities` | 实例过于简化时仍可能遗漏交付物和 owner 边界 | Architecture owner | 2026-08-19 | 2026-09-18：建立目录和初始实例（已完成） | 2026-10-18：详细回填 ticket/project/review（已完成全量 12 能力 6 节标准化回填） | 2026-11-17：全部 Implementing 能力有完整实例和验收包 | Phase 1 | 禁止新增未登记能力 | Resolved | `docs/capabilities/README.md`，全部 12 个实例均具备 6 节标准化内容 |
| DEBT-010 | P1 | 物理表中 review_result/ticket 混有 metrics 派生列 | `review/metrics` | 所有权语义混淆、派生回写和迁移耦合 | Review/Metrics owners | 2026-08-19 | 2026-09-18：冻结新列增长（设计已批准） | 2026-10-18：实施 projection schema/backfill | 2026-11-17：完成 expand/contract 或保留回滚读路径 | Phase 1/2 | 禁止新增列级 owner | In Progress | `architecture/metrics-projection-design.md` |

## 3. 状态和治理规则

- 债务状态只能是 `Open / Planned / In Progress / Blocked / Resolved / Accepted Risk`。
- 新增 P0/P1 债务必须在同一 PR 建立记录；没有 owner、日期和利息字段的记录无效。
- P1 必须有 30/60/90 天检查点；逾期自动升级到架构负责人并阻断对应 Phase 准入。
- `Resolved` 必须附代码、测试、指标或迁移证据；关闭 Issue 不等于偿还完成。
- `Accepted Risk` 必须同时存在有效豁免，且不能用于核心不变量或 team/enterprise 阻断项。
- 每两周更新 owner、目标日期、利息和证据；逾期项进入架构周报。
