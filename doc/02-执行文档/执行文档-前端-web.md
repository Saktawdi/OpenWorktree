# Gate Web UI — 前端执行文档

> 状态：**暂停开发 —— 先出原型，原型定稿后再实现**（决策见 §0.1，原型提示词见 [`x.md`](x.md)）
> 上游：`../01-立项与调研/立项讨论-v2.md`（§6 闸门设计、§12 竞品检索）、`../归档/done/执行文档.md`（后端闸门层执行文档）
> 平行：后端执行文档 `执行文档-后端-web.md`（契约方，产出中）
> 日期：2026-08-14

---

## 0. 这份文档是什么 / 不是什么

**是**：可以直接照着做的前端执行计划——技术栈决策、工程结构、页面设计、阶段划分（S0-Sn，与后端对齐）、每阶段交付物与验收标准、止损条件、ADR。

**不是**：立项论证（在 `../01-立项与调研/立项讨论-v2.md`）、后端契约定义（在后端执行文档，前端是消费方）、详细到像素的视觉稿。

**与后端执行文档的关系**：后端是**契约方**（REST API 路由、状态机、错误码、SessionMessage 结构、Decision 结构均由后端定义），前端是**消费方**。本文档中所有 API 形态、字段名、枚举值以后端文档为准；若本文档与后端文档冲突，**以后端文档为准**。本文档只声明前端如何消费这些契约、如何组织视图与状态。

**与立项 v2 的关系**：v2 曾判定"闸门层不需要 Web UI"并删除前端章节。经 dogfooding 复盘与 H1（成本假设）复测需求，本轮**重新引入 Web UI**，定位为"闸门操作台 + agent 会话编排可视化"。本文档以新定位为准，与 v2 旧结论冲突处以本文档为准。

### 0.1 决策变更（2026-08-14）：暂停开发，先出原型

**当前前端实现（`gate-web-ui/`，S0 骨架 + S1 首页看板雏形）质量未达预期，本迭代前端开发暂停。** 后续按"原型先行"推进：

1. **暂停编码**：冻结 `gate-web-ui/` 现状，不再往 S1–S4 堆页面。
2. **先出原型**：以 [`x.md`](x.md) 中的提示词，用 OpenDesign 生成一份可点击的高保真原型，覆盖 §3 路由表全部页面与 §4 核心交互。
3. **原型定稿后恢复开发**：原型评审通过后，将其作为**视觉与交互的唯一事实来源（design spec）**，再按本文档 S0–S4 阶段、§5 状态管理、§6 API 契约重新实现。S0/S1 已有雏形按原型重构，S2–S4 按原型 + 本文档契约新建。
4. **冲突优先级**：原型与本文档在**字段、状态机、路由、闭环环节**上冲突时以本文档为准（本文档以后端契约为准）；原型只在**视觉、布局、组件观感、交互细节**上拥有决定权。

> 本文档其余章节（技术栈、路由、页面设计、状态管理、API、阶段划分、ADR、止损）保持不变，作为原型定稿后的实现依据。

---

## 1. 定位与设计原则

### 1.1 一句话

**gate 的 Web 操作台**：让闸门闭环（status → presubmit → review → publish → reconcile）可视化可操作，并补齐 agent 会话编排（session）与成本度量（metrics）的可视化入口。

### 1.2 设计原则

| 编号 | 原则 | 含义 | 反模式 |
|---|---|---|---|
| P1 | **闸门优先** | 审核台是重心，会话 commoditized；视觉与交互投入按此权重分配 | 把会话页做成主英雄页、审核台做成弹窗 |
| P2 | **状态机驱动** | 看板列直接映射 `TicketStage`，一列不多加、一列不少 | 自创"待评估""已挂起"等业务态列 |
| P3 | **tree_hash 可见** | 审核台顶部显式展示 tree_hash + base_commit，让人确认"审的就是要提交的" | 把 hash 藏在折叠面板里 |
| P4 | **成本可见** | 每工单显示 exec/review token；成本面板是 H1 复测入口 | 只在汇总页报总数，工单级看不到 |
| P5 | **防蠕变** | 每个页面/组件必须标注服务闸门闭环哪一环；答不上的不立项 | 借口"以后可能用得上"加无关页面 |

### 1.3 明确不做（本阶段）

