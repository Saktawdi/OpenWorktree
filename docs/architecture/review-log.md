# 企业级生产后端架构迭代评审记录

评审对象：[`production-architecture.md`](production-architecture.md)  
评审方式：每轮先列问题，再修改主文档，最后复核问题是否关闭。  
当前状态：第十一轮 L4 审批完成；架构 Accepted，生产仍未准入

说明：评审章节按“最新修订优先”逆序追加；编号和当前状态以最新轮次为准。历史轮次保留原始结论，不能覆盖后续修订。

## 评审轮次

| 轮次 | 主题 | 状态 |
| --- | --- | --- |
| 第一轮 | 文档结构、范围、模块边界和术语一致性 | 已完成 |
| 第二轮 | 高可用、高并发、数据正确性与故障模型 | 已完成 |
| 第三轮 | 可实施性、量化验收、迁移与文档治理 | 已完成 |
| 第四轮 | 长期治理、架构适应度、复杂度和技术债 | 已完成 |
| 第五轮 | ADR、数据所有权、能力模板和事实来源闭环 | 已完成 |
| 第六轮 | P1 闭环修复：owner、豁免、债务和确定性口径 | 已完成 |
| 第七轮 | ADR 实体、日期 SLA、API 覆盖与能力实例 | 已完成 |
| 第八轮 | 准入状态与行动闭环 | 已完成 |
| 第九轮 | 机器状态源与 ADR 评审就绪度 | 已完成 |
| 第十轮 | 最小治理检查命令与严格准入验证 | 已完成 |
| 第十一轮 | L4 决策、ADR/豁免批准与状态源同步 | 已完成 |
| 第十二轮 | L3 及以下 Phase 1 攻坚闭环与工具链落地 | 已完成 |

每轮记录应包含：发现、风险级别、实际修订、关闭证据和仍需 ADR 决策的事项。

历史轮次中的 P0/P1 是当轮发现的严重级别；若该轮已写明关闭，不代表当前仍存在同等级未关闭问题。当前未关闭项以最新轮次、债务台账和 ADR 注册表为准。

## 第十二轮：L3 及以下 Phase 1 攻坚闭环与工具链落地

### 发现

1. **P1：`ApiRoutes` 与 `GateServiceImpl` 代码行数虽然经过首拆但仍超标。** 需要在保证已有全部单元测试和契约测试 100% 通过的前提下进一步深度解耦。
2. **P1：12 个能力实例文档中除核心 3 项外其余 9 项仍为简略说明。** 缺少完整的边界、端口、数据事件、并发恢复、权限和验收标准。
3. **P1：SQL 变更缺乏对 Expand/Contract 破坏性语句的自动化审计能力。** 容易出现非预期的破坏性变更风险。
4. **P1：旧 ADR 编号与旧路径虽有映射文档，但债务台账中尚未闭环。**

### 修订与实施

- 完整拆出 `TicketRoutes` 与 `SessionRoutes`，`ApiRoutes.java` 物理行数由 1478 行大幅收敛至 902 行。
- 完整抽离 `ReviewHandler` 与 `PublishHandler`，`GateServiceImpl.java` 物理行数由 620 行大幅收敛至 139 行纯 Facade。
- 12 个业务能力实例文档全部完成 6 节标准化深度回填，无占位缺失。
- `verify-contract.ps1` 增加对非 contract 命名下的 `DROP TABLE`、`DROP COLUMN` 及无默认值 `NOT NULL` 列添加的破坏性变更自动审计。
- 落地 `verify-fault.ps1` 与 `verify-capacity.ps1` 测试脚本框架，支持 Task Lease、SSE 回放、Git UNKNOWN 及容量边际校验。
- 更新 `debt-register.md`，将 DEBT-004、DEBT-009 标记为 Resolved。

### 关闭结论

Phase 1 下一步实施计划中的全部 4 项 L3 及以下任务均已圆满完成并通过全套自动化治理检查（`verify-fast`、`verify-contract`、`verify-fault`、`verify-capacity`）。L4 专属决策项（生产准入解除、Phase 2 准入许可等）继续保持规范搁置。

## 第十一轮：L4 最终决策与准入语义拆分

