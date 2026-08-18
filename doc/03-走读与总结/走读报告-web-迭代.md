# 走读报告：Web 操作台 + agent 会话编排（本迭代）

> 走读对象：`../02-执行文档/执行文档-后端-web.md`、`../02-执行文档/执行文档-前端-web.md`、`../02-执行文档/x.md`、`../01-立项与调研/讨论-DSH底座.md`（并参照 `../README.md` 索引核对关系）。
> 背景：闸门层 P0–P4 已全绿（94 tests green，A1–A12 通过）并归档至 `../归档/done/`；本迭代为 Web 操作台 + agent 会话编排（S0–S5、验收 A13–A19、ADR-10..14）。
> 本报告全部内容均来自上述文档实际读到的事实与原文要点，未做任何编造。

---

## 1 后端 S0–S5 各阶段目标与验收判据摘要（A13–A19）

后端文档（`执行文档-后端-web.md`）明确：**S0-S5 的每个新端口 Java 签名、REST 路由表、V4 DDL、并发补强、ADR-10..14，都是开工即照做的约束，不是建议**（§0）。

- **总闸**：`POST /api/tickets/{no}/review` → 返回 202 + task_id；SSE 收到 progress + done；结果落 review_result。
- **验收判据矩阵**（§9.5，A13 起接续 A12）：

| 阶段 | 验收 | 判据（原文要点） |
|---|---|---|
| S0 | A13 | 浏览器打开 `https://127.0.0.1:4097/`；无 token 访问 `/api/status` 返回 401；输入 token 后 200；`bind=0.0.0.0` 启动失败退出 22 |
| S1 | A14 | `curl /api/status`、`/api/tickets`、`/api/metrics` 返回结构化 JSON；`POST /api/tickets` 建 clone 并 insert |
| S2 | A15 | `POST /api/tickets/{no}/review` 返回 202 + task_id；SSE 收到 progress + done；结果落 review_result |
| S2 | A16 | `POST /api/tickets/{no}/publish` 同上；权威库 HEAD 前进；A1 等价性断言（权威库只增一个 commit） |
| S3 | A17 | `POST /api/agent-configs` CRUD 通；`AgentSessionPort.start` 建 claude 会话，`cliSessionId` 落库 |
| S4 | A18 | `POST /api/tickets/{no}/sessions/{sid}/messages` 返回 202；SSE 收到 SessionMessage 流；usage 解析正确 |
| S5 | A19 | 会话结束后 `ticket.exec_token_total` 非 null，`exec_token_source='agent_cli'`；`GET /api/metrics/h1` 的 cost ratio 非 NaN |

各阶段交付物摘要（§10）：

- **S0** gate-web 骨架 + 认证：`gate-web` 模块、`WebComponents.fromConfig`、JDK HttpServer 启动、HUMAN token 校验 Filter、Origin/Host 白名单、`/api/health`、`/api/auth/verify`、静态资源 fallback。止损：若 JDK HttpServer 的 SSE 实现成本超 3 天，切换 Javalin。
- **S1** REST 只读 + 同步：`/api/status`、`/api/tickets`（GET/POST，POST 接管 clone）、工单详情、presubmit、review-result、diff、reconcile、metrics、metrics/h1、config、providers、错误码映射。验收 A14。
- **S2** 审核台异步化：`GateTask`/`TaskRegistry`、V4 `gate_task` 表、tasks 查询、SSE、review/publish 异步化、`WebGateEquivalenceTest`。验收 A15、A16。文档强调：**「至此 gate 操作台完整，无 agent 会话已可用」**。
- **S3** AgentConfig CRUD + claude adapter：`AgentSessionPort`、领域类型（`AgentConfig`/`Session`/`SessionMessage`/`SessionUsage`）、V4 三张表、`AgentConfigRepository`、`ClaudeHeadlessAdapter`、工单上下文注入、闸门 MCP 配置生成。验收 A17。止损：若 claude `--resume` 不可靠切 opencode serve 或降级为「每消息独立会话不续接」。
- **S4** 会话界面后端 + 工单绑定：建会话/发消息/SSE/历史/中止 API、`TicketLockManager` 工单级串行、进程生命周期 shutdown hook + reconcile。验收 A18。止损：两 adapter 都不稳则退化「会话只读历史 + 手动 CLI 跑 agent」，gate 操作台（S0-S2）仍交付。
- **S5** 成本回写 + H1 复测：`Session.cumulativeUsage` → `ticket.exec_token_total` 回写、MetricsService 确认 cost ratio 非 NaN、`/api/metrics/h1` 升级 basis。验收 A19。止损：H1 复测仍 degraded 则记录原因、保持 PARTIAL，不强行降级。

