# Gate Web UI — 后端执行文档

> 状态：全部完成（S0–S5/A13–A19；gate-web 42 tests green，BypassMatrix 回归通过）
> 上游：`../归档/done/架构落地执行文档.md`（地基，端口/DDL/锁/ADR-1..9）、`../归档/done/执行文档.md`（阶段计划与止损体例）、`../归档/done/h1-stage-report.md`（H1 degraded basis）、`../01-立项与调研/立项讨论-v2.md`（§6.2 安全硬要求）
> 平行：`执行文档-前端-web.md`（消费方）
> 日期：2026-08-14
> 定位：这是**本迭代的执行约束**。以下所有新端口 Java 签名、REST 路由表、V4 DDL、并发补强、ADR-10..14，都是开工即照做的约束，不是建议。

---

## 0. 这份文档是什么 / 不是什么

**是**：可以直接照着写代码的后端执行计划——新增模块边界、新增端口的完整 Java 签名、REST 路由总表、V4 DDL 全文、并发锁补强规则、S0-S5 阶段划分与可运行验收命令、ADR。

**不是**：立项论证（在 `立项讨论-v2.md`）、前端视图设计（在 `执行文档-前端-web.md`）、闸门层的既有契约（以 `架构落地执行文档.md` 为准，本文档只在其上**追加**，不改写）。

**与已完成 P0-P4 的关系**：P0-P4 已全绿（94 tests green，A1-A12 通过），闸门层（CLI + MCP stdio）已成定案。本迭代不重做闸门，只**在闸门之上**加一层 Web adapter 与 agent 会话编排层。闸门核心契约（pre-receive、approval、tree 锚定、fail-closed、错误码）一字不动；新增的 V4 迁移、新端口、新模块**不得**回归 B1-B19 绕过矩阵。

**与立项讨论 v2 的关系**：v2 §1.3 曾判定「闸门层不需要 Web UI」并删除前端章节，会话/编排被归入"编排层职责"。本轮经 dogfooding 复盘与 H1 复测需求，**部分恢复 UI**，但范围严格收窄为：

- **闸门操作台**（status/presubmit/review/publish/reconcile/status 的 Web 化）
- **agent 会话编排**（session 层，路线 B 结构化接口，非 PTY）

**不恢复** v2 被删除的工单/看板大系统、inbox/通知、autopilot、squad、skill 市场、真终端 PTY、多用户团队、远端协作、自研 agent CLI。本文档与 v2 冲突处，**以本文档为准**。

---

## 1. 定位与边界

### 1.1 一句话

**为已完成的闸门层补一个 Web 操作台 + agent 会话编排，使 gate 可日常使用，并补齐 H1 执行侧度量。**

### 1.2 三条自建理由（按强度排序）

1. **闸门操作台无现成方案。** 审核台锚定 `tree_hash` + `base_commit`，让人确认「审的就是要落地的」，这是 gate 独有的咽喉动作；现有工具（agtx/agent-mesh/llmux）都没有"经 git 协议强制"这一环，更没有 tree 锚定可视化。
2. **会话编排路线 B 补齐 H1 执行侧 token。** 当前 H1 = PARTIAL，degraded basis 之一是「执行侧 token 不可用（agent CLI 未捕获）」。走路线 B，session adapter 从 stream-json / opencode 事件流里能拿到每轮 usage——**会话层恰好补齐 H1 度量的执行侧拼图**。这是本迭代的硬代理由，写进 §5.7 成本回写。
3. **复用现有 GateService/CredentialRepository，增量小。** 应用层已有 `GateService` 5 方法门面与 `CredentialRepository` 双域 token，Web 层是 driver adapter，零核心改动。闸门闭环的权威性不依赖 Web 层是否在线。

### 1.3 明确不做（本阶段，防范围蠕变）

- inbox / 通知中心 / autopilot / squad / skill 市场
- 真终端 PTY / xterm.js / 交互式 stdin 转发（路线 A 已否决，见 ADR-11）
- 多用户、团队、RBAC、远端协作
- 自研 agent CLI（复用 opencode / claude code 现成二进制）
- 工单/看板大系统（不造 multica；只做闸门闭环所需的工单视图）
- 跨项目调度、多 gate 实例联邦

### 1.4 防蠕变规矩（铁律）

**每个后端 endpoint 必须在路由表标注 `[闭环环节: xxx]`**，闭环环节取自固定集合：`presubmit / review / publish / reconcile / status / session / auth`。答不上来或标注为「以后可能用得上」的 endpoint 不立项。新增 endpoint 时同步扩路由表与 ADR。

---

## 2. 架构与模块

### 2.1 依赖图（在现有六边形里加一层 web adapter）

```
                          ┌─────────────────────────────┐
   浏览器 SPA (Vue 3)  ───▶│        gate-web             │
   (静态资源, /api/*)      │  (HTTP adapter + 会话编排)  │
                          └──────────┬──────────────────┘
                                     │ 依赖（单向）
                  ┌──────────────────┼──────────────────┐
                  ▼                  ▼                  ▼
          gate-application    gate-adapters       gate-ports
          (GateService,       (Jdbc*, GitCli,     (AgentSessionPort 新增,
           MetricsService)     FileChannelLock)    其余既有)
                                     │
                                     ▼
                                gate-domain
                          (AgentConfig/Session/SessionMessage 新增,
                           其余既有)
```

- `gate-web` 依赖 `gate-application + gate-adapters + gate-ports + gate-domain`，**不反向依赖 `gate-cli`**（CLI 是同级 driver adapter）。
- ArchUnit 规则扩展：`gate-web` 不得被任何模块依赖；`gate-web` → `gate-cli` 依赖判定为违规。

### 2.2 新增模块 `gate-web`

| 项 | 值 |
|---|---|
| Maven artifactId | `gate-web` |
| 父 POM `</modules>` 追加 | `<module>gate-web</module>` |
| 依赖 | gate-application, gate-adapters, gate-ports, gate-domain |
| 主类 | `gate.web.GateWebApp`（轻量 main，**不**走 Spring Boot web；见 §2.4） |
| 装配根 | `gate.web.WebComponents.fromConfig(Path gateToml, String gitExecutable)`（复刻 `GateComponents.fromConfig` 的装配风格，外加 web/session 专属组件） |
| 静态资源 | `src/main/resources/web/`（前端 Vite 产物 `dist/` 拷入；`/api/*` 走后端，其余走静态 fallback） |

### 2.3 进程模型

