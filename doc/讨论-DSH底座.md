# 讨论：DeepSeek Harness 能否提炼作为本项目的底座（v2）

> 状态：**讨论稿 v2（已倾向：自建 + 预留 DSH 适配，待批复）**
> 日期：2026-08-14（v1 同日本文；v2 按批复意见修订结论）
> 上游：`执行文档-后端-web.md`（S0–S5、ADR-10..14）、`立项讨论-v2.md`（§6.2 安全硬要求、§12 竞品定位、§13.1 M1 范围）、`归档/done/*`（P0–P4 定案）
> 平行：`执行文档-前端-web.md`
> 事实来源：GitHub `deepseek-ai/deepseek-harness` README（2026-08）+ 本机 npx 缓存内 `@deepseek-ai/dsh@0.1.0-rc.6` 全部包源码（本会话即运行于该版本）
>
> **结论先行（v2 修订）**：**否决「gate 插件化进 DSH」**——插件化使 gate 与 DSH 生态、升级节奏、UI 形态强绑定，自降市场受众，且直接违背 v2 定案的「**CLI 无关**的 git 提交闸门层」定位（见 §4.4）。**维持自建**：本项目自行创建并管理终端 CLI 子进程（原 S0–S5 计划不变）。**新增一项预留决策**：`AgentSessionPort` 按既有契约追加 `DshCliAdapter`，把 `dsh` CLI 作为可被 gate 管理的执行 agent 之一适配进来（S4 之后的可选增量，见 §5 D3）。

---

## 0. 一句话结论

> DSH 的价值在于：它证明了「会话编排 + 会话 UI + 任务 UI + token 成本投影」这一层可以被一个成熟宿主免费提供；它的 rc.6 形态也证明了这一层迭代极快、耦合极深。
> 但 gate 的定位是**闸门层、CLI 无关**——做成 DSH 插件集等于把「适配任意 agent CLI」的承诺改成「只适配 DSH 生态」，受众、升级节奏、认证、UI 全部被宿主绑架。
> 因此：**自建会话编排（保持 CLI 无关），把 DSH 当作「又一个可被管理的 CLI」来适配**——`dsh --profile headless`（rc.6 已有的一次性任务面）就是 gate adapter 的一个新后端。

---

## 1. DSH 是什么（事实清单，rc.6 核对，v1 保留）

| # | 事实 | 出处/证据 |
|---|---|---|
| F1 | DeepSeek AI 开源的 agent harness，**MIT**；「everything is a plugin」，基于 Cordis 插件框架 | 官方 README |
| F2 | **Developer preview（rc.6），README 明示 "THERE WILL BE COMPATIBILITY-BREAKING CHANGES"** | 官方 README + `@deepseek-ai/dsh@0.1.0-rc.6` |
| F3 | 运行形态：`npx @deepseek-ai/dsh web` 起 Web UI（默认 `127.0.0.1:3080`）；另有 **`dsh --profile headless "task"` 一次性任务面**（不开端口，任务文本即命令行，退出码 0/1，stdout 打印最后一条 assistant 消息） | README、`dsh-headless` README |
| F4 | **自带 agent 循环（in-process）**：`dsh-agent-loop` + 供应商无关 LLM 抽象 `dsh-llm`；现成 adapter = `dsh-llm-deepseek`（chat-completions，`baseURL`/模型清单可配）与 `dsh-llm-pi-ai`；有 `registerAdapter` / `registerConfigurableProviders` 扩展缝 | `dsh-llm` README |
| F5 | **MCP 客户端**（`dsh-mcp-client`）：stdio 或 streamable-http 挂外部 MCP server，工具以 `mcp__<server>__<tool>` 暴露；stdio 支持 `command/args/env` | `dsh-mcp-client` README |
| F6 | **无 MCP 服务端**（不对外暴露工具面）——gate 的 Java MCP server 不受影响、也无需让位 | 包清单 |
| F7 | 会话：持久化 canonical JSONL 日志（`~/.dsh` 下）、resume/fork、投影；**token 计量** `dsh-token-meter`（provider 上报 usage + 启发式估计）、会话统计、OTel 遥测 | 包 README |
| F8 | Web 宿主：`node:http` + SPA fallback；**CLI 层拒绝 `--host 0.0.0.0`**；`/api` browser-trust 篱笆（Host 权威 + Origin 相等 + sec-fetch-site 拒绝，防 DNS rebinding） | `dsh-web-app` / `dsh-client-connection` README |
| F9 | **「The fence is a reachability policy, not authentication; the Web carrier provides no authentication layer.」**——无 token 认证，官方明示 remote access 认证层未做 | `dsh-client-connection` README |
| F10 | 浏览器端是 Vue 3 客户端 + 客户端插件系统（HMR、slots、schema-form；另有 React 变体） | 包清单 |
| F11 | 自带能力面：goal/jobs/skills/subagent/workflow/plan mode/审批预设/沙箱（bash/pwsh，含 Windows ACL）/web search/schedule 等 | 包清单 |
| F12 | **Windows 一等公民**（pwsh 工具、Windows ACL 沙箱）；本会话即运行于 Windows | 包清单 + 本机实况 |
| F13 | 体积/形态：约 150 个 `@deepseek-ai/dsh-*` 包，Cordis composition（`cordis.yml`/preset）组装，非单一库 API | 包清单 |

