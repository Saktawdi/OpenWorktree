# ADR 注册表

状态：8 项决策均已完成 L4 评审并达到 `Accepted`
ADR（Architecture Decision Record）记录架构取舍、替代方案、影响和接受状态。代码中的 `ADR-1`、`ADR-8`、`ADR-9` 等历史编号目前只代表旧文档/旧执行阶段引用，不自动视为本基线下的 `Accepted` ADR。

## 1. 当前注册表

| 编号 | 决策主题 | 状态 | 负责人 | 批准人 | 复审日期 | 验证证据 | 目标 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| [ADR-001](001-execution-topology.md) | Git/Agent 执行拓扑与工作区重建 | Accepted | Architecture owner | Codex（项目负责人授权 L4） | 2026-11-17 | ADR 内迁移/回滚/验证计划 | Phase 0/3 |
| [ADR-002](002-task-lease-fencing.md) | PostgreSQL 任务领取、lease、fencing 和重试 | Accepted | Task owner | Codex（项目负责人授权 L4） | 2026-10-18 | ADR 内 SQL/故障/negative test 计划 | Phase 0/2 |
| [ADR-003](003-git-ref-cas.md) | Git ref OID CAS 与服务端授权验证 | Accepted | Publish owner | Codex（项目负责人授权 L4） | 2026-10-18 | ADR 内 CAS/重放/UNKNOWN 测试计划 | Phase 0/3 |
| [ADR-004](004-outbox-event-sse.md) | Transactional Outbox、事件序号和 SSE 游标 | Accepted | Event owner | Codex（项目负责人授权 L4） | 2026-10-18 | ADR 内 outbox/cursor/慢消费者测试计划 | Phase 0/2 |
| [ADR-005](005-web-concurrency-model.md) | Web 并发模型与阻塞操作隔离 | Accepted | Web owner | Codex（项目负责人授权 L4） | 2026-11-17 | ADR 内延迟/SSE/连接风暴容量计划 | Phase 0/1/4 |
| [ADR-006](006-agent-sandbox-secrets.md) | Agent 沙箱、网络出口和 Secret 注入 | Accepted | Security owner | Codex（项目负责人授权 L4） | 2026-11-17 | ADR 内沙箱/出口/Secret negative test 计划 | Phase 0/3/4 |
| [ADR-007](007-tenancy-rbac-audit.md) | 租户、RBAC、职责分离和审计保留 | Accepted | Security owner | Codex（项目负责人授权 L4） | 2026-11-17 | ADR 内越权/SoD/审计完整性计划 | Phase 0/4 |
| [ADR-008](008-ha-rpo-rto.md) | PostgreSQL/Git/Object Storage HA、RPO 和 RTO | Accepted | Platform owner | Codex（项目负责人授权 L4） | 2026-11-17 | ADR 内切换/PITR/恢复演练计划 | Phase 0/3/4 |

上述 ADR 已满足主规范第 18 节的决策批准条件。`Accepted` 允许实施，不等于 `Implemented` 或 `Verified`；各自验证计划仍须在对应 Phase 证据包中完成。

## 2. ADR 状态机

```text
Missing -> Draft -> Proposed -> Accepted -> Implemented -> Verified
                      |             |
                      +-> Rejected  +-> Superseded
```

- `Missing`：注册表要求该决策，但实体文档尚不存在；不能进入评审。
- `Draft`：实体已建立，内容未批准，不能作为强制实现依据。
- `Proposed`：内容完整且负责人已提交批准。
- `Accepted`：决策已批准，可以作为实现依据。
- `Implemented`：代码/基础设施已落地，但尚未完成全部验证。
- `Verified`：有自动化测试、演练和运行证据。
- `Superseded`：由新 ADR 替代，必须保留替代关系和迁移说明。

## 3. ADR 最小模板

见 [`template.md`](template.md)。禁止只写结论不写约束、失败模式、迁移和回滚。

ADR 从 Draft 转为 Proposed 前必须通过 [`review-readiness.md`](review-readiness.md)。本轮已完成实质内容评审和 L4 批准；批准范围与生产边界见 [`../architecture/l4-approval-record.md`](../architecture/l4-approval-record.md)。
