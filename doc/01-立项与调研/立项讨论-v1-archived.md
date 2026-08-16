# 本地 Git 工单系统 — 立项讨论

> 状态：草案（待审查）
> 作者：Sisyphus（代笔）
> 日期：2026-08-11

---

## 0. 一句话定位

一个**本地运行的 Git 工单 + 强模型代码审核**系统。它以 MCP Server 形式暴露能力，让 vibe coding 工作流里那个"质量参差不齐"的环节——代码提交——被一个强模型审核员卡住，只有审核通过才允许真正的 `git commit`。

核心价值：**成本可控的质量闸门**。弱模型（执行 agent）便宜地干活，强模型（审核员）只在"预提审"这一步少量介入，用最低 token 成本守住代码质量底线。

---

## 1. 背景与动机

- **问题**：vibe coding 下，执行 agent 的代码质量层次不齐。让强模型全程参与每个工单成本太高；让弱模型自由提交又容易引入垃圾代码 / 安全漏洞 / 破坏构建。
- **洞察**：审核是低频高价值动作。一个工单里 95% 的 token 花在"写代码"，只有 5% 该花在"审代码"。把强模型严格限制在审核节点，成本可控且收益最大。
- **约束**：必须本地、必须基于真实 git（不能自己造版本控制）、必须能对接多种 LLM（执行模型可弱可强，审核模型必须强）。

---

## 2. 核心工作流（关键设计）

```
agent 完成工单
   ↓
[预提审] 收集 git 记录 + 改动代码（working tree diff / staged）
   ↓
[代码审核界面] 在线 diff 对比  ←── 人工 or 一键 LLM 审核员
   ↓（仅当审核通过）
[正式 git commit]  ←── 系统代为执行，agent 无权直接 commit
   ↓
工单状态 → 完成
```

**铁律**：状态机里"完成"这个终态，**只能**由"审核通过 → commit 成功"这条路径到达。agent 自己写的 `git commit` 被系统屏蔽或视为无效（见 §6 安全）。

---

## 3. 领域模型（数据层）

### 3.1 项目 Project
| 字段 | 说明 |
|---|---|
| 项目名称 | 唯一标识 / 显示名 |
| 项目工作目录 | 绝对路径，指向真实 git repo |
| 创建日期 / 更新日期 | 系统维护 |
| 规模 | 小型/中型/大型，或自动按代码行数/文件数估算 |
| 当前阶段 | 立项/开发中/维护/归档 |
| 代码提交记录 | 镜像 git log（可缓存） |
| 项目 git 树 | 当前分支、远端、状态快照 |
| 备注 | 自由文本 |

### 3.2 工单 Ticket（项目 1:n）
| 字段 | 说明 |
|---|---|
| 工单号 | 项目内唯一，如 `PROJ-12` |
| 标题 | |
| 需求描述 | agent 的输入 |
| 创建日期 / 更新日期 | |
| 执行 agent 模型 | 用了哪个模型干的活（便于复盘质量） |
| 当前阶段 | 待处理 / 进行中 / 预提审 / 审核中 / 已完成 / 已驳回 |
| 评论 | 时间线式，含人工 + LLM 审核意见 |

### 3.3 预提审 PreSubmit
- 关联一个工单
- 捕获：`git diff`（含 untracked 文件）、base commit、目标分支
- 审核结论：通过 / 驳回（带理由 + 行级评论）
- 通过后才解锁 commit

### 3.4 终端会话 TerminalSession（项目 n 个）
- 绑定项目工作目录
- 实时 stdout/stderr 流
- 可被"监督系统"抽取关键信息（见 §7）
- **两种开法**：
  - **空白终端页**：用户手动新开，自己输入 `opencode` / `claude code` 等 CLI。
  - **agent 终端页（核心）**：系统一键新开，并**自动拉起 agent CLI**（opencode / claude code），且通过 MCP 把工单上下文喂给它。见 §3.5。

### 3.5 agent 调度（B 模式：系统通过 MCP 驱动 agent）

**已确认：采用 B 模式**——系统不是旁观者，而是 agent 的调度方与闸门。

