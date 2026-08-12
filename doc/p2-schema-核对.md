# P2 首日：prism JSON schema 核对

> 日期：2026-08-13
> 上游：`spike-结论.md §4.4 #2`（P2 首日必做）、`架构落地执行文档.md §5.3`（Finding 模型）、`§10.1.1`（newapi provider 映射）
> 方法：造含已知改动（SQL 注入）的 dangling commit，跑真实 `prism review commit`，拿真实 JSON 逐字段对照 §5.3
> 结论：**§5.3 的 Finding 模型与 prism 真实输出兼容，无需改 domain 类型**。3 处实测与文档偏差已记录，adapter 据此实现。

---

## 1. 执行命令

```sh
# .env: NEWAPI_BASE_URL=https://newapi.sakta.top/v1, NEWAPI_API_KEY=<filled>
PRISM="C:/Users/17428/go/bin/prism.exe"
export OPENAI_API_KEY="$NEWAPI_API_KEY"
export PRISM_OPENAI_BASE_URL="$NEWAPI_BASE_URL/chat/completions"   # 完整 endpoint，非 base path

prism review commit <dangling_sha> --parent <base_sha> \
  --provider openai --model "DeepSeek-V4-Flash[free]" \
  --format json --fail-on none
# EXIT=0  （fail-on=none ⇒ 找到 high+medium 仍 exit 0，判定权在 GatePolicy）
```

---

## 2. 实测与文档的偏差（3 项，adapter 已据此修正）

| # | 文档原文 | 实测（prism 0.5.0） | adapter 处置 |
|---|---|---|---|
| 1 | spike §1.3 称 prism v0.6.0 | 二进制版本 **0.5.0**（`prism version` 输出 `prism version 0.5.0`） | `EngineDescriptor.engineVersion` 记实测版本（运行期 `prism version` 解析），不硬编码 |
| 2 | §10.3 preflight "引擎二进制可解析 + `--version` 成功（F5）" | prism **无 `--version` flag**，只有 `prism version` 子命令 | preflight 用 `prism version` 探活，而非 `--version` |
| 3 | spike §1.3 / §10.1.1：`OPENAI_BASE_URL` 指向网关 | prism 0.5.0 的 openai provider 读 **`PRISM_OPENAI_BASE_URL`**（源码 `internal/providers/openai.go:31`），且值是**完整 endpoint**（`.../v1/chat/completions`），非 base path；`OPENAI_BASE_URL` 被忽略 | adapter 从 `.env` 读 `NEWAPI_BASE_URL`，运行时注入 `PRISM_OPENAI_BASE_URL=<base>/chat/completions` + `OPENAI_API_KEY`。`.env` 命名保持 `NEWAPI_*`（用户友好），adapter 负责转译 |

> 偏差 3 的证据：未设 `PRISM_OPENAI_BASE_URL` 时 prism 直连 `https://api.openai.com/v1/chat/completions`（exit 4, provider 网络）。设了之后 exit 0 并产出 JSON。源码确认：`os.Getenv("PRISM_OPENAI_BASE_URL")` 为空时 fallback 到 `defaultOpenAIURL = "https://api.openai.com/v1/chat/completions"`，且该值在第 77 行直接作为 POST 的完整 URL。

---

## 3. 真实 JSON 样本（节选）

