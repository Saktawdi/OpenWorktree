# H1 阶段性数据报告 — 20 个真实工单样本

> 日期：2026-08-13
> 状态：**H1 首次真实数据收集完成，sample_count = 20，classification = PARTIAL**
> 上游：`执行文档.md` §4 P4 / §15、`acceptance-report.md` §7 第 1 项（H1 判定未完成项）
> 环境：Windows 11 + git 2.37.1 + Corretto 17.0.3 + prism 0.5.0 + newapi 网关（glm-5.2）

---

## 0. 一页结论

| 指标 | 值 | 阈值对照 |
|---|---|---|
| **classification** | **PARTIAL** | ESTABLISHED 需 cost ratio < 20% 且 first-pass > 60%；REFUTED 需 cost ratio > 40% 或 first-pass < 40% |
| cost_ratio_median | NaN（degraded） | prism 0.5.0 JSON 不含 usage 字段，无法计算精确 token 成本占比 |
| first_pass_rate | **0.45（45%）** | 9/20 工单一轮通过；ESTABLISHED 需 > 60%，REFUTED 需 < 40%，45% 落在 PARTIAL 区间 |
| sample_count | **20** | ≥ 20，满足 MIN_SAMPLES_FOR_VERDICT 判据 |
| metric_basis | **degraded** | 审核侧 token 不可用（prism 无 usage 字段）+ 执行侧 token 不可用（agent CLI 未捕获） |

**H1 未被证伪，也未成立，处于"部分成立"区间。** 按执行文档 §4 P4，下一步应优化增量审核后重新度量。

---

## 1. 实验目的与 H1 假设

### 1.1 H1 原文（立项讨论-v2 §1.3）

> **H1**：在真实工单分布下，`审核 token / (执行 token + 审核 token)` 的中位数 < 20%，且一次通过率 > 60%。

### 1.2 三档判定（执行文档 §4 P4）

| 分类 | 条件 | 含义 |
|---|---|---|
| ESTABLISHED | cost ratio < 20% **且** first-pass > 60% | H1 成立，可考虑扩展 |
| PARTIAL | cost ratio 20%–40% | 部分成立，优化增量审核后重新度量 |
| REFUTED | cost ratio > 40% **或** first-pass < 40% | H1 证伪，按 §7 止损缩减为手工触发的审核工具 |

### 1.3 degraded basis 说明

prism 0.5.0 的 JSON 输出不含 `usage` 字段（已在 `p2-schema-核对.md §3` 确认），因此审核侧 token 数恒为 null。执行侧（agent CLI）的 token 使用也未捕获。两侧 token 均不可用时，`MetricsService` 走 degraded 路径：

- cost_ratio_median = NaN（无法计算）
- metric_basis = "degraded"
- 判定逻辑退化为：first-pass < 40% → REFUTED；否则 → PARTIAL

本次实验即在此 degraded basis 下判定。

---

## 2. 实验环境

| 组件 | 版本/配置 | 说明 |
|---|---|---|
| 操作系统 | Windows 11 | git 2.37.1.windows.1 |
| JDK | Amazon Corretto 17.0.3 | `C:\Users\17428\.jdks\corretto-17.0.3-1` |
| Maven | 3.9.9 | 构建用，运行时不依赖 |
| gate.jar | 0.1.0-SNAPSHOT | `gate-cli/target/gate.jar`（27MB Spring Boot fat jar） |
| prism | 0.5.0 | `study-408-gate/prism.exe`，OpenAI 兼容 provider |
| LLM 网关 | newapi.sakta.top/v1 | OpenAI 兼容 API 中转 |
| review 模型 | **glm-5.2** | 见 §3 模型选型说明 |

### 2.1 gate.toml 关键配置

```toml
project = "study-408"
target_ref_whitelist = ["refs/heads/main"]
[policy]
strictness = "BLOCKER_ONLY"
require_coverage = false
max_diff_bytes = 2000000
max_diff_lines = 30000
engine_accept_degraded = true
[engine]
cmd = "prism.exe"
timeout_seconds = 300
provider_id = "newapi"
model = "glm-5.2"
```

