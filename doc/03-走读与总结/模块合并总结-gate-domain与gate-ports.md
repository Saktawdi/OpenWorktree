# 模块合并总结：gate-domain × gate-ports

> 覆盖范围：`gate-domain`（领域模型 / 领域服务 / 值对象）与 `gate-ports`（端口 / 驱动接口）。
> 这是分层架构靠内的两层：`gradle module` 依赖方向为 **gate-domain ← gate-ports ← gate-application ← gate-adapters/cli/web**，二者都不依赖任何适配器或应用层。

---

## ① 包结构与职责

### gate-domain —— 领域模型与唯一业务规则源
纯 Java 领域层，**不含任何 IO / 进程 / 数据库**，只放不可变 record、枚举与少数纯逻辑领域服务（`GatePolicy`）。所有构造校验即失败（fail-closed），多数字段带不可变约束。

| 子包 | 职责 | 核心类型 |
|---|---|---|
| `gate.domain.ticket` | 工单实体与其生命周期状态机 | `Ticket`, `TicketStage` |
| `gate.domain.session` | Agent 会话编排领域 | `Session`, `AgentConfig`, `SessionMessage`, `SessionUsage`, `ToolCall`, `Role`, `SessionStatus`, `AgentCli` |
| `gate.domain.publish` | 发布意图 write-ahead、批准凭证 | `PublishIntent`, `PublishStatus`, `ApprovalId`, `ApprovalGrant`, `CommitIdentity` |
| `gate.domain.policy` | 评审决策（**唯一产证地**） | `GatePolicy`, `Policy`, `Decision`, `PublishAuthorization`, `Strictness` |
| `gate.domain.snapshot` | 工作树不可变快照 + 完整性审计 | `Snapshot`, `CaptureIntegrityReport`, `IntegrityViolation`, `IntegritySeverity` |
| `gate.domain.review` | 评审证据模型（evidence，非 verdict） | `EngineReport`, `EngineFailure`, `Finding`, `Severity`, `EngineDescriptor`, `ReviewEvidence`, `EvidenceVisitor` |
| `gate.domain.git` | 值对象，防类型混淆 | `ObjectId`, `RepoRef` |
| `gate.domain.blob` | 大对象引用（库外存储） | `BlobRef` |
| `gate.domain.config` | 生效配置（解析结果） | `GateConfig`（含 4 个嵌套 record） |
| `gate.domain.audit` | 审计事件负载 | `AuditEvent` |
| `gate.domain.task` | 异步任务元数据 | `GateTask`, `GateTaskStatus` |
| `gate.domain.error` | 领域异常与错误码 | `GateException`, `GateErrorCode`, `GateBusyException` |

### gate-ports —— 纯端口层（出向驱动接口）
`gate.ports` 包。**零业务逻辑**，只声明领域层/应用层所需的外部出向依赖接口（Outbound Port）；绝大多数接口的 `import` 直接引用 `gate.domain.*`，反证它们直接消费领域模型、把可替换的适配器（SQLite/git/进程/引擎/LLM）挡在外面。

按契约用途分四类：

| 分类 | 端口 |
|---|---|
| **仓储 Repository**（SQLite 持久化） | `TicketRepository`, `SessionRepository`, `PublishIntentRepository`, `PresubmitRepository`, `ProviderRepository`, `ReviewResultRepository`, `CredentialRepository`, `AgentConfigRepository` |
| **git/进程/拓扑**（命令驱动） | `ProcessRunner`, `CommitPublisher`, `SnapshotCapture`, `RefObserver`, `TopologyInitializer`, `HookInstaller`, `PreflightChecker`, `PublishProbe` |
| **资源/能力**（IO、锁、时间、审计、订阅） | `Clock`, `AuditLog`, `BlobStore`, `LockManager`, `TicketLockManager`, `DbTransactionRunner`, `TaskRegistry`, `ApprovalStore` |
| **评审/代理**（外部智能引擎与会话） | `ReviewEngine`, `ReviewEngineFactory`, `CostHint`, `AgentSessionPort` |

---

## ② 核心领域模型

