# 文档索引 — 本地 Git 提交闸门层

本目录收录本项目的设计与执行文档。**当前进度：P0–P4 全部完成，94 tests green，A1–A12 验收判据全部通过；H1 首次真实数据收集已完成，classification=PARTIAL。执行阶段的文档已归档至 `归档/done/`，本目录仅保留索引与背景文档。**

阅读顺序（进场人员从上往下）：归档/done/架构落地执行文档 → 归档/done/spike-结论 → 归档/done/执行文档 →（背景）立项讨论。

## 当前目录

| 文档 | 作用 | 状态 |
|---|---|---|
| [README.md](README.md) | 本索引 | 生效 |
| [立项讨论-v2.md](立项讨论-v2.md) | 立项论证：竞品检索、闸门设计、成本假设 H1、MVP 范围。执行文档的上游，仅作背景。 | 草案 v2（背景） |

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

- **构建 / 接口 / 契约层面**：以 `归档/done/架构落地执行文档.md` 为准。
- **做到哪一步 / 验收 / 止损**：以 `归档/done/执行文档.md` 为准。
- **已定的技术选型（D5–D8）**：以 `归档/done/spike-结论.md` 为准（引擎 prism 单选、ProcessRunner 调真实 git 不引入 JGit、MCP stdio 优先、git notes 备选作废）。
- `立项讨论-v2.md` 仅作背景论证，不作实现依据。
- 定位已从"一套本地 Git 工单系统"收窄为"**一个 CLI 无关的 git 提交闸门层**"。

## 已冻结的地基决策（不再重议）

§1.3 威胁模型（防错误+可检测，非隔离同权限对手）、§6 approval 方案替换 tree 令牌（B9 攻破原方案）、§2.2 强制独立 clone（B14 绕过链接 worktree）、ADR-1 权威路径不设 GitBackend 端口、执行文档 D1–D4 —— 5 项全部认可，冻结为定案。

技术选型定案（spike + 批复）：审核引擎 = `dshills/prism` 单选；权威路径 = ProcessRunner 调真实 git（不引入 JGit）；接入 = MCP stdio 优先；**LLM 经 newapi 网关中转（`https://newapi.sakta.top`），不装 Ollama，先配供应商再拉模型（ADR-9）**；竞品定性 = **参考 agent-mesh 思路、从零自建，不在其上二次开发**。

## P1 开工前置（收尾，已闭合）

- 竞品复查（执行文档 §7 第 5 条）已定性为「参考 agent-mesh、从零自建」，仅确认 2026-08 后无新项目完整覆盖三条自建理由——不阻断开工。
- N1–N7 补充约束已并入架构文档（§10.3 preflight / §5.3 / §8.3 等），见架构文档 §13.1。
- P2 首日用真实 prism JSON 核对 §5.3 Finding 模型（`归档/done/p2-schema-核对.md`）。

> 环境提醒：本机 `java` 未在 PATH（jenv 未设全局），JDK 在 `C:\Users\17428\.jdks\corretto-17.0.3-1`，`prism.exe` 已装在 `C:\Users\17428\go\bin`。P1 开工前设好 `JAVA_HOME`。