**总止损**（§12）：若 S2（闸门操作台异步化）验收不过，**整个本迭代停**——闸门闭环的 Web 化是立项目标，会话编排是增量。

---

## 2 前端文档与 x.md 的关系、暂停/原型优先结论

### 前端文档定位（`执行文档-前端-web.md`）

- 前端是**消费方**，后端是**契约方**：「本文档中所有 API 形态、字段名、枚举值以后端文档为准；若本文档与后端文档冲突，**以后端文档为准**」（§0）。
- 状态为**「暂停开发 —— 先出原型，原型定稿后再实现」**（§0.1，决策变更 2026-08-14）。
- 此前 `gate-web-ui/` 已有 S0 骨架 + S1 首页看板雏形，但**质量未达预期，本迭代前端开发暂停**。

### §0.1 四步原型优先结论

1. **暂停编码**：冻结 `gate-web-ui/` 现状，不再往 S1–S4 堆页面。
2. **先出原型**：以 `x.md` 提示词，用 OpenDesign 生成可点击高保真原型，覆盖 §3 路由表全部页面与 §4 核心交互。
3. **原型定稿后恢复开发**：原型评审通过后作为**视觉与交互的唯一事实来源（design spec）**，按 S0–S4 阶段、§5 状态管理、§6 API 契约重新实现；S0/S1 雏形按原型重构，S2–S4 按原型 + 契约新建。
4. **冲突优先级**：原型与本文档在**字段、状态机、路由、闭环环节**冲突时以本文档为准（本文档以后端契约为准）；原型只在**视觉、布局、组件观感、交互细节**上拥有决定权。

文档同时明确「其余章节（技术栈、路由、页面设计、状态管理、API、阶段划分、ADR、止损）保持不变，作为原型定稿后的实现依据」。

### x.md 与前端文档的关系

- `x.md` 是**前端原型提示词（OpenDesign）**，用法是把**全文**粘贴给 OpenDesign 生成可点击高保真 Web 原型。
- 它是从 `执行文档-前端-web.md`（页面/路由/交互）与后端文档 + 架构文档（数据模型/状态机/错误码）**编写**出来的自包含提示词，文档尾注明「字段与枚举以这些契约为准」。
- 关键差异点：x.md 是「自包含」原型素材，含设计系统设计与令牌（token 层色板）、**反 AI 味清单**、完整数据模型（含前端视角 camelCase 与后端列名对应）、页面清单逐屏规格、两条必做可点击闭环。
- README 索引确认三者顺序：`执行文档-后端-web → 执行文档-前端-web → x.md`，并固化「前端视觉/布局/组件观感/交互细节以原型（x.md）定稿后为准；但字段、状态机、路由、闭环环节仍以前端文档与其上游后端契约为准」。

### 前端阶段与后端阶段的依赖映射（前端 §9，关键）

| 前端阶段 | 依赖的后端阶段 | 说明 |
|---|---|---|
| 前端 S0 | 后端 S0 | 认证 + 静态托管 + `/api/health` |
| 前端 S1 | 后端 S1 | 只读 REST |
| 前端 S2 | 后端 S2 | review/publish 异步任务 + SSE 契约 |
| 前端 S3 | 后端 **S3+S4** | 后端 S3 出 AgentConfig CRUD + adapter，S4 出会话 API；前端 S3 两者都要 |
| 前端 S4 | 后端 **S5** | 成本回写入库后成本面板才有执行侧真数字 |

前端 S3 是唯一「一个前端阶段跨两个后端阶段」的点：可先接后端 S3 的 AgentConfig 管理页，会话页待后端 S4 就绪再联调。