### 领域实体/聚合
**`Ticket`**（`gate/domain/ticket/Ticket.java`）——工单聚合根。字段：`ticketNo`（非空校验）/`title`/`targetRef`/`clonePath`/`executorProviderId`/`executorModel`/`reviewerProviderId`/`reviewerModel`/`stage`/`createdAt`/`updatedAt`，P4 成本遥测 `execTokenTotal`+`execTokenSource`（可空）。提供向后兼容 12 参构造函数（成本字段置 null）。`withStage(newStage, now)` 无死角拷贝。

**`TicketStage`**（`TicketStage.java`）——生命周期枚举：`PENDING → IN_PROGRESS → PRESUBMITTED → IN_REVIEW → REJECTED | READY_TO_PUBLISH | NEEDS_HUMAN | DONE | CANCELLED`。`DONE/CANCELLED` 为终态。**约束**：agent 只能触发 `IN_PROGRESS→PRESUBMITTED`，`PASS/REJECT` 只由 `GatePolicy` 铸造，agent 不可触发。

### 会话领域
**`Session`**（`session/Session.java`）——单次执行（≈ multica Task）：`id`/`ticketNo`/`agentConfigId`/`cli`/`status`/`cliSessionId`/`clonePath`/`allocatedPort`（opencode serve 端口，claude 为 -1）/`startedAt`/`finishedAt`/`cumulativeUsage`。含 4 个 `withXxx` 拷贝器、`isTerminal()`。

**`AgentConfig`**（`session/AgentConfig.java`）——可复用代理配置：`id`/`name`/`cli`(OPENCODE|CLAUDE)/`providerId`/`model`/`systemPrompt`/`extraFlags`/`description`；`extraFlags` null 归 `List.of()`。

**`SessionMessage`**（`session/SessionMessage.java`）——`id`/`sessionId`/`role`(USER|ASSISTANT|TOOL|ERROR)/`content`/`toolCalls`/`usage`/`degraded`/`timestamp`。
**`SessionUsage`**（`SessionUsage.java`）——token(prompt/completion/total)，`add()` 全空安全。

### 发布领域（write-ahead 核心）
**`PublishIntent`**（`publish/PublishIntent.java`）——发布前置日志：`id`/`ticketNo`/`reviewRound`/`treeHash`/`baseCommit`/`targetRef`/`commitMessage`/`author`/`committer`/`approvalId`/`commitSha`/`status`/`observedRefBefore`/`observedRefAfter`/`createdAt`/`finishedAt`/`cloneRepo`/`authRepo`。关键约束：持久化必须 `synchronous=FULL` **先于** `commit-tree`；`commitSha` 反填后带 UNIQUE 约束（幂等）；此后仅 `status/commitSha/observed*/finishedAt` 可变。`toGrant()` 铸造 `ApprovalGrant`。

**`PublishStatus`**（`PublishStatus.java`）——`PENDING / PUBLISHED / ABANDONED`。

**`ApprovalId`**（`publish/ApprovalId.java`）——128-bit 随机 32 hex，**非机密**（argv 世界可读），构造被类内强校验 32 位 hex；`toPushOption` → `gate-approval=<id>`；防路径穿越(B19)。

**`ApprovalGrant`**（`publish/ApprovalGrant.java`）——绑定 `(ref, oldCommit, newCommit, tree)` 四元组，`old` 绑定堵住 B15"快照洗白"；`toRecordText()` 用 LF（CR 会让 hook `grep -qx` 全 miss）。

**`CommitIdentity`**（`publish/CommitIdentity.java`）——`name/email/date`，重放确定性的载体。

### 策略领域（唯一判定源）
**`GatePolicy`**（`policy/GatePolicy.java`）——**全局唯一产决点**：`decide(ticketNo, reviewRound, evidence, snapshot, policy)`。一切默认 reject：engine 失败→reject；`degraded`→reject（fail-closed）；覆盖缺口→`REQUIRES_HUMAN`；证据 tree≠快照 tree→reject（防陈旧证据重放）；超大 diff→`REQUIRES_HUMAN`；findings 达严格度→reject；其余→`PASS` + 铸造 `PublishAuthorization`（`mint` 为包私有，唯一调用方）。`worst` 聚合严重级。

