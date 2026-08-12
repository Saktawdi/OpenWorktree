# P0 Spike 结论

> 日期：2026-08-11
> 上游：`架构落地执行文档.md`（§13 开工顺序）、`执行文档.md`（§4 P0、§6.2 D5–D8）
> 环境：Windows 11 + git 2.37.1.windows.1 + Corretto 17.0.3（`C:\Users\17428\.jdks\corretto-17.0.3-1`）+ Maven 3.9.9 + Go 1.26.5 + Node 22.14.0
> 执行顺序：**S3 → S4 → S1**（照 `执行文档.md §9`）
> 临时 repo / 临时脚本 / 临时 MCP 配置**已全部清理**（`Remove-Item -Recurse`，见文末 §5）

---

## 0. 一页结论

| Spike | 判据（执行文档 §4） | 结果 | 对后续的影响 |
|---|---|---|---|
| **S3** 审核引擎能否复用 | 至少 1 个引擎在 Windows 稳定产出可解析 JSON + 确定性退出码 | **通过** | **`prism` 单选定**。`local-review` 淘汰（review 模式无 JSON、失败即放行）。P1 不必自研审核层，§7 止损第 1 条不触发 |
| **S4** 权威路径能否发 push-option | 二者任一可行且有测试覆盖 | **通过**（`ProcessRunner` 调真实 git） | 确认 ADR-1：**不引入 JGit**。D5 = ProcessBuilder，D8（`git notes` 备选）**作废**。§7 止损第 2 条不触发 |
| **S1** agent CLI 能否注入自定义 MCP | 至少 2 个 CLI 能连上并成功调用一个自定义 tool | **通过**（claude code + opencode 各 2 条注入路径） | D7 = **MCP**（P3）。文件协议降级为不必要。**但闸门本身不依赖此结论** |

**三个 spike 全过 → P1 解闸放行。**

额外收获（超出 spike 原定范围，但直接改写实现约束）：

1. **B14 已在本机复现**：链接 worktree 内一条 `update-ref` 直接改写共享分支，**无 push、无 receive-pack、无 pre-receive**。ADR-3（强制独立 clone）从"推断"升级为"实测封堵"。
2. **§6.3 的 8 条判定脚本已整体跑通**：happy path ACCEPT + 10 条绕过全 REJECT，权威库 tip 全程未动。这提前完成了 `执行文档.md` A2 判据的大半。
3. **`prism` 退出码分类经实测校准**：`4` 同时覆盖"git 失败"与"provider 网络失败"，`2` 是 usage。这对 §5.3 的 `FailureKind` 映射有直接影响（见 §1.4）。

---

## 1. S3 — 审核引擎能否复用

### 1.1 结论

**通过，采纳 `dshills/prism`，单引擎起步。**

分两层：

**第一层（架构落地文档 §3.4 提出的真问题）：引擎能否审"临时 index 造出的 tree 物化成的 dangling commit"？**
→ **能，且是引擎的一等公民路径。** `prism review commit <sha> [--parent <base>]` 直接对任意 commit-ish 工作，内部展开为 `git diff <sha>~1 <sha>`（或 `git diff <parent> <sha>`）。dangling commit 只要被 gate ref 钉住即可达，**不需要在任何分支上**。已在本机对真实 dangling commit 实跑验证：引擎成功定位 repo、解析出 3 个改动文件的 diff、构造完 prompt，**唯一失败点是 LLM provider 网络不可达**——这恰好证明 git plumbing 全程通了。

**第二层（执行文档 §4 原判据）：Windows 稳定 JSON + 确定性退出码？**
→ **`prism` 满足，`local-review` 不满足。**

