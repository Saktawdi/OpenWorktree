# gate-web / gate-web-ui 迭代走读报告

> 走读对象：`D:\project\ai-generate\local-git-ticket-system` 下的新目录 `gate-web`（Maven 后端模块）、`gate-web-ui`（Vue3 前端工程）。
> 对照基线：`../02-执行文档/执行文档-后端-web.md`（S0-S5 阶段约束、REST 路由表、V4 DDL、会话编排端口、锁补强、ADR-10..14）。
> 结论速览：**S0、S1 已完整落地且 20 个测试全绿；S2 异步闸门与 S3-S5 会话编排基本未动，前端 `types/api` 多为占位**。存在若干前后端契约不一致与静态资源路径错位，需在下一迭代处理。

---

## 1. gate-web 现有内容（后端模块）

### 1.1 模块与装配

- Maven 模块：`gate-web/pom.xml`，artifactId `gate-web`，父为 `gate-parent`。
- 根 `pom.xml` 的 `<modules>` 已追加 `<module>gate-web</module>`（第 28 行），与文档 §2.2 一致。
- `gate-web/pom.xml` 依赖 `gate-domain` / `gate-ports` / `gate-application` / `gate-adapters`，并显式声明 `spring-jdbc`、`spring-context`（适配器装配用，Spring MVC/JDBC Web 不用）、`slf4j-api` / `slf4j-simple`；测试用 `junit-jupiter` + `archunit-junit5`。**未引入 Javalin / Spring Boot Web / Jackson / JGit**，与 ADR-8、ADR-1 一致。
- **未配置 `spring-boot-maven-plugin` 的 `<executions>`，也没有 frontend 构建插件**。这直接影响 §6 的运行方式（见第 4 节）。

### 1.2 已落地的 Java 类（全部实际读到的文件）

