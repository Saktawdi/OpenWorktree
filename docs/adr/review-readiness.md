# ADR 评审就绪清单

状态：8 项均为 Draft，尚未达到 Proposed  
用途：说明每项 ADR 从 Draft 转为 Proposed 前必须补齐的内容，避免“文件存在”被误解为“决策可批准”。

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

| ADR | 当前已有 | 转 Proposed 前仍缺 | 当前结论 |
| --- | --- | --- | --- |
| ADR-001 | 控制面/执行面候选拓扑、迁移方向 | 调度选项对比、节点亲和性、工作区 GC 容量、批准人 | Not Ready |
| ADR-002 | lease/fence 候选协议、故障范围 | PostgreSQL SQL、隔离级别、租约参数、状态转换表、批准人 | Not Ready |
| ADR-003 | Git OID CAS、nonce 和 UNKNOWN 原则 | Git 服务实现选项、规范签名载荷、密钥轮换、批准人 | Not Ready |
| ADR-004 | outbox、sequence、SSE 游标原则 | schema、保留水位、poll/notify 参数、兼容迁移、批准人 | Not Ready |
| ADR-005 | 阻塞隔离原则和候选 MVC/虚拟线程 | 容量实验、WebFlux 对照、框架决定、JDK 迁移、批准人 | Not Ready |
| ADR-006 | 沙箱/Secret 基本边界 | Windows/Linux 方案对比、威胁模型、成本与回收、批准人 | Not Ready |
| ADR-007 | tenant/RBAC/审计候选模型 | tenant 迁移、权限矩阵、保留等级、例外审批、批准人 | Not Ready |
| ADR-008 | HA/RPO/RTO 初始目标 | 产品/基础设施选项、成本、故障域、恢复演练设计、批准人 | Not Ready |

任何 ADR 未通过本清单时只能保持 Draft。评审会议不能仅通过修改注册表状态将其升级为 Accepted。