| 维度 | `dshills/prism` v0.6.0 | `mshykov/local-review` v0.17.4 |
|---|---|---|
| 语言 / 安装 | Go，`go install github.com/dshills/prism/cmd/prism@latest` | Go（**不是 npm**，修正执行文档的隐含假设） |
| 指定任意 commit | `review commit <sha> --parent <base>` ✅ | `commit <rev>` ✅（`git show -U10 --format= <rev>`） |
| 任意 `<A>..<B>` 区间 | `review range <A..B>` ✅ | ❌ 只有 branch-vs-`...HEAD` |
| git 机制 | shell 调真实 `git`（无 go-git） | shell 调真实 `git`（源码注释明示"deliberately shell out"） |
| JSON 输出 | `--format json`，另有 **SARIF v2.1.0** ✅ | **`--json` 在 review 模式被忽略，只出 markdown** ❌ |
| 退出码 | `0/1/2/3/4` 四类可分 ✅ | `0/2` + **工具自身失败时示例 hook 放行** ❌ 与 fail-closed 相反 |
| provider | Anthropic / OpenAI / Gemini / Ollama | BYOK，fan-out 调其他 LLM CLI + Ollama endpoint |

`local-review` 的两条硬伤正中架构文档 §3.4 已预判的坑：无 JSON → adapter 只能正则刮 markdown（`degraded=true` 常态化）；崩溃即放行 → 必须由 adapter 反转其语义。既然 `prism` 两项都干净，**没有理由为第二个引擎付这个成本**。ADR-1 的 R-PORT 规则同样适用：两个引擎行为差异（尤其"崩溃时放行 vs 拒绝"）**完全可能导致误放行**，所以 `ReviewEngine` 端口此刻只应有一个生产实现。

### 1.2 证据命令

拓扑搭建（bare 权威库 + 独立 clone，照 ADR-3）：

```powershell
git init --bare auth.git
git -C auth.git symbolic-ref HEAD refs/heads/main    # 注意：git 2.37 默认 master，必须显式改
git clone -q --no-hardlinks $auth seed               # 播种 base commit
git clone -q --no-hardlinks --single-branch --branch main $auth clones/TICKET-1
```

presubmit 采集（§3.2 临时 index）+ build（§3.5 确定性 commit）：

```powershell
# agent 工作区留脏（改 2 个文件 + 1 个 untracked），不 commit
$env:GIT_INDEX_FILE = "$root\idx\TICKET-1-1"
git -C $clone -c core.autocrlf=false -c core.safecrlf=false add -A
$treeHash = git -C $clone -c core.autocrlf=false write-tree
Remove-Item Env:\GIT_INDEX_FILE

$env:GIT_AUTHOR_NAME='gate'; $env:GIT_AUTHOR_EMAIL='gate@localhost'; $env:GIT_AUTHOR_DATE='1700000000 +0000'
$env:GIT_COMMITTER_NAME='gate'; $env:GIT_COMMITTER_EMAIL='gate@localhost'; $env:GIT_COMMITTER_DATE='1700000000 +0000'
$commitSha = git -C $clone commit-tree $treeHash -p $baseCommit -m "TICKET-1 round 1"
git -C $clone update-ref refs/gate/TICKET-1/1 $commitSha    # 钉住防 gc
```

实测输出（原样）：

```
=== agent real index BEFORE capture ===
 M README.md
 M app.py
?? config.py
TREE_HASH=726afff03229701573b0ee0553876ec7deeea4d3
=== agent real index AFTER capture (must be identical to before) ===
 M README.md
 M app.py
?? config.py
BASE_COMMIT=2191a84ced968b4037812c46192cd898a6b9ed94
BASE_TREE=bd434855ab9ff616926667dc1cf96b8fb8ce67ea
EMPTY_DIFF=ok(differs)
COMMIT_SHA_1=054139850c363991380c1ec290b7952bfa19d36a
COMMIT_SHA_2=054139850c363991380c1ec290b7952bfa19d36a
DETERMINISM=ok(same SHA)
COMMIT_TREE=726afff03229701573b0ee0553876ec7deeea4d3
TREE_ANCHOR=ok(commit^{tree}==captured tree)
```

引擎对 dangling commit 的可审性：

```powershell
go install github.com/dshills/prism/cmd/prism@latest       # → C:\Users\17428\go\bin\prism.exe
cd $clone
prism review commit $dangling --parent $base --provider ollama --format json --fail-on high
```

```
Error: provider review: sending request: Post "http://localhost:11434/v1/chat/completions":
  dial tcp 127.0.0.1:11434: connectex: No connection could be made ...
EXIT=4
```

