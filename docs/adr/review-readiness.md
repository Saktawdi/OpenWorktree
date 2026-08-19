# ADR 评审就绪清单

状态：8 项均已通过就绪检查并由 L4 批准为 `Accepted`
用途：保存本轮 ADR 实质评审结论，避免把 `Accepted` 误解为实现或生产验证完成。

## 通用 Proposed 门槛

- 背景包含当前代码/运行事实和问题范围。
- 至少比较两个可行选项及“不改变现状”选项。
- 明确决策标准、舍弃原因和负面后果。
- 写出协议、数据模型、错误语义和安全边界。
- 写出迁移、兼容、回滚、可观测性和容量影响。
- 指定批准人、评审参与角色和复审日期。
- 给出验证计划、negative test 和完成证据位置。
- 与主规范、能力注册表、owner 目录、债务和行动项交叉链接。

## 当前就绪度

| ADR | 已完成的决策内容 | 后续实现/验证证据 | 当前结论 |
| --- | --- | --- | --- |
| ADR-001 | 拓扑选项、节点/工作区生命周期、迁移和回滚 | Phase 3 Worker 接管、重建、draining、GC 故障测试 | Ready / Accepted |
| ADR-002 | PostgreSQL 隔离级别、原子 claim、60s/20s lease、fence 和状态条件 | claim/renew/complete、节点暂停、DB 切换、stale fence negative test | Ready / Accepted |
| ADR-003 | Git 服务选项、Ed25519、JCS 载荷、nonce 与 ref 原子事务、UNKNOWN 收敛 | 并发 CAS、重放/篡改、超时及 Git 成功/DB 未写测试 | Ready / Accepted |
| ADR-004 | outbox/schema、原子 sequence、DB cursor、通知定位、保留策略 | 原子性、通知丢失、Relay 重放、cursor/慢消费者/跨节点测试 | Ready / Accepted |
| ADR-005 | MVC/WebFlux 对照、Java 21 虚拟线程决定、迁移和回滚 | API 延迟、1000 SSE、慢客户端、连接风暴和 Worker 饱和压测 | Ready / Accepted |
| ADR-006 | Windows/Linux 对照、rootless OCI、默认拒绝出口、Secret 生命周期 | 路径/符号链接/出口/泄漏/资源耗尽/回收测试 | Ready / Accepted |
| ADR-007 | tenant RBAC、职责分离、WORM/KMS 审计和保留策略 | 跨租户/水平越权/职责冲突/撤销/审计完整性测试 | Ready / Accepted |
| ADR-008 | 故障域、单地域 HA、RPO/RTO、成本边界和恢复策略 | DB 切换/PITR、Git/对象恢复、KMS 故障与 reconcile 演练 | Ready / Accepted |

本轮不是仅修改状态：每项 ADR 均已补齐替代方案、决策标准、负面后果、迁移、回滚和验证计划，并由 L4 逐项批准。后续只有实际代码/基础设施落地后才能转 `Implemented`，只有对应自动化或演练证据完成后才能转 `Verified`。