**`Policy`**（`policy/Policy.java`）——`strictness`(BLOCKER_ONLY|BLOCKER_AND_WARNING)/`requireCoverage`/`maxDiffBytes`/`maxDiffLines`/`engineAcceptDegraded`（默认 false，fail-closed）。`defaults()`=BLOCKER_ONLY+requireCoverage+2MB/2万行。

**`Decision`**（`policy/Decision.java`）——三元 `Verdict`(PASS|REJECT|REQUIRES_HUMAN) + `reason`/`detail`/`authorization`。构造约束：`PASS` 必须带授权，非 `PASS` 必须不带——**"无判定即发布"在类型上不可表达**。

**`PublishAuthorization`**（`policy/PublishAuthorization.java`）——能力令牌，无公开构造；绑定 `(ticketNo, reviewRound, treeHash, engine)`；`authorises(ticket, round, tree)` 守卫防他用；仅 `GatePolicy` 可 mint。

### 快照/完整性
**`Snapshot`**（`snapshot/Snapshot.java`）——不可变快照：`treeHash`/`baseCommit`/`baseTree`/`targetRef`/`changedPaths`/`diff`/`integrity`。`core.autocrlf=false`（ADR-5）。`isEmptyDiff() == treeHash.equals(baseTree)`——空 diff 必须被应用层拒绝且不耗评审轮。

**`CaptureIntegrityReport`**（`snapshot/CaptureIntegrityReport.java`）——R1–R5 规则结果，`blockers()`/`warnings()`/`hasBlockers()`。
**`IntegrityViolation`**（`IntegrityViolation.java`）——`rule(R1–R5)/severity/detail`（只存路径不含密钥值）。

### 评审证据模型（evidence ≠ verdict）
**`EngineReport`**（`review/EngineReport.java`）——`engine`/`treeHash`/`findings`/`coveredPaths`/`degraded`/`rawOutput(BlobRef)`/`exitCode`/`duration`；实现 `ReviewEvidence`。
**`EngineFailure`**（`review/EngineFailure.java`）——失败值；任何评审失败都归一成它，绝不抛异常。**`EngineDescriptor`**（`EngineDescriptor.java`）——仅在外的 LLM 坐标：`engineId/engineVersion/argvFingerprint/providerId/modelName`；**绝不含** base_url/API key（ADR-9）。**`Finding`**（`Finding.java`）——归一化严重级 + raw 词全保留；未知引擎词映射 BLOCKER。**`Severity`**（`Severity.java`）——闭集 BLOCKER/WARNING/NIT/INFO。

### 基础设施值对象
- **`ObjectId`**（`git/ObjectId.java`）——40-hex SHA-1 包装；`ZERO` 全 0 哨兵；正确性=哈希等值比较，包装防"截断/大写/空白"串入 hook 比对（堵静默全拒）。**`RepoRef`**（`git/RepoRef.java`）——区分 `auth.git` vs 克隆，防 B14 传参混淆。
- **`BlobRef`**（`blob/BlobRef.java`）——`relPath/bytes/sha256`；拒 `..`。
- **`GateConfig`**（`config/GateConfig.java`）——`schemaVersion=2` + `targetRefWhitelist`(须 `refs/heads/`) + 各路径 + `gateIdentity` + `policy` + `engine`/`web`/`session`/`agent`（web 三块 CLI/MCP 路径可空）。内嵌 `EngineConfig`/`WebConfig`(禁 0.0.0.0 绑定)/`SessionConfig`(端口区间)/`AgentConfigDefaults`。含 `engineConfigured()`、`isWhitelisted()`。
- **`AuditEvent`**（`audit/AuditEvent.java`）——哈希链追加日志负载，禁密钥（ADR-9）。
- **`GateTask`**（`task/GateTask.java`）——`RUNNING→SUCCEEDED/FAILED` 终态；`id` 幂等键。
- **`GateException`**（`error/GateException.java`）——携带 `GateErrorCode`；刻意 unchecked，评审路径禁吞。

---

## ③ 端口 / 接口签名

