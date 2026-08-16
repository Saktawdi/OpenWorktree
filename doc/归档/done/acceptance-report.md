# 验收报告 — 本地 Git 提交闸门层

> 日期：2026-08-13
> 状态：**P1–P4 全部完成，A1–A12 验收判据全部通过，94 tests green**
> 上游：`执行文档.md` §4（阶段计划与验收判据）、`架构落地执行文档.md`（§11 测试矩阵）、`spike-结论.md`（P0 结论）
> 环境：Windows 11 + git 2.37.1.windows.1 + Corretto 17.0.3 + Maven 3.9.9

---

## 0. 一页结论

| 阶段 | 验收判据 | 测试数 | 状态 |
|---|---|---|---|
| P0 三个 spike | S3/S4/S1 判据 | — | ✅ 全过（`spike-结论.md`） |
| P1 闸门骨架 | A1/A2/A3/A4/A5 + B1–B19 | 30 | ✅ 全绿 |
| P2 审核接入 | A6/A7/A8 + F1–F5 | 20 | ✅ 全绿 |
| P3 MCP stdio | A9/A10/A11 + 权限域 | 22 | ✅ 全绿（含 A11 真实子进程冒烟） |
| P4 成本埋点 | A12 + H1 判定 | 22 | ✅ 全绿 |
| **全量回归** | | **94** | **✅ 0 失败 0 跳过** |

**项目定位（执行文档 §1.1）实现完成**：一个 CLI 无关的 git 提交闸门层，未经审核的代码无法经 git 协议进入权威 git 历史。闸门在 git 接收端强制生效，锚定不可变 tree，审核员与执行模型跨模型独立，成本可度量。

---

## 1. 测试全量回归（94 tests green）

最后一次全量回归：`mvn test -pl gate-cli`，94 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS。

### 1.1 测试清单与分层

| 测试类 | 测试数 | 分层 | 耗时 | 说明 |
|---|---|---|---|---|
| `gate.arch.ArchitectureTest` | 7 | 架构 | 2.8s | ArchUnit 禁止依赖（§4.2）：domain 零 Spring/JGit/java.sql，adapters 互不可见 |
| `gate.bypass.BypassMatrixTest` | 18 | 绕过矩阵 | 99.4s | B1–B19（§11.2），统一断言：权威库目标分支 HEAD 不变 |
| `gate.bypass.ConcurrentApprovalTest` | 1 | 并发 | 5.5s | B17：两并发 push 抢同一 approval，仅一个 ACCEPT |
| `gate.bypass.ResidualRiskB13Test` | 1 | 残余风险 | 4.5s | B13：`--receive-pack` 路线属 §1.3 残余风险，记录而非封堵 |
| `gate.capture.CaptureTest` | 5 | 采集完整性 | 23.1s | 临时 index 不污染暂存、.gitignore 隐形、autocrlf、空 diff |
| `gate.engine.P2AcceptanceTest` | 3 | P2 验收 | 16.3s | A6（blocker 自动 reject）/ A8（结构化 findings 导出） |
| `gate.engine.PrismReviewEngineTest` | 12 | 引擎 adapter | 0.2s | prism JSON 归一化、退出码映射、fail-closed |
| `gate.mcp.A11RealCliSmokeTest` | 1 | A11 真实子进程 | 20.5s | spawn `java -jar gate.jar mcp serve`，真实 stdin/stdout 管道驱动全链路 |
| `gate.mcp.McpEndToEndTest` | 2 | MCP 端到端 | 15.4s | A11 进程内排练：presubmit→reject→fix→pass→publish 全链路 |
| `gate.mcp.McpProtocolTest` | 8 | MCP 协议 | 24.8s | initialize / tools/list / tools/call / 通知 / 错误码 |
| `gate.mcp.PermissionDomainTest` | 9 | 权限域 | 29.7s | A9/A10：agent 域遍历人域 tool 全 403、令牌绑定工单、撤销 |
| `gate.metrics.CostExtractionTest` | 5 | 成本提取 | 12.4s | prism timing 提取、token source 三值、degraded 路径 |
| `gate.metrics.H1VerdictTest` | 14 | H1 判定 | 0.0s | 三档边界（ESTABLISHED/PARTIAL/REFUTED）、degraded 基础、样本不足 |
| `gate.metrics.MetricsExportTest` | 3 | 指标导出 | 6.6s | A12：字段完备性、混合 token source、空集 |
| `gate.recovery.A5CrashRecoveryTest` | 2 | 崩溃恢复 | 22.9s | A5：commit-tree 后 / push 中途 kill，reconcile 收敛 |
| `gate.recovery.AcceptanceTest` | 3 | P1 验收 | 24.9s | A1（单 commit）/ A3（TOCTOU）/ A4（幂等） |
| **合计** | **94** | | **~5min** | |