- **gate-web 是常驻进程**，与 gate-cli 的「一次性命令」不同。复用 `GateComponents.fromConfig` 装配全图，但**每个 gate 实例 = 一份 `gate.toml` = 一个 `GateComponents`**。
- 单进程单项目 vs 单进程多项目：当前 `GateConfig` 是单 `project` 字符串，`LockManager` 锁键含 `project`，`FileChannelLockManager` 的 in-process `ReentrantLock` map 也按 `project+targetRef` 分桶——**结构上**支持单进程多项目。但 §7 的应用内串行锁、§5 的端口分配表、§3 的 token 域都按「单进程单项目」最简实现。**首期决策：单进程单项目**（ADR-14）。多项目留待真实需求验证后再做，不在本迭代。

### 2.4 HTTP 层选型决策

| 项 | Javalin | JDK HttpServer | Spring Boot Web |
|---|---|---|---|
| 依赖体积 | 小（~1MB） | 零（JDK 自带） | 大（全家桶） |
| 与「无框架」现状一致性 | 中 | **高** | 低（ADR-8 明确无 web） |
| SSE / 静态资源 | 内置 | 需手写 | 内置 |
| 路由表达力 | 好 | 原始 | 好 |

**推荐：JDK HttpServer。** 理由：(1) 与 ADR-8「Spring Boot 无 web、domain/application 零 Spring」一致，gate-web 不引入第二个框架；(2) 路由数量小（§4 路由表 ~15 条），手写 `Filter` + `HttpContext` 完全够用；(3) SSE 在 JDK HttpServer 上是「写 `text/event-stream` + 不关连接」，可控且无魔法；(4) 静态资源 fallback 手写 30 行。Javalin 列为备选（若 SSE 实现成本超预期）。

### 2.5 静态资源与 SPA fallback

- 前端 Vite 产物放入 `gate-web/src/main/resources/web/`，构建时由 frontend 流水线产出（前端文档约定）。
- 路由规则：`/api/*` → 后端 handler；其余 → 静态资源，找不到文件则返回 `index.html`（SPA history 模式）。
- 静态资源不走 token 校验（见 §3.4 例外白名单），但 `/api/*` 一律校验。

---

## 3. 认证与安全

### 3.1 复用 CredentialRepository 的 HUMAN 域 token

浏览器会话凭据**复用 HUMAN 域 token**，不新增 WEB 域（ADR-10）。理由见决策表 D1。

| # | 问题 | 选项 | 推荐 | 理由 |
|---|---|---|---|---|
| D1 | Web 会话 token 域 | ① 复用 HUMAN 域 ② 新增 WEB 域 | **①** | HUMAN 的语义就是「人通过任何客户端操作」——CLI、MCP、Web 都是 HUMAN 的客户端。新增 WEB 域会迫使同一个人持两套 token，徒增管理面，且 `review_run/commit_and_publish` 的授权语义本就属于人域 |

### 3.2 token 生成与下发

- 启动时若 `[web] human_token_file` 指向的文件不存在或为空，**调用 `CredentialRepository.issueHumanToken(now)`** 生成明文 token，写入该文件（权限 0600，Windows 上 ACL 限当前用户）。已存在且非空则读出明文供启动日志打印。
- 启动日志打印一次：`GATE_WEB_TOKEN=<plaintext>  (also written to <file>)`，提示用户复制到浏览器登录页。
- 浏览器登录页输入 token → 后端 `POST /api/auth/verify`（见 §4）校验 sha256 → 返回 200 + 浏览器写入 `localStorage.gate_token`。后续请求带 `Authorization: Bearer <token>`。
- token 可由 `gate credential revoke`（既有 CLI）撤销；撤销后浏览器请求一律 401。

### 3.3 v2 §6.2 硬要求落地（逐条）

| v2 §6.2 条目 | 落地 |
|---|---|
| bind 127.0.0.1，绝不 0.0.0.0 | `GateWebApp` 用 `InetSocketAddress("127.0.0.1", port)`；配置项 `[web] bind` 默认 `127.0.0.1`，**禁止配置为 0.0.0.0**（启动校验，命中即 fail-closed 退出 22） |
| 每请求校验 Bearer token | 全局 `Filter`（`/api/*` 与 SSE 端点）调用 `CredentialRepository.validate(token)`，非 HUMAN 域 → 401 |
| 校验 Origin/Host 白名单（防 DNS rebinding） | 全局 `Filter` 校验 `Host` 头在 `[web] allowed_origins` 白名单（默认 `["127.0.0.1", "localhost"]`）；SSE/WS 握手**同样**校验 `Origin` |
| SSE/WS 握手同样校验 | SSE 端点（`GET /api/tasks/{id}/events`、`GET /api/sessions/{sid}/events`）的 `Filter` 链与普通 `/api/*` 一致，不豁免 |
| 两套独立凭据 | agent 域 token 仍由 `gate mcp issue-token --ticket T` 签发；Web **不接管** agent token 签发（见 §3.5） |

### 3.4 token 校验的路径白名单

免校验路径**仅**：`/api/auth/verify`（登录校验本身）、`/api/health`（存活探针）、所有静态资源（`/`、`/index.html`、`/assets/**`）。其余 `/api/*` 一律校验。

### 3.4.1 SSE 端点接受 query-param token（对齐前端 ADR-F3）

浏览器 `EventSource` **不支持自定义请求头**，无法带 `Authorization: Bearer`。因此 SSE 端点（`GET /api/tasks/{id}/events`、`GET /api/sessions/{sid}/events`）的 `Filter` 必须**额外接受 `?token=<plaintext>`**：优先读 `Authorization` 头，缺失时回落读 query param `token`，二者取到后走同一 `CredentialRepository.validate`。

- 仅 SSE 端点开放 query-param token，普通 `/api/*` 仍只认 header（减少 token 进 URL 的暴露面）。
- token 进 URL 会落 access log —— gate-web 的请求日志对 `token` query 值做脱敏（记为 `token=***`），写进 §9 测试断言。
- 因 bind 127.0.0.1 + Host/Origin 白名单，query-param token 的残余暴露面仅限本机日志，可接受（继承 §3.6 威胁模型）。

### 3.5 agent 域 token 签发：Web 不接管

| # | 问题 | 选项 | 推荐 | 理由 |
|---|---|---|---|---|
| D2 | Web 是否接管 agent token 签发 | ① 不接管，仍走 `gate mcp issue-token --ticket T` ② Web 提供一个人域 endpoint 代签发并写入工单 | **① 首期；② 列为 S4 可选** | 首期保持 agent token 签发的唯一入口在 CLI/MCP，避免 Web 引入第二条签发路径（多一条路径多一处滥用面）。S4 若发现"看板上一键建工单后还要切到 CLI 签 token"体验割裂，再加 `POST /api/tickets/{no}/agent-token` 代签发（仍落 `credential` 表，仍 HUMAN 域调用） |

### 3.6 威胁模型继承