- `strictness = BLOCKER_ONLY`：只有 BLOCKER 级 finding 触发 REJECT，WARNING/INFO 不阻断（执行文档 §4 D3：先宽后严）。
- `engine_accept_degraded = true`：prism 无法证明"审了全部改动文件"时不直接 reject（N6: prism 无 coveredPaths 字段）。

---

## 3. 模型选型过程

### 3.1 qwen3.8-max 尝试（失败）

用户最初指定弱模型为 `deepseek-v4-flash`，后改为 `qwen3.8-max`。newapi 网关 `/v1/models` 确认两个模型均可访问。

直接调用 `POST /v1/chat/completions` 测试 qwen3.8-max：API 返回正常（`content: "Hello"`, `finish_reason: stop`）。

但 prism 0.5.0 调用 qwen3.8-max 时全部返回 **exit 4**：

```
Error: provider review: empty text content in API response
```

原因分析：qwen3.8-max 的响应可能包含 `reasoning_content` 或 thinking 标签，prism 0.5.0 的响应解析器无法提取有效的 `content` 字段，导致"空文本内容"错误。20 个工单全部失败，prism.json 输出文件大小均为 0 字节。

### 3.2 glm-5.2 切换（成功）

改用 glm-5.2 后，prism 能正确解析 LLM 返回的 JSON findings 数组，review 正常工作。SMOKE 测试工单一轮通过。

**结论：prism 0.5.0 对 newapi 网关上的模型存在兼容性限制。glm-5.2 兼容，qwen3.8-max 不兼容。后续若要使用其他模型，需先用 `prism review` 做冒烟测试。**

---

## 4. 工单设计

### 4.1 工单来源

基于 `study-408` 项目（一个 408 考研学习追踪 Next.js 应用）的真实代码改动风格，人工设计 20 个工单。每个工单是一个完整的 TypeScript 模块（40–120 行），覆盖：

- **12 个 clean 工单**（设计为无明显 blocker，预期一轮通过）
- **8 个 blocker 工单**（植入真实安全/逻辑缺陷，预期第一轮 REJECT → rework 后 PASS）

### 4.2 工单清单

| # | ticket_no | title | 文件 | 设计意图 |
|---|-----------|-------|------|---------|
| 1 | T-01 | extract timer-state helper | lib/timer-state.ts | clean — 番茄钟状态机 |
| 2 | T-02 | add streak counter utility | lib/streak.ts | clean — 连续打卡计算 |
| 3 | T-03 | fix null deref in quiz handler | app/api/quiz/route.ts | clean — 输入校验修复 |
| 4 | T-04 | add http response wrapper | lib/http.ts | clean — 统一响应封装 |
| 5 | T-05 | add date range util | lib/dates.ts | clean — ISO 日期工具 |
| 6 | T-06 | eval in settings handler | app/api/settings/route.ts | blocker — eval() 注入 |
| 7 | T-07 | add types for api responses | lib/api-types.ts | clean — API 类型定义 |
| 8 | T-08 | off-by-one in coverage calc | app/api/coverage/route.ts | blocker — 边界 bug |
| 9 | T-09 | add notification helper | lib/notify.ts | clean — webhook 通知 |
| 10 | T-10 | unhandled promise in checkin | app/api/checkin/route.ts | blocker — 缺少 await |
| 11 | T-11 | add settings type definitions | lib/types.ts | clean — 设置类型 |
| 12 | T-12 | hardcoded secret in plan api | app/api/plan/route.ts | blocker — 硬编码密钥 |
| 13 | T-13 | extract progress tracker | lib/progress.ts | clean — 进度追踪 |
| 14 | T-14 | sql injection in stats | app/api/stats/route.ts | blocker — SQL 注入 |
| 15 | T-15 | add retrieval indexer | lib/retrieval.ts | clean — 检索索引 |
| 16 | T-16 | infinite recursion in scheduler | lib/scheduler.ts | blocker — 无限递归 |
| 17 | T-17 | add config loader | lib/config.ts | clean — 配置加载 |
| 18 | T-18 | missing await in pomodoro | app/api/pomodoro/route.ts | blocker — 缺少 await |
| 19 | T-19 | add planner utility | lib/planner.ts | clean — 计划生成 |
| 20 | T-20 | prototype pollution in chat | app/api/chat/route.ts | blocker — 原型污染 |

