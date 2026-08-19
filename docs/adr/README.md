# ADR 注册表

状态：8 项决策草案已建立，均待评审和批准  
ADR（Architecture Decision Record）记录架构取舍、替代方案、影响和接受状态。代码中的 `ADR-1`、`ADR-8`、`ADR-9` 等历史编号目前只代表旧文档/旧执行阶段引用，不自动视为本基线下的 `Accepted` ADR。

## 1. 当前注册表

| 编号 | 决策主题 | 状态 | 负责人 | 批准人 | 复审日期 | 验证证据 | 目标 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| [ADR-001](001-execution-topology.md) | Git/Agent 执行拓扑与工作区重建 | Draft | Architecture owner | 待指定 | 2026-09-02 | 尚无 | Phase 0 |
| [ADR-002](002-task-lease-fencing.md) | PostgreSQL 任务领取、lease、fencing 和重试 | Draft | Task owner | 待指定 | 2026-09-02 | 尚无 | Phase 0 |
| [ADR-003](003-git-ref-cas.md) | Git ref OID CAS 与服务端授权验证 | Draft | Publish owner | 待指定 | 2026-09-02 | 尚无 | Phase 0 |
| [ADR-004](004-outbox-event-sse.md) | Transactional Outbox、事件序号和 SSE 游标 | Draft | Event owner | 待指定 | 2026-09-02 | 尚无 | Phase 0 |
| [ADR-005](005-web-concurrency-model.md) | Web 并发模型与阻塞操作隔离 | Draft | Web owner | 待指定 | 2026-09-02 | 尚无 | Phase 0 |
| [ADR-006](006-agent-sandbox-secrets.md) | Agent 沙箱、网络出口和 Secret 注入 | Draft | Security owner | 待指定 | 2026-09-16 | 尚无 | Phase 0 |
| [ADR-007](007-tenancy-rbac-audit.md) | 租户、RBAC、职责分离和审计保留 | Draft | Security owner | 待指定 | 2026-09-16 | 尚无 | Phase 0 |
| [ADR-008](008-ha-rpo-rto.md) | PostgreSQL/Git/Object Storage HA、RPO 和 RTO | Draft | Platform owner | 待指定 | 2026-09-16 | 尚无 | Phase 0 |

主规范第 18 节要求以上 ADR 在架构状态转为 `Accepted` 前至少达到 `Accepted`。在此之前，架构主文档保持 `Reviewing`，不得以旧编号或代码注释替代决策记录。

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

ADR 从 Draft 转为 Proposed 前必须通过 [`review-readiness.md`](review-readiness.md)。当前 8 项均为 `Not Ready`，不得仅修改注册表状态升级。