### 仓储类
- **`TicketRepository`**：`insert(Ticket)`; `Optional<Ticket> find(String)`; `updateStage(String, TicketStage, Instant)`; `updateExecTokens(String, long, String, Instant)`(P4 回写 token); `findByStage(TicketStage)`; `findAll()`。
- **`SessionRepository`**：`find(String)`; `findByTicket(String)`; `findByAgentConfig(String)`; `insert(Session)`; `update(Session)`; `abortOrphanedActive(Instant)`(启动 reconcile); `insertMessage(SessionMessage)`; `findMessages(String)`。
- **`PublishIntentRepository`**：`insertPending(ticketNo, reviewRound, Snapshot, commitMessage, author, committer, approvalId, now, cloneRepo, authRepo)→PublishIntent`; `find(ticketNo, reviewRound, treeHash)`; `findById(long)`; `updateCommitSha(long, ObjectId)`; `updateOutcome(long, PublishStatus, before, after, Instant)`; `findPending()`(reconcile 输入); `findByTicket(String)`。
- **`PresubmitRepository`**：`nextRound(String)→int`(max+1, 从1); `insert(ticketNo, round, Snapshot, BlobRef diff, now)→PresubmitRow`; `find(findLatest/findById/findAllByTicket)`; 行含 `(ticket_no, review_round, tree_hash)` UNIQUE。
- **`ProviderRepository`**：`upsert(ProviderRow, Instant)`; `find/findAll`; `replaceModels(providerId, List, Instant)`; `models(providerId)`; Row 的 `apiKeyRef` 仅存引用/密文（ADR-9）。
- **`ReviewResultRepository`**：`insert(presubmitId, engine, verdict, BlobRef findings, coveredOk, degraded, BlobRef raw, now[, CostRecord])`（P4 成本重载，bypass 不阻发布）; `findLatestForPresubmit(long)`; `findAllForMetrics()`(P4 H1 指标)。行含 P4 全能空遥测字段。
- **`CredentialRepository`**：双域 MCP 凭证：`Domain(name, ticketNo)`(AGENT/HUMAN/INVALID); `validate(String token)→Domain`; `issueAgentToken(ticketNo, now)`; `issueHumanToken(now)`; `revoke(String)`。只存 SHA-256 哈希，明文一次性返回。`review_run`/`commit_and_publish` 永不对 agent 域暴露。
- **`AgentConfigRepository`**：CRUD `findAll/find/insert/update/delete`。

### git / 进程 / 拓扑（命令驱动端口）
- **`ProcessRunner`**：`run(List argv, Path cwd, Map env, Duration timeout)→ProcRun`。**唯一拥有进程机制**的角色：timeout/`destroyForcibly`/回收子进程/重定向 stdout/stderr 到文件/argv 转义——防止管道缓冲导致 waitFor 永不触发（§8.4 item 9）。`ProcRun` 含 `ok()`/`stderrFirstLine()`。
- **`CommitPublisher`**：`buildCommit(PublishIntent)→ObjectId`(env 钉死 author/committer/date，同输入同 SHA，C2 恢复依赖); `pinGateRef(cloneRepo, ticketNo, round, commit)`(防 gc 收集); `publish(PublishIntent, PublishAuthorization)→PublishOutcome`(`--push-option=gate-approval=<id>`，授权参数是编译期防线); `recomputeTree(cloneRepo)→ObjectId`(TOCTOU 复查)。
- **`SnapshotCapture`**：`capture(cloneRepo, authRepo, targetRef)→Snapshot`(克隆须为独立 clone，非 linked worktree); `rebuild(cloneRepo, targetRef, treeHash, baseCommit, diff)→Snapshot`(不重跑 `add -A`，防 TOCTOU)。
- **`RefObserver`**：**唯一"是否已发布"判定源（I4，绝不信 DB）**: `tip(repo, ref)`; `isAncestor(...)`; 默认 `published(repo, target, commitSha)` = commit 是 target 祖先（**匹配 commit_sha 非 tree_hash**，ADR-4）; `countCommits(repo, ref)`(A1/A4 断言)。
- **`TopologyInitializer`**：`initAuthRepo(authRepo, targetRef, approvalsDir)→InitResult(baseCommit, hookSha256)`(先种 base 后装 hook：根提交永无法过会审); `createClone(authRepo, targetRef, cloneDir)→RepoRef`(`--no-hardlinks --single-branch --branch`，禁 worktree add，堵 B14/ADR-3)。
- **`HookInstaller`**：`install(authRepo, refs, approvalsDir)→sha256`; `render/installedDigest/expectedDigest`(hook 漂移检测)。hook 为生成物，LF+无 BOM（N2）。
- **`PreflightChecker`**：`check()→Report`; 分级 `CORE`(P1 恒查：auth 裸/钩子命中/denyDeletes/denyNonFastForwards/symbolic-ref 等)与 `ENGINE`(P2 有引擎时)。含 hook sandbox 自检（同生成器回环测 ACCEPT/REJECT）。任一失败 exit 22 fail-closed。
- **`PublishProbe`**：故障注入缝：`NOOP`; `at(phase)`（`AFTER_INTENT/AFTER_COMMIT_TREE/AFTER_APPROVAL_ISSUE/AFTER_PUSH`）。生产走 NOOP，A5 崩溃恢复据此精确杀 JVM。

