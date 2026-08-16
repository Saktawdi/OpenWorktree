# 后端 Web 迭代验收/收尾报告

> 状态：已验收
> 日期：2026-08-16
> 范围：`doc/02-执行文档/执行文档-后端-web.md` 定义的 S0–S5（Web 操作台 + agent 会话编排）
> 上游：`doc/归档/done/架构落地执行文档.md`（P0–P4 地基）
> 平行：`doc/02-执行文档/执行文档-前端-web.md`

---

## 1. 结论

**后端 Web 迭代 S0–S5 全部完成并通过验收。**

- 全量回归：**140 tests green**
  - gate-cli：98 tests（含 B1–B19 绕过矩阵、架构约束、H1 判定）
  - gate-web：42 tests（S0–S5 新增 Web/会话/锁/成本测试）
- BypassMatrix B1–B19 无回归。
- 新增 V4 迁移已落地：`agent_config` / `agent_session` / `session_message` / `gate_task`，并给 `ticket` 增加 `agent_config_id`。

---

## 2. 验收判据逐条核对（A13–A19）

| 验收 | 判据 | 证据 | 结果 |
|---|---|---|---|
| A13 | 浏览器打开 `/`，无 token 访问 `/api/status` 401；输入 token 后 200；`bind=0.0.0.0` 启动失败退出 22 | `WebAuthTest`（6 tests） | ✅ |
| A14 | `/api/status` `/api/tickets` `/api/metrics` 返回结构化 JSON；`POST /api/tickets` 建 clone 并 insert | `WebReadOnlyApiTest`（7 tests） | ✅ |
| A15 | `POST /api/tickets/{no}/review` 返回 202 + task_id；SSE 收到 progress + done；结果落 `review_result` | `TaskRegistryTest`（5）、`WebSseTest`（3）、`WebGateEquivalenceTest`（3） | ✅ |
| A16 | `POST /api/tickets/{no}/publish` 同上；权威库 HEAD 前进；等价性断言只增一个 commit | `WebGateEquivalenceTest` | ✅ |
| A17 | `/api/agent-configs` CRUD 通；`AgentSessionPort.start` 建 claude/opencode 会话，`cliSessionId` 落库 | `AgentConfigApiTest`（1）、`ClaudeHeadlessAdapterTest`（2）、`OpenCodeServeAdapterTest`（1）、`PortAllocatorTest`（2） | ✅ |
| A18 | 建会话/发消息/SSE/历史/中止；SSE 收到 SessionMessage 流；usage 解析正确 | `SessionOrchestrationTest`（1）、`ConcurrentPresubmitAndSessionTest`（1）、`TicketLockManagerTest`（2） | ✅ |
| A19 | 会话结束后 `ticket.exec_token_total` 非 null，`exec_token_source='agent_cli'`；`cost ratio` 非 NaN | `SessionCostWritebackTest`（1） | ✅ |

---

## 3. 交付物清单

### 3.1 新增/修改模块

| 模块 | 内容 |
|---|---|
| `gate-web` | JDK HttpServer Web 操作台；认证 Filter；REST 路由；SSE；TaskRunner；静态 SPA |
| `gate-domain` | `task` 包（GateTask/GateTaskStatus）；`session` 包（AgentConfig/Session/SessionMessage/SessionUsage 等） |
| `gate-ports` | `TaskRegistry`、`AgentSessionPort`、`AgentConfigRepository`、`SessionRepository`、`TicketLockManager`；扩展 `TicketRepository` |
| `gate-adapters` | `JdbcGateTaskRepository`、`JdbcAgentConfigRepository`、`JdbcSessionRepository`、`ClaudeHeadlessAdapter`、`OpenCodeServeAdapter`、`DispatchAgentSessionPort`、`PortAllocator`、`FileChannelTicketLockManager` |
| 迁移 | `V4__agent_sessions.sql` |

### 3.2 核心 REST 能力

- 认证与安全：`/api/auth/verify`、`/api/health`、Bearer/query token、Host/Origin 白名单
- 闸门操作台：status / tickets / presubmit / review / publish / reconcile / metrics / config / providers
- 异步任务：review/publish/session-send 统一 `GateTask` + SSE
- 会话编排：AgentConfig CRUD、工单绑定会话、消息收发、历史、SSE、abort
- 并发：工单级串行锁；session 持锁时 presubmit 快速 422
- 成本回写：agent_cli token 写回 `ticket.exec_token_total`

---

## 4. 实现决策与 ADR 遵守

| 决策 | 落地 |
|---|---|
| ADR-10 | Web 会话复用 HUMAN 域 token，不新增 WEB 域 |
| ADR-11 | 会话走结构化接口（stream-json / opencode HTTP），不做 PTY |
| ADR-12 | Claude + OpenCode 双 adapter 共用 `AgentSessionPort`，按 `AgentCli` 分发 |
| ADR-13 | 在 FileLock 之上补 `TicketLockManager` 工单级串行锁 |
| ADR-14 | 单进程单项目首期 |

D1–D7 均已按文档选择落地（D2 首期 Web 不接管 agent token 签发；D4 先 claude、opencode 第二实现；D5 动态端口；D7 degraded 标注入库）。

---

## 5. 测试统计

| 模块 | 数量 | 说明 |
|---|---|---|
| gate-cli | 98 | 含 BypassMatrix 18、Architecture 7、H1Verdict 14 等 |
| gate-web | 42 | Web/会话/锁/成本/SSE/等价性 |
| **合计** | **140** | 全部通过 |

### 关键回归命令

```bash
# 全量回归
mvn test

# 闸门层绕过矩阵（不得回归）
mvn -pl gate-cli test -Dtest=BypassMatrixTest

# Web 层关键测试
mvn -pl gate-web -am test
```

---

## 6. 提交记录

```
35f6d33 S3-S5 补齐: OpenCode adapter + PortAllocator + 会话 reconcile + 并发锁/H1 测试
0b4a078 S4+S5: session REST/SSE/abort + ticket lock + cost writeback (A18/A19)
d2c11e0 S3: AgentConfig CRUD + session domain + ClaudeHeadlessAdapter (A17)
d444632 S2: async review/publish tasks + SSE (A15/A16)
3ca0643 Web iteration: S0+S1 gate-web checkpoint (A13/A14)
```

---

## 7. 已知偏差 / 残余说明

1. **未审直接 publish 的 HTTP 语义**：`POST /publish` 统一按 §4.1 返回 `202 + task_id`；前置校验失败以 `task.errorJson` 呈现（现有 `GateService` 为 `USAGE/64`），不在 HTTP 响应层返回 422。等价性测试断言权威库 HEAD 不变。
2. **会话 SSE 当前为回放式**：`streamEvents` 回放已持久化消息；实时推送可后续在 `SessionRepository` 事件总线上扩展，不阻塞现有验收。
3. **OpenCode adapter**：测试使用 fake HTTP server；真实 `opencode serve` 需本机安装 opencode，且在配置了 executable 时会 spawn 进程。
4. **Web 不签发 agent token**（D2 ①）：agent 域 token 仍由 `gate mcp issue-token --ticket T` 签发。
5. **前端联调**：本报告只覆盖后端验收；前端 `gate-web-ui` 与后端 S2+ 的联调由前端执行文档另行跟踪。

---

## 8. 总评

后端 Web 迭代已达到 `doc/02-执行文档/执行文档-后端-web.md` 的 S0–S5 全部验收标准，且未破坏 P0–P4 闸门层契约。可进入前端联调与后续运营收尾。