| 类（`gate.web.*`） | 职责 | 对应执行文档 |
|---|---|---|
| `GateWebApp` | `main` 入口，解析 `--config/--git`，调 `WebComponents.fromConfig` → `WebToken.ensure` → `new WebServer(...).start()`；打印 `GATE_WEB_TOKEN=` 启动日志；将 `GateException.code().code()` 映射到进程退出码（`GateErrorExit.USAGE=64`、`INTERNAL=70`） | §2.2、§2.3、§3.2 |
| `WebComponents` | 手工装配对象图（复刻 `GateComponents.fromConfig` 风格）；构造 `GateService` / `MetricsService` / `TopologyInitializer` / `PreflightChecker` / 各 `Jdbc*Repository` / `BlobStore` / `CredentialRepository` / `Clock`；要求 `config.webConfigured()` 非空才启动；`.env` 锚定到 gate.toml 目录 | §2.2、§2.3 |
| `WebServer` | 包装 JDK `com.sun.net.httpserver.HttpServer`，绑定 `web.bind():web.port()`，16 线程固定池；`/api` 上下文挂 `ApiHandler`，`/` 挂 `StaticHandler`；`AutoCloseable.close()` → `server.stop(0)` | §2.4、§3.3 |
| `AuthFilter` | 每 `!api` 请求：① Host/Origin 白名单（防 DNS rebinding，违例 403）② Bearer token → `CredentialRepository.validate`，仅接受 HUMAN 域（ADR-10），401；SSE 端点（`path.endsWith("/events")`）额外接受 `?token=`（§3.4.1） | §3.3、§3.4、§3.4.1 |
| `ApiHandler` | `/api/*` 路由壳：`/api/health`、`/api/auth/verify` 免 token 白名单内联处理；其余先过 `AuthFilter` 再转 `ApiRoutes.route`；`GateException` → `HttpStatus.forGateError` 映射，非 GateException 一律 500 INTERNAL（fail-closed），响应体统一 `{error_code,error,message,detail}` | §4、§4.4 |
| `ApiRoutes` | **S1** 只读+同步路由实现（详见 1.3） | §10 S1、§4.1 |
| `WebToken` | 启动时若 `human_token_file` 不存在/为空则 `issueHumanToken` 并写文件（POSIX 0600，Windows 依赖默认 ACL），否则读回明文 | §3.2 |
| `StaticHandler` | 从 classpath `/web/**` 提供 SPA 静态资源；找不到文件回退 `index.html`；拒绝 `..` / `\` 路径穿越 | §2.5 |
| `Http` | body/json/`Content-Type` 辅助 + access log 对 `token=` query 脱敏（`maskToken`，§3.4.1） | §3.4.1、§9 |
| `Json` | 无依赖极简 JSON 写出器（仅支持 Map/List/String/Number/Boolean/null） | §4.4 |
| `HttpStatus` | `GateErrorCode` → HTTP 状态码映射（§4.4 表） | §4.4 |

### 1.3 ApiRoutes 实际实现的路由（S1 已落地）

`sroute(method, path, body)` 分发（匹配文档 §4.1 路由表中子集）：

| Method | Path | 已实现 | 文档对应 |
|---|---|---|---|
| POST | `/api/auth/verify` | ✔`ApiHandler.authVerify`（200 HUMAN / 401 / 405 GET） | S0 |
| GET | `/api/health` | ✔`ApiHandler.health`（免 token） | S0 |
| GET | `/api/status` | ✔ 投影 `target_ref`/`auth_tip`/`auth_commit_count`/`tickets[]`，复用 `GateService.status`/`StatusResult` | S1 |
| GET | `/api/tickets` | ✔ 返回 `{tickets:[...]}`，复用 `tickets.findAll()` | S1 |
| GET | `/api/tickets/{no}` | ✔ | S1 |
| POST | `/api/tickets` | ✔（D3 clone 接管：`createClone` + `insert`，返回 201 `{ticket_no,target_ref,clone_path,stage}`） | S1 |
| POST | `/api/tickets/{no}/presubmit` | ✔（同步，返回 `PresubmitResult` 投影） | S1 |
| GET | `/api/tickets/{no}/review-result` | ✔（blob 回读 findings） | S1 |
| GET | `/api/tickets/{no}/presubmit/{round}/diff` | ✔（blob 回读 diff 文本） | S1 |
| POST | `/api/reconcile` | ✔（`ReconcileResult.outcomes`） | S1 |
| GET | `/api/metrics` | ✔（`MetricsService.export`） | S1 |
| GET | `/api/metrics/h1` | ✔（`MetricsService.verdict`，NaN→null） | S1 |
| GET | `/api/config` | ✔（脱敏；**无** api_key/base_url） | S1 |
| GET | `/api/providers` | ✔ | S1 |
| GET | `/api/tasks/{id}` / `/events` | ✘ 未实现 | S2 |
| POST | review / publish（异步 202） | ✘ 未实现 | S2 |
| session / agent-configs 全部 | ✘ 未实现 | S3-S4 |

**S2（review/publish 异步化 + TaskRegistry + `gate_task` 表 + task SSE）在 gate-web / gate-domain / gate-adapters 中均无代码**：全文 grep `TaskRegistry` / `GateTask` 仅在注释中出现；`gate.web`、`gate.domain.task`、`gate.ports.TaskRegistry` 均不存在。

### 1.4 AgentSessionPort 实现与存储（S3-S5）—— 完全缺失

执行文档 §5 指定的整套会话编排在代码库中**找不到任何实现文件**（grep `AgentSessionPort`、`SessionMessage`、`agent_session`、`OpenCodeServe`、`ClaudeHeadless`、`TicketLockManager`、`PortAllocator`、`V4__` 无命中）：

- `gate.ports.AgentSessionPort` 端口：不存在。
- `gate.domain.session` 领域类型（`AgentConfig`/`Session`/`SessionMessage`/`SessionUsage`/`ToolCall`/`Role`/`AgentCli`）：不存在。当前文件里名为"AgentConfig"的只有 `GateConfig.AgentConfigDefaults`（配置默认值 record，`gate-domain/.../GateConfig.java`），与文档 §5.2 的会话 `AgentConfig` 是两回事。
- 两个 adapter（`OpenCodeServeAdapter` / `ClaudeHeadlessAdapter`）：不存在。
- `TicketLockManager` / `FileChannelTicketLockManager` / `PortAllocator`：不存在（§7 工单级串行锁、端口分配未实现）。
- **V4 DDL 未提交**：`gate-adapters/src/main/resources/db/migration/` 目前只有 `V1__init.sql`、`V2__credentials.sql`、`V3__cost_metrics.sql`，**没有 `V4__agent_sessions.sql`**。`agent_config`/`agent_session`/`session_message`/`gate_task` 四张新表与 `ticket.agent_config_id` 列均不存在。
- 配置段已铺好但无消费者：`GateConfig` 已含 `web`/`session`/`agent` 三段（`WebConfig`/`SessionConfig`/`AgentConfigDefaults`，`CURRENT_SCHEMA_VERSION=2`，`webConfigured()`），`TomlGateConfigLoader.build` 已能解析 `[web]/[session]/[agent]`（`gate-adapters/.../TomlGateConfigLoader.java` 第 200-233 行）——但只有 `[web]` 被 gate-web 消费，`[session]`/`[agent]` 尚无任何组件读取。

### 1.5 测试现状（S0/S1 验收）

`src/test/java/gate/web/` 下 `WebHarness`（用真实 git、真实适配器搭临时工程）驱动以下集成测试（`target/surefire-reports/*.txt` 显示全绿）：

| 测试类 | 数量 | 验收点 |
|---|---|---|
| `WebAuthTest` | 6 | **A13**：health 免 token 200、无 token 401、有效 HUMAN token 200、`/auth/verify` 200/401、错 Origin 403、`web.bind=0.0.0.0` 构造时拒绝 |
| `WebReadOnlyApiTest` | 7 | **A14**：status/tickets/metrics/metrics-h1/config/providers 结构化 JSON、`POST /api/tickets` 建 clone+insert、presubmit 同步+diff 回读、config 脱敏、reconcile、review-result |
| `WebErrorMappingTest` | 5 | 错误信封/状态码：404、USAGE→400、REJECT_PRECONDITION→422、auth/verify 405 |
| `WebArchTest` | 2 | `gate.web` 不得依赖 `gate.cli`；不得引入 `org.eclipse.jgit` |

合计 **20 测试全绿**。但文档 §9.5 的 A15/A16（S2）、A17（S3）、A18（S4）、A19（S5）以及 `WebGateEquivalenceTest`、`ClaudeHeadlessAdapterTest`、`OpenCodeServeAdapterTest`、`SessionOrchestrationTest`、`SessionCostWritebackTest`、`TicketLockManagerTest`、`PortAllocatorTest`、`WebSseTest` 均**不存在**。

---

## 2. gate-web-ui 现有内容（前端工程）

### 2.1 工程类型与依赖

- `gate-web-ui/package.json`：Vue 3.5 + TypeScript + Vite 5 + Naive UI 2.40 + Pinia 2 + vue-router 4 + axios；devDeps 含 `vite`、`vue-tsc`、`vitest`、`@vue/test-utils`、`jsdom`、`msw`。**是完整脚手架 + 部分页面，非纯模板**。
- `vite.config.ts`：别名 `@→./src`；`server.port=5173 strictPort`；代理把 `/api/.*/events` 转发到 SSE target（默认 mock `127.0.0.1:4098`），普通 `/api` 转发到真后端 `127.0.0.1:4097`；`build.outDir='../gate-web/src/main/resources/static'`（见第 3 节错位），`emptyOutDir:true`。
- `main.ts`：`VITE_ENABLE_MSW!=='false'` 时启动 MSW worker（`mocks/browser.ts`），`VITE_ENABLE_MSW=false` 关 mock 切真后端。
- 三条 mock 脚本：`mocks/browser.ts`、`mocks/handlers.ts`（假后端：`/auth/verify`、`/health`、`/status` 等）、`mocks/sse-server.mjs`（Node SSE server on 4098）；`package.json` 有 `mock:sse`、`dev`、`build`（`vue-tsc --noEmit && vite build`）、`test`、`test:ui`、`typecheck` 脚本，未找到该工程的 vitest 用例文件（`*.test.*` / `*.spec.*` 未在 `src/` 出现）。

### 2.2 页面清单（哪些是完整页面、哪些是占位）

| 路径 | 组件 | 状态 |
|---|---|---|
| `/login` | `LoginView.vue` | **完整**：token 输入 → POST `/auth/verify` → `setToken` → 跳转；401/403 本地提示 |
| `/`（AppLayout 内 home） | `HomeView.vue` | **完整（S1 看板）**：stat 行 + 项目卡片网格 + 空态；读 `GET /status`（targetRef/authTip/authCommitCount/tickets），统计进行中/待审核；空项目提示 `gate init`。卡片点击 toast「S1 实现」— **尚未跳到工单看板** |
| 壳 | `AppLayout.vue` | 侧栏：工单看板（可用）、审核台/会话/成本（`disabled` 占位，点击警告「S1 实现」）；退出登录 |
| — | `HomeView` 由路由 `meta.closedLoop='status'` 标注 | — |

`router/index.ts` 路由表当前仅 `login` + `home` 两条实际路由，兜底 `/:pathMatch` → `/`。**没有**工单看板、审核台、会话页、成本页的路由（`AppLayout` 导航项均为 disabled 占位）。

### 2.3 依赖 / 类型层（多数为 S2-S5 的占位契约）

- `src/api/`：`client.ts`（axios 拦截器：注入 `Authorization: Bearer`，401/403 清 token+跳登录，业务错误经 `formatGateError`）、`auth.ts`（`verifyToken`/`health`）、`status.ts`（`getStatus`）、`tickets.ts`（`listTickets`/`getTicket`/`createTicket`）、`sessions.ts`（sessions 全套）、`tasks.ts`（`getTask`/`taskEventsPath`）、`review.ts`（`startReview`/`startPublish`，占位 202）、`index.ts`（re-export）。
- `src/composables/useSSE.ts`：完整的 EventSource 封装（`?token=` query 注入、指数退避重连 1s→30s、命名事件分发、卸载自动 close），注释声明服务 S2（task SSE）与 S3+（session SSE）。
- `src/types/`：`errors`/`stage`/`ticket`/`decision`/`session`/`session-message`/`agentConfig`/`metrics`/`task`/`index`，定义了完整的 `Session`/`SessionMessage`/`AgentConfig`/`GateTask` 等类型，**但多数当前无后端实例消费**。
- `src/utils/`：`errorCodeMap.ts`（GateErrorCode→中文）、`format.ts`（如 `truncateMiddle`）。
- `src/stores/authStore.ts`：token 持久化 `localStorage.gate_token`，`isAuthenticated`/`tokenDigest`。

`App.vue` 挂 Naive UI 深色主题（主色 `#0C5CAB`，通过绿/待人工橙/驳回红对应 P1 语义），`NConfig/NMessage/NDialog/NLoadingBar` Provider，并注入 router/client 的 auth/business 错误处理器。