### 资源 / 能力端口
- **`Clock`**：`now()`——可钉定，供恢复/确定性测试。
- **`AuditLog`**：`append(AuditEvent)`——哈希链追加，应用不轮转。
- **`BlobStore`**：`put(byte[], relPath)→BlobRef`; `get(BlobRef)→byte[]`——存 diff/原始引擎输出于 SQLite 外。
- **`LockManager`**：每 `(project, targetRef)` 串行锁；`acquire(...)→AutoCloseable`（持锁抛 `GateBusyException`，绝不空转）；仅争 `auth.git` ref 更新。顺序固定 R-LOCK：进程内锁→FileLock→SQLite 写事务。
- **`TicketLockManager`**：按 `ticketNo` 串行（session send/presubmit/publish 同克隆）；阻塞 `acquire(String)` 与快速失败 `tryAcquire(String)→Optional<AutoCloseable>`。
- **`DbTransactionRunner`**：纯 DB 单事务 `inTransaction(Supplier<T>)`。参数刻意用 `Supplier` → 类型上排除了在事务里塞进 `ProcessRunner`（I3：SQLite 事务绝不跨越 ProcessBuilder 调用）。
- **`TaskRegistry`**：异步任务 → SSE：`register(type, ticketNo, sessionId)→GateTask`(RUNNING+初 progress); `update(GateTask)`(progress↝最后单个 done，done 后流永久关闭); `find(String)`; `stream(String)→Stream<GateTaskEvent>(taskId, kind, payloadJson, at)`——重放历史+实时，终态后有限终止。
- **`ApprovalStore`**：一次性批准记录（存于 auth 外，路径烘焙进 hook）：`allocate()→ApprovalId`; `issue(id, grant)`（存在则失败）; `reissue(id, grant)`（已消耗则拒——防单次凭证二次授权）; `isConsumed`/`isLive`。

### 评审 / 代理端口
- **`ReviewEngine`**：`describe()→EngineDescriptor`; **`review(ReviewRequest)→ReviewEvidence`（契约：永不抛异常**，内部 `catch(Throwable)` 归一 `EngineFailure` 值，fail-closed 结构性成立）; 默认 `extractCost(evidence)→Optional<CostHint>`（P4，bypass）。`ReviewRequest(cloneRepo, ticketNo, reviewRound, snapshot, danglingCommit)`（引擎 shell 到活 git，需可达 commit，ADR-6）。返回 evidence 非 verdict。
- **`ReviewEngineFactory`**：`forManualVerdict(pass, note)→ReviewEngine`; `forPrism()`(未配置返 null)。应用层不依赖具体适配器（§4.2 依赖倒置缝）。
- **`CostHint`**：P4 成本载体，bypass 数据（失败绝不阻发布）：`prompt/completion/totalTokens`+`tokenSource`("engine_json"|"gateway_usage"|"unavailable")+`reviewWallMs`/`llmWallMs`。`timingOnly()` 与 `hasTokenData()`。
- **`AgentSessionPort`**：`start(StartRequest)→Session`; `sendMessage(SendRequest)→String`(任务 id，进度走 streamEvents); `abort(sessionId)`; `getHistory(sessionId)→List<SessionMessage>`; `streamEvents(sessionId)→Stream<SessionEvent>`。契约与 `ReviewEngine` 镜像：**happy path 不抛**，传输/解析失败→`role=ERROR` 的消息 + degraded 标记。两个适配器共享（ADR-12）：`OpenCodeServeAdapter`/`ClaudeHeadlessAdapter`，按 `AgentConfig.cli()` 分派。