继承 `架构落地执行文档 §1.3`：闸门防的是「无意的错误与走捷径」，不隔离同 OS 权限持 shell 的对手。Web 层不改变这一点——**Web 层是闸门的 driver，不是闸门本身**。即便 Web 层被攻破（token 泄漏、SSE 被劫持），权威库仍受 pre-receive 保护；Web 层能做的最坏事情是「触发一次 review 或 publish」，而这两者仍走既有 GateService，verdict 仍由 GatePolicy 铸造，approval 仍单次消费。

---

## 4. REST API 设计

### 4.1 路由总表

所有 `/api/*` 端点（除 §3.4 白名单）均需 `Authorization: Bearer <HUMAN token>` + Host/Origin 白名单。`闭环环节` 取自 §1.4 固定集合。

| Method | Path | 域 | 对应 GateService/Repository | 闭环环节 | 说明 |
|---|---|---|---|---|---|
| POST | /api/auth/verify | HUMAN | CredentialRepository.validate | auth | 校验 token，登录 |
| GET | /api/health | — | — | — | 存活探针（免 token） |
| GET | /api/status | HUMAN | GateService.status | status | 项目状态总览 |
| GET | /api/tickets | HUMAN | TicketRepository.findAll | status | 工单列表 |
| GET | /api/tickets/{no} | HUMAN | TicketRepository.find | status | 工单详情 |
| POST | /api/tickets | HUMAN | TopologyInitializer.createClone + TicketRepository.insert | presubmit | 建工单（接管 clone，见 D3） |
| POST | /api/tickets/{no}/presubmit | HUMAN | GateService.presubmit | presubmit | 预提审（同步） |
| POST | /api/tickets/{no}/review | HUMAN | GateService.review（异步任务） | review | 审核（202 + 任务 id） |
| POST | /api/tickets/{no}/publish | HUMAN | GateService.publish（异步任务） | publish | 发布（202 + 任务 id） |
| GET | /api/tickets/{no}/review-result | HUMAN | ReviewResultRepository | review | 驳回回喂（最新 findings） |
| GET | /api/tickets/{no}/presubmit/{round}/diff | HUMAN | BlobStore（按 presubmit.diff_blob 取） | presubmit | 取 diff 文本 |
| POST | /api/reconcile | HUMAN | GateService.reconcile | reconcile | 收敛检查 |
| GET | /api/metrics | HUMAN | MetricsService.export | status | 成本明细导出 |
| GET | /api/metrics/h1 | HUMAN | MetricsService.verdict | status | H1 判定 |
| GET | /api/config | HUMAN | GateConfig（脱敏投影） | status | 配置只读（脱敏：不含 api_key） |
| GET | /api/providers | HUMAN | ProviderRepository | status | 供应商列表 |
| GET | /api/tasks/{id} | HUMAN | TaskRegistry | — | 查异步任务状态 |
| GET | /api/tasks/{id}/events | HUMAN | TaskRegistry（SSE） | — | 任务进度流 |
| GET | /api/tickets/{no}/sessions | HUMAN | SessionRepository | session | 工单下的会话列表 |
| POST | /api/tickets/{no}/sessions | HUMAN | AgentSessionPort.start | session | 在工单下建会话（唯一入口，落 ticketNo 绑定） |
| GET | /api/sessions/{sid} | HUMAN | SessionRepository | session | 会话详情 |
| POST | /api/sessions/{sid}/messages | HUMAN | AgentSessionPort.sendMessage（异步任务） | session | 发消息（202 + 任务 id） |
| GET | /api/sessions/{sid}/messages | HUMAN | AgentSessionPort.getHistory | session | 消息列表（历史只读回放，运行中/结束态均可取） |
| GET | /api/sessions/{sid}/events | HUMAN | AgentSessionPort.streamEvents（SSE） | session | 会话消息流 |
| POST | /api/sessions/{sid}/abort | HUMAN | AgentSessionPort.abort | session | 中止会话 |
| GET | /api/agent-configs/{id}/sessions | HUMAN | SessionRepository | session | 某 AgentConfig 的历史会话列表 |
| GET/POST/PUT/DELETE | /api/agent-configs[/{id}] | HUMAN | AgentConfigRepository | session | AgentConfig CRUD（更新用 PUT 整体替换） |

> **会话路由约定（与前端文档一致）**：会话**创建/列表**挂在工单下（`/api/tickets/{no}/sessions`），强制 ticketNo 绑定；单会话操作走**扁平** `/api/sessions/{sid}/...`（`sid` 为全局唯一 UUID，无需重复带 `{no}`）。历史回放复用 `GET /api/sessions/{sid}/messages`，不单设 `/history`。

### 4.2 工单创建：Web 接管 clone 生成

| # | 问题 | 选项 | 推荐 | 理由 |
|---|---|---|---|---|
| D3 | Web 是否接管 ticket create 的 clone 生成 | ① 接管，`POST /api/tickets` 直接 clone+insert ② 不接管，只 insert，clone 由 CLI 做 | **①** | 让看板能一键建工单（dogfooding 的基本诉求）。复用 `TopologyInitializer.createClone`（既有 `--no-hardlinks --single-branch`），逻辑与 `TicketCommand.Create` 等价，只是 driver 从 picocli 换成 HTTP |

### 4.3 长操作异步化与统一任务模型

review（prism 子进程，120s 级）与 publish（git push）**必须**包成异步任务，前端走 SSE 收进度。

```java
// gate-domain (新增)
package gate.domain.task;

public record GateTask(
        String id,                       // UUID
        String type,                    // "review" | "publish" | "session-send"
        String ticketNo,                 // 可空（session-send 时关联工单）
        String sessionId,                // 仅 session-send
        GateTaskStatus status,           // RUNNING | SUCCEEDED | FAILED
        Instant startedAt,
        Instant finishedAt,              // 可空
        String resultJson,               // 成功时的结构化结果 JSON
        String errorJson) {              // 失败时的错误 JSON
}

public enum GateTaskStatus { RUNNING, SUCCEEDED, FAILED }
```

```java
// gate-ports (新增)
package gate.ports;

public interface TaskRegistry {
    GateTask register(String type, String ticketNo, String sessionId);
    void update(GateTask task);
    Optional<GateTask> find(String id);
    /** SSE 订阅；返回的是永不重复的事件流（已发出的事件先回放，后续实时推） */
    java.util.stream.Stream<GateTaskEvent> stream(String id);
    record GateTaskEvent(String taskId, String kind, String payloadJson, Instant at) {}
}
```

- 路由：`POST /api/tickets/{no}/review` → 注册 task → 提交到单线程 `ExecutorService` → 返回 `202 + {task_id}`。
- SSE：`GET /api/tasks/{id}/events` → `Content-Type: text/event-stream`，每条 `GateTaskEvent` 一行 `data: {json}`，task 终态后发 `event: done` 再关流。
- 任务持久化：task 元数据写 SQLite（V4 加 `gate_task` 表，幂等键 `id`），崩溃后 reconcile 可扫出 RUNNING 但无 finishedAt 的任务标记为 FAILED（类比 publish reconcile）。