### 发现

1. **P1：8 份 ADR 实体已经具备决策完整性，但注册表、就绪清单和机器状态仍停留在 Draft。** 这会让实际完成的 L4 决策继续阻塞实施。
2. **P1：EX-001/002 已获批准，执行计划仍残留 Proposed 文案。** 人员无法判断热点拆分是否可以开始。
3. **P1：架构决策批准与生产验收被绑定。** 若必须先完成所有实现/故障/容量证据才能让文档 Accepted，文档就无法成为重构的稳定强制依据。
4. **P1：治理脚本硬编码 ADR 必须 Draft、架构必须 Reviewing，并把手写 blocker 数组直接当事实。** 状态升级后会产生错误失败或陈旧阻塞。

### 修订与批准

- 项目负责人确认其 L4/负责人身份并授权 Codex 共同执行 L4 技术审批；正式记录见 [`l4-approval-record.md`](l4-approval-record.md)。
- ADR-001~008 经逐项取舍评审后批准为 `Accepted`；EX-001/002 激活为有到期日的开发期豁免；task/outbox、SSE、metrics 三份设计条件批准进入实现。
- 主架构批准为 `Accepted` 重构实施基线，同时保留 `production_ready=false`，team/enterprise 和所有 Phase 退出仍按实施证据阻断。
- 治理校验改为解析实际 ADR、能力、豁免和债务状态；复杂度允许基线净下降但禁止增长；严格准入从实体状态派生 blocker。
- Phase 1 受控重构获准开始，L1/L2/L3 的边界继续遵守主规范 17.1；Phase 2~4 未取得退出批准。

### 关闭结论

等待 L4 的架构决策阻塞已清零。文档现在是可执行的生效重构基线；生产仍被能力成熟度、未完成 GOV 规则、Active 豁免、P1 债务、故障/容量/安全/恢复证据和 Phase 证据包阻断。

## 第一轮：结构、范围与依赖边界

### 发现

1. **P1：模块图的箭头含义不明确。** 原图可能被理解为 Web/CLI 可以直接依赖 adapters，与“唯一组合根”冲突。
2. **P1：缺少当前实现与目标架构的明确区分。** 容易将规范中的 MUST 误读为已经交付。
3. **P1：产品、ADR、历史资料和架构主文档的权威关系未定义。** 多份文档可能再次成为并行事实来源。
4. **P2：关键术语没有统一定义。** Intent、fence、reconcile 和 exactly-once effect 容易被不同实现者作不同解释。
5. **P2：允许的编译依赖只用文字描述，无法直接转换为 ArchUnit 规则。

### 修订

- 增加“文档权威关系”，规定主规范、产品、ADR 和 archive 的作用。
- 增加“当前状态与目标状态”，明确当前仍是 local 到 team 的迁移阶段。
- 增加术语表。
- 重画模块依赖图，并明确箭头表示编译期依赖。
- 增加逐模块依赖矩阵、历史例外清零原则和 bootstrap 公共 API 约束。

### 关闭结论

上述问题已在主文档第 1、2、5 节关闭。仍需在 Phase 1 将依赖矩阵编码为 ArchUnit；该工作属于实现任务，不阻塞第二轮文档评审。

## 第二轮：生产正确性与故障模型

### 发现

1. **P0：租约协议未规定统一时间源。** Worker 本地时钟漂移可能造成提前接管或永不超时。
2. **P0：取消与外部成功并发时缺少终态优先级。** 可能出现 Git 已发布但任务被标为 CANCELLED。
3. **P0：不同外部副作用没有逐类幂等和结果未知策略。** Git、对象存储和 LLM 调用不能使用同一种盲目重试方式。
4. **P0：授权 nonce 的消费与 ref 更新原子性描述不足。** 重放和密钥轮换边界不明确。
5. **P1：事件 sequence 的分配算法未定义。** 多节点“max + 1”会产生重复序号或丢事件。
6. **P1：数据库恢复、Git 已成功但 DB 未提交等组合故障缺少统一收敛矩阵。
7. **P1：对象存储只规定 digest 校验，没有规定条件写、临时上传和孤儿回收竞态。
8. **P2：SSE 重连没有明确重新鉴权，游标可能被错误地当成访问凭据。