- inbox / 通知中心
- autopilot（自动跑完闭环）
- squad / 多 agent 协同视图
- skill / prompt 市场
- 真终端 PTY（会话走路线 B 结构化接口，不做 xterm.js）
- 多用户 / 权限体系（单用户 Bearer token，复用后端 CredentialRepository）
- 移动端适配（开发者自用工具，桌面优先）
- 自研审核引擎 / prompt 编辑（审核走后端，前端只消费 Decision）

---

## 2. 技术栈与工程结构

### 2.1 技术栈决策

| 项 | 选择 | 备选 | 理由 |
|---|---|---|---|
| 框架 | Vue 3 + `<script setup>` | React/Svelte | 已定案（立项决策 1） |
| 语言 | TypeScript（strict） | JS | 状态机/契约多，类型对齐后端 record |
| 构建 | Vite | Webpack/Rsbuild | 已定案；dev proxy 简单 |
| UI 库 | Naive UI | Element Plus / 纯手写 | 见 ADR-F1：轻量、TS 友好、暗色优先 |
| 路由 | Vue Router 4 | - | SPA 标准 |
| 状态 | Pinia | - | Vue 3 官方推荐 |
| HTTP | axios + EventSource | fetch | 拦截器生态成熟；SSE 见 ADR-F3 |
| diff 渲染 | Monaco Editor（diff editor） | CodeMirror | 见 ADR-F2：tree_hash 视觉锚定一致性 |
| 测试 | Vitest + Vue Test Utils + Playwright(可选) | Jest/Cypress | Vite 原生 |

### 2.2 目录结构

```
gate-web-ui/                         ← 前端工程根（与 gate-web 后端模块同级或内嵌）
├── index.html
├── vite.config.ts                   ← dev proxy: /api → 127.0.0.1:后端端口
├── tsconfig.json
├── package.json
├── src/
│   ├── main.ts
│   ├── App.vue
│   ├── router/
│   │   └── index.ts                 ← 路由表 + 守卫（401 跳登录）
│   ├── views/                       ← 页面级组件
│   │   ├── LoginView.vue            [auth]
│   │   ├── ProjectBoardView.vue     [status]
│   │   ├── TicketKanbanView.vue     [status]
│   │   ├── TicketDetailView.vue     [presubmit+review]
│   │   ├── ReviewConsoleView.vue    [review]   ← 核心
│   │   ├── SessionView.vue          [session]
│   │   ├── AgentConfigView.vue      [session]
│   │   └── CostPanelView.vue        [metrics]
│   ├── components/                  ← 可复用组件（每组件标闭环环节）
│   │   ├── layout/
│   │   │   ├── AppLayout.vue
│   │   │   └── ProjectNav.vue       [status]
│   │   ├── ticket/
│   │   │   ├── TicketCard.vue       [status]
│   │   │   ├── StageFlowDiagram.vue [status]
│   │   │   └── TicketCreateForm.vue [presubmit]
│   │   ├── review/
│   │   │   ├── DiffViewer.vue       [presubmit]
│   │   │   ├── FindingsList.vue     [review]
│   │   │   ├── FindingItem.vue      [review]
│   │   │   ├── VerdictBadge.vue     [review]
│   │   │   └── TreeHashBar.vue      [presubmit]
│   │   ├── session/
│   │   │   ├── SessionList.vue      [session]
│   │   │   ├── ChatMessage.vue     [session]
│   │   │   ├── MessageInput.vue    [session]
│   │   │   └── UsageBadge.vue       [metrics]
│   │   ├── agent/
│   │   │   ├── AgentConfigForm.vue  [session]
│   │   │   └── ModelSelect.vue      [session]
│   │   └── common/
│   │       ├── TaskProgress.vue     [review+publish]
│   │       └── ErrorBoundary.vue
│   ├── stores/                      ← Pinia
│   │   ├── authStore.ts
│   │   ├── projectStore.ts          [status]
│   │   ├── ticketStore.ts           [status+presubmit+review+publish]
│   │   ├── sessionStore.ts          [session]
│   │   ├── taskStore.ts             [review+publish]
│   │   ├── agentConfigStore.ts      [session]
│   │   └── metricsStore.ts          [metrics]
│   ├── api/                         ← HTTP 客户端
│   │   ├── client.ts                ← axios 实例 + 拦截器
│   │   ├── sse.ts                   ← useSSE 封装
│   │   ├── status.ts
│   │   ├── tickets.ts
│   │   ├── review.ts
│   │   ├── publish.ts
│   │   ├── sessions.ts
│   │   ├── agentConfigs.ts
│   │   ├── metrics.ts
│   │   └── tasks.ts
│   ├── types/                       ← 与后端 record 对齐
│   │   ├── ticket.ts
│   │   ├── stage.ts
│   │   ├── decision.ts
│   │   ├── session.ts
│   │   ├── agentConfig.ts
│   │   ├── metrics.ts
│   │   ├── task.ts
│   │   └── errors.ts
│   ├── composables/
│   │   ├── useSSE.ts
│   │   ├── useTask.ts               ← 异步任务状态机
│   │   ├── useTokenCost.ts          [metrics]
│   │   └── useStageFlow.ts          [status]
│   └── utils/
│       ├── errorCodeMap.ts          ← GateErrorCode → 可读消息
│       └── format.ts
└── tests/
    ├── unit/
    └── e2e/
```