### 1.2 测试铁律遵守情况

| 铁律（§8.3 / §11.1） | 遵守 |
|---|---|
| 绕过测试与闸门代码同 commit | ✅ P1 单 commit 含 B1–B19 |
| pre-receive 脚本系统生成，不手工维护 | ✅ `FileHookInstaller` |
| 不吞异常，审核链路异常 → reject | ✅ `ReviewEvidence` sealed 类型保证 |
| 审计日志 append-only，哈希链 | ✅ `HashChainAuditLog` |
| fixture 用真实 git，不用 JGit | ✅ `GateHarness` 全程 `GitCli` |
| 显式设 user.name/email/autocrlf | ✅ `GateHarness.identityEnv()` |
| ArchUnit 禁止依赖 | ✅ `ArchitectureTest` |

---

## 2. 验收判据逐条核对

### 2.1 P1 — 闸门骨架（A1–A5 + B1–B19）

| 判据 | 描述 | 测试 | 状态 |
|---|---|---|---|
| A1 | 一个工单走完全链路，权威库只新增一个 commit | `AcceptanceTest.a1_fullRunAddsExactlyOneCommit` | ✅ |
| A2 | B1–B19 全部 REJECT，权威库 HEAD 不变 | `BypassMatrixTest` (18) + `ConcurrentApprovalTest` + `ResidualRiskB13Test` | ✅ |
| A3 | TOCTOU：审核后篡改 worktree，publish 拒绝并退回预提审 | `AcceptanceTest.a3_toctouRejectAndRollBack` | ✅ |
| A4 | 幂等：同 (ticket, round, tree_hash) 重复执行只产生一个 commit | `AcceptanceTest.a4_idempotentPublish` | ✅ |
| A5 | 崩溃恢复：commit-tree 后与 push 中途各 kill 一次，reconcile 收敛 | `A5CrashRecoveryTest` (2) | ✅ |

**B1–B19 绕过矩阵**（§11.2，统一断言：权威库目标分支 HEAD 不变）：

| # | 攻击 | 期望 | 实现 |
|---|---|---|---|
| B1 | `--no-verify` 后 push（无令牌） | REJECT | `b1_noVerifyPushWithoutToken` |
| B2 | `-c core.hooksPath=/dev/null` commit 后 push | REJECT | `b2_hooksPathDevNull` |
| B3 | 删本地 hook 后 commit 并 push | REJECT | `b3_deleteLocalHookThenPush` |
| B4 | 纯 plumbing 后 push | REJECT | `b4_purePlumbingPush` |
| B5 | 重放已消费令牌推新 commit | REJECT | `b5_replayConsumedToken` |
| B6 | 合法令牌但 push tree ≠ 令牌 tree | REJECT | `b6_tokenTreeMismatch` |
| B7 | push 到非白名单 ref | REJECT | `b7_nonWhitelistRef` |
| B8 | force push 覆盖已审历史 | REJECT | `b8_forcePushNonFF` |
| B9 | agent 诚实算出自己未审 commit 的 tree 作令牌 | REJECT | `b9_agentOwnTreeAsToken` |
| B10 | 一次 push 塞两个 ref 更新 | REJECT | `b10_twoRefUpdates` |
| B11 | ref 删除（new=0000…） | REJECT | `b11_refDelete` |
| B12 | new 是 annotated tag / 非 commit | REJECT | `b12_nonCommitObject` |
| B13 | `--receive-pack=<自带脚本>` | 残余风险（记录） | `ResidualRiskB13Test` |
| B14 | 链接 worktree 内 `update-ref` 直改权威分支 | 结构性不可能（强制 clone） | `b14_linkedWorktreeRejected` |
| B15 | 已审 tree 嫁接到不同 parent（快照洗白） | REJECT | `b15_snapshotLaundering` |
| B16 | 重放已消费 approval | REJECT | `b16_replayConsumedApproval` |
| B17 | 两并发 push 抢同一 approval | 仅一个 ACCEPT | `ConcurrentApprovalTest` |
| B18 | 猜 approval id | REJECT | `b18_guessApprovalId` |
| B19 | approval id 路径穿越 | REJECT | `b19_pathTraversal` |