---

## 2. 本项目下一步需要什么（S0–S5 需求摘要，v1 保留）

| 阶段 | 计划自研内容 | 闭环环节 |
|---|---|---|
| S0 | `gate-web` 模块（JDK HttpServer）、HUMAN token 认证、Host/Origin 白名单、静态 SPA fallback | auth |
| S1 | ~15 条 REST 路由（status/tickets/presubmit/review-result/diff/reconcile/metrics/config/providers）、错误码映射 | status/presubmit/reconcile |
| S2 | GateTask/TaskRegistry + SSE、review/publish 异步化、WebGateEquivalenceTest | review/publish |
| S3 | AgentSessionPort、领域类型、V4 DDL、ClaudeHeadlessAdapter/OpenCodeServeAdapter、上下文注入、MCP 配置生成 | session |
| S4 | 会话 REST + SSE + abort + 历史、TicketLockManager 工单级串行、进程生命周期 | session |
| S5 | usage 累计 → `ticket.exec_token_total` 回写 → H1 cost ratio 非 NaN | session/metrics |
| 前端 | Vue 3 SPA：登录/看板/工单详情/**审核台（Monaco diff + tree_hash）**/会话/AgentConfig/成本面板，SSE 消费 | 全部 |

---

## 3. DSH 与本迭代的映射（v1 保留，结论不变）

### 3.1 DSH 能提供的（若做插件化可省下的自研量）

| 计划项 | DSH 对应物 |
|---|---|
| S0 Web 宿主 + 静态资源 + 127.0.0.1 | `dsh-web-app`（node:http + SPA fallback，拒绝 0.0.0.0） |
| S0 Host/Origin 白名单 | browser-trust 篱笆（F8） |
| S3/S4 agent 会话编排全节 | DSH 自带 agent loop + 会话 + 消息 + resume/abort + 历史 + 事件流 |
| S3 MCP 配置生成 | `dsh-mcp-client` stdio 挂 `gate mcp serve`（F5），直接消费 P3 已验收的两域 MCP 契约 |
| S5 usage 解析 + 回写 | `dsh-token-meter` + session 投影（F7） |
| S2 TaskRegistry + SSE | `dsh-jobs` + DSH 事件通道 |
| S3 AgentConfig CRUD + 设置 | `dsh-settings-file` + 设置 UI |
| 前端骨架（登录/布局/会话/任务/设置/模型选择/成本） | DSH 自带 Vue 客户端全部页面 |

### 3.2 仍然必须自研（不因换底座而减少）

**审核台**（Monaco diff + tree_hash + findings，咽喉）、看板/工单详情/成本面板 client 插件、认证层（F9）、工单级串行锁与「会话进行中」互斥、闸门本体（pre-receive / approval / tree 锚定 / fail-closed / B1–B19，**与 DSH 无关，一字不动**）。

### 3.3 DSH 真正有价值的三个参照点（自建时吸收）