### 4.4 错误模型：GateErrorCode → HTTP 状态码

`架构落地执行文档 §8.3` 的 GateErrorCode 映射到 HTTP：

| GateErrorCode | HTTP | 说明 |
|---|---|---|
| OK (0) | 200 | 成功 |
| REJECT_FINDINGS (10) | 422 | 审核驳回（业务结果，非错误） |
| REJECT_TOCTOU (11) | 409 | tree 不一致，冲突 |
| REJECT_PRECONDITION (12) | 422 | 前置条件不满足（base 移动/非 FF/空 diff/ref 非白名单） |
| REJECT_NEEDS_HUMAN (13) | 422 | 需人工 |
| GATE_ERROR_ENGINE (20) | 502 | 引擎失败 |
| GATE_ERROR_IO (21) | 503 | IO/锁忙/DB |
| GATE_ERROR_CONFIG (22) | 500 | 配置错误 |
| USAGE (64) | 400 | 参数错误 |
| INTERNAL (70) | 500 | 不可达 |

响应体统一：

```json
{ "error_code": 10, "error": "REJECT_FINDINGS", "message": "...", "detail": ["..."] }
```

`GateException` 在 web 层一个 `ExceptionHandler` 捕获，映射后返回；非 `GateException` 一律 500 + INTERNAL（fail-closed，不泄漏堆栈到响应体）。

---

## 5. Agent 会话编排（核心新增）

### 5.1 新增端口 `AgentSessionPort`

写法对齐 `ReviewEngine`：端口返回结构化 record，不返回 verdict 之外的副作用；流式用 `Stream<SessionMessage>`。

```java
// gate-ports (新增)
package gate.ports;

import gate.domain.session.*;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Agent session orchestration port (本迭代新增).
 *
 * <p>Two adapters share this contract (ADR-12): {@code OpenCodeServeAdapter} and
 * {@code ClaudeHeadlessAdapter}, dispatched by {@link AgentConfig#cli()}.
 *
 * <p>Contract mirroring {@link ReviewEngine}: the port returns structured records and never throws
 * on the happy path; transport/parse failures surface as a {@link SessionMessage} with
 * {@code role=ERROR} and a degraded marker, never as an exception that escapes the port
 * (analogous to ReviewEngine's EngineFailure value pattern, §5.3).
 */
public interface AgentSessionPort {

    /** Start a session bound to a ticket's clone; returns the new session id. */
    Session start(StartRequest request);

    /** Send a message; returns a task id (async) whose progress streams via {@link #streamEvents}. */
    String sendMessage(SendRequest request);

    /** Abort an in-flight message or tear down the session process. */
    void abort(String sessionId);

    /** Read-only history, independent of any running process. */
    java.util.List<SessionMessage> getHistory(String sessionId);

    /** SSE event stream: replays past messages, then streams live. */
    Stream<SessionEvent> streamEvents(String sessionId);

    record StartRequest(
            String ticketNo,
            String agentConfigId,
            String clonePath,        // ticket.clone_path
            String targetRef,
            String initialPrompt,   // 工单上下文已由 §5.4 注入 clone，这里只是首条用户消息
            java.util.Map<String, String> env  // 含 GATE_DOMAIN_TOKEN
    ) {}

    record SendRequest(
            String sessionId,
            String message,
            boolean resume          // true: 续接既有会话；false: 实际上不该发生
    ) {}

    record SessionEvent(
            String sessionId,
            SessionMessage message,  // 非流式聚合消息
            String kind              // "message" | "usage" | "tool_call" | "done" | "error"
    ) {}
}
```

### 5.2 新增领域类型（放 gate-domain）

```java
// gate-domain/session (新增包)
package gate.domain.session;

/** A reusable agent configuration (≈ multica Agent, but no Squad/Skill/Runtime). */
public record AgentConfig(
        String id,                  // e.g. "claude-sonnet-default"
        String name,               // 展示名
        AgentCli cli,              // OPENCODE | CLAUDE
        String providerId,         // 关联 provider 表
        String model,              // e.g. "claude-3-5-sonnet-20241022"
        String systemPrompt,       // 可空；额外 system prompt
        java.util.List<String> extraFlags,  // 透传 CLI flag
        String description) {}

public enum AgentCli { OPENCODE, CLAUDE }

/** A single session execution (≈ multica Task). */
public record Session(
        String id,                  // UUID
        String ticketNo,
        String agentConfigId,
        AgentCli cli,
        SessionStatus status,       // ACTIVE | ABORTED | CLOSED
        String cliSessionId,        // claude 的 session-id / opencode 的 session id
        String clonePath,
        int allocatedPort,         // opencode serve 端口；claude 为 -1
        Instant startedAt,
        Instant finishedAt,        // 可空
        SessionUsage cumulativeUsage) {}  // 累计 usage，每次 send 后更新

public enum SessionStatus { ACTIVE, ABORTED, CLOSED }

/** One message in a session. */
public record SessionMessage(
        String id,                  // UUID
        String sessionId,
        Role role,                  // USER | ASSISTANT | TOOL | ERROR
        String content,
        java.util.List<ToolCall> toolCalls,
        SessionUsage usage,         // 本条消息的 usage；非 LLM 消息为 null
        boolean degraded,           // usage 解析失败标记
        Instant timestamp) {}

public record ToolCall(String name, String argumentsJson, String resultJson) {}

public enum Role { USER, ASSISTANT, TOOL, ERROR }

/** Token usage (OpenAI-style: prompt/completion/total). */
public record SessionUsage(Long promptTokens, Long completionTokens, Long totalTokens) {
    public SessionUsage add(SessionUsage other) { /* 逐字段相加，null 视为 0 */ }
    public static final SessionUsage EMPTY = new SessionUsage(null, null, null);
}
```

### 5.3 两个 adapter

#### 5.3.1 `OpenCodeServeAdapter`

- 每工单 clone 起 `opencode serve --port <allocated> --hostname 127.0.0.1`。
- 端口分配：49152-65535 区间（可配 `[session] port_range_min/max`），`PortAllocator` 原子分配 + 释放（见 §7.3）。
- 生命周期：
  - `start`：spawn serve 进程，等 `/health` 就绪（超时 10s），`POST /session` 建会话拿 session id。
  - `sendMessage`：`POST /session/:id/message`（同步）或 `prompt_async` + `GET /event` SSE（异步）；解析事件流为 `SessionMessage`（含 usage）。
  - `abort`：`POST /session/:id/abort`，杀 serve 进程。
  - `getHistory`：`GET /session/:id/message`（不依赖运行中的 serve——opencode 会话持久化在 `~/.local/share/opencode` 或项目级存储）。