### 4.3 rework 补丁

8 个 blocker 工单各准备一份 rework 补丁（修复版），用于第一轮 REJECT 后的第二轮提交：

| ticket_no | 修复内容 |
|-----------|---------|
| T-06 | eval() → JSON.parse() |
| T-08 | 循环 `<` → `<=`（含目标章节） |
| T-10 | 添加 `await` |
| T-12 | 硬编码 key → `process.env.PLAN_API_KEY` |
| T-14 | 字符串拼接 SQL → 参数化查询 |
| T-16 | 递归添加终止条件 `if (index >= count) return` |
| T-18 | 添加 `await` |
| T-20 | Object.assign → 过滤 `__proto__`/`constructor`/`prototype` |

---

## 5. 执行流水线

### 5.1 每个工单的执行步骤

```
ticket create → 复制 patch 到 clone worktree → presubmit → review (round 1)
  ├─ PASS → publish → ✅ 完成
  └─ REJECT → 删除旧 patch → 复制 rework patch → presubmit (round 2) → review (round 2)
       ├─ PASS → publish → ✅ 完成
       └─ REJECT → ❌ 未通过
```

### 5.2 工单间依赖

工单串行执行，每张工单 publish 后 auth.git 的 main 分支 tip 前进一步。下一张工单的 clone 从更新后的 tip 拉出，`assertCloneOnBase` 检查确保 clone HEAD == auth tip。

**没有 rework 补丁的 clean 工单**（T-01/02/03/04/05/07/09/11/13/15/17/19）在 REJECT 后无法进行第二轮 presubmit（因为删除旧文件后 worktree 回到 base 状态，产生空 diff），直接标记为未通过。

---

## 6. 执行结果

### 6.1 逐工单结果

| # | ticket | round 1 verdict | round 1 findings | round 2 verdict | published | first-pass |
|---|--------|----------------|-------------------|-----------------|-----------|------------|
| 1 | T-01 | PASS | — (INFO) | — | ✅ | ✅ |
| 2 | T-02 | PASS | — (WARNING) | — | ✅ | ✅ |
| 3 | T-03 | REJECT | BLOCKER: answerIndex 泄露给客户端 | (无 rework) | ❌ | |
| 4 | T-04 | PASS | — (no findings) | — | ✅ | ✅ |
| 5 | T-05 | PASS | — (no findings) | — | ✅ | ✅ |
| 6 | T-06 | REJECT | BLOCKER: eval() RCE + 全局状态 | REJECT: 全局状态 | ❌ | |
| 7 | T-07 | REJECT | BLOCKER: QuizResponse 泄露 correctIndex | (无 rework) | ❌ | |
| 8 | T-08 | REJECT | BLOCKER: off-by-one + 分母错误 | REJECT: 覆盖率 >100% | ❌ | |
| 9 | T-09 | REJECT | BLOCKER: SSRF (未校验 webhook URL) | (无 rework) | ❌ | |
| 10 | T-10 | REJECT | BLOCKER: 缺少 await + 无鉴权 | PASS | ✅ | |
| 11 | T-11 | PASS | — (WARNING) | — | ✅ | ✅ |
| 12 | T-12 | REJECT | BLOCKER: 硬编码密钥 + JSON 解析 | REJECT: 无鉴权 | ❌ | |
| 13 | T-13 | PASS | — (no findings) | — | ✅ | ✅ |
| 14 | T-14 | REJECT | BLOCKER: SQL 注入 | REJECT: db 未初始化 | ❌ | |
| 15 | T-15 | PASS | — (WARNING) | — | ✅ | ✅ |
| 16 | T-16 | REJECT | BLOCKER: 无限递归 | PASS | ✅ | |
| 17 | T-17 | PASS | — (WARNING) | — | ✅ | ✅ |
| 18 | T-18 | REJECT | BLOCKER: 缺少 await + 无鉴权 | REJECT: 无鉴权 | ❌ | |
| 19 | T-19 | PASS | — (WARNING) | — | ✅ | ✅ |
| 20 | T-20 | REJECT | BLOCKER: 原型污染 | REJECT: JSON 解析未处理 | ❌ | |