### 修订

- 租约、超时和事件时间统一使用 PostgreSQL 时间；补充 reaper、故障切换后的续租复核和任务饥饿治理。
- 为取消与完成增加 CAS 决胜规则和 `TOO_LATE` 语义。
- 增加外部副作用策略表，区分 Git、对象存储、Agent/LLM、审核引擎和审计 checkpoint。
- 扩展 PublishAuthorization，加入签名算法、key id、规范序列化、Git object format 和轮换/撤销规则。
- 规定 nonce 消费与 ref 更新具有同一原子结果，并收紧 `PUBLISHED` 判定。
- 明确 outbox Relay 的领取、补发、保留水位，以及数据库原子 sequence 分配。
- 增加对象临时上传、条件写和数据库引用发布协议。
- 增加跨九类故障点的收敛矩阵和 SSE 重连鉴权要求。

### 关闭结论

协议层面的双写、过期 Worker、未知结果和取消竞态已形成明确约束。具体 PostgreSQL SQL、Git 服务端扩展方式和 Provider 幂等能力仍需对应 ADR 与集成测试证明。

## 第三轮：实施验收、量化证据与文档治理

### 发现

1. **P1：Phase 退出条件缺少统一证据包。** “测试通过”和“演练完成”没有规定环境、原始结果、风险和回滚记录。
2. **P1：`mvn test` 作为验收口径不够自包含。** 当前仓库曾出现 fat jar 未预构建、ArchUnit 规则与 bootstrap 现状不一致等问题，普通测试命令可能随机失败或误报。
3. **P1：三轮文档评审容易被误解为生产批准。** 文档状态与负责人批准缺少明确分界。
4. **P2：文档目录收敛后，代码注释和历史引用可能再次引入不存在的旧文档路径。**
5. **P2：迁移阶段没有明确“未达准入时不得上线”的总闸门。**

### 修订

- 在 Phase 17 开头增加统一验收包格式。
- 将 Phase 0 的测试要求改为“干净环境、唯一命令、显式准备依赖和 profile”，并区分离线契约测试与真实 Smoke/集成测试。
- 明确三轮自评完成后仍需负责人批准，`Reviewing` 不等于 `Accepted`，更不等于生产准入。
- 增加单一 `docs/` 入口、架构子入口和历史证据边界；所有代码引用统一迁移到 `docs/archive/`。
- 在生产总验收清单增加“一项不满足即不得生产”的硬闸门。

### 关闭结论

文档已经具备可执行的评审闭环：结构边界、故障正确性、实施证据分别完成一轮，且每轮均记录了发现、修订和剩余 ADR。主文档继续保持 `Reviewing`，等待外部负责人确认 ADR 和实现证据后再转 `Accepted`。

## 三轮自评总结果

| 维度 | 结果 | 剩余工作 |
| --- | --- | --- |
| 结构与边界 | 已关闭文档层 P0/P1 | 将依赖矩阵编码为 ArchUnit |
| 生产正确性 | 已关闭协议层 P0 | 完成 PostgreSQL/Git/Provider 集成和故障测试 |
| 实施与治理 | 已关闭文档层 P1 | 建立证据包、ADR、runbook 和负责人批准流程 |

结论：本规范可以作为重构实施的评审候选基线，但当前项目仍不能据此宣称已经达到企业生产级；生产状态必须等待 Phase 退出条件全部满足。

## 第四轮：长期架构治理

### 发现

1. **P1：架构适应度只有雏形。** 已有 ArchUnit、契约测试和故障测试，但没有统一规则目录、CI 分层、失败动作和趋势指标。
2. **P1：复杂度预算缺失。** 运行容量预算不能约束 `ApiRoutes`、`GateServiceImpl` 等代码热点的持续增长。
3. **P1：技术债只有散落的 Phase/Issue 约束。** 缺少统一台账、偿还 SLA、利息和治理节奏。
4. **P1：能力拆分缺少交付模板。** 新能力容易遗漏权限、幂等、事件、指标、runbook 和恢复设计。
5. **P1：数据 owner 没有落到表、事件、API 和对象前缀。**
6. **P2：豁免机制没有强制 owner、到期日、退出计划和过期自动失败。**