- 权限模式：agent 已在独立 clone（ADR-3），可用宽松权限（opencode 的 `auto_approve` 或 permission mode）；不暴露人域 token。
- 闸门 MCP 注入：opencode 项目级 `opencode.json` 的 `mcp` 段指向 `gate mcp serve` 子进程，`environment` 带 `GATE_DOMAIN_TOKEN`（agent 域）。Web 后端代为生成该 `opencode.json`（见 §5.5）。

#### 5.3.2 `ClaudeHeadlessAdapter`

- 每消息 `claude -p --input-format stream-json --output-format stream-json --model <m> --resume <session-id> --append-system-prompt-file <工单上下文文件> --mcp-config <闸门 mcp 配置> --strict-mcp-config --permission-mode acceptEdits`。
- 解析 stream-json 每行为 `SessionMessage`（含 `usage` 字段）。
- 首条消息无 `--resume`（新建会话，拿到 session-id 落库 `Session.cliSessionId`）；后续 `--resume <session-id>` 续接。
- 优化项（不在首期）：长驻 stream-json stdin 进程，避免每消息 spawn。列为 S4 之后的优化。

### 5.4 工单上下文注入

spawn 时写文件到 clone（不进 git，加 `.gitignore` 或写到 clone 外的 gate-home blob）：

- `CLAUDE.md`（claude 用）含：工单号、标题、目标分支、`tree_hash` 约定（"你无权 push 到权威库，预提审请调 `presubmit_create` MCP 工具"）、审核反馈循环说明。
- `AGENTS.md`（opencode 用）同上。
- `--append-system-prompt-file` / opencode `instructions` 引用之。
- 上下文文件路径在 `Session` 记录里登记，便于审计。

### 5.5 闸门 MCP 配置生成

Web 后端代为生成 MCP 配置（不依赖手工）：

- claude：生成临时 `mcp-config.json`，指向 `gate mcp serve` 子进程（stdio），`env.GATE_DOMAIN_TOKEN` = 该工单的 agent 域 token。`--strict-mcp-config` 保证只挂闸门 server。
- opencode：生成项目级 `opencode.json` 的 `mcp` 段，同样指向 `gate mcp serve`，env 带 agent 域 token。
- agent 域 token 的获取：首期由 CLI 签发后填入 `Session.env`（D2 ①）；S4 可选 D2 ② 由 Web 代签发。

### 5.6 历史会话只读

- opencode：`opencode export <id>` 导出为 JSON/markdown。
- claude：`~/.claude/projects/**/*.jsonl` 解析（按 clone 的绝对路径哈希定位项目目录）。
- `getHistory` 不依赖运行中的进程；会话关闭后仍可读。

### 5.7 成本回写：补齐 H1 执行侧

- 每条 `SessionMessage.usage` 累计到 `Session.cumulativeUsage`。
- 会话结束（CLOSED/ABORTED）或每 N 条消息，将 `cumulativeUsage.totalTokens` 写回 `ticket.exec_token_total`，`exec_token_source = "agent_cli"`（V3 已有列）。
- **这是 H1 从 PARTIAL 升级的直接路径**：`MetricsService.computeCostRatioMedian` 当前因 `execTokenTotal` 全 null 返回 NaN（degraded basis），session 层回写后分母非空，cost ratio 可算。
- 写入是 bypass-only：不阻塞 review/publish，失败只记 degraded 标记（类比 review 侧 CostHint）。

### 5.8 进程生命周期铁律

- **工单进终态（DONE/CANCELLED）或 Web 进程退出时，清理所有 serve 子进程与 spawn 的 claude 进程。** 用 `Runtime.addShutdownHook` + `SessionLifecycle` 注册表。
- **启动 reconcile 扫描孤儿进程**：类比现有 `publish reconcile`，扫描 DB 中 `Session.status=ACTIVE` 但进程已死的记录，标记为 ABORTED。Windows 上 `tasklist` / `wmic` 查 PID 存活。

### 5.9 决策表

| # | 问题 | 选项 | 推荐 | 理由 |
|---|---|---|---|---|
| D4 | 会话 adapter 首选实现 | ① opencode serve ② claude headless per-message | **② claude 首选，① opencode 备选** | claude per-message spawn 进程管理最简（每消息独立，无需常驻 serve），`--resume` 续接可靠；opencode serve 常驻进程有泄漏风险。先做 claude，opencode 列为 S3 第二实现 |
| D5 | 端口分配策略 | ① 固定端口 ② 动态区间分配 | **②** | 49152-65535 动态分配，`PortAllocator` 原子占位 + 进程退出释放；固定端口无法支持多工单并发会话 |
| D6 | Web 是否接管 ticket create 的 clone | ① 接管 ② 不接管 | **①**（见 D3） | 看板一键建工单 |
| D7 | session usage 解析失败的降级 | ① 整条消息丢弃 ② 标 degraded 仍入库 | **②** | 类比 review 侧 degraded：usage 拿不到不影响消息内容入库，只在 `SessionMessage.degraded=true` + `Session.cumulativeUsage` 不累加该条；H1 复测时 degraded 行单独标注 |

---

## 6. 数据层迁移

### 6.1 新增 `V4__agent_sessions.sql`