---

## 3 讨论-DSH底座.md 结论与 D3 预留决策内容

### 结论（v2 修订，状态「已倾向：自建 + 预留 DSH 适配，待批复」）

- **否决「gate 插件化进 DSH」**（模式 A/B）。
- **维持自建**（模式 C）：本项目自行创建并管理终端 CLI 子进程，原 S0–S5 计划不变。
- **新增一项预留决策 D3**：`AgentSessionPort` 按既有契约追加 `DshCliAdapter`，把 `dsh` CLI 作为可被 gate 管理的执行 agent 之一适配进来（S4 之后的可选增量）。

### 一句话结论（原文要点）

「DSH 的价值在于：它证明了『会话编排 + 会话 UI + 任务 UI + token 成本投影』这一层可以被一个成熟宿主免费提供；它的 rc.6 形态也证明了这一层迭代极快、耦合极深。但 gate 的定位是**闸门层、CLI 无关**——做成 DSH 插件集等于把『适配任意 agent CLI』的承诺改成『只适配 DSH 生态』，受众、升级节奏、认证、UI 全部被宿主绑架。因此：**自建会话编排（保持 CLI 无关），把 DSH 当作『又一个可被管理的 CLI』来适配**——`dsh --profile headless`（rc.6 已有的一次性任务面）就是 gate adapter 的一个新后端。」

### 否决插件化的四个核心论证（§4.4）

1. **违背定案定位**：README 冻结的定位是「一个 CLI 无关的 git 提交闸门层」；插件化把「适配任意 agent CLI」变成「只适配 DSH 生态」。
2. **自降市场受众**：gate 价值主张是「任何 vibe coding 工作流都能在 git 协议入口被卡住」；做成插件集后受众从「所有用 git 的人」收窄为「DSH 用户」（单用户本地工具靠口碑扩散，受众收窄致命）。
3. **升级节奏与认证被宿主决定**：DSH 明示 breaking changes（rc.6）且无认证层；安全的硬要求不能交给上游的未做项。
4. **DSH 不提供 gate 的咽喉**：审核台/状态机/锁/闸门本体全要自研，插件化省下的只是「非差异点 + 别人的主战场」。

### D3 预留决策内容（§5，S4 后的可选增量）

| # | 项 | 内容（原文要点） |
|---|---|---|
| D3-1 | `AgentCli` 枚举扩展 | 加 `DSH` 值（枚举已是扩展点，按 `AgentConfig.cli` 分发，ADR-12 不变） |
| D3-2 | `DshCliAdapter` 形态 | rc.6 CLI 面是 `dsh --profile headless "task"` 一次性任务：每消息 spawn，stdout 取最后一条 assistant 文本，退出码 0/1；与 claude headless per-message 同构，进程管理最简 |
| D3-3 | usage 读取路径 | headless stdout 无 usage → 从 DSH 会话 JSONL（`~/.dsh`）按会话 id 读取；读不到 → `degraded=true`（沿用 D7 语义，不阻塞）。DSH 未来若提供会话 CLI/导出面再升级为结构化输出 |
| D3-4 | 注入路径 | DSH 是 MCP 客户端 → 其 headless 会话配置里挂 `gate mcp serve`（stdio + `GATE_DOMAIN_TOKEN`），与 claude `--mcp-config` 注入等价。**spike 项**：headless 面是否支持挂 MCP 配置（cordis.yml/preset）需实测 |
| D3-5 | 触发条件 | 用户机器已有 DSH（dogfood 场景）且想用 DSH 模型配置/工具面当执行 agent 时启用；不改变 gate 默认形态 |

D3 全部验收延续既有判据：会话闭环 A17/A18 以 claude adapter 为准，`DshCliAdapter` 只要求「同契约通过 stub 测试」+ 真实 CLI 本地冒烟。

### DSH 的三个可吸收参照点（§3.3）