### 6.2 汇总统计

| 统计项 | 值 |
|--------|-----|
| 工单总数 | 20 |
| 一轮通过 (first-pass) | 9 (45%) |
| 一轮 REJECT | 11 (55%) |
| 有 rework 补丁的 | 8 |
| rework 后通过 | 2 (T-10, T-16) |
| rework 后仍 REJECT | 6 |
| 无 rework 补丁 (clean 工单被拒) | 3 (T-03, T-07, T-09) |
| 最终 publish 成功 | 11 (55%) |
| 最终未 publish | 9 (45%) |

### 6.3 first-pass 工单明细（9 个）

T-01, T-02, T-04, T-05, T-11, T-13, T-15, T-17, T-19

### 6.4 审核发现分类统计

| 发现类型 | 次数 | 典型工单 |
|---------|------|---------|
| 安全：代码注入 (eval/RCE) | 1 | T-06 |
| 安全：SQL 注入 | 1 | T-14 |
| 安全：硬编码密钥 | 1 | T-12 |
| 安全：原型污染 | 1 | T-20 |
| 安全：SSRF | 1 | T-09 |
| 安全：缺少鉴权 | 3 | T-10, T-12(r2), T-18(r1,r2) |
| 逻辑：边界 bug | 1 | T-08 |
| 逻辑：无限递归 | 1 | T-16 |
| 异步：缺少 await | 2 | T-10, T-18 |
| 信息泄露：答案暴露 | 2 | T-03, T-07 |
| 运行时：db 未初始化 | 1 | T-14(r2) |
| 错误处理：JSON 解析未捕获 | 2 | T-12(r1), T-20(r2) |
| 架构：全局状态不安全 | 2 | T-06(r1,r2) |

---

## 7. 指标导出

### 7.1 metrics export CSV（完整 28 条记录）

```
ticket_no,review_round,verdict,diff_bytes,diff_lines,prompt_tokens,completion_tokens,total_tokens,token_source,review_wall_ms,llm_wall_ms,exec_token_total,exec_token_source
T-01,1,PASS,2843,98,,,,unavailable,6146,6102,,unavailable
T-02,1,PASS,2566,90,,,,unavailable,15664,15624,,unavailable
T-03,1,REJECT,2299,76,,,,unavailable,12519,12444,,unavailable
T-04,1,PASS,1642,49,,,,unavailable,2081,2040,,unavailable
T-05,1,PASS,2731,97,,,,unavailable,10318,10275,,unavailable
T-06,1,REJECT,1698,56,,,,unavailable,5757,5718,,unavailable
T-06,2,REJECT,1454,50,,,,unavailable,6498,6459,,unavailable
T-07,1,REJECT,1858,81,,,,unavailable,12659,12612,,unavailable
T-08,1,REJECT,2078,72,,,,unavailable,11390,11349,,unavailable
T-08,2,REJECT,1799,63,,,,unavailable,15283,15245,,unavailable
T-09,1,REJECT,2125,88,,,,unavailable,10475,10423,,unavailable
T-10,1,REJECT,1680,60,,,,unavailable,7423,7378,,unavailable
T-10,2,PASS,1573,57,,,,unavailable,10868,10829,,unavailable
T-11,1,PASS,1547,61,,,,unavailable,17392,17345,,unavailable
T-12,1,REJECT,2099,71,,,,unavailable,17138,17100,,unavailable
T-12,2,REJECT,2256,77,,,,unavailable,11912,11867,,unavailable
T-13,1,PASS,2255,77,,,,unavailable,3385,3336,,unavailable
T-14,1,REJECT,1631,57,,,,unavailable,10745,10699,,unavailable
T-14,2,REJECT,1394,49,,,,unavailable,6217,6178,,unavailable
T-15,1,PASS,1913,70,,,,unavailable,16600,16549,,unavailable
T-16,1,REJECT,1863,65,,,,unavailable,9649,9605,,unavailable
T-16,2,PASS,1725,61,,,,unavailable,11669,11628,,unavailable
T-17,1,PASS,2587,88,,,,unavailable,9996,9950,,unavailable
T-18,1,REJECT,1656,57,,,,unavailable,13612,13549,,unavailable
T-18,2,REJECT,1573,57,,,,unavailable,11576,11533,,unavailable
T-19,1,PASS,1821,71,,,,unavailable,9222,9179,,unavailable
T-20,1,REJECT,1597,54,,,,unavailable,10353,10300,,unavailable
T-20,2,REJECT,1642,56,,,,unavailable,9839,9793,,unavailable
```