```sql
-- Flyway V4: agent session orchestration (本迭代新增).
--
-- Session = multica Task 等价物: one execution of an AgentConfig against a ticket's clone.
-- Messages: 数量可能很大, 内容落 blob store (与 review_result 一致), DB 存路径.
-- Gate task: 异步任务元数据 (review/publish/session-send), 幂等键 id.

CREATE TABLE agent_config (
  id            TEXT PRIMARY KEY,
  name          TEXT NOT NULL,
  cli           TEXT NOT NULL,           -- OPENCODE | CLAUDE
  provider_id   TEXT NOT NULL REFERENCES provider(id),
  model         TEXT NOT NULL,
  system_prompt TEXT,
  extra_flags   TEXT,                   -- JSON array of strings
  description   TEXT,
  created_at    TEXT NOT NULL,
  updated_at    TEXT NOT NULL
);

CREATE TABLE agent_session (
  id              TEXT PRIMARY KEY,
  ticket_no       TEXT NOT NULL REFERENCES ticket(ticket_no),
  agent_config_id TEXT NOT NULL REFERENCES agent_config(id),
  cli             TEXT NOT NULL,
  status          TEXT NOT NULL,        -- ACTIVE | ABORTED | CLOSED
  cli_session_id  TEXT,                 -- claude session-id / opencode session id
  clone_path      TEXT NOT NULL,
  allocated_port  INTEGER,              -- opencode serve port; claude NULL
  context_file    TEXT,                 -- 工单上下文文件路径 (审计)
  prompt_tokens   INTEGER,              -- cumulative usage
  completion_tokens INTEGER,
  total_tokens    INTEGER,
  started_at      TEXT NOT NULL,
  finished_at     TEXT
);
CREATE INDEX idx_session_ticket ON agent_session(ticket_no);
CREATE INDEX idx_session_status ON agent_session(status);

CREATE TABLE session_message (
  id              TEXT PRIMARY KEY,
  session_id      TEXT NOT NULL REFERENCES agent_session(id),
  role            TEXT NOT NULL,        -- USER | ASSISTANT | TOOL | ERROR
  content_blob    TEXT NOT NULL,        -- blob store 路径
  content_bytes   INTEGER NOT NULL,
  tool_calls_blob TEXT,                 -- JSON array, 可空
  prompt_tokens   INTEGER,
  completion_tokens INTEGER,
  total_tokens    INTEGER,
  degraded        INTEGER NOT NULL DEFAULT 0,  -- usage 解析失败标记
  created_at      TEXT NOT NULL
);
CREATE INDEX idx_msg_session ON session_message(session_id, created_at);

CREATE TABLE gate_task (
  id           TEXT PRIMARY KEY,
  type         TEXT NOT NULL,          -- review | publish | session-send
  ticket_no    TEXT,
  session_id   TEXT,
  status       TEXT NOT NULL,          -- RUNNING | SUCCEEDED | FAILED
  result_json  TEXT,
  error_json   TEXT,
  started_at   TEXT NOT NULL,
  finished_at  TEXT
);
CREATE INDEX idx_task_status ON gate_task(status);

-- ticket 加可选 agent_config_id 关联 (工单创建时指定, 可空)
ALTER TABLE ticket ADD COLUMN agent_config_id TEXT REFERENCES agent_config(id);
```

### 6.2 消息落 blob store vs 落 DB

| 项 | 落 blob store（推荐） | 落 DB（TEXT 列） |
|---|---|---|
| 一致性 | 与 review_result 的 findings_blob/raw_blob 一致 | — |
| 大消息 | 不受 SQLite 行大小影响 | 单行可能很大 |
| 查询 | 取消息需二次读 blob | 直接 SELECT |
| 事务性 | 消息元数据入库 + 内容落 blob，分两步 | 原子 |

**推荐落 blob store**（`content_blob` 存路径，与 `review_result.findings_blob` 同款 `BlobStore`）。理由：会话消息数量级远大于 review_result，长会话可能数百条、每条含工具结果可达 KB 级；SQLite 单行不擅长存大文本。

### 6.3 ticket 表加 agent_config_id

可选关联，工单创建时指定（`POST /api/tickets` body 带 `agent_config_id`），默认 NULL（用系统默认 AgentConfig）。不强制 NOT NULL，保持向后兼容。

### 6.4 迁移幂等性

V4 全部 `CREATE TABLE` / `ALTER TABLE ADD COLUMN`，Flyway 管理，重复执行由 Flyway 版本表拦截。`extra_flags` / `tool_calls_blob` 存 JSON 字符串，由 adapter 序列化，DB 不校验 schema。

---

## 7. 并发与锁

### 7.1 现有 FileLock 的盲区（必须补强）

`架构落地执行文档 §9.2` 的 `FileChannelLockManager` 已有 in-process `ReentrantLock` map（按 `project+targetRef`），**但**：

- `LockManager.acquire` 只在 `publish` 路径被 `GateServiceImpl` 调用（保护 auth.git ref 更新）。
- **Web 常驻进程**下，`presubmit`（写 clone 工作区）、`session sendMessage`（agent 在 clone 里改文件）对同一 clone 的并发**未被 FileLock 覆盖**——FileLock 只跨进程，同 JVM 多线程不互斥（`OverlappingFileLockException` 而非阻塞）。

### 7.2 工单级串行（新增）

定义**工单级串行锁**：同一 `ticketNo` 的 `session sendMessage` / `presubmit` / `publish` 串行。理由：这三者都操作同一 clone 的工作区，并发会互踩（agent 正在改文件时 presubmit 固化 tree 会拿到半成品）。

```java
// gate-ports (新增)
public interface TicketLockManager {
    /** 同一 ticketNo 串行; 跨 JVM 由 FileLock 兜底 (键 ticketNo). */
    AutoCloseable acquire(String ticketNo);
}
```

实现 `FileChannelTicketLockManager`：键 = `ticketNo`，in-process `ReentrantLock` + FileLock（锁文件路径 `locksDir/ticket-<ticketNo>.lock`）。

锁顺序不变量（R-LOCK 扩展）：
1. 工单级锁（`ticketNo`）→ 项目级锁（`project+targetRef`）→ FileLock → SQLite 写事务。
2. **永不反转**：持工单级锁时不得反向获取项目级锁之外的东西；SQLite 事务不跨 ProcessBuilder。

### 7.3 端口分配并发安全

`PortAllocator`：`AtomicInteger` 游标 + `ConcurrentHashMap<Integer, Boolean>` 占用表。`allocate()` CAS 占位，`release(port)` 清除。失败重试上限避免活锁。

### 7.4 会话发送 vs presubmit/publish 的并发

- `sendMessage`（异步任务）持工单级锁；若此时 `presubmit` 请求进来，后者直接 422 + `REJECT_PRECONDITION`（"session in progress on this clone"），不排队等待（避免长会话阻塞 presubmit）。
- 反之 `presubmit`/`publish` 持锁时，`sendMessage` 排队（短操作先完成）。前端 SSE 显示「等待工单锁释放」。

---

## 8. 配置

### 8.1 gate.toml 新增键（保持 fail-closed 未知键拒绝）

`TomlGateConfigLoader.KNOWN_KEYS` 扩展白名单：

```toml
schema_version = 2                       # 升级 (CURRENT_SCHEMA_VERSION = 2)

[web]
bind = "127.0.0.1"                       # 禁止 0.0.0.0, 启动校验
port = 4097
allowed_origins = ["127.0.0.1", "localhost"]
human_token_file = "gate-home/web-token"  # 相对 gate_home

[session]
port_range_min = 49152
port_range_max = 65535
default_cli = "claude"                   # claude | opencode
default_agent_config = "claude-sonnet-default"

[agent]
default_model = "claude-3-5-sonnet-20241022"
default_provider = "newapi"
context_template = "gate-home/agent-context.md.tmpl"  # 工单上下文模板
```

### 8.2 schema_version 升级

- `GateConfig.CURRENT_SCHEMA_VERSION` 从 1 升到 2。
- `TomlGateConfigLoader.build` 增加 `[web]` / `[session]` / `[agent]` 段解析；未知键仍 fail-closed 退出 22。
- 旧 `schema_version=1` 的 gate.toml 启动报错并提示升级（不自动迁移配置，避免静默改用户配置）。