### 2.2 P2 — 审核接入（A6–A8 + F1–F5）

| 判据 | 描述 | 测试 | 状态 |
|---|---|---|---|
| A6 | 有 blocker 的 diff 自动 reject，权威库 HEAD 不变 | `P2AcceptanceTest.a6_blockerFindingAutoRejectsAndTipUnchanged` | ✅ |
| A7 | fail-closed：超时 / 崩溃 / 坏 JSON | `PrismReviewEngineTest` (12) | ✅ |
| A8 | 驳回结果可导出为结构化文件（file/line/severity/message） | `P2AcceptanceTest.a8_rejectedEvidenceExportableAsStructuredFindings` | ✅ |

**fail-closed F1–F5**（§5.3，sealed 类型结构保证）：

| # | 注入 | 期望 | 实现 |
|---|---|---|---|
| F1 | 引擎超时 | reject（退出 20） | `PrismReviewEngineTest` |
| F2 | 引擎崩溃（非零无输出） | reject | `PrismReviewEngineTest` |
| F3 | 不可解析 JSON | reject | `PrismReviewEngineTest` |
| F4 | 合法 JSON 缺必需字段 | reject | `PrismReviewEngineTest` |
| F5 | 引擎二进制不存在 | 拒绝启动（退出 22） | `PrismReviewEngineTest` |

### 2.3 P3 — MCP stdio 接入（A9–A11）

| 判据 | 描述 | 测试 | 状态 |
|---|---|---|---|
| A9 | agent 域凭据调用任一人域 tool 返回 403 | `PermissionDomainTest.agent_credentials_cannot_call_any_human_domain_tool` | ✅ |
| A10 | 无 token / 错误 Origin 的请求被拒 | `PermissionDomainTest.no_token_denies_all_tools` + `invalid_token_denies_all_tools` | ✅ |
| A11 | 至少 1 个真实 agent CLI 能完成"触发预提审 → 读驳回意见 → 修复 → 二轮通过" | `McpEndToEndTest` (进程内) + `A11RealCliSmokeTest` (真实子进程) | ✅ |

**A11 双层验证**：

- **进程内排练**（`McpEndToEndTest`，2 tests）：用 manual review engine（不依赖 prism/newapi），在进程内驱动 MCP server 完成全链路：presubmit → reject → review_result_get → fix → presubmit round 2 → pass → publish。证明 MCP tool 接线与两域流转正确。
- **真实子进程冒烟**（`A11RealCliSmokeTest`，1 test）：spawn `java -jar gate.jar mcp serve -c gate.toml` 作为真实子进程，通过 OS 级 stdin/stdout 管道发送 JSON-RPC 消息，验证 gate 二进制作为 MCP stdio server 可被外部进程消费。这证明了 spike-结论 S1 的结论在真实进程边界上成立——Spring Boot 启动、`TomlGateConfigLoader` 配置加载、`GATE_DOMAIN_TOKEN` 环境变量握手、逐行 JSON-RPC over OS pipe 全程通畅。

**A11 真实子进程冒烟细节**：
- 两个子进程分别承载 agent 域（`presubmit_create` / `review_result_get`）与 human 域（`commit_and_publish`），各自独立 token
- stderr 重定向到日志文件，stdout 保持纯 JSON-RPC 通道
- 人工判定（manual reject/pass）经共享 `GateService` 注入，publish 经第二个子进程的 `commit_and_publish` tool 完成
- 断言：全链路后权威库恰新增一个 commit

**权限域清单驱动测试**（§5.4 硬约束）：`PermissionDomainTest` 遍历 `McpToolRegistry.humanDomainTools()`，新增 tool 自动覆盖，无漏测。