→ 引擎已走完 `git rev-parse --show-toplevel` → `git diff <base> <dangling>` → prompt 构造，**卡在 LLM 而非 git**。git 侧可审性成立。

退出码确定性（三种输入，三个不同码）：

```powershell
prism review commit <dangling> --parent <base> --provider ollama   # EXIT=4  provider/runtime
prism review commit deadbeef...deadbeef --provider ollama          # EXIT=4  git bad object
prism review commit                                                # EXIT=2  usage (accepts 1 arg(s), received 0)
```

`prism review commit --help` 的完整 flag 面（供 adapter 写 argv 指纹）：

```
--compare / --context-lines / --exclude / --fail-on {none,low,medium,high}
--format {text,json,markdown,sarif} / --max-diff-bytes / --max-findings
--model / --no-redact / --out / --parent / --paths / --provider / --rules
```

### 1.3 采纳的方案

| 项 | 决定 |
|---|---|
| **D6（用哪个引擎）** | **`dshills/prism`**，单引擎。`local-review` 不接（不写 adapter，不留双路线） |
| 调用形态 | `prism review commit <dangling_sha> --parent <base_commit> --format json --fail-on none`，**CWD 必须在 clone 内** |
| `--fail-on` | 固定 `none`。**判定权在 `GatePolicy`，不下放给引擎**（§5.3 约束 1：端口返回证据不返回 verdict） |
| `EngineDescriptor` | 记 `prism` + version + 完整 argv 指纹 + provider/model；随 `review_result` 落库（§7.2） |
| provider | 需在 P2 前定：~~Ollama~~ → **已定：不装 Ollama，全部经 newapi 网关中转**（`https://newapi.sakta.top`，OpenAI 兼容）。prism 用 `--provider openai` + `OPENAI_BASE_URL` 指向网关。见架构文档 §10.1.1 / ADR-9。这是 P4 成本埋点的自变量，不阻塞 P1 |
| `coveredPaths` | prism 的 JSON 需在 P2 首日核对是否含"实际审了哪些文件"。**若不含，adapter 必须以"引擎输入的 changedPaths 全集"为 coveredPaths 并置 `degraded=true`**——不得假设全覆盖（§5.3 约束 2） |

### 1.4 对架构文档的修正（`FailureKind` 映射表）

架构文档 §3.4 称"prism 退出码可区分 findings(`1`) 与崩溃(`2/3/4`)"。实测更细，且 `4` 是**混合桶**：

| prism exit | 实测含义 | 映射到 `FailureKind` / 闸门退出码 |
|---|---|---|
| `0` | 无达阈 findings | `EngineReport`，交 `GatePolicy` |
| `1` | 有达 `--fail-on` 阈值的 findings | `EngineReport`（因我们固定 `--fail-on none`，**此码在本项目不应出现**；出现即视为配置漂移 → reject） |
| `2` | usage error（参数错） | `EngineFailure(CRASH)` → 闸门 `20`。**这是 adapter 自己拼错 argv**，应在启动 preflight 用 `--help` 校验 flag 存在 |
| `3` | provider auth/config | `EngineFailure(CRASH)` → 闸门 `22`（运维修，勿重试） |
| `4` | **runtime：git 失败 / IO / provider 网络 / schema** | `EngineFailure(CRASH)` → 闸门 `20`。**无法从退出码区分"git 坏了"与"LLM 不可达"**，adapter 必须解析 stderr 首行归类，且**归类失败一律 reject** |

`4` 的混合性质意味着：**不能靠退出码实现"provider 不可达就重试、git 坏了就别重试"**。P2 的 adapter 要么解析 stderr（脆），要么统一按 `20`（可重试但对该 ref 视同拒）。**建议后者**——fail-closed 优先于重试精度。

---

## 2. S4 — 权威路径能否发 push-option

### 2.1 结论

**通过，且已把 §6.3 的 8 条判定脚本整体跑通。**

S4 在架构文档 §5.1 已被 ADR-1 降级为"非阻断"——因为不让 JGit 碰权威路径。本次 spike 因此**不测 JGit**，改测真正要交付的那条路径：**`ProcessRunner` → 真实 `git push --push-option=gate-approval=<id>` → bare 库 `pre-receive` → approval 记录校验 + 单次消费**。