- 用户点「启动 agent 处理工单」→ 系统新开一个 agent 终端页，**自动执行** `opencode` / `claude code`（可指定执行模型）。
- **工单上下文注入**：系统把工单号、需求描述、目标分支、约束（如"禁止自行 git commit"）以 prompt 形式喂给 agent CLI。
- **agent 通过本系统 MCP 回写**（而非 agent 直接碰 git）：
  - 进度 / 阶段变更（进行中 → 预提审）
  - 触发 `presubmit_create` 收集 diff
  - **禁止**调用裸 `git commit`——commit 只能由系统 `commit_execute` 执行。
- **强绑定**：工单 ↔ agent 终端会话 ↔ 分支 三者的生命周期绑定，便于审计与回滚。

> 这要求 agent CLI（opencode / claude code）支持以 MCP client 身份连回本系统，或在启动时被注入指向本系统 MCP 的配置。若 CLI 不原生支持回连，退路是：agent 把"请求"写到约定文件 / 标准输出协议，由终端监督层解析后转调 MCP。

---

## 4. MCP Server 设计

系统启动即拉起一个 **MCP Server**（stdio 或 SSE/HTTP）。它是对外唯一接口，UI 与 agent 都通过它操作。

### 建议 Tool 清单（初版）
- `project_create` / `project_get` / `project_list` / `project_update` / `project_delete`
- `ticket_create` / `ticket_get` / `ticket_list` / `ticket_update_status` / `ticket_comment`
- `presubmit_create`（收集 diff）/ `presubmit_get_diff` / `presubmit_review`（人或 LLM 结论）
- `commit_execute`（**仅**在审核通过后调用，系统代执行 `git commit`）
 - `terminal_open` / `terminal_write` / `terminal_read` / `terminal_close` / `terminal_spawn_agent`（一键拉起 opencode/claude code 并注入工单上下文）
 - `agent_start_ticket`（调度 agent 处理指定工单：开终端 + 注入 MCP 上下文 + 绑定分支）
- `config_get` / `config_set`（LLM 配置、审核员模型、阈值）
- `review_run`（一键调用配置好的审核员模型，返回结构化审查意见）

### 资源 / 订阅
- `git://project/{id}/tree`、`git://project/{id}/log` 作为 MCP resource，支持订阅变更。

---

## 5. UI 构成（参考 SVN/Git 客户端）

1. **项目视图**：列表 + 详情（阶段、规模、git 树、提交记录）。
2. **工单视图**：看板或列表，阶段流转。
3. **代码审核界面**（重点）：
   - 左右/内联 diff 对比（借鉴 GitHub PR diff、VS Code、gitk）。
   - 行级评论 + 整体结论。
   - 「一键 LLM 审查」按钮 → 调 `review_run` → 渲染结构化结果（问题分级：blocker/warning/nit）。
4. **终端系统**：每项目可开 n 个终端页，内嵌 CLI，实时流输出。
5. **设置中心**：LLM provider/key/模型选择（执行模型 vs 审核模型分离）、审核严格度、commit 策略。

---

## 6. 安全与防绕过（必须想清楚）

- **agent 不能直接 commit（已定硬约束）**：每个受管 git repo 安装系统生成的 `pre-commit` hook，拒绝任何未携带"已审核工单令牌"的提交。agent 工作目录为**预提审沙箱**——agent 只写工作区，commit 由系统在审核通过后以令牌放行。hook 是物理闸门，不依赖 agent 自觉。
- **审核员独立性**：审核模型配置与执行模型解耦，避免"自己审自己"。
- **diff 完整性**：untracked 文件、权限变更、` .gitignore` 漂移都要纳入预提审，否则 agent 可把脏东西塞进 repo 而不走审核。
- **审计留痕**：每次 commit 由系统记录"经工单 X / 审核员模型 Y 通过"，写入 commit message 或备注。

---

## 7. 监督系统（终端信息抽取）

- 终端实时流进系统，可用规则 / 轻量模型抽取：构建失败、测试报错、token 消耗、agent 卡死、危险命令（`rm -rf`、推送到错误远端）。
- 用途：人类监督多个并行 agent；异常时自动挂起工单进入"人工介入"。

---

## 8. 技术栈（已定：可靠性优先、强工程能力）

> 整体定位为**大项目立项**，可靠性 > 开发速度，技术选型围绕"强类型、可维护、易运维"。