### 8.3 GateConfig 扩展

```java
public record GateConfig(
        // ... 既有字段 ...
        WebConfig web,
        SessionConfig session,
        AgentConfigDefaults agent) {

    public record WebConfig(String bind, int port, List<String> allowedOrigins, Path humanTokenFile) {}
    public record SessionConfig(int portRangeMin, int portRangeMax, String defaultCli, String defaultAgentConfig) {}
    public record AgentConfigDefaults(String defaultModel, String defaultProvider, Path contextTemplate) {}
}
```

---

## 9. 测试策略

### 9.1 绕过测试矩阵延续

B1-B19 已全绿（94 tests）。**Web 层不能开新绕过面**。核心等价性测试：**Web 闸门 = MCP 闸门**——用 Web endpoint 跑一遍 happy path + 几条关键绕过，断言权威库 HEAD 不变。

- `gate.web.WebGateEquivalenceTest`：`POST /api/tickets` → `POST /presubmit` → `POST /review`（异步，等 task SUCCEEDED）→ `POST /publish`（异步）→ 断言 auth.git HEAD 前进一个 commit。
- 绕过样例：未审直接 `POST /publish` → 422 REJECT_PRECONDITION；并发两 publish → 仅一个 SUCCEEDED。
- 复用既有 `GateHarness`（真实 git，不用 JGit）。

### 9.2 会话 adapter 测试（不依赖真实 CLI）

- `OpenCodeServeAdapterTest`：用 fake HTTP server（`HttpServer` 桩）模拟 opencode serve 的 `/session`、`/event` 端点，喂固定事件流 JSON，断言 `SessionMessage` 解析正确、usage 累加。
- `ClaudeHeadlessAdapterTest`：用固定 stream-json 文件作为子进程 stdout（用一个测试用 `claude` 桩脚本），断言 `--resume` 续接、usage 解析、degraded 标记。
- 不在 CI 跑真实 opencode/claude（与 A11 真实子进程冒烟的定位区分：A11 是既有 MCP 的冒烟，会话的真实 CLI 冒烟列为可选的本地手动测试）。

### 9.3 异步任务 + SSE 测试

- `TaskRegistryTest`：注册 task → `stream(id)` 回放已发事件 + 实时推 → task 终态后发 `done`。
- `WebSseTest`：HTTP 客户端连 `/api/tasks/{id}/events`，断言 `Content-Type: text/event-stream`、事件格式正确、token 缺失 401。

### 9.4 并发锁测试

- `TicketLockManagerTest`：两线程争同一 `ticketNo`，断言串行（第二个阻塞或 busy）；不同 ticketNo 并行。
- `PortAllocatorTest`：并发 allocate 不重复，release 后可重分配。
- `ConcurrentPresubmitAndSessionTest`：session 持锁时 presubmit 返回 422，不阻塞。

### 9.5 验收标准（A13 起，接续 A12）

| 阶段 | 验收 | 判据 |
|---|---|---|
| S0 | A13 | 浏览器打开 `https://127.0.0.1:4097/`，无 token 访问 `/api/status` 返回 401；输入 token 后 200；`bind=0.0.0.0` 启动失败退出 22 |
| S1 | A14 | `curl /api/status` `/api/tickets` `/api/metrics` 返回结构化 JSON；`POST /api/tickets` 建 clone 并 insert |
| S2 | A15 | `POST /api/tickets/{no}/review` 返回 202 + task_id；SSE 收到 progress + done；结果落 review_result |
| S2 | A16 | `POST /api/tickets/{no}/publish` 同上；权威库 HEAD 前进；A1 等价性断言（权威库只增一个 commit） |
| S3 | A17 | `POST /api/agent-configs` CRUD 通；`AgentSessionPort.start` 建 claude 会话，`cliSessionId` 落库 |
| S4 | A18 | `POST /api/tickets/{no}/sessions/{sid}/messages` 返回 202；SSE 收到 SessionMessage 流；usage 解析正确 |
| S5 | A19 | 会话结束后 `ticket.exec_token_total` 非 null，`exec_token_source='agent_cli'`；`GET /api/metrics/h1` 的 cost ratio 非 NaN |

### 9.6 验收命令

```bash
# S0: 骨架 + 认证
mvn test -pl gate-web -Dtest=WebAuthTest
# 启动: java -jar gate-web/target/gate-web.jar --config gate.toml
# 浏览器: https://127.0.0.1:4097/  (无 token 401, 有 token 200)
# bind=0.0.0.0 启动应退出 22

# S1: 只读 + 同步
mvn test -pl gate-web -Dtest=WebReadOnlyApiTest

# S2: 异步闸门
mvn test -pl gate-web -Dtest=WebGateEquivalenceTest
# 断言: 权威库 HEAD 前进一个 commit, B1-B19 回归仍全绿
mvn test -pl gate-cli -Dtest=BypassMatrixTest

# S3: AgentConfig + adapter
mvn test -pl gate-web -Dtest=ClaudeHeadlessAdapterTest,OpenCodeServeAdapterTest

# S4: 会话流
mvn test -pl gate-web -Dtest=SessionOrchestrationTest

# S5: 成本回写 + H1 复测
mvn test -pl gate-web -Dtest=SessionCostWritebackTest
mvn test -pl gate-cli -Dtest=H1VerdictTest  # cost ratio 非 NaN

# 全量回归 (闸门层不能回归)
mvn test
```

---

## 10. 阶段划分（S0-S5）

### S0 — gate-web 骨架 + 认证 + 静态资源 + health

- 交付物：`gate-web` 模块、`WebComponents.fromConfig`、JDK HttpServer 启动、HUMAN token 校验 Filter、Origin/Host 白名单、`/api/health`、`/api/auth/verify`、静态资源 fallback。
- 验收命令：见 §9.6 S0（A13）。
- 止损：若 JDK HttpServer 的 SSE 实现成本超 3 天，切换 Javalin（不阻塞 S1）。

### S1 — REST API 只读 + 同步操作

- 交付物：`/api/status`、`/api/tickets`（GET/POST，POST 接管 clone）、`/api/tickets/{no}`、`/api/tickets/{no}/presubmit`、`/api/tickets/{no}/review-result`、`/api/tickets/{no}/presubmit/{round}/diff`、`/api/reconcile`、`/api/metrics`、`/api/metrics/h1`、`/api/config`、`/api/providers`、错误码映射。
- 验收：A14。看板数据能渲染（与前端 S1 对齐）。
- 止损：若 `POST /api/tickets` 的 clone 接管与 CLI 行为不一致（preflight 失败），退回"只 insert 不 clone"（D3 ②），不阻塞看板只读。