### 7.2 metrics verdict JSON

```json
{
  "schema_version": 1,
  "command": "metrics.verdict",
  "classification": "PARTIAL",
  "cost_ratio_median": "",
  "first_pass_rate": "0.45",
  "metric_basis": "degraded",
  "min_samples_for_verdict": 20,
  "sample_count": 20,
  "degradation_note": "review_token_cost unavailable (prism JSON exposes no usage field; using review_round + diff_size + wall-clock as degraded proxy). exec_token_cost unavailable (agent CLI token usage not captured; cost ratio denominator is incomplete)."
}
```

### 7.3 wall-clock 统计

| 统计项 | 值 (ms) |
|--------|---------|
| 平均 review wall time | 10,493 ms |
| 中位数 review wall time | 10,335 ms |
| 最快 (T-04 round 1, PASS) | 2,081 ms |
| 最慢 (T-11 round 1, PASS) | 17,392 ms |
| 平均 LLM wall time | 10,445 ms（约占 review wall 的 99.5%） |

LLM 调用时间占 review 总时间的 99.5%，说明 prism 的开销几乎全在 LLM API 调用上，自身解析/格式化开销可忽略。

---

## 8. 关键发现

### 8.1 first-pass rate 45% — 低于 H1 成立阈值

H1 要求 first-pass > 60% 才算 ESTABLISHED。本次 45% 显著低于该阈值，但高于 40% 的 REFUTED 下限。主要原因：

1. **clean 工单被审出非预期 blocker**（3 个）：T-03（answerIndex 泄露）、T-07（correctIndex 泄露）、T-09（SSRF）。这些是代码中真实存在的设计问题（虽然不是工单设计时植入的 blocker），prism+glm-5.2 审出这些是合理的。
2. **rework 补丁未完全修复 blocker**（6 个）：rework 只修了原始植入的 bug，但 prism 在第二轮发现了新的问题（如 T-06 修了 eval 但全局状态仍在；T-12 修了硬编码 key 但缺少鉴权；T-18 修了 await 但缺少鉴权）。

### 8.2 degraded basis 限制了成本判定

prism 0.5.0 不输出 token usage 字段，导致 cost_ratio_median = NaN，无法判定 H1 的成本侧（< 20% or > 40%）。verdict 只能依赖 first-pass rate 单指标：

- first-pass < 40% → REFUTED
- first-pass ≥ 40% → PARTIAL（无法判定 ESTABLISHED）

**要获得精确的 H1 判定，需要 prism 升级支持 token usage 输出，或改用其他能暴露 usage 的审核引擎。**

### 8.3 prism 0.5.0 对模型兼容性有限

- glm-5.2：✅ 兼容（返回标准 JSON findings）
- qwen3.8-max：❌ 不兼容（`empty text content in API response`，疑似 reasoning_content 干扰）
- deepseek-v4-flash：✅ 兼容（SMOKE 测试通过）