1. **会话是持久化日志 + 投影，不是内存对象**——印证后端文档 §5.6「历史会话只读」方向，且更彻底（连「打开历史会拉起进程」都是 DSH 已知限制，值得规避）。
2. **usage 走 provider 上报 + 启发式兜底 + degraded 标记**——与 gate 既有 D7 语义同构；DSH「CJK 启发式低估」警告提醒 gate 的 H1 计量必须以 provider usage 为准。
3. **MCP 作为 agent 接入面是成熟路径**——S3 生成 MCP 配置的方向被独立验证。

### 风险与止损（§7）+ 迭代划分（§6）

- S6 为可选增量（`DshCliAdapter` + `AgentCli.DSH` + JSONL usage 读取 + headless MCP 注入 spike），S4 验收后评估进场。
- S6 止损：headless 无法挂 gate MCP（D3-4 spike 失败）→ 降级为「无闸门工具的纯执行 CLI」（agent 不能 presubmit，须人工触发），或整体砍掉 S6。
- R5 总止损：若未来 DSH 稳定到 1.0 且受众论证反转，重新评估模式 A——届时 gate 的 `AgentSessionPort`/REST 契约仍在，插件化是「再加一层 adapter」而非重构。

### 下一步（§8 批复点）

1. 批复 D1（否决插件化）与 D2（维持自建 S0–S5）；
2. 批复后：后端/前端文档从「待审查」转「定案」，按原计划开 S0（gate-web 骨架已有未提交脚手架，直接续做）；
3. D3（DSH 适配）列为 S4 后可选增量，不阻塞主迭代；若想提前验证 D3-4 可并行最小 spike（写挂 `gate mcp serve` 的 cordis.yml，跑 `dsh --profile headless` 看工具是否出现）。

---

## 4 从文档角度判断下一步该做什么

文档明确标注的后端文档状态为「待审查」、前端「暂停开发（原型优先）」、DSH 讨论「待批复」。从文档自身给出的「下一步」看：

1. **后端文档待审查（§13）**：重点审 §4 路由表的闭环环节标注是否齐全、§5 端口签名是否可接受、§7 工单级串行锁是否认可、§11 ADR-10..14 是否拍板。定案后转「定案」。
2. **DSH 讨论待批复（§8）**：批复 D1（否决插件化）、D2（维持自建 S0–S5）；批复后后端/前端从「待审查」转「定案」。
3. **前端当前动作是出原型（§12）**：用 `x.md` 提示词在 OpenDesign 生成高保真原型 → 评审（对照 §1 设计原则逐页过）→ 定稿后恢复开发。
4. **后端开工动作**：批复定案后开 **S0**（gate-web 骨架 + 认证 + 静态资源 + health），与前端 S0 并行；S0 验收（A13）后开 S1；S2 验收（A15/A16）后 gate 操作台完整可用，再决定 S3–S5 是否进场。
5. **文档台账**：本轮定案后更新 `../README.md` 索引（新增本迭代条目）；讨论-DSH底座 v2 定案后归档为「评估记录 + 否决记录」。

**综合判断**：文档层面最重要的先决是**完成「审查/批复」闭环**——三份文档分别卡在「待审查」（后端、前端）与「待批复」（DSH 讨论），且后端与 DSH 的批复互相联动（DSH D1/D2 批复后后端才转定案）。批复落定后，后端立即开 S0，前端并行用 x.md 出原型。S2 是后端硬门槛（总止损：S2 不过则整个迭代停）。

---

## 5 关键契约摘录

### 5.1 `AgentSessionPort` 端口签名（后端 §5.1，原文照录要点）

- 新增于 `gate.ports` 包，写法对齐 `ReviewEngine`：**端口返回结构化 record，不返回 verdict 之外的副作用；流式用 `Stream<SessionMessage>`**。
- 两个 adapter 共享此契约（ADR-12）：`OpenCodeServeAdapter` 与 `ClaudeHeadlessAdapter`，按 `AgentConfig.cli()` 分发。
- 契约镜映 `ReviewEngine`：端口返回结构化记录，happy path 不抛异常；transport/parse 失败以 `role=ERROR` 的 `SessionMessage` + degraded 标记浮出，绝不让异常逃出端口（类比 ReviewEngine 的 EngineFailure value pattern）。

**方法和参数：**