### S2 — 审核台异步化（review/publish 走任务 + SSE）

- 交付物：`GateTask`/`TaskRegistry`、V4 `gate_task` 表、`/api/tasks/{id}`、`/api/tasks/{id}/events`（SSE）、`POST /api/tickets/{no}/review`、`POST /api/tickets/{no}/publish` 异步化、`WebGateEquivalenceTest`。
- 验收：A15、A16。**至此 gate 操作台完整，无 agent 会话已可用。**
- 止损：若 SSE 在 JDK HttpServer 上不稳定，降级为轮询 `GET /api/tasks/{id}`（前端改为 2s 轮询），不阻塞闸门闭环。

### S3 — AgentConfig CRUD + 端口定义 + claude adapter

- 交付物：`AgentSessionPort`、领域类型（`AgentConfig`/`Session`/`SessionMessage`/`SessionUsage`）、V4 `agent_config`/`agent_session`/`session_message` 表、`AgentConfigRepository`、`ClaudeHeadlessAdapter`、工单上下文注入（`CLAUDE.md` 生成）、闸门 MCP 配置生成。
- 验收：A17。`start` 建会话，`cliSessionId` 落库，stub 测试通。
- 止损：若 claude `--resume` 不可靠（session-id 复用失败率 > 20%），切到 opencode serve（D4 ①），或降级为"每消息独立会话不续接"（牺牲上下文连续性但保可用）。

### S4 — 会话界面后端（建会话/发消息/SSE/历史/中止）+ 工单绑定

- 交付物：`POST /api/tickets/{no}/sessions`、`POST /api/tickets/{no}/sessions/{sid}/messages`（异步）、`GET /api/tickets/{no}/sessions/{sid}/events`（SSE）、`POST /abort`、`GET /history`、`TicketLockManager` 工单级串行、进程生命周期 shutdown hook + reconcile。
- 验收：A18。SSE 收到 SessionMessage 流，usage 解析正确。
- 止损：若两 adapter（claude + opencode）都做不稳，退化为"会话只读历史 + 手动 CLI 跑 agent"——gate 操作台部分（S0-S2）仍交付，会话编排降级为只读历史视图。

### S5 — 成本回写 + H1 复测

- 交付物：`Session.cumulativeUsage` → `ticket.exec_token_total` 回写、`MetricsService` 确认 cost ratio 非 NaN、`/api/metrics/h1` 升级 basis。
- 验收：A19。H1 从 NaN 变真数字。
- 止损：若 H1 复测仍 degraded（usage 解析失败率高），记录 degraded 原因，不强行降级 classification（保持 PARTIAL，标注"session usage 待补"）。

---

## 11. ADR

| # | 决策 | 理由 | 被否方案 |
|---|---|---|---|
| ADR-10 | Web 会话 token 复用 HUMAN 域，不新增 WEB 域 | HUMAN 语义即「人通过任何客户端操作」；新增 WEB 域徒增管理面且授权语义本属人域 | 新增 WEB 域（D1 ②） |
| ADR-11 | 会话路线 B（结构化接口 stream-json / opencode HTTP API），不做 PTY | ADR-11 闭合 v2 §16 S2 spike——PTY 的交互式输入/Ctrl-C/resize/进程树 kill 在本机验证成本高且非闸门职责；结构化接口足以补 H1 执行侧 usage | 路线 A PTY（xterm.js + pty4j） |
| ADR-12 | 会话 adapter 双实现共用 `AgentSessionPort`，按 `AgentConfig.cli` 分发 | 两 CLI 行为差异不导致误放行（会话层不碰 verdict/publish，只产出消息 + usage）；与 ReviewEngine 双实现共用端口的模式一致 | 单实现锁死一个 CLI |
| ADR-13 | Web 常驻进程需在 FileLock 之上加工单级串行锁 | `FileChannelLockManager` 的 in-process ReentrantLock 只覆盖 `project+targetRef`（publish 路径）；Web 下 presubmit/session 对同一 clone 的并发未被覆盖，必须补 `TicketLockManager` | 仅靠既有 FileLock（漏锁同 JVM 多线程对 clone 的争用） |
| ADR-14 | 单进程单项目首期 | `GateConfig` 单 project，多项目需求未验证；token 域/端口分配/串行锁按单项目最简；多项目留待真实需求 | 单进程多项目（结构允许但首期不投入） |

---

## 12. 风险与止损

| # | 风险 | 触发条件 | 止损动作 |
|---|---|---|---|
| R1 | opencode serve 进程泄漏 | 工单关闭但 serve 未杀，端口耗尽 | shutdown hook + reconcile 扫描 + 端口分配上限告警；超上限拒绝新会话（429） |
| R2 | claude 每消息 spawn 的延迟与 Windows 进程开销 | 单次 sendMessage > 30s，用户体验差 | 优化项（长驻 stream-json 进程）列为 S4 后；首期接受延迟，SSE 显示「agent 启动中」 |
| R3 | 会话与 presubmit 工作区冲突 | session 改文件时 presubmit 固化 tree 拿到半成品 | 工单级串行锁（§7.2）+ presubmit 遇 session 持锁直接 422 |
| R4 | Web 化引入并发锁回归 | B1-B19 有回归 | S2 验收强制跑 `BypassMatrixTest`；任何回归阻塞发布 |
| R5 | H1 复测仍 degraded | usage 解析失败率高 | 记录 degraded 原因，保持 PARTIAL，不强行降级；列出 session usage 待补项 |
| R6 | 两 adapter 都做不稳 | S3/S4 验收不过 | 退化为「会话只读历史 + 手动 CLI 跑 agent」，gate 操作台（S0-S2）仍交付 |

**总止损**：若 S2（闸门操作台异步化）验收不过，**整个本迭代停**——闸门闭环的 Web 化是立项目标，会话编排是增量。闸门 Web 化做不稳则基础不存。

---

## 13. 下一步

1. **审本文档**，重点：§4 路由表的闭环环节标注是否齐全、§5 端口签名是否可接受、§7 工单级串行锁是否认可、§11 ADR-10..14 是否拍板。
2. 决策后开 **S0**（gate-web 骨架 + 认证 + 静态资源 + health），与前端 S0 并行。
3. S0 验收（A13）后开 S1；S2 验收（A15/A16）后 gate 操作台完整可用，再决定 S3-S5 是否进场。
4. 文档更新：本文档定案后更新 `../README.md` 索引（新增本条目 + 前端条目）。

---
*本文档在 `架构落地执行文档.md`（地基，ADR-1..9）之上追加 Web adapter 与 agent 会话编排层，不改写既有闸门契约。H1 degraded basis 的执行侧拼图（§5.7）是本迭代的硬代理由。会话界面 commoditized，审核台是咽喉（§1.4 防蠕变规矩）。*
