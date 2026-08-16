# 前端原型提示词（OpenDesign）— Gate Web 操作台 v2

> 用法：把本文档**全文**作为提示词粘贴给 OpenDesign（[open-design.ai](https://open-design.ai/) / [Pandemonium-Research/OpenDesign](https://github.com/Pandemonium-Research/OpenDesign)），生成一份**可点击的高保真 Web 原型**。本文档自包含：产品背景、设计方向与设计系统、**设计参考蓝本**、页面清单与逐屏规格、关键交互，以及**完整数据模型**。若工具要求「设计系统」单独输入，取「三、设计系统」一节。
>
> **v2 变更**：依据 `../01-立项与调研/调研报告-三个开源项目对比.md`（multica / Plane / Wekan 三项目调研），新增「§3.4 设计参考蓝本」并把参考点落到各页面规格：以 **Plane** 为主参考（工单详情信息架构、多视图看板、URL 深链），以 **Multica** 为第二参考（agent 即成员、审核台/Inbox 语义、工作台 shell、⌘K 命令面板），以 **Wekan** 为看板交互基线（拖拽/泳道心智）。**红线：三者仅作概念/信息架构/交互蓝本，禁止复制其代码、CSS、组件**（Plane AGPL-3.0 传染、Multica 自定义商业条款均禁复用；Wekan MIT 可抄代码但其技术栈不值得跟随）。

---

## 一、角色与任务

你是一名资深的产品设计系统前端设计专家。请为下面这个**开发者自用的本地工具**产出一套**可点击的高保真 Web 原型**。注意：这是**工具型控制台（devtool console）**，不是营销落地页，不套营销页模板。

交付物：

1. 一个**全局布局（应用外壳 AppLayout）** + **8 个页面**（见「五、页面清单」）。
2. 每个页面给出**主态**；对标注了「关键态」的页面，额外给出指定态（空态 / 加载态 / 错误态）。
3. 至少覆盖**两条可点击闭环**（见「六、关键交互」）。
4. 视觉严格遵循「三、设计系统」，遵守「三、反 AI 味清单」，并按「§3.4 设计参考蓝本」借鉴概念（不抄代码）。

---

## 二、产品背景（给设计所需的最少上下文）

**产品**：gate，一个**本地运行的 git 提交闸门层**。未经审核的代码无法经 git 协议进入权威 git 历史。核心闭环：

```
建工单 → 预提审(固化 tree_hash) → 审核(锚定 tree_hash) → 通过 → 发布(commit-tree + push)
                                      └─ 驳回 → 回喂 agent 修改 → 重新预提审
```

**Web 操作台**是把这条闭环可视化、可操作，并补齐 agent 会话编排与成本度量的入口。使用者是**单个开发者（单用户、单机、本地，桌面优先）**。工单的「执行者」可以是人，也可以是 **agent**（OPENCODE / CLAUDE CLI）——agent 在会话页干活，审核台永远由**人**拍板。

**设计基调一句话**：工具感、信息密度高、不花哨；深色优先；语义色只用来表达闸门结论（通过绿 / 驳回红 / 待人工橙），主色克制。信息架构与排版节奏参照 Plane，AI 工作台质感与「agent 即成员」语义参照 Multica，看板交互基线参照 Wekan。

---

## 三、设计系统

### 3.1 设计读法（Design Read）

> 开发者自用的本地 git 提交闸门操作台（devtool console），面向单人开发者；深色、高信息密度的 cockpit 工具感语言。**信息架构与排版节奏**参考 Plane（工单详情页的分组布局、列表⇄看板多视图、URL 可深链）；**工作台 shell 与 AI 语义**参考 Multica（固定工具条 + 可折叠侧栏 + 主画布的多层 shell、⌘K 命令面板、卡片上「执行者=agent」）；**看板交互基线**参考 Wekan（Trello 式拖拽换列/列内排序/泳道心智）。色彩层面参考 GitHub Primer / Linear 的信息密度 + VS Code 暗色；等宽字体承载 hash / 路径 / 工单号 / token 计数 / 时间戳。

### 3.2 设计令牌（token 层，禁止组件内散落硬编码色值）

| 令牌 | 值（暗色默认） | 用途 |
|---|---|---|
| `bg-canvas` | `#09090b` | 页面底色 |
| `bg-surface` | `#0f0f12` | 卡片 / 面板 |
| `bg-hover` | `#17171b` | 悬浮态 |
| `border` | `#1f1f23` | 分隔线 / 边框 |
| `text-primary` | `#fafafa` | 主文本 |
| `text-secondary` | `#a1a1aa` | 次级文本 |
| `text-muted` | `#71717a` | 弱文本 / 标签 |
| `text-disabled` | `#52525b` | 禁用 |
| `accent` | 单一蓝 `#3b82f6`（hover `#60a5fa`） | 品牌 mark、激活态、链接、主按钮、focus ring |
| `success` | `#10b981`（前景可 `#34d399`） | 仅 verdict=pass、健康态 |
| `danger` | `#ef4444`（前景可 `#f87171`） | 仅 verdict=reject、severity=blocker、错误 |
| `warning` | `#f59e0b` | 仅 verdict=requires_human、severity=warning、degraded |

- **语义色纪律**：`success/danger/warning` 只用于 verdict / severity / 状态，不得当装饰色。全站**一个主色 accent**，锁定后所有主按钮、激活态、链接都用它。
- **圆角**：一套锁死——交互控件（按钮/输入/标签）`6–8px`，卡片/面板 `12px`，pill（仅状态徽章）全圆。禁止混用三套以上。
- **字体**：UI 用系统无衬线（`system-ui` / `IBM Plex Sans` / PingFang SC / Microsoft YaHei）；**等宽**用 `JetBrains Mono` / `IBM Plex Mono`，用于 `tree_hash`、`base_commit`、路径、工单号、token 计数、时间戳、SHA。
- **间距**：4px 基准网格；信息密度高，表格/列表紧凑（不要大面积留白）——参照 Plane 的「高密度但克制」排版节奏。
- **投影**：几乎不用投影，用 `border` + `bg-surface` 分层。需要时投影带背景色相（不用纯黑）。
- **动效**：克制，仅用于状态反馈（hover 1px 位移、进度条、流式打字光标、SSE 进度、拖拽落位）。不做滚动视差、不做无意义循环动画。

### 3.3 反 AI 味清单（务必遵守）

- 禁止紫色 / 蓝紫渐变、禁止网格光晕背景、禁止玻璃拟态滥用。
- 禁止 emoji 当图标；图标用统一线性图标集，`strokeWidth` 统一。
- 禁止「三张等分卡片」默认排布；信息用表格 / 分区列表 / 状态徽章承载。
- 每个页面必须有明确的**加载态（骨架屏，贴合最终布局形状）/ 空态（引导文案 + 下一步动作）/ 错误态（内联，非 toast 独占）**。
- 按钮文本在桌面不换行；主按钮标签 1–4 字；同页不出现两个同义主按钮。
- **不要模仿 Plane / Multica 的营销页与浅色 SaaS 质感**：这是暗色 devtool 控制台，不是 SaaS 官网。

### 3.4 设计参考蓝本（v2 新增，概念借鉴 + 许可证红线）

> 以下三个项目经调研（许可证与活跃度结论见 `../01-立项与调研/调研报告-三个开源项目对比.md`，取数 2026-08-14）确定为原型蓝本。**只借鉴信息架构、交互与排版节奏；禁止复制任何代码、CSS、图标与组件**——Plane 为 AGPL-3.0（直接复用源码会传染整个项目）、Multica 为自定义 Apache-2.0+商业附加条款（UI 派生代码受品牌与托管双重约束），二者均不可抄；Wekan 为 MIT（唯一可抄代码的来源），但其 Meteor/Blaze + jquery-ui 技术栈不值得跟随，仅取交互心智。

| 项目 | 角色 | 借鉴点（概念层） | 红线 |
|---|---|---|---|
| **Plane**（[makeplane/plane](https://github.com/makeplane/plane)，AGPL-3.0，55.9k★ 活跃） | **主参考**：工单详情 + 多视图 | ① 工单详情页信息架构：标题行（状态机徽章/优先级/指派）+ 描述 + **活动时间线** + 关联 commit/PR 的分组布局与信息密度；② 看板**列表⇄看板多视图切换**、列头聚合计数、拖拽即时落库；③ **URL 可深链**（`/projects/:id/tickets/:no` 直达任意工单） | 不复制组件/CSS/hooks 源码；仅重写实现 |
| **Multica**（[multica-ai/multica](https://github.com/multica-ai/multica)，自定义商业条款，45.9k★ 高速成长） | **第二参考**：AI 工作台语义 | ① **「执行者 = agent」画在卡片上**：卡片承载 agent 名/运行状态/审核门徽章；② **Inbox/审核台信息架构**：只在需要人介入时才把人叫来——gate 的审核台即此语义；③ 多层 shell（固定工具条 + 可折叠侧栏 + 主画布）、**⌘K 命令面板**、独立工单窗口（master-detail）；④ **执行痕迹一等公民**：一次 agent 执行（run/execution log、token 用量）可回放、可附属于工单 | 不复制 `packages/ui`、`packages/views` 等 UI 代码；仅参考交互 |
| **Wekan**（[wekan/wekan](https://github.com/wekan/wekan)，MIT，21k★ 稳定） | **交互基线**：经典看板 | ① Trello 式**列拖拽换列 / 列内排序 / 泳道**心智；② 卡片字段展示范式（标签、到期日、清单）——用于对照确认 8 列看板没有遗漏用户熟悉的交互 | MIT 可抄代码，但技术栈（Meteor/Blaze/jquery-ui）不跟随；GPL 插件（gantt）避开 |

---

## 四、数据模型（完整，逐实体说明）

> 本表是原型所有页面字段的唯一来源。字段名用**前端视角的 camelCase**，并在「后端列/枚举名」列标注对应关系。取值集合是**闭集**，原型里出现的任何状态/徽章颜色必须取自这些枚举。

### 4.1 工单状态机 `TicketStage`

```ts
type TicketStage =
  | 'PENDING'          // 待开始
  | 'IN_PROGRESS'      // 进行中（含 REJECTED 回流，标红 + round 徽章）
  | 'PRESUBMITTED'     // 已预提审（tree_hash 已固化）
  | 'IN_REVIEW'        // 审核中（异步进行中显示 spinner）
  | 'REJECTED'         // 已驳回（非终态，回流 IN_PROGRESS）
  | 'READY_TO_PUBLISH' // 可发布（verdict=pass）
  | 'NEEDS_HUMAN'      // 待人工（verdict=requires_human）
  | 'DONE'             // 已完成（终态，折叠窄列）
  | 'CANCELLED';       // 已取消（终态，折叠窄列）
```

- 终态仅 `DONE` / `CANCELLED`。
- **看板列映射（一列不多一列不少）**：`PENDING | IN_PROGRESS | PRESUBMITTED | IN_REVIEW | READY_TO_PUBLISH | NEEDS_HUMAN | DONE | CANCELLED`，共 8 列；`REJECTED` **不单独成列**，渲染时并入 `IN_PROGRESS` 列并标红 + 右上角 `review_round: n` 徽章。

### 4.2 工单 `Ticket`

| 字段 | 类型 | 说明 / 后端列名 |
|---|---|---|
| `no` | string | 工单号，项目内唯一（如 `PROJ-12`）／`ticket_no` |
| `title` | string | 标题 |
| `description` | string | 需求描述（agent 的输入） |
| `stage` | `TicketStage` | 见 4.1 |
| `targetRef` | string | 目标分支（如 `refs/heads/main`）／`target_ref` |
| `reviewRound` | number \| null | 最新审核轮次（派生自 presubmit） |
| `treeHash` | string \| null | 最新 `tree_hash`（预提审固化锚点） |
| `baseCommit` | string \| null | 最新基线 commit |
| `execTokenTotal` | number \| null | 执行侧累计 token（V3 列，通常 null） |
| `execTokenSource` | `'agent_cli' \| 'manual' \| 'unavailable'` \| null | 执行 token 来源（V3） |
| `agentConfigId` | string \| null | 工单创建时绑定的 AgentConfig（V4，null=系统默认） |
| `createdAt` / `updatedAt` | string | 时间戳 |

### 4.3 预提审 `Presubmit`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | number | 自增主键 |
| `ticketNo` | string | 关联工单 |
| `reviewRound` | number | 审核轮次（驳回重提时 +1） |
| `treeHash` | string | **核心锚点**：`git write-tree` 固化的不可变 tree 对象 ID |
| `baseCommit` | string | 本轮基线 commit |
| `targetRef` | string | 目标分支 |
| `diffBlob` / `diffBytes` / `diffSha256` | string / number / string | diff 文本落 blob store 的路径/大小/哈希 |

唯一约束 `(ticketNo, reviewRound, treeHash)`。

### 4.4 审核结果 `Finding` / `Decision` / `Severity` / `Verdict`

```ts
type Severity = 'blocker' | 'warning' | 'nit';      // 后端另有 'info'，前端可并入 nit
type Verdict  = 'pass' | 'reject' | 'requires_human';

interface Finding {
  severity: Severity;
  file: string;        // 仓库相对路径
  lineStart: number;
  lineEnd: number;
  message: string;     // 问题描述
  suggestion: string;  // 修复建议
}

interface Decision {
  verdict: Verdict;
  reason: string;      // 结论理由（一行摘要）
  detail: string[];    // 详细说明
  findings: Finding[];
  authorization?: string; // 人工裁决授权（单次消费，可空）
}
```

- **徽章色**：`pass` 绿 / `reject` 红 / `requires_human` 橙。
- **severity 分组色**：`blocker` 红 / `warning` 黄 / `nit` 灰。右侧 Findings 面板按此三级分组。
- 判定规则（后端已固化，前端只展示）：任一 `blocker` → `reject`；仅 `warning`/`nit` → 按严格度配置决定（可转 `requires_human`）。

### 4.5 AgentConfig 与 Provider

```ts
type AgentCli = 'OPENCODE' | 'CLAUDE';

interface AgentConfig {
  id: string;
  name: string;
  cli: AgentCli;              // 选择哪个 agent CLI
  providerId: string;         // 关联供应商
  model: string;              // 如 "claude-3-5-sonnet-20241022"
  systemPrompt?: string | null;
  extraFlags?: string[] | null; // 透传 CLI flag
  description?: string | null;
}

interface Provider {
  id: string;
  name: string;
  models: string[];           // 从供应商拉取的模型清单
}
```

### 4.6 会话 `Session` / `SessionMessage` / `ToolCall` / `SessionUsage` / `SessionEvent`

```ts
type SessionStatus = 'ACTIVE' | 'ABORTED' | 'CLOSED';
type Role = 'USER' | 'ASSISTANT' | 'TOOL' | 'ERROR';

interface SessionUsage {
  promptTokens: number | null;
  completionTokens: number | null;
  totalTokens: number | null;
}

interface Session {
  id: string;
  ticketNo: string;           // 会话强制绑定工单
  agentConfigId: string;
  cli: 'OPENCODE' | 'CLAUDE';
  status: SessionStatus;
  cliSessionId: string | null; // claude session-id / opencode session id
  clonePath: string;
  allocatedPort: number | null; // opencode serve 端口；claude 为 -1
  startedAt: string;
  finishedAt: string | null;
  cumulativeUsage: SessionUsage | null; // 累计 token
}

interface ToolCall {
  name: string;
  argumentsJson: string;
  resultJson: string | null;
}

interface SessionMessage {
  id: string;
  sessionId: string;
  role: Role;
  content: string;
  toolCalls: ToolCall[];
  usage: SessionUsage | null; // 本条消息 token；非 LLM 消息为 null
  degraded: boolean;          // usage 解析失败标记（true 时不累加累计）
  timestamp: string;
}

// SSE 事件
interface SessionEvent {
  sessionId: string;
  message: SessionMessage;
  kind: 'message' | 'usage' | 'tool_call' | 'done' | 'error';
}
```

- 会话页消息卡片按 role 区分：`USER`（右对齐）/ `ASSISTANT`（左对齐，流式打字光标）/ `TOOL`（折叠 tool_call 展示 `name` + 可展开 arguments/result）/ `ERROR`（红边提示）。
- 每条 ASSISTANT/TOOL 消息下挂 `UsageBadge`：`prompt / completion / total`，是 H1 成本复测的执行侧入口。

### 4.7 异步任务 `GateTask` / `GateTaskEvent`

```ts
type GateTaskType = 'review' | 'publish' | 'session-send';
type GateTaskStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED'; // 前端另有 'idle' 初始态

interface GateTask {
  id: string;
  type: GateTaskType;
  ticketNo: string | null;
  sessionId: string | null;
  status: GateTaskStatus;
  startedAt: string;
  finishedAt: string | null;
  resultJson: string | null;
  errorJson: string | null;
}

// SSE 事件（task 进度）
interface GateTaskEvent {
  taskId: string;
  kind: 'progress' | 'done' | 'error'; // progress 的 payloadJson 含 percent 0..100 + label
  payloadJson: string;
  at: string;
}
```

- 审核台 / 发布台 / 会话发消息，长操作统一走 `POST → 202 { taskId } → SSE 进度`。进度组件 `TaskProgress` 三处复用。

### 4.8 成本度量 `MetricsRow` / `H1Verdict`

```ts
interface MetricsRow {
  ticketNo: string;
  title: string;
  stage: string;
  execTokenTotal: number | null;   // 执行侧 token
  execTokenSource: 'agent_cli' | null;
  reviewTokenTotal: number | null; // 审核侧 token
}

type H1Classification = 'PASS' | 'PARTIAL' | 'FAIL';

interface H1Verdict {
  firstPassRate: number;      // 首过率（一次 pass 占比）
  costRatioMedian: number;    // 成本比中位数；NaN = degraded basis
  classification: H1Classification;
  degraded: boolean;
  degradedReasons: string[];
}
```

- 成本面板要区分**执行 token（exec）**与**审核 token（review）**；H1 判定卡展示 `first_pass_rate`、`cost_ratio_median`、`classification`（PASS 绿 / PARTIAL 黄 / FAIL 红），`degraded` 时明确标注原因。

### 4.9 项目状态 `StatusResult`

```ts
interface StatusResult {
  targetRef: string;          // 目标分支 ref
  authTip: string;            // 权威库 HEAD（SHA）
  authCommitCount: number;    // 权威库提交数
  tickets: TicketStatus[];    // 工单状态列表
}

interface TicketStatus {
  ticketNo: string;
  stage: string;
  latestRound: number | null;
  latestTreeHash: string | null;
  latestIntentStatus: string | null;
  latestCommitSha: string | null;
  publishedInAuth: boolean;   // 是否已发布进权威库
}
```

### 4.10 错误模型（`GateErrorCode` → HTTP）

| code | 名称 | HTTP | 含义 |
|---|---|---|---|
| 0 | OK | 200 | 成功 |
| 10 | REJECT_FINDINGS | 422 | 审核驳回（业务结果） |
| 11 | REJECT_TOCTOU | 409 | tree 不一致，冲突 |
| 12 | REJECT_PRECONDITION | 422 | 前置不满足 |
| 13 | REJECT_NEEDS_HUMAN | 422 | 需人工 |
| 20 | GATE_ERROR_ENGINE | 502 | 引擎失败 |
| 21 | GATE_ERROR_IO | 503 | IO/锁忙/DB |
| 22 | GATE_ERROR_CONFIG | 500 | 配置错误 |
| 64 | USAGE | 400 | 参数错误 |
| 70 | INTERNAL | 500 | 内部不可达 |

错误响应体：`{ "error_code": 10, "error": "REJECT_FINDINGS", "message": "...", "detail": ["..."] }`。前端把 `error_code` 映射为中文可读消息（内联展示 + toast 仅用于瞬时反馈）。

---

## 五、页面清单与逐屏规格

> 全局外壳 `AppLayout`（参照 Multica 多层 shell）：左侧固定侧栏（品牌 mark「GATE 操作台」+ 分组导航「闸门闭环」：工单看板 / 审核台 / 会话 / 成本 + 底部 token 摘要 + 退出），右侧主区（顶栏：当前页标题 + 当前项目名 + 当前工单号 + token 累计小徽章 + 登录态；下方内容区）。审核台与会话页可全屏收起左侧导航。**全站 ⌘K 命令面板**（新建工单 / 跳转工单号 / 切换项目 / 打开审核台）。

| # | 路由 | 页面 | 闭环环节 | 关键态 |
|---|---|---|---|---|
| 1 | `/login` | 登录页 | auth | 错误态（401 提示） |
| 2 | `/` | 项目看板列表 | status | 空态（引导 `gate init`） |
| 3 | `/projects/:id/tickets` | 工单看板 | status | 空态 |
| 4 | `/projects/:id/tickets/:no` | 工单详情 | presubmit+review | 加载态 |
| 5 | `/projects/:id/tickets/:no/review` | **审核台（核心）** | review | 加载态 + 审核中 spinner |
| 6 | `/projects/:id/tickets/:no/session` | agent 会话页 | session | 流式加载态 |
| 7 | `/projects/:id/agents` | AgentConfig 管理 | session | 空态 |
| 8 | `/projects/:id/cost` | 成本面板 | metrics | degraded 标注 |

### 5.1 登录页 `/login`

居中卡片：品牌「GATE 操作台」+ 单行说明「输入启动日志打印的 `GATE_WEB_TOKEN`」+ 密码型输入框 +「进入操作台」按钮。错误态：token 无效时输入框下方红色内联提示「令牌无效或已撤销」。

### 5.2 项目看板列表 `/`

- 顶部统计行（4 项）：工单总数 / 进行中 / 待审核 / AUTH TIP（SHA 摘要，等宽）。
- 项目卡片：项目名（=targetRef）+ 权威库健康徽章 + 路径摘要 + `authCommitCount` commits + 工单三指标 + 「进入工单看板」。
- 空态：无项目时居中提示「暂无项目，请在 CLI 侧执行 `gate init` 初始化权威库后刷新」。
- 单项目模型（本阶段）：一个项目一张卡即可，不要铺多项目 CRUD。

### 5.3 工单看板 `/projects/:id/tickets`

- **8 列 Kanban**（见 4.1 映射），列头 = 阶段中文名 + 该列数量（列头聚合计数，参照 Plane）。`DONE`/`CANCELLED` 折叠为窄列。
- 工单卡片（参照 Multica「执行者 = agent」语义）：`ticketNo`（等宽）/ `title` / **执行者行（agent 名 + 运行状态徽章，或「人工」）** / `review_round` 徽章（>1 才显示）/ token 缩略（`exec·review`）。`REJECTED` 回流卡片标红。
- **视图切换**（参照 Plane）：看板 ⇄ 列表两种视图，列表视图列：`no / title / stage 徽章 / review_round / token / updatedAt`，行内可快速改 stage。
- **拖拽**（参照 Wekan 基线 + Plane 即时落库）：跨列拖拽换列、列内排序；拖拽即落库（乐观更新，失败回滚）；`REJECTED` 标红卡不可拖出 `IN_PROGRESS` 列。可选泳道：按执行者（agent）分泳道。
- 顶部「新建工单」按钮 → 抽屉表单：`title / description / targetRef / agentConfigId`（下拉选 AgentConfig）。
- 空态：无工单时引导「新建工单」或 CLI `gate create`。

### 5.4 工单详情 `/projects/:id/tickets/:no`

Tab 式：**概览 / diff / 审核 / 会话 / 历史**。整体参照 Plane 工单详情页的分组布局与信息密度。

- **概览**：标题行（`ticketNo` 等宽 + stage 徽章 + `review_round` + token 摘要，紧凑排布）；`StageFlowDiagram` 状态机当前位置高亮（含 REJECTED 回流箭头）；`tree_hash` + `base_commit` + `targetRef`（等宽，可复制）；描述区；**活动时间线**（参照 Plane activity timeline / Multica run 回放）：按时间倒序展示 presubmit / review / publish / 会话事件流（每个事件 = 时间戳 + 类型徽章 + 摘要，可点入对应页面）。
- **diff**：Monaco 风格 diff editor（双栏 / inline 切换），顶部锚定 `tree_hash`。
- **审核 / 会话**：跳转对应页面（或内嵌精简版）。
- **历史**：该工单 presubmit / review / publish 任务时间线（Task=执行记录 + 结果回写）。

### 5.5 审核台 `/projects/:id/tickets/:no/review`（核心页面）

三区布局。本页是全站**唯一需要人拍板的地方**，信息架构上等同 Multica 的「审核台/Inbox」语义——所有待人工事项集中于此，人只看这一处：

- **顶栏 `TreeHashBar`**（常驻、不可折叠）：`tree_hash` / `base_commit` / `targetRef`，等宽 + 复制按钮。让人一眼确认「审的就是要提交的」。
- **左：DiffViewer**（Monaco diff，inline/双栏切换）。
- **右：FindingsList** 按 severity 分组（blocker 红 2 / warning 黄 1 / nit 灰 0），每条 `FindingItem` = `file : lineStart-lineEnd` + `message` + `suggestion`；点击跳转左侧对应行。
- **底部操作区（审核门）**：`VerdictBadge`（pass 绿 / reject 红 / requires_human 橙）+ `reason`；按钮组「一键 LLM 审核 / 人工通过 / 人工驳回 / 驳回并回喂 agent」；`READY_TO_PUBLISH` 时「发布」激活。
- **底部 `TaskProgress`**：审核/发布 SSE 进度条（running percent + label）。

### 5.6 agent 会话页 `/projects/:id/tickets/:no/session`

- 左侧 `SessionList`：当前工单会话（实时 + 只读历史）+ 该 AgentConfig 历史会话；「+ 新建会话」选 AgentConfig 建会话。
- 右侧 chat 视图：`ChatMessage`（user 右 / assistant 左流式 / tool_call 折叠 / usage 徽章）；`MessageInput` + 中止按钮；`AgentConfig` 下拉切换。
- **执行痕迹**（参照 Multica execution log）：每条 agent 动作（tool_call / 命令 / 错误）带时间戳，可展开回放（arguments/result），usage 徽章挂消息下——「这一次执行到底干了什么、花了多少 token」一目了然。
- 历史会话只读模式：无输入框，纯回放消息列表。

### 5.7 AgentConfig 管理 `/projects/:id/agents`

列表（name / cli 徽章 / providerId / model / 描述）+ 表单 `AgentConfigForm`（`name / cli(OPENCODE|CLAUDE) / providerId / model / systemPrompt / extraFlags / description`）+ `ModelSelect`（从 Provider.models 拉取，保存前校验模型存在）+ 行内「查看该配置历史会话」。

### 5.8 成本面板 `/projects/:id/cost`

- 按工单/项目聚合 **exec vs review token**（区分两条柱或两列，不混算）。
- H1 判定卡：`first_pass_rate` / `cost_ratio_median` / `classification`；`degraded` 时标黄并列出 `degradedReasons`。
- 导出按钮：CSV / JSON。

---

## 六、关键交互与闭环

1. **tree_hash 可见性（硬要求）**：凡涉及审核/预提审的页面，`tree_hash` + `base_commit` 必须顶栏可见、等宽、可复制。
2. **状态机驱动（硬要求）**：看板列与 `TicketStage` 一一对应，`REJECTED` 并入 `IN_PROGRESS` 标红 + round 徽章；不要自创额外业务列。
3. **成本可见（硬要求）**：每工单卡片显示 exec/review token 缩略；会话消息挂 usage 徽章；成本面板是 H1 入口。
4. **异步任务 SSE**：审核 / 发布 / 发消息 → `202 + taskId` → 进度条或流式渲染；`RUNNING→SUCCEEDED/FAILED` 三态完整。
5. **finding 跳转**：点击 Finding → diff 编辑器跳对应行并高亮。
6. **看板拖拽（硬要求，参照 Wekan 基线 + Plane 即时落库）**：跨列拖拽换列 + 列内排序，拖拽即落库（乐观更新、失败回滚）；拖拽过程中卡片有 lift/placeholder 状态。
7. **URL 深链（参照 Plane）**：所有工单页面路由可直达（`/projects/:id/tickets/:no[/review|/session]`），刷新/分享不丢状态。
8. **⌘K 命令面板（参照 Multica）**：全局唤起，支持「新建工单 / 跳转工单号 / 切换项目 / 打开审核台」。
9. **两条必做可点击闭环**：
   - (a) 登录 → 项目看板 → 新建工单 → 工单详情 → 审核台 → 一键 LLM 审核 → `pass` → 发布 → 已完成。
   - (b) 工单详情 → 会话页 → 新建会话（选 AgentConfig）→ 发消息 → 流式回复 + tool_call + usage 徽章 → 中止/结束。

---

## 七、输出要求（验收口径）

1. 输出 1 个全局布局 + 8 个页面；每个页面标注对应的闭环环节。
2. 每个页面至少主态；§5 标注的关键态必须补齐（登录错误、看板空态、审核加载/进行中、会话流式、成本 degraded）。
3. 桌面优先，**不做移动端适配**；但布局在 1280–1920px 宽度下不失衡。
4. 交付可点击原型，能跑通 §6 两条闭环；附一张**组件/样式速览页**（按钮、徽章、Tag、表格、表单、进度条、骨架屏、空态、错误态、Token 色板，并覆盖看板卡片、拖拽态、⌘K 命令面板、活动时间线）。
5. 全站深色优先（可提供浅色切换），遵守「三、设计系统」、「三、反 AI 味清单」与「§3.4 设计参考蓝本」的红线（概念借鉴，不抄代码）。

---

*本提示词依据 `执行文档-前端-web.md`（页面/路由/交互）与 `执行文档-后端-web.md` + `../归档/done/架构落地执行文档.md`（数据模型/状态机/错误码）编写；字段与枚举以这些契约为准。v2 设计参考依据 `../01-立项与调研/调研报告-三个开源项目对比.md`（multica / Plane / Wekan 调研，取数 2026-08-14）。*