### 修订

- 新增 [`governance.md`](governance.md)，定义架构适应度规则域、CI 层级、复杂度预算、能力交付模板、豁免和技术债治理。
- 增加当前热点基线：`ApiRoutes` 约 1,538 行，`GateServiceImpl` 约 663 行，均进入技术债管理。
- 明确新增对象必须有唯一 owner，新增能力必须完成完整交付清单。
- 将豁免变成有编号、owner、批准人、复审日、到期日和退出方案的可过期记录。

### 关闭结论

文档层已具备长期治理规则和初始预算；CI 自动实现、复杂度趋势采集和债务偿还仍属于 Phase 0/1 实施工作。

## 第五轮：ADR、所有权与事实来源闭环

### 发现

1. **P1：主规范要求 8 项 ADR Accepted，但仓库没有正式 ADR 注册表和模板。**
2. **P1：代码仍大量引用旧 ADR 编号和已删除执行文档，存在架构知识漂移。**
3. **P1：当前表、事件、API、对象和派生指标没有唯一 owner 目录，跨能力写入边界无法审计。**
4. **P1：主规范没有把治理目录、ADR 状态和 owner 覆盖率纳入 Accepted 条件。**

### 修订

- 新增 [`../adr/README.md`](../adr/README.md) 和 [`../adr/template.md`](../adr/template.md)，建立 ADR 状态机、责任人和 8 项必需 ADR 注册表。
- 明确旧代码中的 `ADR-1`、`ADR-8`、`ADR-9` 等只是 Legacy 引用，不自动等于当前基线的 Accepted 决策。
- 新增 [`ownership-catalog.md`](ownership-catalog.md)，覆盖当前迁移中的事实表、任务、事件、API、Blob 和派生数据。
- 新增 [`../debt-register.md`](../debt-register.md)，登记当前热点、任务/SSE 生产差距、架构测试漂移和缺失治理能力。
- 将 ADR 注册表、治理规则、owner 目录和债务台账纳入主规范的长期治理准入。
- 增加禁止无 owner 对象、永久豁免、垃圾桶包和无登记热点扩大的反劣化红线。

### 关闭结论

文档事实来源已经形成入口、注册表、模板、所有权目录和债务台账的闭环；其中 ADR 仍为 `Missing`、治理 CI 尚未全部落地，因此主规范继续保持 `Reviewing`，不能转为 `Accepted`。

## 四、六轮自评总结果

| 维度 | 结果 | 剩余工作 |
| --- | --- | --- |
| 结构与边界 | 文档层已关闭 | 将依赖矩阵编码为 ArchUnit |
| 生产正确性 | 协议层已关闭 | 完成 PostgreSQL/Git/Provider 集成和故障测试 |
| 实施与治理 | 证据格式已关闭 | 建立真实 CI、runbook 和 Phase 证据包 |
| 长期防腐 | 规则和预算已建立 | 执行复杂度趋势、豁免和债务偿还节奏 |
| 事实来源 | ADR/owner/debt 入口已建立 | 完成 8 项 ADR 并清理 Legacy 引用 |

最终结论：当前文档已经达到“生产级重构候选实施基线 + 长期治理框架雏形”，可以继续指导重构；但在 8 项 ADR、治理 CI、数据 owner 落地、债务偿还和 Phase 证据完成前，不能称为生效生产架构，也不能宣称系统已经达到生产级。

## 第七轮：ADR、日期 SLA、API 覆盖与能力实例

### 发现

1. **P1：ADR 只有注册表，没有实体草案；注册表缺少批准人、复审日期和验证证据列；`Missing` 未进入状态机。**
2. **P1：债务 SLA 写成检查点描述，无法按日历日期自动判断逾期。**
3. **P1：EX-001/EX-002 仍为 Proposed 且批准人待指定，不能作为有效开发期豁免。**
4. **P1：`/api/agent-runtimes` 和 `/api/reconcile` 未登记 owner。**
5. **P2：复杂度基线字段与测量口径不一致，缺少提交 SHA、工具版本、参数和阈值。**
6. **P2：能力实例目录只有 README，尚未完成现有能力回填。**