- **后端 / MCP Server**：**Java（Spring Boot）**。MCP Server 用 `modelcontextprotocol/java-sdk` 以 **stdio** 启动。Java 在强类型、工程化、长期可维护上最契合本项目定位。
- **Git 操作**：**JGit**（纯 Java 库，无需 spawn 进程，行为可控、易测试）；少数 JGit 不支持的场景（如 hook 触发）用 `ProcessBuilder` 调真实 git。
- **存储**：**SQLite** 作主基座（见 §9.5 边界策略），通过 Spring Data / JDBI 访问。大对象（diff 全文、终端流、原始审计日志）落文件系统，SQLite 仅存路径索引。
- **终端**：后端 `ProcessBuilder` + PTY 库（如 `java-pty` / JNI PTY）spawn `opencode` / `claude code`；前端 `xterm.js` 渲染，双向流经 WebSocket 桥接。
- **前端**：**Web 应用，Vue 3 + TypeScript**（Vite 构建）。diff 用 Monaco / CodeMirror diff 能力；终端用 xterm.js（经 WebSocket 连后端 PTY）。TS 与后端 Java 类型契约通过 OpenAPI / 共享 DTO 对齐，降低前后端摩擦。
- **LLM 接入**：provider 抽象层（OpenAI / Anthropic / 本地 Ollama），审核员默认走强模型；执行模型按工单可配。
- **进程模型**：后端常驻服务 + 内嵌 MCP stdio server；同时为每项目管理 n 个 agent 终端子进程。

---

## 9. 关键决策（已拍板）

1. **语言栈（已定）**：**Java 后端（Spring Boot）+ Vue 3 + TypeScript 前端（Vite）**。双语言，但可靠性优先，代价可接受。
2. **MCP 传输（已定）**：**stdio**。本系统作为本地 agent 的 MCP server。
3. **agent 接入（已定：B 模式）**：系统调度 agent——一键开终端自动拉起 opencode/claude code，通过 MCP 注入工单上下文、驱动预提审与 commit 闸门。
4. **防绕过强度（已定：硬约束）**：**git pre-commit hook 物理拦截** + agent 工作目录改为"预提审沙箱"。任何未走审核的 commit 被拒绝。agent 不依赖自觉。
5. **存储（已定：SQLite 作基座，带边界）**：元数据/结构化记录入 SQLite；大对象（diff 全文、终端流、审计日志）落文件系统，SQLite 存路径索引。不引入 Postgres。
6. **UI 形态（已定）**：**Web 应用**，浏览器访问本地服务。

### 9.5 SQLite 边界策略（补充）
- 入库：项目/工单/预提审/评论/终端会话元数据/配置 —— 结构化、低并发、本地单用户，SQLite 完美胜任。
- 落盘：完整 diff 文本、终端原始流、长审计日志 —— 按 `项目/工单/会话` 分目录存文件，DB 仅存相对路径 + 大小 + 哈希。
- 收益：DB 文件始终轻量、易备份；避免大文本撑爆单表或拖慢查询。

---

## 10. 风险与开放问题

- **diff 爆炸**：大模型审核大 diff 成本高。需做 diff 分块 / 聚焦改动 / 增量审核策略。
- **误杀 vs 漏杀**：审核员太严 agent 反复被打回，太松失去意义。严格度需可调 + 人工兜底。
- **并发**：多 agent 同时预提审同一项目，分支管理要清晰（建议每工单一分支）。
- **git 状态污染**：agent 改坏 working tree 后系统如何回滚到预提审快照？需要 snapshot/restore 机制。
- **agent CLI 回连 MCP 的可行性**：opencode / claude code 是否支持以 MCP client 身份连回本系统、或在启动时注入 MCP 配置？**因已定硬约束（§9.4），此问题降级为非阻断**——即使 CLI 不回连，commit 闸门仍在 git hook 层物理生效，B 模式的"调度+上下文注入"通过启动参数/prompt 注入即可，回写动作可降级为终端 stdout 协议解析或人工触发预提审。
- **分支爆炸**：每工单一分支 + agent 长期运行，分支/terminal 生命周期清理策略需明确。

---

## 11. 下一步建议

1. 你审查本草案，回复 §9 的 6 个决策。
2. 决策落地后，我先出一份**技术方案 + 工作分解（plan）**，再进入实现。
3. 实现顺序建议：MCP 骨架 + 数据层 → git 预提审/commit 闸门 → 审核界面 → 终端 → 监督抽取 → 设置中心。

---
*本文件为讨论草案，未包含任何实现代码。审查通过后进入规划阶段。*