### 2.3 构建产物与后端托管

- 构建命令：`pnpm build` → 产物输出到 `gate-web/src/main/resources/static/`。
- 后端 gate-web 模块托管静态资源（SPA fallback 到 `index.html`）。
- dev 模式：`pnpm dev` 启 Vite dev server，`vite.config.ts` 配 proxy 把 `/api` 与 `/api/tasks/*/events` 转发到后端 `127.0.0.1:<port>`。

### 2.4 类型对齐机制

| 方案 | 取舍 |
|---|---|
| A. OpenAPI 代码生成（后端若提供 `/openapi.json`） | 强一致；依赖后端产出 schema |
| B. 手写 types + 契约测试 | 灵活；需维护对齐 |

**初版取 B（手写 types）**：后端 gate-web 是轻量 Javalin 路由，未必出 OpenAPI。手写 types 文件集中放 `src/types/`，与后端 record 字段一一对应；P1 起若后端补出 OpenAPI 再切 A。types 文件头注释标注"对齐后端 `XxxRecord`，以后端为准"。

---

## 3. 页面结构与路由

### 3.1 路由表

| Path | 组件 | 闭环环节 | 说明 |
|---|---|---|---|
| `/login` | LoginView | auth | Bearer token 输入 |
| `/` | ProjectBoardView | [status] | 项目看板列表 |
| `/projects/:id/tickets` | TicketKanbanView | [status] | 工单看板（按 TicketStage 分列） |
| `/projects/:id/tickets/:no` | TicketDetailView | [presubmit+review] | 工单详情（tab：概览/diff/审核/会话/历史） |
| `/projects/:id/tickets/:no/review` | ReviewConsoleView | [review] | **核心页面**：审核台 |
| `/projects/:id/tickets/:no/session` | SessionView | [session] | agent 会话 chat 视图 |
| `/projects/:id/agents` | AgentConfigView | [session] | AgentConfig CRUD |
| `/projects/:id/cost` | CostPanelView | [metrics] | 成本面板（H1） |

### 3.2 布局

- `AppLayout`：左侧 `ProjectNav`（项目列表 + 当前项目下的工单/审核台/会话/成本入口），右侧 `<router-view>` 主内容区。
- 会话页 `SessionView` 与审核台 `ReviewConsoleView` 可全屏收起左侧导航（信息密度优先）。
- 顶部常驻：当前项目名、当前工单号、token 累计小徽章。

---

## 4. 核心页面详细设计

### 4.1 项目看板列表 `/`  [闭环环节: status]

- 项目卡片网格：每卡显示项目名、权威库路径 tip、auth 库状态、工单总数、当前进行中工单数。
- 点击卡片 → `/projects/:id/tickets`。
- 顶部右上：登录态指示（当前 token 摘要、退出）。
- 空态：无项目时引导在 CLI 侧 `gate init`。

### 4.2 工单看板 `/projects/:id/tickets`  [闭环环节: status]

- Kanban 列 = `TicketStage`，一列不多：

| 列 | Stage | 备注 |
|---|---|---|
| 待开始 | PENDING | 新建工单落此列 |
| 进行中 | IN_PROGRESS | 含 REJECTED 回流（标红 + review_round badge） |
| 已预提审 | PRESUBMITTED | tree_hash 已固化 |
| 审核中 | IN_REVIEW | 异步审核进行中显示 spinner |
| 可发布 | READY_TO_PUBLISH | verdict=pass |
| 待人工 | NEEDS_HUMAN | verdict=requires_human |
| 已完成 | DONE | 折叠为窄列 |
| 已取消 | CANCELLED | 折叠为窄列 |