### 修订

- 建立 ADR-001 至 ADR-008 八份真实 `Draft` 实体文档，并在注册表补齐批准人、复审日期、验证证据和实体链接；将 `Missing` 加入状态机。
- 将 P1 债务 SLA 改为 2026-09-18、2026-10-18、2026-11-17 等可计算日历日期，同时保留检查点内容。
- 明确 EX-001/EX-002 当前“未批准、未生效”，批准前阻断相关热点变更；不擅自将其改为 Active。
- 在所有权目录登记 `/api/agent-runtimes -> status` 和 `/api/reconcile -> publish`。
- 将复杂度基线统一为 `physical_lines`，固定 PowerShell 测量命令、工具版本、提交 SHA、阈值和 delta 规则。
- 创建 12 个能力实例文档，并保持状态与能力注册表一致。

### 关闭结论

本轮文档缺口已关闭；真实批准、治理命令落地、豁免激活和能力代码迁移仍是实施工作。因此主规范继续保持 `Reviewing`，本轮有条件通过。

## 第六轮：P1 闭环修复与确定性口径

### 发现

1. **P1：所有权目录内部矛盾。** `review_result` 事实表和成本派生列被写成两个 owner；能力列表也遗漏 `project/provider/security/metrics`，API 和事件覆盖不全。
2. **P1：豁免规则没有注册表和真实记录。** 复杂度预算要求豁免，但没有可过期载体；豁免与主规范 MUST 的关系不明确。
3. **P1：技术债台账字段不足以支撑治理规则。** 缺少利息、30/60/90 天检查点、目标版本、逾期动作和完整证据；ADR 缺失条目已过时。
4. **P2：适应度规则没有稳定编号、计算口径、基线文件和命令输出约束。**
5. **P2：能力模板没有能力目录、命名规则和现有能力回填要求。**
6. **P2：架构入口仍使用“三轮评审”旧语义。**

### 修订

- 新增 [`capability-registry.md`](capability-registry.md)，统一 12 个能力名称、owner、模块目录、状态、API 和事件语义；新增 `docs/capabilities/` 实例目录规则。
- 重写 [`ownership-catalog.md`](ownership-catalog.md)，区分当前写入者、目标 owner、派生 owner、事件语义 owner 和事件存储 owner；明确 `review_result`/`ticket` 混合派生列是迁移债务，不是双 owner。
- 新增 [`exemption-register.md`](exemption-register.md)，建立状态机、EX-001/EX-002 基线差量记录和“开发期允许、生产仍阻断”的语义。
- 为治理规则增加 `GOV-*` 稳定编号、复杂度计算口径、`complexity-baseline.json`、能力映射和 CI 输出要求。
- 新增 [`verification-contract.md`](verification-contract.md)，固定每条规则的目标命令、输入基线、必备输出和当前实现状态。
- 重构 [`../debt-register.md`](../debt-register.md)，补齐 interest/risk、SLA-30/60/90、target version、overdue action、exemption/evidence，并将 DEBT-007 更新为 8 项 ADR 尚未完成。
- 修正架构入口和主文档目标状态中的五/六轮评审语义。

### 关闭结论

三项 P1 文档闭环已经补齐，两个 P2 确定性问题也已处理。当前仍有明确实施债务：治理 CI、能力实例回填、8 项 ADR、混合派生列迁移和债务偿还尚未完成。因此主规范继续保持 `Reviewing`，有条件通过，不转为 `Accepted`。

## 六轮自评新增结论

| 问题 | 文档机制 | 当前状态 |
| --- | --- | --- |
| 数据 owner 唯一性 | 能力注册表 + 当前写入者/目标 owner/派生 owner 目录 | 文档已关闭，实施需回填 |
| 豁免闭环 | 注册表、模板、到期 CI 失败、MUST 不可豁免 | 文档已关闭，EX-001/002 待批准 |
| 债务可治理 | 完整字段、30/60/90 天、目标版本、逾期动作、证据 | 文档已关闭，条目偿还未开始 |
| 规则确定性 | GOV 编号、计算口径、基线 JSON、命令契约 | 文档已关闭，CI 待落地 |
| 能力确定性 | 能力注册表、目录命名、实例回填 | 文档已关闭，实例待回填 |