1. **会话是持久化日志 + 投影，不是内存对象**：DSH 的 canonical JSONL + 只读回放 + 冷读不依赖进程——这验证了后端文档 §5.6「历史会话只读」的设计方向，且更彻底（连「打开历史会拉起进程」都是 DSH 的已知限制，值得规避）。
2. **usage 走 provider 上报 + 启发式兜底 + degraded 标记**：与 gate 既有 D7 语义同构，互相印证；DSH 的「CJK 启发式低估」警告也提醒 gate 的 H1 计量必须以 provider usage 为准。
3. **MCP 作为 agent 接入面是成熟路径**：DSH 客户端挂外部 MCP server 的形态，等价于 gate 想要的「系统调度 agent + gate MCP 回写」——S3 生成 MCP 配置的方向被独立验证。

---

## 4. 模式对比与否决理由（v2 修订）

| | 模式 A：DSH 宿主 + gate 插件集 | 模式 B：提炼 DSH 子集 | 模式 C：自建（原 S0–S5） |
|---|---|---|---|
| 形态 | gate 以 cordis 插件 + 客户端插件 + mcp-client 挂进 DSH | fork/vendor 20–40 个耦合包 | gate-web + Vue SPA + 自研 adapter 双实现 |
| 复用率 | 最高 | 中（但永久维护） | 零 |
| 认证（§6.2） | 需补层/论证等价 | 同 A | 天然满足 |
| 上游风险 | 高（rc.6 churn） | 最高（fork 即永久维护） | 无 |
| 市场受众 | **被 DSH 生态绑架（否决点）** | 被 fork 绑架 | **CLI 无关，受众最大化** |
| 结论 | **否决** | **否决** | **采用** |

### 4.4 否决模式 A 的论证（v2 新增，批复依据）

1. **违背定案定位**：`doc/README.md` 冻结的定位是「**一个 CLI 无关的 git 提交闸门层**」。插件化把「适配任意 agent CLI」变成「只适配 DSH 生态」——CLI 无关是 v2 §12 检索后收敛出的差异点之一，不能自毁。
2. **自降市场受众**：gate 的价值主张是「任何 vibe coding 工作流都能在 git 协议入口被卡住」。做成 DSH 插件集后，使用 gate 的前提变成「先装 DSH、跟随 rc.6 升级、接受 DSH 的 UI 与认证姿态」——受众从「所有用 git 的人」收窄为「DSH 用户」。单用户本地工具靠口碑扩散，受众收窄是致命的。
3. **升级节奏与认证被宿主决定**：F2/F9——DSH 明示 breaking changes 且无认证层；gate 的 §6.2 硬要求若靠「威胁模型等价」论证过关，等于把安全姿态交给上游的未做项，风险不可控。
4. **DSH 不提供 gate 的咽喉**：审核台/状态机/锁/闸门本体全要自研，插件化省下的只是「非差异点 + 别人的主战场」（v2 §12 已判定这层不值得自建大系统，但自建一个 adapter 层成本有限）。

### 4.5 为什么不选模式 B（提炼子集）

DSH 的组件粒度是 Cordis 插件不是库 API；fork 子集 = 继承 rc.6 churn + 失去上游修复 + 永久维护债。**「提炼」的正确动作是写插件（模式 A）或写 adapter（模式 C+），而不是拆包。**

---

## 5. 决策记录（v2）

### D1：否决插件化（模式 A/B）——已倾向，待批复

理由见 §4.4。批复后：`执行文档-后端-web.md` 维持原样（S0–S5 不变），本讨论归档为「评估记录 + 否决记录」。

### D2：自建会话编排，自行创建并管理终端 CLI 子进程——已倾向，待批复

即原 S0–S5 计划不变：`AgentSessionPort` 双 adapter（claude headless / opencode serve），进程生命周期铁律（§5.8）、工单级串行锁（§7）、端口分配（§7.3）。DSH 的参照点（§3.3）写入对应章节的注释即可，不改变契约。

### D3：预留 DSH 适配（新增，S4 之后的可选增量）