- REJECTED 不单独成列，回流到"进行中"列并标红，卡片右上角 `review_round: n` 徽章。
- 工单卡片：`ticketNo / title / review_round / token 缩略（exec·review）`。
- 顶部「新建工单」按钮 → `TicketCreateForm` 抽屉：字段 `title / description / targetRef / agentConfigId` → `POST /api/tickets`。
- 卡片点击 → 工单详情。

### 4.3 工单详情 `/projects/:id/tickets/:no`  [闭环环节: presubmit+review]

- Tab 式布局：概览 / diff / 审核 / 会话 / 历史。
- **概览**：
  - `StageFlowDiagram`：状态机当前位置高亮（PENDING→IN_PROGRESS→PRESUBMITTED→IN_REVIEW→{REJECTED|READY_TO_PUBLISH|NEEDS_HUMAN}→DONE|CANCELLED）。
  - tree_hash + base_commit + target_branch。
  - token 累计（exec / review / total）。
  - review_round 计数。
- **diff**：调 `GET /api/tickets/{no}/presubmit/{round}/diff`，Monaco diff editor 渲染（原 base_commit vs 现 tree）。
- **审核**：进审核台 `/review`（或内嵌审核台精简版）。
- **会话**：进会话页 `/session`。
- **历史**：该工单的 presubmit/review/publish 任务记录时间线（复用 `agtx` 思路：Task=执行记录 + 结果回写）。

### 4.4 审核台 `/projects/:id/tickets/:no/review`  [闭环环节: review] — 核心页面

