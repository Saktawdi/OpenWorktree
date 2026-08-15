# 文档索引 — 本地 Git 提交闸门层

本目录收录本项目的设计与执行文档。**当前进度：P0–P4 全部完成，94 tests green，A1–A12 验收判据全部通过；H1 首次真实数据收集已完成，classification=PARTIAL。本迭代（Web UI + agent 会话编排，S0–S5）：前端已出高保真原型（`doc/prototype/`）与 `gate-web-ui/` 原型实现；后端 `gate-web` 模块 **S0（骨架+认证，A13）、S1（只读+同步 API，A14）、S2（异步 review/publish + SSE，A15/A16）已完成**，gate-web 侧 31 tests green，V4 迁移已落地，静态资源已接入 `gate-web-ui` 构建产物。**下一动作：S3 AgentConfig/会话领域与 claude adapter。** 执行阶段的文档已归档至 `归档/done/`。**

阅读顺序（进场人员从上往下）：归档/done/架构落地执行文档 → 归档/done/spike-结论 → 归档/done/执行文档 →（背景）立项讨论 →（本迭代）执行文档-后端-web → 执行文档-前端-web →（原型）x.md。

## 当前目录

| 文档 | 作用 | 状态 |
|---|---|---|
| [README.md](README.md) | 本索引 | 生效 |
| [立项讨论-v2.md](立项讨论-v2.md) | 立项论证：竞品检索、闸门设计、成本假设 H1、MVP 范围。执行文档的上游，仅作背景。文首含「后续进展（2026-08-14）」节，标注 v2 各章在实现阶段的实际结局。 | 草案 v2 + 后续进展补注（背景） |
| [执行文档-后端-web.md](执行文档-后端-web.md) | **下一迭代（Web UI + agent 会话编排）后端执行文档**：新增模块 `gate-web`、`AgentSessionPort` 端口签名、REST 路由总表（含闭环环节标注）、V4 DDL、工单级串行锁、S0–S5 阶段与验收 A13–A19、ADR-10..14。在 `归档/done/架构落地执行文档.md` 之上追加，不改写既有闸门契约。 | 执行中（S0/A13、S1/A14、S2/A15+A16 已完成，S3 进行中） |
| [执行文档-前端-web.md](执行文档-前端-web.md) | 下一迭代前端执行文档（Vue 3 + TS + Vite SPA），消费后端契约。原型已出（`doc/prototype/`），`gate-web-ui/` 为按原型实现的 Vue 工程（构建产物已接入 gate-web 静态资源）。 | 原型定稿，实现待与后端 S2+ 对齐 |
| [x.md](x.md) | **前端原型提示词 v2**（OpenDesign）：含页面清单、逐屏规格、关键交互、设计系统与**完整数据模型**。v2 新增「§3.4 设计参考蓝本」——以 Plane（工单详情/多视图）为主参考、Multica（agent 即成员/工作台 shell）为第二参考、Wekan（经典看板交互）为基线，附许可证红线（仅概念借鉴、不抄源码）。出原型用它。 | 生效（v2） |
| [调研报告-三个开源项目对比.md](调研报告-三个开源项目对比.md) | **开源项目调研**（multica / Plane / Wekan）：许可证（MIT / AGPL-3.0 / 自定义商业条款）与社区活跃度对比（取数 2026-08-14），及 gate-web-ui 前端原型参考建议。`x.md` v2 蓝本依据。 | 生效 |
| [讨论-DSH底座.md](讨论-DSH底座.md) | **讨论稿 v2**：DeepSeek Harness（rc.6）能否提炼作为本项目底座。结论：**否决插件化**（自降市场受众 + 违背「CLI 无关」定案定位）；**维持自建** S0–S5；新增预留决策 D3——`AgentCli.DSH` + `DshCliAdapter`（`dsh --profile headless`）列为 S4 后可选增量。 | 待批复 |

## 归档：已完成的执行阶段文档（`归档/done/`）

下列文档对应的项目范围（P0–P4）已全部完成并通过验收，移入归档。冲突优先级规则不变：构建/接口/契约以 `归档/done/架构落地执行文档.md` 为准，做到哪一步以 `归档/done/执行文档.md` 为准。