---

## 3. 前后端契约对应情况

### 3.1 一致的契约

- 认证：`POST /api/auth/verify`、`GET /api/health` 免 token，`Authorization: Bearer`；SSE 端点 `?token=` 双通道 —— 后端 `AuthFilter` 与前端 `useSSE` 实现完全对齐（含 URL `?token=` 拼装、Vite 代理正则容忍 query）。
- `/api/status`：前端 `StatusResult` 与后端 `StatusResult` record 投影（`targetRef/authTip/authCommitCount/tickets[]` 及 `TicketStatus` 各字段）字段一致。
- 错误信封：前端 `GateErrorResponse`/`formatGateError` 认后端 `{error_code,error,message,detail}`；后端 `ApiHandler`+`Json.error` 一致。
- `/api/metrics`、`/api/metrics/h1`、`/api/config`、`/api/providers` 字段与前端 `types/metrics.ts` 对应。

### 3.2 明显不一致（需下一迭代修正）

1. **Ticket 形状不匹配（最要紧）**：
   - 前端 `types/ticket.ts` 的 `Ticket` 用 `no`、`targetRef`、`reviewRound`、`treeHash`、`agentConfigId`；`CreateTicketRequest` 用 `{title, description, targetRef, agentConfigId?}`。
   - 后端 `ApiRoutes.ticketCreate` 期望 body `{ticket_no, title}`，`ticketJson` 输出 `ticket_no`/`title`/`target_ref`/`clone_path`/`stage`/`exec_token_total`/…（snake_case，且无 `reviewRound`/`treeHash` 派生字段）。
   - `listTickets()` 期望返回裸数组，但后端返回 `{"tickets":[...]}`。
   - 结论：**前端 tickets/sessions 页面所需的 `Ticket` 字段与后端 S1 实际 JSON 不一致**；若 S1 接入看板列表必须先对表或拆 DTO。