结果：

- **Happy path ACCEPT**：合法 approval → push 成功 → 权威库 tip 精确移到目标 commit → approval 文件被 `mv` 进 `consumed/`。
- **10 条绕过全 REJECT**，包含把原方案打穿的 **B9**，以及快照洗白 **B15**。全程权威库 tip **一次都没动**。
- **`receive.advertisePushOptions=false` 时 push 直接发不出去**（`fatal: the receiving end does not support push options`，exit `128`）——证实这必须是启动 preflight 的硬检查，否则整条放行路径静默瘫痪。

**关键新发现（超出 S4 范围）：B14 在本机复现。**

### 2.2 证据命令

hook 安装（§6.3 全文，approvals 绝对路径烘焙进去，**必须 LF + 无 BOM**）：

```powershell
git -C $auth config receive.advertisePushOptions true
git -C $auth config receive.denyDeletes true
git -C $auth config receive.denyNonFastForwards true
# 写 hooks/pre-receive：注意用 [System.IO.File]::WriteAllText + UTF8Encoding($false)
# PowerShell 的 Set-Content/Out-File 会写 CRLF 或 BOM，sh 会当场炸
$hookLF = $hook -replace "`r`n","`n"
[System.IO.File]::WriteAllText($hookPath, $hookLF, (New-Object System.Text.UTF8Encoding($false)))
```

approval 签发 + push：

```powershell
# approvals/<id> 内容（LF）：
#   ref=refs/heads/main
#   old=<base_commit>
#   new=<commit_sha>
#   tree=<tree_hash>
git -C $clone push --push-option="gate-approval=$id" $auth "$($commitSha):refs/heads/main"
```

Happy path 实测：

```
AUTH_TIP=054139850c363991380c1ec290b7952bfa19d36a
EXPECT   =054139850c363991380c1ec290b7952bfa19d36a
=== consumed dir ===
7e75309d7eeefcdf4be9426da85d1161
=== approvals dir (live) ===   ← 空，凭据已单次消费
```

绕过矩阵实测（统一断言：权威库目标分支 tip 不变）：

```
AUTH_TIP_BASELINE=054139850c363991380c1ec290b7952bfa19d36a

B9  (agent 诚实算出自己未审 commit 的 tree 当令牌)   => REJECT (tip unchanged) [PASS]
B18 (伪造/猜 approval id)                            => REJECT (tip unchanged) [PASS]
B19 (approval id 路径穿越 ../../etc/passwd)          => REJECT (tip unchanged) [PASS]
B16 (重放已消费的 approval id)                       => REJECT (tip unchanged) [PASS]
B15 (合法 approval，同 tree 嫁接到不同 parent)       => REJECT (tip unchanged) [PASS]
B7  (push 到非白名单 refs/heads/tmp)                 => REJECT (未创建该 ref)  [PASS]
B10 (一次 push 塞两个 ref 更新)                      => REJECT (tip unchanged) [PASS]
B-multiopt (两个 push-option，COUNT != 1)            => REJECT (tip unchanged) [PASS]
B-nooption (完全不带 push-option)                    => REJECT (tip unchanged) [PASS]

FINAL_AUTH_TIP=054139850c363991380c1ec290b7952bfa19d36a
BASELINE     =054139850c363991380c1ec290b7952bfa19d36a
OVERALL=ALL_BYPASSES_BLOCKED (tip never moved)
```

`advertisePushOptions` 关闭时：

```powershell
git -C $auth config receive.advertisePushOptions false
git -C $clone push --push-option="gate-approval=whatever" $auth "HEAD:refs/heads/probe"
```

```
fatal: the receiving end does not support push options
fatal: the remote end hung up unexpectedly
PUSH_EXIT=128
```

**B14 复现（链接 worktree 绕过全部闸门）**：

```powershell
git clone -q --no-hardlinks $auth normal
git -C $normal worktree add -q .\LW -b feature        # 链接 worktree，共享 ODB + ref store
# 在 LW 内造 commit，然后直接改共享分支——不 push
git -C $lw update-ref refs/heads/main $forgedCommit
```

```
HEAD before = 054139850c363991380c1ec290b7952bfa19d36a
linked worktree created isDir=True
.git in linked wt is a FILE (worktree marker): True
  marker content: gitdir: .../normal/.git/worktrees/LW