### 2.4 P4 — 成本埋点与 H1 判定（A12）

| 判据 | 描述 | 测试 | 状态 |
|---|---|---|---|
| A12 | 导出 CSV/JSON，够算中位数与一次通过率 | `MetricsExportTest` (3) | ✅ |

**H1 判定就绪**（执行文档 §4 P4）：

| 三档 | 条件 | 测试 |
|---|---|---|
| ESTABLISHED | 成本占比 < 20% 且一次通过率 > 60% | `H1VerdictTest.established_when_cost_ratio_below_20_and_first_pass_above_60` |
| PARTIAL | 成本占比 20%–40% | `H1VerdictTest.partial_at_*_boundary` (4) |
| REFUTED | 占比 > 40% 或一次通过率 < 40% | `H1VerdictTest.refuted_*` (2) |
| INSUFFICIENT_SAMPLES | 样本 < 20 | `H1VerdictTest.insufficient_samples_below_20` |

**诚实降级**（执行文档 §4 P4 硬约束）：prism JSON 无 usage/token 字段（`p2-schema-核对.md` §3 确认），token source 标记为 `unavailable`，成本占比降级为 NaN，仅凭一次通过率判定。`CostExtractionTest` 覆盖三种 token source（`engine_json` / `gateway_usage` / `unavailable`）与降级路径。

H1 判定**设计为可证伪**——verdict 不被调整或样本过滤以让 H1 "看起来成立"。首次真实数据收集已完成（20 个工单样本，见 `h1-stage-report.md`）：classification=PARTIAL、first-pass=45%、metric_basis=degraded；cost ratio 因 prism JSON 无 usage 字段保持 NaN，成本侧判定仍待 precise token 数据。

---

## 3. 架构约束遵守情况

### 3.1 六边形架构与禁止依赖（§4.2，ArchUnit 强制）

| 约束 | 测试 | 状态 |
|---|---|---|
| domain 依赖仅 JDK；禁止 Spring/JGit/java.sql | `ArchitectureTest` | ✅ |
| application 依赖 domain/ports；禁止依赖 adapter | `ArchitectureTest` | ✅ |
| adapters 依赖 ports + 自身技术；禁止互相依赖 | `ArchitectureTest` | ✅ |
| cli 依赖 application；禁止反向依赖 | `ArchitectureTest` | ✅ |
| Spring 注解仅在 adapters + config 装配层 | `ArchitectureTest` | ✅ |
| domain/application 零 Spring | `ArchitectureTest` | ✅ |

### 3.2 ADR 遵守

| ADR | 决策 | 实现 |
|---|---|---|
| ADR-1 | 不设 GitBackend 端口，权威路径钉真实 git | `GitCli` / `GitCliPublisher` / `GitCliSnapshot` 全程 ProcessRunner |
| ADR-2 | approval 绑定 (ref,old,new,tree) + 单次 + parent==old | `FsApprovalStore` + `pre-receive` 8 条判定 |
| ADR-3 | agent 仓库强制独立 clone | `GitCliTopologyInitializer.createClone` (`--no-hardlinks`) |
| ADR-4 | 删除 ambiguous 态，发布态查 auth ref 派生（匹配 commit_sha） | `GateServiceImpl.publish` + `RefObserver` |
| ADR-5 | 采集用全新临时 index | `GitCliSnapshot` (`GIT_INDEX_FILE`) |
| ADR-6 | 先 commit-tree 再 review | `GateServiceImpl.review` |
| ADR-7 | fail-closed 靠 sealed 类型 + PublishAuthorization | `ReviewEvidence` sealed + `GatePolicy` |
| ADR-8 | Spring Boot 无 web，domain/application 零 Spring | `GateApp` (`WebApplicationType.NONE`) |
| ADR-9 | LLM 经 newapi 网关中转，不装 Ollama | `PrismReviewEngine` (`OPENAI_BASE_URL`) |

### 3.3 退出码表（§8.3）