---

## ④ 模块依赖

- **compile-time 依赖方向**（仅此单向）：
  `gate-domain`（无外部依赖，只依赖 JDK）
  ⇒ `gate-ports`（依赖 `gate-domain` —— 几乎所有端口 import `gate.domain.*`）
  ⇒ `gate-application`（实现用例，依赖 domain + ports）
  ⇒ `gate-adapters` / `gate-cli` / `gate-web`（提供端口实现与装配）
- **反向不可见**：`gate-domain` 与 `gate-ports` 都不 import 任何 adapter/应用类；因此换库/换 git 实现/换评审引擎不改动这两层。
- **依赖倒置的落点**：
  - 评审：应用层 `ReviewEngineFactory` → `ReviewEngine` → adapter。
  - 会话：应用层 `AgentSessionPort` → `OpenCodeServeAdapter`/`ClaudeHeadlessAdapter`。
  - git：`CommitPublisher`/`SnapshotCapture`/`ProcessRunner` → `GitCliPublisher`/`GitCliSnapshot`/`GitProcessRunner`。
  - 持久化：各 `*Repository` → `Jdbc*`/SQLite adapter。
- **数据依赖**：端口返回的 `record` 类型（如 `ReviewResultRow`/`PresubmitRow`/`ProviderRow`/`CostRecord`）嵌套定义在各端口接口内，属于端口契约的一部分；领域模型则跨模块共享（domain 不依赖 ports）。

---

## ⑤ 架构约束（两条线交汇处）

1. **分层内聚**：domain 只装纯逻辑 + 值对象；ports 只装接口与契约 record；**二者都不碰 IO/进程/DB**。git 命令、SQLite、进程都必须通过 ports 的适配器实现。
2. **依赖只向内**：domain ← ports ← application ← adapters/cli/web，靠 Maven module 依赖方向固化，不靠纪律。
3. **fail-closed / 结构性强约束**（不是靠 review 靠类型/契约）：
   - `ReviewEngine.review` 永不抛异常——失败归一为 `EngineFailure` 值。
   - `PublishAuthorization` 无公开构造，仅 `GatePolicy.mint`；`CommitPublisher.publish` 以它作参数 → "无判定即发布" 类型不可达。
   - `DbTransactionRunner` 参数用 `Supplier` → SQLite 事务无法包住 `ProcessRunner`（I3）。
   - `BlobRef`/`ObjectId`/`ApprovalId`/`RepoRef` 用包装类型防明文串拼接、路径穿越、repo 混淆。
4. **判定唯一性**：`GatePolicy` 是唯一产决点与唯一 `PublishAuthorization` 铸造者；端口 `ReviewEngine` 只产 evidence；DB 不是授权权威（证据存库、策略每次重推判决）。
5. **发布正确性链**：`ObjectId` 等值比较贯穿 "snapshot tree == 发布 commit tree == pre-receive 重算 tree"；`RefObserver`（非 DB）才是 "是否已发布" 权威（I4）；`hook` 为生成物、LF、可自检，用 `-push-option` 认证并绑定 `ApprovalGrant` 四元组。
6. **事务→进程边界**：任何 SQLite 事务（经 `DbTransactionRunner`）**【不得】**跨越 `ProcessBuilder` 调用——git 工作必须发生在事务之外。
7. **锁序固定 R-LOCK**：In-process 锁 → `FileLock` → SQLite 写事务，顺序不可颠倒；`LockManager` 只争 `auth.git` ref 更新粒度。
8. **启动 fail-closed exit 22**：`PreflightChecker` core 检查（P1 恒查）逐项通过；`GateConfig` schema_version 不符、web 非回环绑定、目标 ref 非 `refs/heads/` 皆拒绝启动。
9. **审计与密钥纪律**：`AuditLog` 哈希链、只存字段不存秘密；`ProviderRow.apiKeyRef` 与 `CredentialRepository` token 只存 SHA-256 哈希，明文一次性；LLM 坐标只用 `EngineDescriptor`（ADR-9）。
10. **P4 成本遥测为 bypass**：`CostHint`/`ReviewResultRow` 成本列/`Ticket.execToken*` 均可空，记录失败/缺失**绝不**影响 verdict 或发布（"成本记录失败不能让 publish 失败"）。