| # | 项 | 内容 |
|---|---|---|
| D3-1 | `AgentCli` 枚举扩展 | 加 `DSH` 值（枚举已是扩展点，`AgentConfig.cli` 分发，ADR-12 不变） |
| D3-2 | `DshCliAdapter` 形态 | rc.6 的 CLI 面是 **`dsh --profile headless "task"` 一次性任务**（F3）：每消息 spawn，stdout 取最后一条 assistant 文本，退出码 0/1。与 claude headless per-message 同构，进程管理最简 |
| D3-3 | usage 读取路径 | headless stdout 无 usage → 从 DSH 会话 JSONL（`~/.dsh`，F7）按会话 id 读取；读不到 → `degraded=true`（沿用 D7 语义，不阻塞）。**DSH 未来若提供会话 CLI/导出面，再升级为结构化输出** |
| D3-4 | 注入路径 | DSH 是 MCP 客户端（F5）→ 其 headless 会话配置里挂 `gate mcp serve`（stdio + `GATE_DOMAIN_TOKEN`），与 claude 的 `--mcp-config` 注入等价。**spike 项**：headless 面是否支持挂 MCP 配置（cordis.yml/preset），需实测 |
| D3-5 | 触发条件 | 用户机器已有 DSH（dogfood 场景）且想用 DSH 的模型配置/工具面当执行 agent 时启用；不改变 gate 默认形态 |

**D3 的全部验收延续既有判据**：会话闭环 A17/A18 以 claude adapter 为准，DshCliAdapter 只要求「同契约通过 stub 测试」+ 真实 CLI 本地冒烟（对齐 A11 的定位）。

---

## 6. 迭代划分（v2：维持原计划 + 增量）

| 阶段 | 内容 | 变化 |
|---|---|---|
| S0–S5 | 原计划不动（`执行文档-后端-web.md` / `执行文档-前端-web.md`） | 无 |
| S6（可选增量） | `DshCliAdapter` + `AgentCli.DSH` + 会话 JSONL usage 读取 + headless MCP 注入 spike（D3） | 新增；S4 验收后评估是否进场 |

S6 止损：headless 面无法挂 gate MCP（D3-4 spike 失败）→ DshCliAdapter 降级为「无闸门工具的纯执行 CLI」（agent 不能 presubmit，须人工触发），或整体砍掉 S6（不影响任何已交付）。

---

## 7. 风险表（v2 更新）

| # | 风险 | 触发条件 | 止损动作 |
|---|---|---|---|
| R1 | S6 的 DSH 适配随 rc.6 升级漂移 | D3-2/D3-3 依赖的 CLI 面变化 | S6 是可选增量，契约不变（`AgentSessionPort` 稳定），最多推迟 |
| R2 | DSH headless 无法挂 MCP | D3-4 spike 失败 | 降级为无工具执行 CLI 或砍 S6（§6） |
| R3 | H1 执行侧 token 仍拿不到（自建 adapter usage 解析失败率高） | claude stream-json 解析不稳 | 沿用 D7 degraded 语义；S6 的 DSH JSONL 读取是第二路径 |
| R4 | 自建 S3–S5 成本超预期 | adapter 双实现 + 会话 UI 全自研 | 既有止损链（后端文档 §12 R6：退化为「会话只读历史 + 手动 CLI」；gate 操作台 S0–S2 仍交付） |
| R5 | 总止损：若未来 DSH 稳定到 1.0 且 gate 受众论证反转 | 重新评估模式 A | 届时 gate 的 `AgentSessionPort`/REST 契约仍在，插件化是「再加一层 adapter」而非重构——本讨论的否决不预设永久 |

---

## 8. 下一步（批复点）

1. **批复 D1**（否决插件化，理由 §4.4）与 **D2**（维持自建 S0–S5）；
2. 批复后：`执行文档-后端-web.md` / `执行文档-前端-web.md` 从「待审查」转「定案」，按原计划开 **S0**（gate-web 骨架已有未提交脚手架，直接续做）；
3. **D3（DSH 适配）列为 S4 后的可选增量**，不阻塞主迭代；若你希望提前验证 D3-4（headless 挂 MCP），可并行的最小 spike 是：在任意目录写一份挂 `gate mcp serve` 的 cordis.yml，跑 `dsh --profile headless` 看工具是否出现。

> 一句实话：v2 检索后我们早已判定「编排层是别人的主战场」。DSH 是这片主战场上最新的成熟宿主——它的正确用法不是让我们搬进去，而是让我们**保持 CLI 无关，并在需要时把它当作第 N 个可管理的 CLI**。闸门层（pre-receive + tree_hash + approval + fail-closed）是唯一不可外包的部分，它一个字都不需要改。

---

*本文档为立项/执行系列的讨论稿 v2。v1（推荐插件化）因「市场受众 + CLI 无关定位」论证被否决，本文档取代 v1；事实清单（§1）与映射（§3）保留为评估记录。定案后按 §8 流程更新 `doc/README.md` 索引并归档。*