**ASCII 线框图：**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  Ticket-7  IN_REVIEW   round=2                              [全屏] [返回详情] │
├─────────────────────────────────────────────────────────────────────────────┤
│  tree_hash: a1b2c3d4...e5f6     base_commit: f0e1d2c3...     target: main   │  ← TreeHashBar (P3)
├──────────────────────────────────────┬──────────────────────────────────────┤
│                                      │  Findings                          │
│  Monaco Diff Editor (inline/双栏)    │  ── blocker (2) ──────────────────  │
│                                      │  ▸ src/Main.java:42-58              │
│  @@ -40,7 +40,12 @@                   │     资源未关闭                    │
│  - old line                          │     建议: try-with-resources       │
│  + new line   ← 点击 finding 跳转    │  ── warning (1) ──────────────────  │
│                                      │  ▸ src/Util.java:10                │
│                                      │     命名不规范                    │
│                                      │  ── nit (0) ─────────────────────  │
├──────────────────────────────────────┴──────────────────────────────────────┤
│  Verdict: [pass/reject/requires_human]   reason: ...                       │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────────┐   │
│  │ 一键 LLM 审核 │ │ 人工通过     │ │ 人工驳回      │ │ 驳回并回喂 agent │   │
│  └──────────────┘ └──────────────┘ └──────────────┘ └──────────────────┘   │
│  [发布]  ← READY_TO_PUBLISH 时激活                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│  TaskProgress: 审核 SSE 进度 (running 35% ...) / 发布 SSE 进度              │
└─────────────────────────────────────────────────────────────────────────────┘
```

- **左侧 `DiffViewer`**：Monaco diff editor，inline/双栏切换；顶部 `TreeHashBar` 显式展示 tree_hash + base_commit + target（P3，让人确认"审的就是要提交的"）。
- **右侧 `FindingsList`**：按 severity 分组（blocker 红 / warning 黄 / nit 灰），每条 `FindingItem` 显示 `file : lineStart-lineEnd` + `message` + `suggestion`；点击 → Monaco 跳到对应行。
- **操作区**：
  - 「一键 LLM 审核」→ `POST /api/tickets/{no}/review`（202 + taskId）→ SSE 跟进度。
  - 「人工通过」/「人工驳回」→ 直接写 verdict（前端构造 Decision，若后端提供人工裁决端点则调用）。
  - 「驳回并回喂」→ findings 原样回喂给 agent，调后端回喂接口，agent 在会话页收到。
  - 「发布」→ `POST /api/tickets/{no}/publish`（202 + taskId）→ SSE 跟进度；仅 READY_TO_PUBLISH 激活。
- **`VerdictBadge`**：pass 绿 / reject 红 / requires_human 橙。
- **`TaskProgress`**：审核台与发布台共享异步任务状态机（§5）。

### 4.5 agent 会话页 `/projects/:id/tickets/:no/session`  [闭环环节: session]

**ASCII 线框图：**

```
┌─────────────────────┬──────────────────────────────────────────────────────┐
│ 会话列表            │  Chat 视图                            [历史/实时 切换]│
│ ── 当前工单 ─────── │  ┌────────────────────────────────────────────────┐  │
│ ▸ sess-3 (实时)    │  │ [user]      把审核 finding 修一下                │  │
│ ▸ sess-2 (只读)    │  │ [assistant] 好的，开始处理 src/Main.java...     │  │
│ ── 该 AgentConfig  │  │   tool_call: edit_file(src/Main.java, ...)      │  │
│   历史会话 ──────── │  │   usage: prompt=1200 completion=450 total=1650 │  │
│ ▸ sess-old-9       │  │ [user] 继续验证                                 │  │
│ ▸ sess-old-8       │  │ [assistant] ▌流式逐 token 输出中...             │  │
│                     │  └────────────────────────────────────────────────┘  │
│ [+ 新建会话]        │  AgentConfig: [opencode-claude ▼]                   │
│ 选配置 → 建会话     │  ┌──────────────────────────────────┐  ┌─────────┐   │
│                     │  │ 输入消息...                       │  │ 中止    │   │
│                     │  └──────────────────────────────────┘  │ 发送    │   │
│                     │                                          └─────────┘   │
└─────────────────────┴──────────────────────────────────────────────────────┘
```

- **左侧 `SessionList`**：当前工单的会话（实时 + 只读历史）+ 该 AgentConfig 的历史会话；切换只读查看历史。
- **右侧 chat 视图**：
  - `ChatMessage` 卡片区分 role：user / assistant / tool_call（折叠展示）/ usage（`UsageBadge`：prompt/completion/total）。
  - 流式渲染：SSE 逐 token 出，光标动画。
  - `MessageInput`：发消息 → `POST /api/sessions/{id}/messages`。
  - 中止按钮 → `POST /api/sessions/{id}/abort`。
- **新建会话**：选 AgentConfig 下拉 → `POST /api/tickets/{no}/sessions` 建会话，会话绑定工单。
- **历史会话只读模式**：无输入框，纯回放 `GET /api/sessions/{id}/messages`。
- **usage 累计**：每条消息显示 token，工单成本面板同步累计（P4，补齐执行侧度量，服务 H1）。

### 4.6 AgentConfig 管理 `/projects/:id/agents`  [闭环环节: session]

- 列表 + 表单 `AgentConfigForm`：字段 `name / cli(opencode|claude) / providerId / model / systemPrompt / extraFlags`。
- `ModelSelect`：从 `GET /api/providers` 拉可用模型，保存前校验模型存在。
- CRUD：`GET/POST/PUT/DELETE /api/agent-configs`。
- 行内操作：复制配置、查看该配置历史会话（`GET /api/agent-configs/{id}/sessions`）。

### 4.7 成本面板 `/projects/:id/cost`  [闭环环节: metrics]

- 按工单/项目聚合 exec vs review token。
- H1 判定展示：`first_pass_rate`、`cost_ratio_median`、`classification`（PASS/PARTIAL/FAIL）。
- 会话 SessionMessage.usage 累计补齐执行侧度量（背景：H1 degraded basis 含"执行侧 token 不可用"，会话界面是补齐入口）。
- 导出按钮：CSV/JSON 导出聚合数据。

---

## 5. 状态管理与数据流

### 5.1 Pinia stores

| Store | 闭环环节 | 职责 |
|---|---|---|
| `authStore` | auth | token 持久化（localStorage）、登录态 |
| `projectStore` | status | 项目列表、当前项目 |
| `ticketStore` | status+presubmit+review+publish | 工单列表/详情、看板分组、状态机流转 |
| `sessionStore` | session | 会话列表、当前会话、消息流 |
| `taskStore` | review+publish | 异步任务状态机（全局，审核台与发布台共享） |
| `agentConfigStore` | session | AgentConfig 列表/CRUD |
| `metricsStore` | metrics | 成本聚合、H1 判定 |

### 5.2 SSE 统一封装

`useSSE` composable（`src/composables/useSSE.ts`）：
- 封装 `EventSource`，统一处理重连（指数退避）、token 注入（query param，见 ADR-F3）、事件解析（按后端 SSE 事件名分发）。
- 用法：审核进度、发布进度、会话流式响应三处复用。
- 销毁时自动 `close()`，防泄漏。

### 5.3 异步任务状态机

`taskStore` 维护全局任务状态机：`idle / running / succeeded / failed`，key 为 taskId。审核台与发布台共享同一组件 `TaskProgress`。

```
idle ──POST review/publish──▶ running ──SSE done──▶ succeeded
                                  │                    │
                                  └──SSE error──▶ failed