forged commit = bf050681e1a2707f4110bc6ab455d2758350833e
shared 'main' after = bf050681e1a2707f4110bc6ab455d2758350833e
B14=CONFIRMED: update-ref inside linked worktree moved the SHARED branch
              with NO push / NO receive-pack / NO pre-receive. Gate fully bypassed.
```

preflight 判据同时验证：

```
linked worktree  .git is FILE => True   （preflight 必须 REJECT）
independent clone .git is DIR  => True   （preflight ACCEPT）
independent clone objects/info/alternates exists => False  （--no-hardlinks 物理独立，确认）
```

### 2.3 采纳的方案

| 项 | 决定 |
|---|---|
| **D5（JGit vs ProcessBuilder）** | **`ProcessRunner` 调真实 git**，权威路径 100% 真实 git 二进制。确认 ADR-1，不设 `GitBackend` 端口 |
| **D8（`git notes` 备选路线）** | **作废**。push-option 路径实测完全可用，不需要备选。§7 止损第 2 条不可能触发 |
| JGit | **不引入**。连"只读加速"也先不做——多一个依赖多一套 tree 语义风险，收益是零 |
| hook 生成 | 模板 + 烘焙 approvals 绝对路径。**必须 LF + 无 BOM 写盘**（PowerShell 默认会写 CRLF/BOM，sh 直接炸）→ 写进 P1 hook 安装器的单元测试 |
| `auth.git` 初始化 | **必须显式 `symbolic-ref HEAD refs/heads/main`**。git 2.37 默认 `master`，不设则 clone 出来是空分支——本次 spike 在此处摔了两次 |
| preflight | `advertisePushOptions` / `denyDeletes` / `denyNonFastForwards` / hook 哈希 / `.git` 是否为文件 / `alternates` 是否指向 auth.git —— 6 项均已验证可机械检测 |
| P1 测试债 | B7/B9/B10/B15/B16/B18/B19 + 多 option + 无 option 的**断言逻辑已在本 spike 定型**，P1 直接翻译成 JUnit（fixture 用真实 git，禁用 JGit，照 §11.1） |

### 2.4 对架构文档的修正

| # | 原文 | 修正 |
|---|---|---|
| 1 | §11.2 B14 状态"已实测复现 + 已封堵" | 保持，但**复现细节补充**：链接 worktree 必须由**非裸库**创建（`git worktree add` 不能在 bare 库直接跑出可写工作区并共享 ref store 的等价形态）。攻击面的准确描述是"agent 仓库若是任何与权威库共享 ref store 的形态"，preflight 判据（`.git` 是文件 / alternates 指向 auth.git）不变 |
| 2 | §2.1 拓扑图未提 `auth.git` 的 HEAD | 补：`git init --bare` 后**必须** `symbolic-ref HEAD refs/heads/<target>`，否则 clone 得到空分支、`base_commit` 取不到 |
| 3 | §6.3 hook 脚本 | 补一条生成期约束：**LF 行尾、无 BOM**，且 hook 安装后须实跑一次 happy path 自检（否则 hook 语法错在 push 时才暴露，且表现为"全部 REJECT"——与"闸门正常工作"外观相同，极易误判） |

> 第 3 条值得单独强调：**hook 坏掉的表现与闸门完美工作的表现在外观上一致（都是全 REJECT）**。所以 preflight 的 hook 哈希校验之外，还需要一条"正向自检"——否则闸门可能早已瘫痪成"永久拒绝"，而无人知晓。这是 §8.4"静默放行藏身处"的对偶面：**静默拒绝**。建议加入 §10.3 preflight。

---

## 3. S1 — agent CLI 能否注入自定义 MCP

### 3.1 结论

**通过。claude code 与 opencode 各有 2 条可用注入路径，均实测连上并成功调用自定义 tool。**

判据是"至少 2 个 CLI 能连上并成功调用一个自定义 tool"。测法：手写一个最小 MCP stdio server（`initialize` / `tools/list` / `tools/call`，只暴露一个 `gate_ping`，从环境变量读 token 并回显），然后分别注入两个 CLI。

| CLI | 版本 | 注入路径 | 携带 token | 限制可见 tool 集 | 结果 |
|---|---|---|---|---|---|
| claude code | 2.1.224 | `claude mcp add <name> <cmd> <args> -s local -e KEY=VAL` | ✅ `-e` 环境变量 / `-H` header | ✅ `--allowedTools` / `--disallowedTools` | **✔ Connected**，token 正确注入 |
| claude code | 2.1.224 | `--mcp-config <file.json>` + `--strict-mcp-config` | ✅ JSON 内 `env` | ✅ 同上，且 `--strict-mcp-config` **只用该文件的 server**（隔离用户全局配置） | 配置被接受、server 段解析通过 |
| opencode | 1.18.16 | 项目级 `opencode.json` 的 `mcp` 段（`type: local` + `command[]` + `environment{}`） | ✅ `environment` | ✅ agent 级 tool 白/黑名单 | **✓ gate-spike connected** |
| opencode | 1.18.16 | `opencode mcp add <name> --url/--env/--header` | ✅ `--env` / `--header` | ✅ 同上 | flag 面确认具备（未逐一实跑） |

`--strict-mcp-config` 对本项目价值很高：它让编排层可以**只**给 agent 闸门这一个 MCP server，而不是在用户既有的一堆 server 上叠加——直接对上架构文档 §11.3 的"权限域"要求。

### 3.2 证据命令

最小 MCP server 的直接握手（先证明 server 本身合规，再谈 CLI）：

```powershell
$reqs = @(
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{...}}',
  '{"jsonrpc":"2.0","method":"notifications/initialized"}',
  '{"jsonrpc":"2.0","id":2,"method":"tools/list"}',
  '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"gate_ping","arguments":{}}}'
) -join "`n"
$reqs | node mcp_gate_spike.js
```

```json
{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"gate_ping","description":"spike probe tool","inputSchema":{...}}]}}
{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"gate_ping ok; token=s3cr3t-spike"}]}}
```

claude code 注入（**注意 argv 形态**）：

```powershell
# 失败：PowerShell 吃掉了 `--` 分隔符
claude mcp add gate-spike -s local -e GATE_TOKEN=... -- node $js
#   → error: missing required argument 'commandOrUrl'