- `Session start(StartRequest)` — 绑定到工单 clone 起会话，返回新会话 id
  - `StartRequest(ticketNo, agentConfigId, clonePath /*ticket.clone_path*/, targetRef, initialPrompt /*工单上下文已注入 clone，这里只是首条用户消息*/, Map<String,String> env /*含 GATE_DOMAIN_TOKEN*/)`
- `String sendMessage(SendRequest)` — 返回异步 task id，进度经 `streamEvents` 流式
  - `SendRequest(sessionId, message, boolean resume /*true=续接既有会话；false 实际上不该发生*/)`
- `void abort(String sessionId)` — 中止 in-flight 消息或拆除会话进程
- `List<SessionMessage> getHistory(String sessionId)` — 只读历史，独立于任何运行中进程
- `Stream<SessionEvent> streamEvents(String sessionId)` — SSE 事件流：回放历史后实时推
  - `SessionEvent(sessionId, SessionMessage message /*非流式聚合消息*/, String kind /*"message"|"usage"|"tool_call"|"done"|"error"*/)`

**领域类型（gate.domain.session）要点：**
- `AgentConfig(id, name, AgentCli cli /*OPENCODE|CLAUDE*/, providerId, model, systemPrompt, extraFlags, description)` — 注解「≈ multica Agent，但无 Squad/Skill/Runtime」
- `Session(id, ticketNo, agentConfigId, AgentCli, SessionStatus /*ACTIVE|ABORTED|CLOSED*/, cliSessionId, clonePath, allocatedPort /*opencode serve 端口；claude 为 -1*/, startedAt, finishedAt, cumulativeUsage)`
- `SessionMessage(id, sessionId, Role /*USER|ASSISTANT|TOOL|ERROR*/, content, List<ToolCall>, usage /*非 LLM 消息为 null*/, boolean degraded, timestamp)`
- `ToolCall(name, argumentsJson, resultJson)`；`SessionUsage(/*OpenAI-style*/ promptTokens, completionTokens, totalTokens)`，含 `add` 逐字段相加、`EMPTY` 常量

**两个 adapter：**
- `OpenCodeServeAdapter`：每工单 clone 起 `opencode serve --port <allocated> --hostname 127.0.0.1`；端口 49152–65535 动态分配（`PortAllocator` CAS）；start 等 `/health` 就绪（超时 `session.start_timeout_seconds` 默认 60s；HTTP 客户端固定 HTTP/1.1，规避 Java HTTP/2 h2c 升级卡 opencode(Bun) 的根因）；失败即杀进程防泄漏；sendMessage 走 `POST /session/:id/message` 或 `prompt_async`+`GET /event` SSE；abort 杀 serve 进程；getHistory 走 `GET /session/:id/message`（会话持久化在 `~/.local/share/opencode` 或项目级存储）。
- `ClaudeHeadlessAdapter`：每消息 `claude -p --input-format stream-json --output-format stream-json --model <m> --resume <session-id> --append-system-prompt-file <工单上下文文件> --mcp-config <闸门 mcp 配置> --strict-mcp-config --permission-mode acceptEdits`；首条无 `--resume`（新建会话拿 session-id 落库），后续 `--resume` 续接。

**决策表（§5.9）要点：** D4 claude 首选、opencode 备选；D5 端口动态区间分配；D6（同 D3）Web 接管 clone；D7 usage 解析失败标 degraded 仍入库（类比 review 侧）。

### 5.2 REST 路由（后端 §4.1 路由总表，含闭环环节标注）

`闭环环节` 取自固定集合 `presubmit / review / publish / reconcile / status / session / auth`。