```json
{
  "tool": "prism",
  "version": "1.0",
  "runId": "f37b8f6c677d78c6c5d8a90c03fca0b2",
  "repo": { "root": "...", "head": "<base_sha>", "branch": "main" },
  "inputs": { "mode": "commit", "range": "<dangling_sha>" },
  "summary": {
    "counts": { "low": 0, "medium": 1, "high": 1 },
    "highestSeverity": "high"
  },
  "findings": [
    {
      "id": "c8c11ece8f090cdf",
      "severity": "high",
      "category": "security",
      "title": "SQL injection in login query",
      "message": "The `user` parameter is concatenated directly into a SQL query...",
      "suggestion": "Use parameterized queries, e.g., `execute(\"SELECT * FROM users WHERE name = %s\", (user,))`...",
      "confidence": 1,
      "locations": [ { "path": "app.py", "lines": { "start": 2, "end": 2 } } ],
      "tags": ["sql-injection", "security", "input-validation"],
      "provider": "openai",
      "model": "DeepSeek-V4-Flash[free]"
    }
  ],
  "timing": { "gitMs": 0, "llmMs": 32936, "totalMs": 32981 },
  "_provenance": [ { "ai_generated": true, "provider": "openai", "model": "DeepSeek-V4-Flash[free]" } ]
}
```

---

## 4. 逐字段对照 §5.3 Finding

| §5.3 字段 | prism JSON 字段 | 结论 |
|---|---|---|
| `severity` (BLOCKER\|WARNING\|NIT\|INFO) | `severity`: **low/medium/high** | 词表 low/medium/high，经 severity_map 映射；未知值 → BLOCKER + degraded（§5.3 约束 3） |
| `rawSeverity` | `severity` 原值 | 保留 low/medium/high 供审计 |
| `path` | `locations[].path` | 仓库相对、`/` 分隔 ✅ |
| `lineStart`/`lineEnd` | `locations[].lines.{start,end}` | `{start,end}` 结构，单行 start==end ✅ |
| `ruleId` (可空) | `id`（hash，如 `c8c11ece8f090cdf`） | 无稳定规则词表，符合 §5.3 预期 ✅ |
| `message` | `message`（`title` 并入 message 前缀） | ✅ |
| `suggestion` | `suggestion` | ✅ |

**prism 有、§5.3 Finding 没有的字段**：`category`、`title`、`confidence`、`tags`、每条 finding 的 `provider`/`model`。§5.3 Finding 是各引擎差异的**超集**设计，这些暂不归一化（不影响 GatePolicy 判定）。`title` 信息量大，adapter 将其并入 `message`（`title + ": " + message`）以免丢失。

---

## 5. coveredPaths 核对（spike §4.4 #2 明令）

**结论：prism JSON 不含「实际审了哪些文件」字段。**

`summary` 只有 `counts{low,medium,high}` + `highestSeverity`，无 coveredPaths 等价字段；每条 finding 的 `locations[].path` 是**有 finding 的文件**，不是「审过的全部文件」（无 finding 的文件不出现）。

→ **按 N6 处置**：adapter 置 `coveredPaths = 输入的 changedPaths 全集`，并 `degraded = true`。不得假设 `locations[].path` 的并集 = 全覆盖（一个文件可能被审过但无 finding，从而不在 locations 里）。

`degraded=true` 经 GatePolicy 强制 reject（§8.2 / GatePolicy.visit(EngineReport) 第 52-55 行）。**这意味着 P2 阶段所有 prism 审核都会 reject**——因为 prism 0.5.0 不输出 coveredPaths，N6 强制 degraded。

> 这是 fail-closed 的正确行为，不是 bug：在引擎无法证明「审了全部改动文件」时，默认拒。P4/H1 若评估发现 prism 升级后输出该字段，可摘掉 degraded。当前阶段验证的是「管道通 + fail-closed 严」，不是「审得准」（spike §4.4 #3 已批"忽略"）。

**对 A6（blocker reject）的影响**：A6 用「已知能触发 blocker 的构造 diff」验证 reject 链路。但 P2 下 degraded=true 会让 GatePolicy 在到达 finding 判定前就 reject（degraded 检查在 coverage/finding 之前）。为验证「blocker finding 本身能触发 reject」（而非靠 degraded），A6 测试用 **ManualReviewEngine** 走 blocker 路径（ManualReviewEngine 的 coveredPaths=changedPaths、degraded=false），验证 GatePolicy 的 finding→reject 分支；prism 的端到端 reject 由 A7 的 fail-closed 覆盖。两引擎走同一 `decide()`，验证「加第二引擎零 core 改动」。

---

## 6. 退出码映射确认（spike §1.4 修正版）

实测 exit 0（有 findings 但 fail-on=none）。映射照修正版：

| prism exit | 实测含义 | 映射 |
|---|---|---|
| 0 | 无达阈（fail-on=none 下即「跑完」） | EngineReport，交 GatePolicy |
| 1 | 有达 --fail-on 阈值 | 配置漂移 → reject（--fail-on=none 下不应出现） |
| 2 | usage / flag 漂移 | EngineFailure(CRASH) → 闸门 22（勿重试） |
| 3 | provider auth/config | EngineFailure(CRASH) → 闸门 22（勿重试） |
| 4 | runtime（git/provider 网络/IO/schema） | EngineFailure(CRASH) → 闸门 20（可重试，视同拒） |

exit 4 是混合桶，adapter 解析 stderr 首行归类（仅记入 detail，不做退出码级重试分流，N5）；归类失败一律 reject。

---

## 7. argv 指纹（EngineDescriptor.argvFingerprint）

adapter 记录实际 argv 的稳定指纹（sha256），含 `review commit <sha> --parent <base> --provider openai --model <name> --format json --fail-on none`。base/provider/model/sha 随工单变，指纹用于历史可比与漂移检测（N4）。