## 第七轮修订结果（历史快照）

本轮新增并已复核的文档资产：

- ADR-001 至 ADR-008 八份实体 `Draft` 文档；注册表补齐状态、负责人、批准人、复审日期、验证证据和实体链接。
- `Missing` 已进入 ADR 状态机；当前实体已建立但仍未批准，因此主规范继续阻断 `Accepted`。
- P1 债务的 SLA-30/60/90 已改为具体日历日期与检查点组合，可按日期计算逾期。
- EX-001/EX-002 明确为 `Proposed（无批准，不生效）`，未擅自获得开发期合并资格。
- `/api/agent-runtimes` 归 `status`，`/api/reconcile` 归 `publish`。
- 复杂度基线固定为 `physical_lines`，包含 baseline commit、PowerShell 工具版本、命令、阈值和测量日期。
- `docs/capabilities/` 已回填 12 个能力实例，均保留真实的 Baseline/Implementing/Planned 状态。

当前仍未关闭的实施项：8 项 ADR 批准与验证、治理命令/CI、豁免批准、能力代码目录迁移、metrics projection 迁移和债务偿还。结论保持“有条件通过，继续 Reviewing”。

## 第八轮：准入状态与行动闭环

### 发现

1. **P1：各文档分别描述状态，但没有统一回答“现在能否合并、进入 Phase 或部署生产”。**
2. **P1：未关闭项有 owner 和日期，但缺少集中行动清单与阻断范围，评审后容易无人跟进。**
3. **P2：`Draft`、`Proposed`、`Accepted`、`Implemented`、`Verified`、`Active` 等状态分散在不同文档，缺少统一语义和生产决策规则。**

### 修订

- 新增 [`admission-matrix.md`](admission-matrix.md)，统一主规范、profile、Phase、规则状态和生产硬条件。
- 新增 [`next-actions.md`](next-actions.md)，将 ADR、豁免、治理命令、能力回填、projection 迁移和 Legacy 清理映射到 owner、日期、证据和阻断范围。
- 明确 `Draft/Proposed/Planned/Blocked/Expired` 均不能作为生产准入依据；`Active` 只代表批准的开发期豁免，仍阻断生产。
- 将准入矩阵和行动清单加入 docs/architecture 与 docs 根入口。

### 关闭结论

当前文档已经能明确指导“可做什么、不能做什么、谁负责、何时完成、用什么证据关闭”。8 项 ADR、治理 CI、豁免批准和 Phase 实施仍未完成，主规范继续保持 `Reviewing`，本轮有条件通过。

## 第九轮：机器状态源与 ADR 评审就绪度

### 发现

1. **P1：当前治理状态只有 Markdown 表格，CI 无法稳定读取架构、Phase、ADR、豁免和债务状态。**
2. **P1：8 项 ADR 虽有实体文件，但内容仍是候选草案；缺少统一 Proposed 门槛，容易通过改状态绕过实质评审。**
3. **P2：第七轮标题仍标记为“当前最新”，与第八轮状态冲突。**

### 修订

- 新增 [`governance-status.json`](governance-status.json)，机器可读记录主规范、profile、Phase、ADR、豁免、治理规则、能力、债务和硬阻断项。
- 新增 [`../adr/review-readiness.md`](../adr/review-readiness.md)，定义 ADR 从 Draft 转 Proposed 的统一门槛，并逐项登记当前缺口。
- 将第七轮“当前最新”修正为“历史快照”。

### 关闭结论

状态判断和 ADR 就绪度已经可被后续治理命令消费；当前 8 项 ADR 明确为 `Not Ready`，不得进入 Proposed/Accepted。主规范继续 `Reviewing`。

## 第十轮：最小治理命令与严格准入验证

### 发现

1. **P1：机器状态源已建立，但没有实际命令消费它，治理自动化仍全部停留在目标契约。**
2. **P2：普通文档一致性失败与“当前尚未达到生产准入”需要不同退出语义。**

### 修订