---

## ⑥ 核心类文件路径清单

### gate-domain（根：`gate-domain/src/main/java/gate/domain/`）
| 核心类 | 文件路径 |
|---|---|
| `Ticket` | `gate-domain/src/main/java/gate/domain/ticket/Ticket.java` |
| `TicketStage` | `gate-domain/src/main/java/gate/domain/ticket/TicketStage.java` |
| `Session` | `gate-domain/src/main/java/gate/domain/session/Session.java` |
| `AgentConfig` | `gate-domain/src/main/java/gate/domain/session/AgentConfig.java` |
| `SessionMessage` | `gate-domain/src/main/java/gate/domain/session/SessionMessage.java` |
| `SessionUsage` | `gate-domain/src/main/java/gate/domain/session/SessionUsage.java` |
| `PublishIntent` | `gate-domain/src/main/java/gate/domain/publish/PublishIntent.java` |
| `PublishStatus` | `gate-domain/src/main/java/gate/domain/publish/PublishStatus.java` |
| `ApprovalId` | `gate-domain/src/main/java/gate/domain/publish/ApprovalId.java` |
| `ApprovalGrant` | `gate-domain/src/main/java/gate/domain/publish/ApprovalGrant.java` |
| `CommitIdentity` | `gate-domain/src/main/java/gate/domain/publish/CommitIdentity.java` |
| `GatePolicy` | `gate-domain/src/main/java/gate/domain/policy/GatePolicy.java` |
| `Policy` | `gate-domain/src/main/java/gate/domain/policy/Policy.java` |
| `Decision` | `gate-domain/src/main/java/gate/domain/policy/Decision.java` |
| `PublishAuthorization` | `gate-domain/src/main/java/gate/domain/policy/PublishAuthorization.java` |
| `Snapshot` | `gate-domain/src/main/java/gate/domain/snapshot/Snapshot.java` |
| `CaptureIntegrityReport` | `gate-domain/src/main/java/gate/domain/snapshot/CaptureIntegrityReport.java` |
| `IntegrityViolation` | `gate-domain/src/main/java/gate/domain/snapshot/IntegrityViolation.java` |
| `IntegritySeverity` | `gate-domain/src/main/java/gate/domain/snapshot/IntegritySeverity.java` |
| `EngineReport` | `gate-domain/src/main/java/gate/domain/review/EngineReport.java` |
| `EngineFailure` | `gate-domain/src/main/java/gate/domain/review/EngineFailure.java` |
| `Finding` | `gate-domain/src/main/java/gate/domain/review/Finding.java` |
| `Severity` | `gate-domain/src/main/java/gate/domain/review/Severity.java` |
| `EngineDescriptor` | `gate-domain/src/main/java/gate/domain/review/EngineDescriptor.java` |
| `ReviewEvidence`/`EvidenceVisitor` | `gate-domain/src/main/java/gate/domain/review/ReviewEvidence.java` / `EvidenceVisitor.java` |
| `ObjectId` | `gate-domain/src/main/java/gate/domain/git/ObjectId.java` |
| `RepoRef` | `gate-domain/src/main/java/gate/domain/git/RepoRef.java` |
| `BlobRef` | `gate-domain/src/main/java/gate/domain/blob/BlobRef.java` |
| `GateConfig` | `gate-domain/src/main/java/gate/domain/config/GateConfig.java` |
| `AuditEvent` | `gate-domain/src/main/java/gate/domain/audit/AuditEvent.java` |
| `GateTask` | `gate-domain/src/main/java/gate/domain/task/GateTask.java` |
| `GateException` / `GateBusyException` | `gate-domain/src/main/java/gate/domain/error/GateException.java` / `GateBusyException.java` |

