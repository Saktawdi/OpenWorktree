# 架构长期治理规范

状态：评审中（与生产架构规范同步）  
适用范围：所有 Gate 后端模块、数据库对象、公开 API、事件、Worker 能力和基础设施适配器

本文件补充 [`production-architecture.md`](production-architecture.md)，解决“第一次重构完成后，系统如何在未来两三年内不重新长成屎山”的问题。

实施人员等级和审批边界以主规范 [17.1 节](production-architecture.md#171-实施人员等级与审批边界) 为强制规则。每个 PR/实施工单必须标注最低实施等级、复核人和是否触及 ADR、owner、豁免或核心不变量；人员等级不能替代架构批准。

## 1. 架构适应度函数

架构约束必须同时具备规则、执行位置、失败动作和趋势指标。只有写在文档中的原则不算治理机制。

规则编号格式为 `GOV-<DOMAIN>-<NNN>`。规则编号是稳定引用；CI、豁免和债务记录只能引用规则编号，不能引用易漂移的文档行号。

| 规则编号 | 规则域 | 最低自动检查 | 执行位置 | 失败动作 | 趋势指标 |
| --- | --- | --- | --- | --- | --- |
| `GOV-DEP-001` | 依赖方向 | Maven 依赖白名单、ArchUnit 包边界、无循环依赖 | 每次 PR + nightly | 阻断合并 | 越层依赖数、循环数 |
| `GOV-BOOT-001` | 组合根 | Web/CLI 不得 `new` 基础设施；bootstrap 不暴露框架类型 | 每次 PR | 阻断合并 | 违规构造数 |
| `GOV-TX-001` | 事务边界 | 事务闭包禁止 Git/HTTP/ProcessRunner/睡眠 | 静态扫描 + 集成测试 | 阻断合并 | 违规调用数、慢事务 p95 |
| `GOV-CON-001` | 并发安全 | 任务 claim/renew/complete 必须携带 attempt/fence；禁止无界队列 | 契约测试 + 故障测试 | 阻断合并 | stale fence、租约过期、队列水位 |
| `GOV-DATA-001` | 数据所有权 | 写表、发事件、对象写入必须匹配 owner catalog | PR 检查 + 数据库审计 | 阻断合并 | 跨 owner 写入数 |
| `GOV-API-001` | API/事件兼容 | OpenAPI/事件 schema diff；删除和破坏性变更需版本策略 | CI contract profile | 阻断合并或要求 ADR | 兼容性违规数 |
| `GOV-DB-001` | 迁移安全 | expand/contract、漂移检测、锁影响检查 | migration CI + staging | 阻断发布 | 迁移耗时、锁等待 |
| `GOV-CPLX-001` | 复杂度 | 类/方法/构造依赖/路由规模预算 | 每次 PR + 周报 | 超预算必须引用豁免或基线差量记录 | 超预算项、趋势斜率 |
| `GOV-OBS-001` | 可观测性 | 新任务/外部副作用必须有指标、trace、结构化错误码 | 能力验收 | 阻断 Phase | 无 owner 指标数 |
| `GOV-DOC-001` | 文档同步 | ADR、owner、runbook、Phase 证据链接存在且状态一致 | 每次 PR | 阻断合并 | 过期文档、悬空引用 |

### 1.1 CI 分层

- `verify-fast`：编译、单元测试、ArchUnit、依赖扫描、复杂度和文档链接检查，必须在每个 PR 执行。
- `verify-contract`：API、事件、数据库迁移、端口实现和 local/production 契约测试，在合并前执行。
- `verify-fault`：节点暂停、租约接管、Git UNKNOWN、数据库切换、慢 SSE 和对象存储部分成功，按 nightly 或发布候选执行。
- `verify-capacity`：压测、容量趋势和 SLO 验证，按版本或基础设施变更执行。

任一层失败时，必须明确是代码失败、环境缺失还是测试未执行；“跳过”不能计为通过。

每个规则必须在 CI 输出规则编号、执行命令、基线文件、当前值、阈值和关联的 `EX-NNN`（如有）。

### 1.2 计算口径与基线

- Java 类行数本轮统一采用 `physical_lines`：对指定生产源码文件执行 PowerShell `@(Get-Content -LiteralPath <file>).Count`，包含空行和注释，排除测试、生成源码和 `target/`；工具版本、命令和提交 SHA 固定在基线 JSON。后续切换到 cloc 必须重新生成全量基线，不能混用两种口径。
- 方法行数按 AST 起止行统计，包含代码和注释行但不包含嵌套声明；超限报告必须给出 fully-qualified method。
- 构造依赖数按构造函数参数类型去重计数；Provider/Factory 注入的集合按一个端口计数，但不得用万能上下文规避。
- “涉及能力数”按变更文件映射到 [`capability-registry.md`](capability-registry.md) 的能力目录计数；未注册目录直接触发 `GOV-DOC-001`。
- 复杂度基线文件为 `docs/architecture/complexity-baseline.json`；基线只允许净减少，不允许无记录增长。
- 文档检查至少包括本地链接、ADR/owner/debt 引用、规则编号和评审轮次语义一致性。
- 规则对应的命令、基线和输出契约见 [`verification-contract.md`](verification-contract.md)；目标命令尚未实现时，规则状态只能为 `Planned`。

## 2. 复杂度预算

预算是架构准入阈值，不是绝对的代码风格要求。超过预算必须先建立 `EX-NNN` 豁免记录；基线热点使用 [`exemption-register.md`](exemption-register.md) 的差量策略，不能直接提交。

### 2.1 初始预算

| 对象 | 警告线 | 阻断线 | 当前基线/备注 |
| --- | ---: | ---: | --- |
| 单个 Java 类 | 400 行 | 600 行 | `ApiRoutes` 约 1,538 行，`GateServiceImpl` 约 663 行，均为迁移热点 |
| 单个方法 | 60 行 | 120 行 | 超过阻断线必须拆分 use case 或策略 |
| 构造函数直接依赖 | 8 个 | 12 个 | 超过说明组合根或职责边界失控 |
| 单个路由/Controller 文件 | 300 行 | 500 行 | 按资源/能力拆分 |
| 单个应用服务公开用例 | 8 个 | 12 个 | 超过应拆成能力 handler |
| 单模块直接依赖模块数 | 5 个 | 7 个 | 通过 port 或 bootstrap 收敛 |
| 单次变更涉及能力数 | 3 个 | 5 个 | 超过必须附跨能力 ADR |
| 技术债豁免数 | 5 个 | 10 个 | 超过需架构负责人专项治理 |

警告线进入周报；阻断线进入 CI 失败。历史热点的当前值冻结为基线，后续变更必须 `delta <= 0`，并同时存在有效豁免、债务记录和拆分目标。该策略只允许开发期合并，不解除生产准入。

### 2.2 预算例外

以下情况不能作为永久例外：

- “现在改动风险太大”。
- “以后功能稳定再拆”。
- “这个类只是 facade，但实际包含业务逻辑”。
- “框架生成代码行数较多”。

确需保留大类时，必须将其限制为稳定的协议 facade，并证明内部逻辑已经委托给能力 handler；否则按债务处理。

## 3. 能力交付模板

新增任何业务能力，必须先登记到 [`capability-registry.md`](capability-registry.md)，再创建规范目录和 `docs/capabilities/<capability>.md` 实例。没有完成清单的能力不得进入 `Implemented`；现有能力必须在 Phase 1 结束前完成回填。

```text
能力名称：
能力目录：`gate-application/src/main/java/gate/application/<capability>/`（以及对应 ports/adapters/web 目录）
业务 owner：
技术 owner：
最低实施等级：L1 / L2 / L3
复核/批准等级：L2 / L3 / L4
状态：Draft / Implementing / Accepted / Deprecated

1. 边界
   - 负责的业务不变量：
   - 明确不负责的内容：
   - 上游/下游能力：

2. 端口与实现
   - 驱动端口：
   - 被动端口：
   - local 实现：
   - production 实现：
   - 超时、取消、错误码：

3. 数据与事件
   - owner 表/列：
   - 只读投影：
   - 写入事务边界：
   - outbox 事件及 sequence：
   - 幂等键和 request digest：
   - 对象存储引用及 GC：

4. 并发与恢复
   - 资源锁/CAS：
   - lease/fence：
   - UNKNOWN_OUTCOME 查询方式：
   - reconcile：
   - 节点宕机行为：

5. 权限与运营
   - RBAC 权限：
   - 审计事件：
   - 指标、trace、日志：
   - 告警和 runbook：
   - 成本/配额：

6. 验收
   - 单元/契约/架构测试：
   - 故障测试：
   - 容量测试：
   - API/事件兼容证据：
   - 数据库迁移和回滚证据：
```

## 4. 数据所有权规则

每张表、每类事件、每个公开 API 资源、每个对象前缀和每个派生指标必须有唯一 owner。Repository 名称不等于数据 owner；唯一写入权才是 owner 的核心定义。

规则：

1. 一个事实表只能有一个能力负责写入和状态迁移。
2. 其他能力只能通过 owner port、只读投影或领域事件读取。
3. 跨 owner 更新必须通过明确的应用用例或 outbox，不得直接写对方表。
4. 数据库外键保证引用完整性，不能替代能力边界、租户鉴权或业务授权。
5. 派生数据可以重建，必须记录来源表/事件、刷新策略和允许的陈旧时间。
6. 删除、归档和保留策略由 owner 负责；跨 owner 的数据保留冲突必须通过 ADR 解决。

表级初始 owner 见 [`ownership-catalog.md`](ownership-catalog.md)。新增表必须先更新该目录，再提交迁移。

## 5. 架构豁免机制

任何偏离阻断线、历史依赖或 owner 规则的开发期变更都必须在 [`exemption-register.md`](exemption-register.md) 有记录。

豁免不允许修改主规范 MUST：触及 I1–I7、Git CAS、fencing、租户隔离、Secret 或 Fail-Closed 的申请必须拒绝；若规则本身不合理，必须修改主规范并通过 ADR。

有效开发豁免至少包含：

- 唯一编号。
- 偏离的具体规则和代码/数据库位置。
- 业务原因与替代方案评估。
- 风险、影响范围和补偿控制。
- 业务 owner、技术 owner、批准人。
- 创建日期、复审日期、强制到期日期。
- 退出方案、拆分步骤和目标版本。
- 到期后的动作：自动阻断合并，除非重新批准。

复杂度基线例外只允许冻结而不得增长，并且始终阻断生产准入。豁免数量、逾期数量和平均存活时间必须进入架构周报；过期或缺少批准人的豁免在 CI 中等同失败。

## 6. 技术债治理

技术债必须进入统一台账 [`../debt-register.md`](../debt-register.md)，而不是散落在 Issue、注释或口头承诺中。

每项债务至少记录：影响能力、触发原因、利息/风险、严重级别、owner、创建日期、偿还 SLA、目标版本、验收证据和逾期处理。

最低治理节奏：

- 每个 PR 不得新增未登记的 P0/P1 架构债务。
- 每个 Phase 必须偿还至少一项同能力的高利息债务，或说明无法偿还的原因。
- P0/P1 债务必须有 30/60/90 天内的处置计划。
- 债务总量、逾期率、最高利息项和复杂度趋势每两周复盘。
- 债务偿还后必须删除兼容层、旧入口、临时配置和对应豁免，不能只标记 Done。

## 7. 维护节奏

- 每次合并：依赖、复杂度、文档链接、owner 和人员等级/复核人检查。
- 每周：任务积压、SLO、技术债、豁免和架构违规趋势。
- 每两周：能力 owner 复核数据所有权、API/事件兼容和债务偿还。
- 每月：架构委员会复核 ADR、豁免到期、模块耦合和热点拆分。
- 每季度：灾备演练、容量重测、SLO 调整和生产架构复审。

任何治理报告没有负责人和下一步日期，都视为未完成。