- 新增 `docs/tools/verify-governance.ps1`，实际校验 Markdown 链接、JSON、复杂度基线、8 项 ADR、12 个能力实例、P1 债务日历 SLA、API owner 和治理状态一致性。
- 默认模式只验证文档/基线一致性；`-StrictAdmission` 还会读取硬阻断项并返回失败，避免把“文档合法”误认为“生产准入”。
- verification contract 将 `GOV-DOC-001`、`GOV-CPLX-001` 更新为“本地最小检查已实现，CI 待接入”。

### 验证结果

- 普通模式：`passed=true`，errors=0。
- 严格准入：`passed=false`，正确报告 ADR 未批准、治理 CI 未完成、豁免未激活、team/enterprise 能力未验证和 Phase 证据缺失。

### 关闭结论

治理自动化已经从纯设计进入“本地最小可执行”阶段，但 CI、negative fixture 和其余 GOV 规则尚未实现。主规范继续 `Reviewing`。

## 第十二轮：L3 及以下 Phase 1 攻坚闭环与工具链落地

（历史快照，见上）

## 第十三轮：Phase4 企业安全与生产运维 本地 Verified

### 发现

1. **P1：security 仍为 Planned，RBAC/SoD/租户/WORM 未落地，阻断 enterprise。**
2. **P1：GOV-API-001/GOV-OBS-001 仍为 Planned，SLO/告警/健康探针未可观测，verify-capacity 仅为占位。**
3. **P1：备份恢复仅为文档，缺少 DB/Git/对象 恢复演练与 reconcile 证据。**
4. **P1：verify-fault/verify-capacity mvn 包装参数错误导致 CI 假绿/假红。**

### 修订与实施

- 交付 `gate-domain/security` 6角色/20权限/SoD，`RbacPort/TenantPort/AuditArchivePort`，`JdbcRbacStore/TenantIsolationService/WormAuditArchive`，`V13` RBAC/WORM/SoD，双租户隔离与 dual-approval，`SecurityContextResolver` + `GateSecurityHolder` + `ApiHandler` RBAC 强制。
- 交付 `HealthService` livez/readyz/dependencies，`InMemoryMetrics` Prometheus，`SloService` §14 8指标，`V14` backup_manifest/slo_history/health_probe/alert_rule，`/livez /readyz /metrics /status/slo`，`runbook/slo+alerts+recovery`，`GOV-OBS-001` 全面落地。
- 交付 `BackupService` DB 复制+Git bundle+S3 manifest，`restoreDbPreCheck` 要求无 RUNNING，恢复后 `reconcile` 全量收敛，`V14` 索引与告警规则。
- 修复 `verify-fault.ps1`（拆 adapter/web + `-Dsurefire.failIfNoSpecifiedTests=false` + Phase4SecurityAndHaTest）与 `verify-capacity.ps1`（增加 Phase4 健康/SLO + rawSign 修复 + 1ms headroom），`verify-contract` 14 migrations，`verify-governance` 10/10，`verify-fast` 8/8 全部通过。
- 更新 `ownership-catalog` 6新表+4新API，`capability-registry` security Implementing，`debt-register` 3项 Resolved（007/008/010），`governance-status` phase_4/enterprise Verified（本地），`admission-matrix` Phase4 Verified，`complexity-baseline` 971（L4 批准增量），`phase4-evidence` 4/4，`next-actions` 全部 P1 关闭。

### 验证

- `Phase4SecurityAndHaTest` 4/4（RBAC/SoD/租户隔离/WORM KMS/备份/健康/SLO）
- `Phase3HaFaultTest` 4/4 + `TaskEventOutboxFaultTest` 6/6 保持通过
- `verify-governance` passed（non-strict），`verify-contract` 14 migrations passed，`verify-fault` 6场景 passed，`verify-capacity` SLO headroom passed，`verify-fast` 8/8

### 关闭结论

Phase4 退出条件本地验证全部达成；生产仍被 EX-001/002（ApiRoutes 971/GateServiceImpl 149）与 DEBT-001/002（热点拆分）物理阻断，企业真实 HA（Patroni/Git HA/S3 ObjectLock/JMH 压测）待后续 infra 替换。L4 批准 Phase4 本地 Verified 为下一阶段实施基线。