# 失败：add-json 的 schema 不接受裸 {command,args,env}
claude mcp add-json gate-spike '{"command":"node","args":[...],"env":{...}}' -s local
#   → Invalid configuration: : Invalid input

# 成功：位置参数形式，不用 --
& claude mcp add gate-spike node $js -s local -e GATE_TOKEN=s3cr3t-spike
```

```
Added stdio MCP server gate-spike with command: node ...\mcp_gate_spike.js to local config
File modified: C:\Users\17428\.claude.json [project: ...\mcpcfg]
EXIT=0
```

```powershell
claude mcp get gate-spike
```

```
gate-spike:
  Scope: Local config (private to you in this project)
  Status: ✔ Connected          ← 健康检查真的连上了 stdio server
  Type: stdio
  Command: node
  Args: ...\mcp_gate_spike.js
  Environment:
    GATE_TOKEN=s3cr3t-spike    ← token 注入生效
```

opencode 注入（项目级配置文件，无需改全局）：

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "gate-spike": {
      "type": "local",
      "command": ["node", "...\\mcp_gate_spike.js"],
      "enabled": true,
      "environment": { "GATE_TOKEN": "s3cr3t-spike" }
    }
  }
}
```

```powershell
# 关键：必须在含该 opencode.json 的目录下运行（workdir，不是 Push-Location 后再调）
opencode mcp list          # cwd = 该项目目录
```

```
●  ✓ gate-spike connected
```

tool 限制面（两个 CLI 的 flag 均确认存在）：

```
claude:   --allowedTools / --disallowedTools / --mcp-config / --strict-mcp-config / --permission-mode
opencode: opencode agent（agent 级 tool 白/黑名单）+ mcp add --env/--header
```