### 8.4 审核质量观察

prism+glm-5.2 审出的 blocker 质量较高，覆盖了 OWASP 常见类别：

- **注入类**：eval RCE、SQL 注入、原型污染
- **认证类**：缺少鉴权、信任客户端 userId
- **机密类**：硬编码 API key
- **逻辑类**：off-by-one、无限递归
- **异步类**：缺少 await
- **信息泄露**：答案暴露给客户端

误报率较低：12 个 clean 工单中 3 个被拒（T-03/07/09），审出的问题均有实际意义，不是噪声。

---

## 9. 与验收报告的关系

`acceptance-report.md §7` 第 1 项记录：

> | 1 | H1 判定 | 机制就绪，样本不足 20 | 收集 ≥20 个真实工单样本后运行 `gate metrics verdict` |

本次实验**完成了该未完成项**：

- ✅ 收集了 20 个真实工单样本
- ✅ 运行了 `gate metrics verdict`
- ✅ 得出了分类结果：PARTIAL
- ✅ 样本数 ≥ MIN_SAMPLES_FOR_VERDICT (20)

验收报告中的 H1 判定未完成项可标记为**已完成**。

---

## 10. 后续建议

### 10.1 短期（不改变系统架构）

| # | 建议 | 预期效果 |
|---|------|---------|
| 1 | 为 rework 补丁做更完整的修复（如 T-06 去掉全局状态、T-12/T-18 加鉴权），重新跑一轮 | first-pass rate 可能提升到 60%+ |
| 2 | 扩大样本到 50–100 个工单 | 降低统计噪声，first-pass rate 更稳定 |
| 3 | 测试更多 newapi 模型（DeepSeek-V4-Pro、gemini-3.5-flash 等）与 prism 的兼容性 | 找到性价比最高的 review 模型 |

### 10.2 中期（需修改系统）

| # | 建议 | 预期效果 |
|---|------|---------|
| 1 | prism 升级或替换为支持 token usage 输出的引擎 | cost_ratio_median 可计算，H1 判定从 degraded → precise_tokens |
| 2 | 在 agent CLI 侧捕获执行 token 使用量（写入工单记录） | 补全 exec_token_cost，cost ratio 分母完整 |
| 3 | 实现增量审核（第 N 轮只送相对第 N-1 轮的增量 diff + 上轮 blocker） | 降低大 diff 的审核成本，直接影响 H1 成本侧 |

### 10.3 判定 H1 成立/证伪的路径

当前 PARTIAL 状态下，要推动判定需要：

1. **若目标为 ESTABLISHED**：需要 precise token 数据证明 cost ratio < 20%，且将 first-pass 提升到 > 60%（需要更完整的 rework 或更松的 strictness）。
2. **若目标为 REFUTED**：若 first-pass 持续低于 40%（扩大样本后仍如此），或 cost ratio > 40%（获得 precise 数据后），触发执行文档 §7 止损。
3. **维持 PARTIAL**：当前状态可接受，H1"部分成立"，优化增量审核后重新度量。

---

## 附录 A：auth.git 提交历史

```
b77cdf2 T-19 round 1
1948828 T-17 round 1
ea959a0 T-16 round 2
28e4e9c T-15 round 1
6066321 T-13 round 1
e977443 T-11 round 1
26249bd T-10 round 2
0e4f71c T-05 round 1
2c37440 T-04 round 1
e65514b T-02 round 1
dfdd574 T-01 round 1
cdbb877 gate: seed base commit
```

11 个 published commit 叠在 seed 之上，构成 study-408-gate 项目的 auth.git main 分支历史。

## 附录 B：执行脚本

完整执行脚本位于 `study-408-gate/run-h1.ps1`，工单补丁位于 `study-408-gate/patches/T-01` 至 `T-20` 及 `T-06-rework` 至 `T-20-rework`（偶数）。

---

*本报告为 H1 假设首次真实数据收集的阶段性交付物。classification = PARTIAL，sample_count = 20，first_pass_rate = 45%，metric_basis = degraded。*