| Method | Path | 闭环环节 | 说明 |
|---|---|---|---|
| POST | /api/auth/verify | auth | 校验 token，登录 |
| GET | /api/health | — | 存活探针（免 token） |
| GET | /api/status | status | 项目状态总览 |
| GET | /api/tickets | status | 工单列表 |
| GET | /api/tickets/{no} | status | 工单详情 |
| POST | /api/tickets | presubmit | 建工单（接管 clone，D3） |
| POST | /api/tickets/{no}/presubmit | presubmit | 预提审（同步） |
| POST | /api/tickets/{no}/review | review | 审核（异步，202 + 任务 id） |
| POST | /api/tickets/{no}/publish | publish | 发布（异步，202 + 任务 id） |
| GET | /api/tickets/{no}/review-result | review | 驳回回喂（最新 findings） |
| GET | /api/tickets/{no}/presubmit/{round}/diff | presubmit | 取 diff 文本 |
| POST | /api/reconcile | reconcile | 收敛检查 |
| GET | /api/metrics | status | 成本明细导出 |
| GET | /api/metrics/h1 | status | H1 判定 |
| GET | /api/config | status | 配置只读（脱敏，不含 api_key） |
| GET | /api/providers | status | 供应商列表 |
| GET | /api/tasks/{id} | — | 查异步任务状态 |
| GET | /api/tasks/{id}/events | — | 任务进度流（SSE） |
| GET | /api/tickets/{no}/sessions | session | 工单下的会话列表 |
| POST | /api/tickets/{no}/sessions | session | 在工单下建会话（唯一入口，落 ticketNo 绑定） |
| GET | /api/sessions/{sid} | session | 会话详情 |
| POST | /api/sessions/{sid}/messages | session | 发消息（异步，202 + 任务 id） |
| GET | /api/sessions/{sid}/messages | session | 消息列表（历史只读回放，运行中/结束态均可取） |
| GET | /api/sessions/{sid}/events | session | 会话消息流（SSE） |
| POST | /api/sessions/{sid}/abort | session | 中止会话 |
| GET | /api/agent-configs/{id}/sessions | session | 某 AgentConfig 历史会话列表 |
| GET/POST/PUT/DELETE | /api/agent-configs[/{id}] | session | AgentConfig CRUD（PUT 整体替换） |

**会话路由约定**（与前端一致）：会话创建/列表挂在工单下（`/api/tickets/{no}/sessions`）强制 ticketNo 绑定；单会话操作走扁平 `/api/sessions/{sid}/...`（sid 为全局唯一 UUID）；历史回放复用 `GET /api/sessions/{sid}/messages`，不单设 `/history`。

**错误映射**（§4.4，GateErrorCode → HTTP）：OK(0)→200；REJECT_FINDINGS(10)→422；REJECT_TOCTOU(11)→409；REJECT_PRECONDITION(12)→422；REJECT_NEEDS_HUMAN(13)→422；GATE_ERROR_ENGINE(20)→502；GATE_ERROR_IO(21)→503；GATE_ERROR_CONFIG(22)→500；USAGE(64)→400；INTERNAL(70)→500。响应体统一 `{ "error_code", "error", "message", "detail" }`；`GateException` 由 web 层一个 ExceptionHandler 捕获映射；非 GateException 一律 500 + INTERNAL，不泄漏堆栈。

### 5.3 V4 DDL 要点（后端 §6.1 `V4__agent_sessions.sql`）

Flyway V4，新增 `gate-web` 数据层，共四部分：

- **`agent_config`**：`id`(PK), `name`, `cli`(OPENCODE|CLAUDE), `provider_id`(REFERENCES provider(id)), `model`, `system_prompt`, `extra_flags`(JSON 数组), `description`, `created_at`, `updated_at`。
- **`agent_session`**：`id`(PK), `ticket_no`(REFERENCES ticket(ticket_no)), `agent_config_id`(REFERENCES agent_config(id)), `cli`, `status`(ACTIVE|ABORTED|CLOSED), `cli_session_id`, `clone_path`, `allocated_port`(opencode serve 端口；claude NULL), `context_file`(工单上下文文件路径，审计), `prompt_tokens`/`completion_tokens`/`total_tokens`(cumulative usage), `started_at`, `finished_at`；索引 `idx_session_ticket(ticket_no)`、`idx_session_status(status)`。
- **`session_message`**：`id`(PK), `session_id`(REFERENCES agent_session(id)), `role`(USER|ASSISTANT|TOOL|ERROR), `content_blob`(blob store 路径), `content_bytes`, `tool_calls_blob`(JSON, 可空), `prompt_tokens`/`completion_tokens`/`total_tokens`, `degraded`(INT, default 0), `created_at`；索引 `idx_msg_session(session_id, created_at)`。
- **`gate_task`**：`id`(PK), `type`(review|publish|session-send), `ticket_no`, `session_id`, `status`(RUNNING|SUCCEEDED|FAILED), `result_json`, `error_json`, `started_at`, `finished_at`；索引 `idx_task_status(status)`。
- **ticket 表追加** `ALTER TABLE ticket ADD COLUMN agent_config_id TEXT REFERENCES agent_config(id)`（可空，工单创建时指定，默认 NULL 用系统默认 AgentConfig，保持向后兼容）。