```

### 5.4 错误处理

- `utils/errorCodeMap.ts`：`GateErrorCode` → 用户可读中文消息。
- axios 响应拦截器统一捕获，按 code 映射后经 Naive UI message 弹出。
- 401/403 → 清 authStore、跳 `/login`。

---

## 6. API 客户端层

### 6.1 HTTP 客户端

- `src/api/client.ts`：axios 实例，`baseURL = /api`。
- 请求拦截器：从 localStorage 读 token，注入 `Authorization: Bearer <token>`。
- 响应拦截器：401/403 → 跳登录；其余按 GateErrorCode 映射。

### 6.2 SSE 与 token 传递

| 方案 | 取舍 |
|---|---|
| A. token 走 query param（`?token=...`） | EventSource 原生支持；URL 暴露 token，需 HTTPS/本地环境；**后端需配合校验** |
| B. token 走 cookie | EventSource 自动带；需后端配 cookie + CSRF 处理 |
| C. 用 fetch + ReadableStream 模拟 SSE | 支持自定义 header；需手写解析，复杂度高 |

**选 A**（ADR-F3）：gate-web 是 127.0.0.1 本地绑定，URL 暴露风险可控；后端需接受 query param token 并校验。`useSSE` 内拼 URL 时附加 `?token=`。

### 6.3 关键类型（与后端 record 对齐）

```ts
// src/types/stage.ts
type TicketStage =
  | 'PENDING' | 'IN_PROGRESS' | 'PRESUBMITTED' | 'IN_REVIEW'
  | 'REJECTED' | 'READY_TO_PUBLISH' | 'NEEDS_HUMAN'
  | 'DONE' | 'CANCELLED';

// src/types/ticket.ts
interface Ticket {
  no: string; title: string; stage: TicketStage;
  reviewRound: number; targetRef: string;
  treeHash?: string; baseCommit?: string;
  execTokens?: number; reviewTokens?: number;
  agentConfigId?: string;
}

// src/types/decision.ts
type Severity = 'blocker' | 'warning' | 'nit';
type Verdict = 'pass' | 'reject' | 'requires_human';
interface Finding {
  severity: Severity; file: string;
  lineStart: number; lineEnd: number;
  message: string; suggestion: string;
}
interface Decision {
  verdict: Verdict; reason: string; detail: string[];
  findings: Finding[]; authorization?: string;
}

// src/types/session.ts
interface SessionMessage {
  role: 'user' | 'assistant';
  content: string;
  toolCalls: ToolCall[];
  usage: { prompt: number; completion: number; total: number };
  timestamp: string;
}