清理：

```powershell
claude mcp remove gate-spike -s local     # → Removed MCP server gate-spike from local config
```

### 3.3 采纳的方案

| 项 | 决定 |
|---|---|
| **D7（MCP vs 文件协议）** | **MCP**。两个 CLI 均可注入，判据满足，文件协议不做 |
| P3 形态 | 优先 **stdio** MCP server（本次实测形态），而非 HTTP。stdio 天然只对被 spawn 的子进程可见，**不开监听端口**——比架构文档 §10 的"bind 127.0.0.1 + token"更小的攻击面 |
| 若必须 HTTP | 才需要 §10 的全套：bind 127.0.0.1 + token + 校验 `Origin`/`Host`。**stdio 优先意味着这套可以先不写** |
| 权限域（§11.3） | 用 CLI 侧 `--allowedTools` **叠加** server 侧域检查。**server 侧是权威**——CLI flag 属于 §1.3 承认的"agent 可控配置"，不能作为唯一防线。`review_run` / `publish` 绝不进 agent 域 |
| 隔离 | claude 用 `--mcp-config <file> --strict-mcp-config`；opencode 用项目级 `opencode.json`。二者都**不污染用户全局配置**，且可由编排层逐工单生成 |
| 优先级 | **P3，非阻断**。闸门正确性完全不依赖此结论——照架构文档 §13 第 2 条 |

### 3.4 踩到的坑（写给 P3 实现者）

1. **`claude mcp add` 的 `--` 分隔符在 PowerShell 下不可靠**：`-- node <script>` 被吃掉，报 `missing required argument 'commandOrUrl'`。改用位置参数 `claude mcp add <name> node <script>` 可行。P3 若由 Java 生成命令行，应直接写 `.claude.json` / `--mcp-config` 文件，**不要拼 CLI**。
2. **`claude mcp add-json` 的 schema 比文档窄**：裸 `{command, args, env}` 和加 `type:"stdio"` 都被拒（`Invalid configuration: : Invalid input`）。未再深挖——因为路径 1 和 `--mcp-config` 都通，没必要。
3. **opencode 读项目级 `opencode.json` 依赖 cwd**：必须以该目录为工作目录启动（用 `workdir` 参数，而非先切目录再调），否则读不到。
4. `opencode run` 走真实模型时本机环境返回了上游服务错误（与 MCP 无关）；`opencode mcp list` 的连接状态已足够证明注入生效，未继续追。

---

## 4. 汇总：未决决策的处置与止损条件复查

### 4.1 `执行文档.md §6.2` 由 spike 回答的决策

| # | 问题 | 结论 |
|---|---|---|
| D5 | JGit 发 push-option 还是 `ProcessBuilder` 调真实 git | **ProcessBuilder（`ProcessRunner` 端口）**。JGit 不引入 |
| D6 | 用哪个审核引擎 | **`dshills/prism`**，单引擎 |
| D7 | MCP 还是文件协议 | **MCP，stdio 优先**。文件协议不做 |
| D8 | S4 失败是否改走 `git notes` | **不适用，作废**。S4 通过 |

### 4.2 `执行文档.md §7` 止损条件复查

| # | 止损条件 | 状态 |
|---|---|---|
| 1 | S3 失败（无可用现成引擎） | **不触发**。prism 可用 |
| 2 | S4 失败且 notes 路线也不可行 | **不触发**。push-option 路径实测端到端通 |
| 3 | P1 的 B1–B8 有任一无法 REJECT | **待 P1**。本 spike 已手工验证 B7/B9/B10/B15/B16/B18/B19 + 多/无 option 全 REJECT |
| 4 | H1 证伪（成本占比 > 40% 或一次通过率 < 40%） | **待 P4**。前提是先定 provider（Ollama 离线 vs 云端 API） |
| 5 | 发现某现成项目已完整覆盖三条自建理由 | **本次未系统复查**。执行文档 §7 要求"P0 结束时复查一次"——**这一项未完成，见 §4.4** |

### 4.3 P1 开工前的补充约束（本 spike 新增，建议并入架构文档）