2. **静态资源路径错位（S0 的一等缺口）**：
   - 文档 §2.5 约定产物放 `gate-web/src/main/resources/web/`（`StaticHandler.ROOT="/web"` 从 classpath `/web/**` 读）。
   - 但 `vite.config.ts` `outDir` 是 `../gate-web/src/main/resources/static`，构建产物全在 `static/` 下。
   - 于是 `resources/web/` 下只有那个**独立登录页 `index.html`**（内联脚本手写，非 Vue SPA、不带 `/assets/*`）。`StaticHandler` 的根是 `/web`，**读不到 `static/` 下的真实 Vue bundle**。即：现在能打开的"Gate 操作台"其实是那个占位静态登录页，Vue SPA（HomeView 看板）未被后端托管。（`target/classes/web/index.html` 是编译期复制的同款占位页。）

3. **异步与 session 路由后端缺位，前端已备契约**：
   - 前端 `review.ts` 已经按 202+`{task_id}`/`{taskId}` 写好 `startReview`/`startPublish`，`tasks.ts` 写了 `getTask`/`taskEventsPath`，`sessions.ts` 写了完整会话 API、`useSSE` 就绪 —— 但后端 `POST .../review|publish`、`GET /api/tasks/*`、session 全部路由**尚未实现**，目前调用会 404（错误信封）。
   - `session-message.ts` / `agentConfig.ts` 前端类型 shape 需与后端未来 `AgentSessionPort`/领域 record 对齐，暂无法校验。