### gate-ports（根：`gate-ports/src/main/java/gate/ports/`）
| 端口 | 文件路径 |
|---|---|
| `TicketRepository` | `gate-ports/src/main/java/gate/ports/TicketRepository.java` |
| `SessionRepository` | `gate-ports/src/main/java/gate/ports/SessionRepository.java` |
| `PublishIntentRepository` | `gate-ports/src/main/java/gate/ports/PublishIntentRepository.java` |
| `PresubmitRepository` | `gate-ports/src/main/java/gate/ports/PresubmitRepository.java` |
| `ProviderRepository` | `gate-ports/src/main/java/gate/ports/ProviderRepository.java` |
| `ReviewResultRepository` | `gate-ports/src/main/java/gate/ports/ReviewResultRepository.java` |
| `CredentialRepository` | `gate-ports/src/main/java/gate/ports/CredentialRepository.java` |
| `AgentConfigRepository` | `gate-ports/src/main/java/gate/ports/AgentConfigRepository.java` |
| `ProcessRunner` | `gate-ports/src/main/java/gate/ports/ProcessRunner.java` |
| `CommitPublisher` | `gate-ports/src/main/java/gate/ports/CommitPublisher.java` |
| `SnapshotCapture` | `gate-ports/src/main/java/gate/ports/SnapshotCapture.java` |
| `RefObserver` | `gate-ports/src/main/java/gate/ports/RefObserver.java` |
| `TopologyInitializer` | `gate-ports/src/main/java/gate/ports/TopologyInitializer.java` |
| `HookInstaller` | `gate-ports/src/main/java/gate/ports/HookInstaller.java` |
| `PreflightChecker` | `gate-ports/src/main/java/gate/ports/PreflightChecker.java` |
| `PublishProbe` | `gate-ports/src/main/java/gate/ports/PublishProbe.java` |
| `Clock` | `gate-ports/src/main/java/gate/ports/Clock.java` |
| `AuditLog` | `gate-ports/src/main/java/gate/ports/AuditLog.java` |
| `BlobStore` | `gate-ports/src/main/java/gate/ports/BlobStore.java` |
| `LockManager` | `gate-ports/src/main/java/gate/ports/LockManager.java` |
| `TicketLockManager` | `gate-ports/src/main/java/gate/ports/TicketLockManager.java` |
| `DbTransactionRunner` | `gate-ports/src/main/java/gate/ports/DbTransactionRunner.java` |
| `TaskRegistry` | `gate-ports/src/main/java/gate/ports/TaskRegistry.java` |
| `ApprovalStore` | `gate-ports/src/main/java/gate/ports/ApprovalStore.java` |
| `ReviewEngine` | `gate-ports/src/main/java/gate/ports/ReviewEngine.java` |
| `ReviewEngineFactory` | `gate-ports/src/main/java/gate/ports/ReviewEngineFactory.java` |
| `CostHint` | `gate-ports/src/main/java/gate/ports/CostHint.java` |
| `AgentSessionPort` | `gate-ports/src/main/java/gate/ports/AgentSessionPort.java` |

---

## 合并要点一句话
> gate-domain 定义**"领域是什么、规则归谁"**（核心是 `GatePolicy` 独裁产决 + 发布 write-ahead `PublishIntent` + 不可变值对象），gate-ports 定义**"要什么外部能力、按什么契约"**（`ReviewEngine` 永不抛、`CommitPublisher` 需授权参数、`DbTransactionRunner` 用类型禁跨度事务、`RefObserver` 是 Is-Published 唯一权威）；二者合起来把系统的**正确性核心**与**外部世界**严丝合缝地切开——所有 fail-closed 约束要么是 `GatePolicy` 的策略要么是端口签名的类型约束。