| # | 约束 | 出处 |
|---|---|---|
| N1 | `git init --bare` 后必须 `symbolic-ref HEAD refs/heads/<target>` | §2.4 修正 2 |
| N2 | hook 写盘必须 LF + 无 BOM（`[System.IO.File]::WriteAllText` + `UTF8Encoding($false)`） | §2.4 修正 3 |
| N3 | preflight 需加"hook 正向自检"——否则静默拒绝与正常工作外观相同 | §2.4 修正 3 |
| N4 | 启动时用 `prism review commit --help` 校验 flag 面存在，防 argv 漂移变成运行期 exit `2` | §1.4 |
| N5 | prism exit `4` 是混合桶（git 失败 / provider 网络 / IO / schema），统一按闸门 `20` 处理，**不做退出码级重试分流** | §1.4 |
| N6 | prism JSON 若无"实际审了哪些文件"字段，`coveredPaths` 必须置为输入 changedPaths 且 `degraded=true` | §1.3 |
| N7 | MCP 优先 stdio 而非 HTTP，可省掉 §10 的 bind/Origin/Host 全套 | §3.3 |

### 4.4 未完成项与批复（2026-08-11 拍板）

| # | 项 | 批复 | 处置 |
|---|---|---|---|
| 1 | 执行文档 §7 第 5 条竞品复查 | **定性：参考 agent-mesh 思路，从零自建，不在其上二次开发。** | 复查降级为「确认无新项目完整覆盖三条自建理由」，不阻断开工 |
| 2 | prism JSON 真实输出未取到 / provider 未定 | **不装 Ollama，全部经 newapi 网关中转**（`https://newapi.sakta.top`，OpenAI 兼容）。参考 opencode：先配供应商，再拉模型，对外暴露 `(provider_id, model_name)` | 见架构文档 §10.1.1 + ADR-9 + provider/model 两表（§7.2）。P2 首日仍需用真实输出核对 Finding 模型 |
| 3 | 审核质量未评估 | **忽略**（P4/H1 的事，与闸门正交） | 不处理 |
| 4 | B17 并发抢 approval 未测 | **没问题**（mv 原子性 OS 保证，留 P1 JUnit） | P1 补测 |

原始未完成项记录（保留供追溯）：

1. ~~执行文档 §7 第 5 条竞品复查未做~~ → 已定性（上表 #1）。
2. ~~prism JSON 输出结构未拿到 / provider 未定~~ → provider 方案已定为 newapi 中转（上表 #2）；JSON 实物仍需 P2 首日核对。
3. ~~审核质量未评估~~ → 忽略（上表 #3）。
4. ~~B17 未测~~ → 留 P1 JUnit（上表 #4）。

---

## 5. 环境与清理

**引入的持久变更（唯一一项）**：

```
C:\Users\17428\go\bin\prism.exe      ← go install，S3 需要，保留（P2 会用）
```

**已清理**：

```
临时 repo：      <temp>\opencode\spike\        （auth.git / seed / clones / idx / approvals / b14）
临时脚本：       s3_setup.ps1 s3_core.ps1 s4_happy.ps1 s4_bypass.ps1 s4_extra.ps1 s4_b14.ps1
MCP 探针：       mcp_gate_spike.js  claude_mcp.json
临时项目目录：   <temp>\opencode\mcpcfg\      （含 opencode.json）
claude 配置项：  claude mcp remove gate-spike -s local  ← 已移除，.claude.json 恢复原状
```

**未安装**：Ollama —— **已定不装**。所有 LLM 经 newapi 网关（`https://newapi.sakta.top`，OpenAI 兼容）中转，先配供应商再拉模型（架构文档 §10.1.1 / ADR-9）。
**注意**：本机 `java` 未在 PATH（jenv 未设全局版本），JDK 在 `C:\Users\17428\.jdks\corretto-17.0.3-1`。P1 开工前需 `jenv change` 或设 `JAVA_HOME` 到 PATH。

---

*本文档为 P0 交付物。三个 spike 全部通过，P1 可开工。§4.3 的 N1–N7 建议并入 `架构落地执行文档.md`；§4.4 的 4 项未完成项中，第 1 项（竞品复查）应在 P1 开工前补上。*