---

## 4. 编译 / 运行现状

### 4.1 编译

- **gate-web 可编译，且已编译、测试通过**：`target/classes/gate/web/*.class` 与 `target/maven-status/.../createdFiles.lst` 存在；`target/surefire-reports` 记录 4 个测试类 20 个用例全绿（含真实 git 的集成测试）。只要 `gate-adapters` 等上游模块能构建，`mvn -pl gate-web -am install` 可复现。
- **gate-web-ui 前端可构建**：构建脚本 `vue-tsc --noEmit && vite build` 存在；`gate-web/src/main/resources/static/` 下已有一次构建产物（`index.html` + `assets/*.js/.css`、`mockServiceWorker.js`），证明 `vite build` 已成功跑过一次。
- **整仓 `mvn test`**：`gate-web` 不依赖 `gate-cli`（ArchUnit 已校验），无新增缺口阻断全仓编译；S2-S5 的缺失是"未实现"，不是"坏了"。

### 4.2 运行

- 文档 §9.6 的命令是 `java -jar gate-web/target/gate-web.jar --config gate.toml`。但 `gate-web/pom.xml` **没有配置 `spring-boot-maven-plugin` 的 repackage execution**，现产出的 `gate-web-0.1.0-SNAPSHOT.jar` 仅 28,750 字节（瘦 jar，无内嵌依赖），且无 frontend 插件把 Vue dist 拷进 jar。因此：
  - `java -jar` 大概率报"no main manifest attribute"或 ClassNotFound（依赖不在瘦 jar 内）；
  - 需改为 `mvn package` 产物配 classpath 运行，`java -cp gate-web.jar:<适配器/应用/域/各依赖> gate.web.GateWebApp --config ...`，或补一个 boot repackage/frontend-maven-plugin。
- 即便启动成功，`/` 经 `StaticHandler`（root `/web`）也只返回占位登录页，Vue SPA 不被托管（见 §3.2.2）。

### 4.3 需要一个 `gate.toml`

文档 §8.1 新增 `[web]/[session]/[agent]`、`schema_version=2`。`GateConfig` 已要求 `schema_version==2` 且未知键 fail-closed；`TomlGateConfigLoader` 已能解析三段；仅 `[web]` 必须非空才启动。运行前需有 schema_version=2 且含 `[web]` 的 gate.toml（当前仓库未发现提供该样例的 gate.toml，需自行准备）。

---

## 5. 明显缺口与下一步建议

按 `../02-执行文档/执行文档-后端-web.md` §10 阶段划分，逐阶段对照实际代码：

| 阶段 | 文档目标 | 实际状态 | 缺口 |
|---|---|---|---|
| **S0** | gate-web 骨架 + 认证 + 静态资源 + health | ✔ 代码/测试齐全（`WebAuthTest` 6 绿） | **SPA 静态托管错位**（`static/` vs `/web`）、**可执行 jar 缺失** |
| **S1** | 只读 + 同步 REST + 错误码映射 | ✔ `ApiRoutes` + `WebReadOnlyApiTest` 7 绿 + 错误映射 | **前端 `Ticket` 契约与后端 JSON 不一致**；`POST /api/tickets` body 字段（`ticket_no,title` vs 前端 `title,description,targetRef,agentConfigId`）需统一 |
| **S2** | review/publish 异步 + `GateTask`/`TaskRegistry` + `gate_task` 表 + task SSE + `WebGateEquivalenceTest` | ✘ 未实现 | 端口、任务表、SSE、等价性测试全缺 |
| **S3** | `AgentSessionPort` + 领域类型 + V4 四表 + `AgentConfigRepository` + `ClaudeHeadlessAdapter`/`OpenCodeServeAdapter` + 上下文注入/MCP 生成 | ✘ 未实现 | 以上全部；`[session]`/`[agent]` 配置无消费者 |
| **S4** | session 路由 + `TicketLockManager` + 进程生命周期 + shutdown hook/reconcile | ✘ 未实现 | session HTTP 层、工单级串行锁、孤儿进程清理 |
| **S5** | usage→`ticket.exec_token_total` 回写、H1 复测（cost ratio 非 NaN） | ✘ 未实现 | 成本回写逻辑（前端 `types/metrics.ts` 已备） |