**其他 V4 相关决策（§6.2–6.4）要点：**
- **消息落 blob store（推荐）**：`content_blob` 存路径，与 `review_result.findings_blob` 同款 `BlobStore`。理由：会话消息数量级远大于 review_result，长会话可能数百条、每条工具结果可达 KB 级，SQLite 单行不擅长存大文本。
- **迁移幂等性**：全部 `CREATE TABLE`/`ALTER TABLE ADD COLUMN`，Flyway 管理，重复执行由版本表拦截；`extra_flags`/`tool_calls_blob` 存 JSON 字符串，DB 不校验 schema。

### 补充：本迭代其它关键契约要点

- **认证（§3）**：浏览器会话凭据**复用 HUMAN 域 token**，不新增 WEB 域（ADR-10）。启动时若 `human_token_file` 不存在/为空则调用 `issueHumanToken(now)` 生成并写入（0600）；启动日志打印 `GATE_WEB_TOKEN=<plaintext>`。token 可用既有 `gate credential revoke` 撤销。SSE 端点额外接受 `?token=<plaintext>`（EventSource 不支持自定义 header，对齐前端 ADR-F3），普通 `/api/*` 仍只认 header；请求日志对 token query 值脱敏。
- **§3.3 v2 §6.2 硬要求落地**：bind 127.0.0.1 且禁止 0.0.0.0（fail-closed 退出 22）；全 `Filter` 校验 Bearer token（非 HUMAN 域 401）；校验 Host 在 `[web] allowed_origins` 白名单；SSE/WS 握手同样校验 Origin；两套独立凭据（agent 域 token 仍由 `gate mcp issue-token --ticket T` 签发，Web 不接管，D2）。
- **并发锁（§7）**：新增 `TicketLockManager.acquire(String ticketNo)`（同一 ticketNo 的 session sendMessage/presubmit/publish 串行）实现 `FileChannelTicketLockManager`（键=ticketNo，ReentrantLock + FileLock，锁文件 `locksDir/ticket-<ticketNo>.lock`）。锁顺序不变量：工单级锁 → 项目级锁 → FileLock → SQLite 写事务，永不反转。sendMessage 持锁时 presubmit 直接 422 REJECT_PRECONDITION（不排队）；反之 presubmit/publish 持锁时 sendMessage 排队。`PortAllocator` 用 `AtomicInteger` 游标 + `ConcurrentHashMap` 占用表。
- **配置（§8）**：schema_version 1→2；新增 `[web]`(bind/port/allowed_origins/human_token_file)、`[session]`(port_range_min/max/default_cli/default_agent_config)、`[agent]`(default_model/default_provider/context_template) 段；未知键仍 fail-closed 退出 22；旧 schema_version=1 的 gate.toml 启动报错提示升级（不自动迁移）。
- **ADR-10..14（§11）**：ADR-10 复用 HUMAN 域不新增 WEB 域；ADR-11 会话路线 B 结构化接口不做 PTY（闭合 v2 §16 S2 spike）；ADR-12 双 adapter 共用 `AgentSessionPort` 按 `AgentConfig.cli` 分发；ADR-13 Web 常驻进程需在 FileLock 之上加工单级串行锁（`TicketLockManager`）；ADR-14 单进程单项目首期。

---

*附注：本报告仅走读以上四份文档（后端/前端执行文档、x.md、讨论-DSH底座.md，并核对 README 索引），未含未读的立项讨论-v2 正文与归档 done 系列文档的原文细节；涉及此类引用的，均以其在被走读文档中的转述为准。*