// src/types/task.ts
type TaskStatus = 'idle' | 'running' | 'succeeded' | 'failed';
interface Task { id: string; status: TaskStatus; progress?: number; error?: string; }
```

> 以上字段以后端 record 为准，前端 types 文件头标注"对齐后端 XxxRecord"。

---

## 7. 组件库与视觉

### 7.1 UI 库选型决策表

| 库 | 体积 | TS | 暗色 | 组件丰富度 | 取舍 |
|---|---|---|---|---|---|
| Naive UI | 中 | 原生 TS | 优先 | 全 | **选**（ADR-F1） |
| Element Plus | 大 | 适配 | 支持 | 全 | 重；样式偏 toC |
| 纯手写 | - | - | - | - | 成本高，偏离闸门优先 |

### 7.2 diff 渲染

- Monaco diff editor（`DiffViewer`），inline/双栏切换。
- 锚定 tree_hash 视觉一致性（ADR-F2）：顶部 `TreeHashBar` 与 Monaco 顶部对齐，确认审的是要提交的。

### 7.3 设计基调

- 工具感、信息密度高、不花哨（开发者自用，非 toC）。
- **深色模式优先**（ADR-F4）：Naive UI `darkTheme` 为默认，提供切换。
- 主色克制（gate 闸门语义：通过绿/驳回红/待人工橙），不堆装饰。
- **视觉与布局的最终定义以原型（[`x.md`](x.md)）为准**；本节基调与 §1 设计原则是原型生成时的约束输入。

---

## 8. 测试策略

### 8.1 组件测试（Vitest + Vue Test Utils）

| 测试重点 | 闭环环节 | 断言 |
|---|---|---|
| 审核台 findings 渲染与跳转 | review | severity 分组正确；点击 finding 跳 Monaco 行 |
| 状态机看板列映射 | status | TicketStage → Kanban 列一对一；REJECTED 回流 IN_PROGRESS 标红 |
| 会话消息流式渲染 | session | SSE token 逐条拼接；usage badge 累计 |
| 异步任务状态机 | review+publish | running/succeeded/failed 切换 |
| 错误码映射 | 全 | GateErrorCode → 可读消息 |

### 8.2 E2E（可选，Playwright）

- happy path：登录 → 建工单 → presubmit → review pass → publish。
- 真终端 vs 结构化：本路线不做 PTY，E2E 只验结构化 chat 流。

### 8.3 视觉验证

- 关键页面（审核台、会话页、成本面板）截图对比，防暗色/布局回归。

### 8.4 验收标准（与后端 S0-Sn 对齐）

见 §9 各阶段验收。

---

## 9. 阶段划分（S0-Sn，与后端对齐）

> **跨轨依赖映射**（前端阶段依赖后端阶段，避免两轨脱节）：
>
> | 前端阶段 | 依赖的后端阶段 | 说明 |
> |---|---|---|
> | 前端 S0 | 后端 S0 | 认证 + 静态托管 + `/api/health` |
> | 前端 S1 | 后端 S1 | 只读 REST（status/tickets/diff/metrics） |
> | 前端 S2 | 后端 S2 | review/publish 异步任务 + SSE 契约 |
> | 前端 S3 | 后端 **S3 + S4** | 后端 S3 出 AgentConfig CRUD + adapter，S4 出会话建/发/流/历史 API；前端 S3 两者都要，故须等后端 S4 完成 |
> | 前端 S4 | 后端 **S5** | 成本回写（SessionMessage.usage → ticket.exec_token_total）落库后，前端成本面板才有执行侧真数字 |
>
> 前端 S3 是唯一"一个前端阶段跨两个后端阶段"的点：可先接后端 S3 的 AgentConfig 管理页，会话页待后端 S4 就绪再联调。

### S0：工程骨架 + 路由 + 登录 + API 客户端 + SSE 封装

- **交付物**：Vite 工程可起、路由表、`LoginView`、`api/client.ts`、`useSSE`、`authStore`、Naive UI 接入、深色默认。
- **验收**：
  - 能登录（输入 token 存 localStorage）。
  - 401 自动跳 `/login`。
  - `useSSE` 能连后端任一 SSE 端点并解析事件。
- **止损条件**：若 SSE 在本地 proxy 下连不通且 1 天内查不出，退化为 §11 止损方案（轮询），不阻塞 S1。

### S1：项目看板列表 + 工单看板 + 工单详情（只读）

- **交付物**：`ProjectBoardView`、`TicketKanbanView`、`TicketDetailView`（概览 + diff + 历史只读）、`StageFlowDiagram`、`TicketCard`、`projectStore`/`ticketStore`。
- **验收**：后端 S1 的只读数据（status/tickets/diff）能正确渲染；看板列与 TicketStage 一一对应。
- **止损条件**：若 diff API 未就绪，diff tab 显示占位，不阻塞看板与详情概览。

### S2：审核台（diff + findings + 异步审核/发布 SSE + 回喂）

- **交付物**：`ReviewConsoleView`、`DiffViewer`（Monaco）、`FindingsList`/`FindingItem`、`VerdictBadge`、`TreeHashBar`、`TaskProgress`、`taskStore`、review/publish API。
- **验收**：**gate 操作台完整可用，无 agent 会话也能走完闭环**——建工单 → presubmit → 一键审核 → pass → 发布。
- **止损条件**：若 Monaco 体积/加载拖慢审核台，改懒加载 + fallback 到纯文本 diff；不阻塞核心流程。

### S3：AgentConfig 管理 + 会话页

- **交付物**：`AgentConfigView`、`AgentConfigForm`、`ModelSelect`、`SessionView`、`SessionList`、`ChatMessage`、`MessageInput`、`UsageBadge`、`sessionStore`、`agentConfigStore`、sessions SSE 流式。
- **验收**：能建 AgentConfig；能建会话发消息流式渲染；能切历史会话只读回放；usage 累计正确。
- **止损条件**：若流式渲染不稳，退化为"发送后轮询历史"（牺牲实时性保可用，见 §11）。

### S4：成本面板 + H1 展示

- **交付物**：`CostPanelView`、`metricsStore`、聚合 exec vs review token、H1 判定展示、导出。
- **验收**：H1 可视化（first_pass_rate / cost_ratio_median / classification）；会话 usage 累计补齐执行侧度量。
- **止损条件**：若后端 metrics 端点未就绪，前端用已缓存的 SessionMessage.usage 本地聚合兜底展示。

---

## 10. ADR

### ADR-F1：UI 库选用 Naive UI

- **背景**：需轻量、TS 友好、暗色优先、组件覆盖全。
- **决策**：选 Naive UI。
- **理由**：原生 TS、暗色主题成熟、按需引入体积可控、不绑死设计语言。
- **后果**：学习曲线低；放弃 Element Plus 的生态广度。

### ADR-F2：diff 用 Monaco Editor

- **背景**：审核台是重心，diff 渲染需与 tree_hash 视觉锚定一致。
- **决策**：用 Monaco diff editor，顶部 `TreeHashBar` 与之对齐。
- **理由**：Monaco diff 行级定位可靠，支持 finding 跳转；与开发者编辑器体验一致。
- **后果**：体积大；通过懒加载 + fallback 缓解（S2 止损）。

### ADR-F3：SSE token 走 query param

- **背景**：`EventSource` 不支持自定义 header；SSE 需鉴权。
- **决策**：token 以 `?token=` 附在 SSE URL 上。
- **理由**：gate-web 127.0.0.1 本地绑定，URL 暴露风险可控；实现最简。
- **后果**：后端需接受 query param token 并校验；token 进 URL 日志需注意脱敏。

### ADR-F4：深色模式优先

- **背景**：开发者自用工具，长时间盯屏。
- **决策**：Naive UI `darkTheme` 为默认，提供浅色切换。
- **理由**：贴合开发者偏好，降视觉疲劳。
- **后果**：需保证 diff/findings 在暗色下对比度。

### ADR-F5：会话界面可读可交互（路线 B），不做真终端

- **背景**：会话路线 B（结构化接口）vs PTY 终端。
- **决策**：会话页是可读可交互的 chat 视图（消息卡片 + 流式 + 输入 + 中止），不做 xterm.js PTY。
- **理由**：已定案（决策 4）；结构化接口便于 usage 埋点与服务 H1。
- **后果**：不支持任意 shell 交互；agent 的 tool_call 折叠展示而非原始终端流。

---

## 11. 风险与止损

| 风险 | 影响 | 止损 |
|---|---|---|
| Monaco 体积拖慢审核台 | S2 审核台加载慢 | 懒加载 + 纯文本 diff fallback |
| SSE 在某些本地代理环境断连 | 审核台/发布台进度丢 | `useSSE` 指数退避重连；仍不稳则降级为轮询 `GET /api/tasks/{id}` |
| 会话流式渲染性能（长会话卡顿） | S3 会话页掉帧 | 虚拟滚动（vue-virtual-scroller）+ 消息分页 |
| 后端契约变更 | types 漂移 | types 文件头标注对齐来源；后端变更需同步通知前端改 types；P1 起切 OpenAPI 生成 |
| 后端 metrics 未就绪 | S4 成本面板空 | 用 SessionMessage.usage 本地聚合兜底 |
| SSE token 走 URL 的安全隐患 | 本地日志泄露 token | 127.0.0.1 绑定 + 日志脱敏；不外网暴露 |
| 会话流式做不稳 | S3 阻塞 | 退化为"发送后轮询历史"（`GET /api/sessions/{id}/messages` 轮询），牺牲实时性保可用 |

---

## 12. 下一步

1. **（当前）出原型**：用 [`x.md`](x.md) 的提示词在 OpenDesign 生成高保真原型，覆盖全部页面与核心交互。
2. **评审原型**：对照 §1 设计原则（闸门优先 / 状态机驱动 / tree_hash 可见 / 成本可见 / 防蠕变）逐页过一遍。
3. **原型定稿后恢复开发**：按 §9 的 S0→S4 顺序实现；S0/S1 已有雏形按原型重构，S2–S4 按原型 + 本文档契约新建。每阶段对照止损条件验收。
4. 审本文档时重点核对与后端执行文档的 S0-Sn 编号与契约是否一致。

---

> 附：防蠕变检查清单（每个新页面/组件立项前过一遍）
> - [ ] 服务闸门闭环哪一环？（status/presubmit/review/publish/reconcile/metrics/session）
> - [ ] 状态机是否映射 TicketStage？是否多加了非状态机列？
> - [ ] tree_hash 是否可见（涉及 review/presubmit 的组件）？
> - [ ] token 成本是否可见（涉及会话/审核的组件）？
> - [ ] 答不上以上任一 → 不立项。