| 文档 | 作用 | 状态 |
|---|---|---|
| [归档/done/架构落地执行文档.md](归档/done/架构落地执行文档.md) | **地基**：分层与禁止依赖、核心端口完整 Java 签名、SQLite DDL 全文、pre-receive 生成契约、错误模型/退出码、锁与并发、测试矩阵、ADR。 | 已归档（定案） |
| [归档/done/spike-结论.md](归档/done/spike-结论.md) | P0 三个 spike（S3/S4/S1）实测结论：引擎选 `prism`、权威路径用真实 git、接入 MCP stdio。含 N1–N7 补充约束。 | 已归档 |
| [归档/done/执行文档.md](归档/done/执行文档.md) | 阶段划分（P0–P4）、每阶段可运行验收命令、止损条件。**做到哪一步以此为准。** | 已归档（生效） |
| [归档/done/acceptance-report.md](归档/done/acceptance-report.md) | **验收报告**：94 tests green、A1–A12 逐条核对、ADR 遵守、止损复查、残余风险声明。**项目结项交付物。** | 已归档 |
| [归档/done/h1-stage-report.md](归档/done/h1-stage-report.md) | **H1 阶段性数据报告**：20 个真实工单样本、first-pass rate 45%、classification=PARTIAL、degraded basis。 | 已归档 |
| [归档/done/p2-schema-核对.md](归档/done/p2-schema-核对.md) | P2 首日 prism 真实 JSON 与 §5.3 Finding 模型（尤其 `coveredPaths`）的核对记录。 | 已归档 |

## 归档：历史文档（`归档/`）

| 文档 | 作用 | 状态 |
|---|---|---|
| [归档/立项讨论-v1-archived.md](归档/立项讨论-v1-archived.md) | 已被 v2 取代的历史版本，仅留证。 | 归档 |

## 文档关系与冲突优先级

- **构建 / 接口 / 契约层面**：已完成的闸门层以 `归档/done/架构落地执行文档.md` 为准；本迭代（Web + 会话）新增的端口/路由/DDL/锁以 `执行文档-后端-web.md` 为准。
- **做到哪一步 / 验收 / 止损**：闸门层以 `归档/done/执行文档.md` 为准；本迭代以 `执行文档-后端-web.md` §10（S0–S5）为准。
- **已定的技术选型（D5–D8）**：以 `归档/done/spike-结论.md` 为准（引擎 prism 单选、ProcessRunner 调真实 git 不引入 JGit、MCP stdio 优先、git notes 备选作废）。
- 本迭代新增决策（ADR-10..14）以 `执行文档-后端-web.md` §11 为准。
- **前端视觉 / 布局 / 组件观感 / 交互细节**：原型（`x.md`）定稿后为准；但**字段、状态机、路由、闭环环节**仍以 `执行文档-前端-web.md` 与其上游后端契约为准。
- `立项讨论-v2.md` 仅作背景论证，不作实现依据。
- 定位已从"一套本地 Git 工单系统"收窄为"**一个 CLI 无关的 git 提交闸门层**"；本迭代在其上补 Web 操作台 + agent 会话编排，不恢复 v2 被删除的工单/看板大系统（见后端文档 §0、§1.3）。

## 已冻结的地基决策（不再重议）

§1.3 威胁模型（防错误+可检测，非隔离同权限对手）、§6 approval 方案替换 tree 令牌（B9 攻破原方案）、§2.2 强制独立 clone（B14 绕过链接 worktree）、ADR-1 权威路径不设 GitBackend 端口、执行文档 D1–D4 —— 5 项全部认可，冻结为定案。

技术选型定案（spike + 批复）：审核引擎 = `dshills/prism` 单选；权威路径 = ProcessRunner 调真实 git（不引入 JGit）；接入 = MCP stdio 优先；**LLM 经 newapi 网关中转（`https://newapi.sakta.top`），不装 Ollama，先配供应商再拉模型（ADR-9）**；竞品定性 = **参考 agent-mesh 思路、从零自建，不在其上二次开发**。

## P1 开工前置（收尾，已闭合）

- 竞品复查（执行文档 §7 第 5 条）已定性为「参考 agent-mesh、从零自建」，仅确认 2026-08 后无新项目完整覆盖三条自建理由——不阻断开工。
- N1–N7 补充约束已并入架构文档（§10.3 preflight / §5.3 / §8.3 等），见架构文档 §13.1。
- P2 首日用真实 prism JSON 核对 §5.3 Finding 模型（`归档/done/p2-schema-核对.md`）。

> 环境提醒：本机 `java` 未在 PATH（jenv 未设全局），JDK 在 `C:\Users\17428\.jdks\corretto-17.0.3-1`，`prism.exe` 已装在 `C:\Users\17428\go\bin`。P1 开工前设好 `JAVA_HOME`。