### 建议的下一步（按优先级）

1. **修复静态资源托管 + 可执行打包（S0 收尾）**
   - 统一产物目录：二选一——把 `vite.config.ts` 的 `outDir` 改回 `resources/web`（对齐文档与 `StaticHandler.ROOT=/web`），或把 `StaticHandler.ROOT` 改为 `/static`/`/`。当前二者错位导致 Vue SPA 无法被后端托管。
   - 在 `gate-web/pom.xml` 配置 `spring-boot-maven-plugin`（repackage execution）或 frontend-maven-plugin，使 `java -jar gate-web.jar --config gate.toml` 在 §9.6 命令下可用；瘦 jar 现状是运行入口缺口。

2. **统一前后端 Ticket 契约（S1 对表）**
   - 建一个与后端 `ApiRoutes.ticketJson` 输出一致的共享 DTO 描述（前后端文档各改），修掉 `no` vs `ticket_no`、camelCase vs snake_case、裸数组 vs `{tickets:[...]}`、`CreateTicketRequest` 字段差异；否则前端工单列表一接入即错。

3. **实现 S2（异步闸门）** —— 这是文档 §12 明确"如果 S2 验收不过，整个本迭代停"的总止损底线：`GateTask`/`TaskRegistry`/`gate_task` 表、`POST .../review|publish`→202、task SSE、`WebGateEquivalenceTest` + B1-B19 回归；前端 `review.ts`/`tasks.ts`/`useSSE` 已就绪，只差后端。

4. **再进入 S3-S5 会话编排**：落 `AgentSessionPort` + 领域类型 + `V4__agent_sessions.sql` + 两个 adapter + `[session]` 配置消费 + `TicketLockManager`/`PortAllocator` + 成本回写，随后补对应测试。

5. **补齐前端页面**：工单看板列表/详情、审核台（review/publish + task SSE）、会话页、成本页路由（`AppLayout` 导航项目前 disabled）。前端 API/types 大多是占位，需在对应阶段解锁并对照后端字段。

---

## 附：走读取证的文件索引（全部为实际读到的文件）

- **后端 `gate-web/src/main/java/gate/web/`**：`GateWebApp`、`WebComponents`、`WebServer`、`AuthFilter`、`ApiHandler`、`ApiRoutes`、`StaticHandler`、`WebToken`、`Http`、`Json`、`HttpStatus`。
- **后端测试 `gate-web/src/test/java/gate/web/`**：`WebHarness`、`WebAuthTest`、`WebReadOnlyApiTest`、`WebErrorMappingTest`、`WebArchTest`（target/surefire 全绿）。
- **配置/领域**：`gate-domain/.../config/GateConfig.java`（含 `WebConfig`/`SessionConfig`/`AgentConfigDefaults`、`webConfigured`）、`gate-adapters/.../config/TomlGateConfigLoader.java`（`[web]/[session]/[agent]` 解析）、`gate-adapters/src/main/resources/db/migration/{V1,V2,V3}*.sql`（**无 V4**）。
- **前端 `gate-web-ui/`**：`package.json`、`vite.config.ts`、`src/main.ts`、`src/App.vue`、`src/router/index.ts`、`src/views/{LoginView,HomeView,AppLayout}.vue`、`src/api/{client,auth,status,tickets,sessions,tasks,review,index}.ts`、`src/composables/useSSE.ts`、`src/stores/authStore.ts`、`src/types/*.ts`、`src/utils/*.ts`、`mocks/{browser,handlers}.ts`、`mocks/sse-server.mjs`。
- **后端资源**：`gate-web/src/main/resources/web/index.html`（占位独立登录页）、`gate-web/src/main/resources/static/{index.html,assets/*}`（Vue 构建产物，与 StaticHandler 根路径错位）。
- **文档**：`root pom.xml`（`<module>gate-web</module>`）、`../02-执行文档/执行文档-后端-web.md`。