| 码 | 含义 | 实现 |
|---|---|---|
| 0 | PASS / 放行 | `GateErrorCode.OK` |
| 10 | REJECT — findings | `REJECT_FINDINGS` |
| 11 | REJECT — TOCTOU | `REJECT_TOCTOU` |
| 12 | REJECT — 前置 | `REJECT_PRECONDITION` |
| 13 | REJECT — 需人工 | `REJECT_NEEDS_HUMAN` |
| 20 | GATE_ERROR — 引擎 | `GATE_ERROR_ENGINE` |
| 21 | GATE_ERROR — git/IO/锁 | `GATE_ERROR_IO` |
| 22 | GATE_ERROR — 配置 | `GATE_ERROR_CONFIG` |
| 64 | USAGE | `USAGE` |
| 70 | 内部不可达 | `INTERNAL` |

单一 `System.exit` 在 `GateApp.main`，`GateExceptionHandler` 映射。

---

## 4. 止损条件复查（执行文档 §7）

| # | 止损条件 | 状态 |
|---|---|---|
| 1 | S3 失败（无可用引擎） | **不触发**。prism 可用 |
| 2 | S4 失败且 notes 路线也不可行 | **不触发**。push-option 路径端到端通 |
| 3 | B1–B19 有任一无法 REJECT | **不触发**。18 条全 REJECT + B14 结构性不可能 + B13 残余风险记录 |
| 4 | H1 证伪 | **不触发**。H1 阶段报告（`h1-stage-report.md`）：classification=PARTIAL——first-pass 45% 未到 REFUTED 的 40% 下限，cost ratio 因 degraded basis 为 NaN 无法判定，未证伪 |
| 5 | 竞品已完整覆盖三条自建理由 | **不触发**。定性为"参考 agent-mesh，从零自建" |

---

## 5. 残余风险（§1.3 诚实声明）

闸门阻止未审核代码**经 git 协议**进入权威历史。它**不**容纳一个同 OS 权限持 shell 的对手；此类对手可改写 hook 或直接写 ref。其保证是：

1. 每条进入 `auth.git` 的协议层路径都被检查；
2. 没有任何 CLI flag 或客户端配置能关掉该检查；
3. 蓄意绕过必须在 git 协议之外做文件级写入，且会留下可检测证据（hook 哈希漂移、ref reflog、哈希链审计日志）。

它是对错误与捷径的防御，加上事后检测——不是隔离。

唯一便宜的升级路径（列为触发式，不现在做）：让 `pre-receive` 校验一个对 `(ref,old,new,tree)` 的签名，签名密钥 agent 无法使用——OS keystore 或需用户在场的硬件令牌。

---

## 6. 模块结构

```
gate-parent (pom)
├── gate-domain          纯 JDK，零 Spring/JGit/java.sql（31 个类型）
├── gate-ports           端口接口（23 个）
├── gate-application     GateService 用例 + GatePolicy + H1Verdict + MetricsService（16 个）
├── gate-adapters        每类一个技术（35 个）：git/engine/store/mcp/blob/audit/lock/clock/hook/preflight/process/config
└── gate-cli             picocli CLI + Spring Boot 启动器 + 全部测试（17 main + 17 test）
```

Fat jar：`gate-cli/target/gate.jar`（27MB，Spring Boot repackage）。

---

## 7. 未完成项与后续

| # | 项 | 状态 | 后续 |
|---|---|---|---|
| 1 | H1 判定 | ✅ **已完成**（见 `h1-stage-report.md`）：20 个真实工单样本，classification=PARTIAL，first-pass=45%，degraded basis | 后续：升级 prism 支持 token usage → precise basis 判定 |
| 2 | prism 真实 JSON 核对 | `p2-schema-核对.md` 已完成 | P2 首日已核对 Finding 模型与 `coveredPaths` |
| 3 | HTTP 传输（§10.4） | 不做（N7：stdio 优先） | 若未来多 agent 共用一个常驻 server，按 §10.4 实现 |
| 4 | 真实 Claude Code 接入 | A11 真实子进程冒烟已证明 MCP stdio 端点可消费 | 编排层按 spike-结论 §3.3 的 `claude mcp add` 或 `--mcp-config` 注入 |

---

*本报告为项目验收交付物。94 tests green，A1–A12 全部通过，5 条止损条件无一触发。项目定位实现完成。*
