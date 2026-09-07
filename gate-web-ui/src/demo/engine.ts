import { addSnapshot, setFindings, setGateBusy, setOutcome, setTask, setVerdict } from "@/features/gate";
import { addUsage, applyTodosSnapshot, clearSessionEnded, ensureCurrentSession, finishAssistant, markSessionEnded, patchAssistant, pushAssistantPlaceholder, pushSystemMessage, pushUserMessage, setBusy, setContextLimit, setContextTokens, setSessionBusy } from "@/features/session";
import { currentCancelSeq, requestCancel, setDiffs, setStage } from "@/features/ticket";
import { setCenterTab, showToast, appStore } from "@/store";
import type { ChatItem, DiffFile, Finding, ToolCallView, TodoItem } from "@/shared/types";
import { approxDiffBytes } from "@/shared/diff";
import { fakeSha, sleep, uid } from "@/shared/format";
import { DIFF_T104_R1, DIFF_T104_R2, FINDINGS_R1 } from "@/demo/scenario";
type Assistant = Extract<ChatItem, { kind: "assistant" }>;

function alive(no: string, seq: number): boolean {
  return currentCancelSeq(no) === seq;
}

/**
 * demo 模式中止当前回合（actions.abort 的 demo 分支）：
 * · bump cancelSeq 使 in-flight 回合的 alive() 立即失效——过期回合的 finally
 *   守卫住，不会再做任何状态回写（取代-清理语义：谁取代，谁负责清理）；
 * · 立即释放工单级/会话级运行态（对齐 live abortLive 的乐观翻转）——回合已被
 *   主动终结，composer 与前置运行点必须马上解锁，不等待脚本自然退出。
 * · 目标会话按 sessionBusy 反查，而非 activeSessionId：demo 的中止按钮走工单级
 *   busy，运行中切换会话后 activeSessionId 已指向别处，按它定位会误清/误标
 *   新会话，真正在跑的会话反而残留 sessionBusy 且收不到 failed 标记。
 * 无在跑回合时为幂等 no-op。中止的回合按 live abortingSessions 口径打"failed"。
 */
export function demoAbort(no: string) {
  const st = appStore.getState();
  if (!st.busy[no]) return;
  // busy 守卫保证每工单至多一个在跑回合，且回合起止与 sessionBusy 严格同步
  // （demoSendPrompt / demoReturnWithFindings / 本函数），据此反查唯一运行会话。
  const sid = (st.sessions[no] ?? []).find((sess) => st.sessionBusy[sess.id] === true)?.id;
  if (!sid) return;
  requestCancel(no);
  setBusy(no, false);
  setSessionBusy(sid, false);
  markSessionEnded(no, "failed", sid);
}

async function typeInto(
  no: string,
  id: string,
  full: string,
  aliveFn: () => boolean,
  key: "text" | "thinking" = "text",
): Promise<boolean> {
  let i = 0;
  while (i < full.length) {
    if (!aliveFn()) return false;
    const step = 2 + Math.floor(Math.random() * 3);
    i = Math.min(full.length, i + step);
    const slice = full.slice(0, i);
    patchAssistant(no, id, (a) =>
      key === "text"
        ? { ...a, text: slice }
        : { ...a, thinking: { ...(a.thinking as NonNullable<Assistant["thinking"]>), text: slice } },
    );
    await sleep(key === "text" ? 16 + Math.random() * 26 : 24 + Math.random() * 30);
  }
  return true;
}

export async function streamThinking(
  no: string,
  id: string,
  text: string,
  aliveFn: () => boolean,
): Promise<boolean> {
  patchAssistant(no, id, (a) => ({
    ...a,
    thinking: { text: "", startedAt: Date.now(), done: false },
  }));
  const ok = await typeInto(no, id, text, aliveFn, "thinking");
  patchAssistant(no, id, (a) => ({
    ...a,
    thinking: a.thinking ? { ...a.thinking, text: ok ? text : a.thinking.text, done: true } : a.thinking,
  }));
  await sleep(320);
  return ok;
}

export async function runTool(
  no: string,
  id: string,
  spec: Omit<ToolCallView, "id" | "status">,
  durationMs: number,
  result: { summary: string; detail?: string },
  aliveFn: () => boolean,
): Promise<boolean> {
  const toolId = uid("tool");
  patchAssistant(no, id, (a) => ({
    ...a,
    tools: [...a.tools, { ...spec, id: toolId, status: "running" }],
  }));
  const tick = Math.max(6, durationMs / 14);
  let elapsed = 0;
  while (elapsed < durationMs) {
    if (!aliveFn()) {
      patchAssistant(no, id, (a) => ({
        ...a,
        tools: a.tools.map((t) => (t.id === toolId ? { ...t, status: "error", resultSummary: "已中断" } : t)),
      }));
      return false;
    }
    await sleep(tick);
    elapsed += tick;
  }
  patchAssistant(no, id, (a) => ({
    ...a,
    tools: a.tools.map((t) =>
      t.id === toolId ? { ...t, status: "ok", resultSummary: result.summary, resultDetail: result.detail } : t,
    ),
  }));
  await sleep(140);
  return true;
}

const IMPLEMENT_THINKING =
  "用户要求为结算接口添加速率限制。先确认现有路由结构与错误处理约定；项目里应该还没有限流实现，需要新建中间件；注意超限时应当返回 429，并且不能阻塞正常流量……";

const FIX_THINKING =
  "门禁驳回了第 1 轮快照：阻断项是桶表无上限增长，警告项是缺少 Retry-After 断言，还有一处魔法数字。逐条修复后补上测试，再走一轮预提审。";

const IMPLEMENT_REPLY = [
  "速率限制中间件已完成：",
  "",
  "· 新增 src/middleware/rate-limit.ts —— 令牌桶算法，60 秒窗口内每客户端最多 30 次请求",
  "· 在 POST /api/checkout 挂载中间件，超限返回 429",
  "· 本地单测全部通过（47 passed）",
  "",
  "工作区改动已就绪，可以预提审锁定快照；审查结果出来后我会继续跟进。",
].join("\n");

const FIX_REPLY = [
  "三项审查意见已全部修复：",
  "",
  "· 阻断项：为桶表增加容量上限，超限直接返回 503，杜绝内存被撑爆",
  "· 警告项：新增 tests/rate-limit.test.ts，断言 429 响应携带 Retry-After",
  "· 细微项：魔法数字 60 提取为命名常量 WINDOW_SECONDS",
  "",
  "本地单测 49 项全部通过。请再次预提审，生成第 2 轮快照。",
].join("\n");

/* ─── demo 任务清单与上下文占用模拟（T-113 侧栏环形图标的演示数据源） ─── */

const DEMO_CONTEXT_LIMIT = 8192;

const todo = (content: string, status: TodoItem["status"]): TodoItem => ({ content, status });

async function runTodoWrite(
  no: string,
  id: string,
  todos: TodoItem[],
  summary: string,
  ok: () => boolean,
): Promise<boolean> {
  const done = await runTool(
    no,
    id,
    { name: "任务清单", icon: "todo", argsSummary: `${todos.length} 项任务` },
    500,
    { summary, detail: JSON.stringify(todos, null, 2) },
    ok,
  );
  // 清单投影按会话 id 键控（V21）：demo 从工单号解析出当前活跃会话。
  const sessionId = appStore.getState().activeSessionId[no];
  if (done && sessionId) applyTodosSnapshot(sessionId, todos.map((t) => ({ ...t })));
  return done;
}

export async function demoSendPrompt(no: string, userText: string, images: string[] = []) {
  const st0 = appStore.getState();
  if (st0.busy[no]) return;
  // 草稿态（无当前会话）时首条消息创建新会话并固化所选 agent，与 live 模式行为一致。
  const sid = ensureCurrentSession(no);
  const seq = currentCancelSeq(no) + 1;
  appStore.setState({ cancelSeq: { ...st0.cancelSeq, [no]: seq } });
  // 工单重新进入运行状态：上一次的"会话已结束"提醒随之失效
  clearSessionEnded(no);
  pushUserMessage(no, userText, images);
  setBusy(no, true);
  // 会话级运行态与 live 同步（T-105 第 6 轮）：进入运行即熄灭该会话的中断红点，
  // demo 不再只依赖工单级 busy——否则 marked failed 的会话红点永远不会清除。
  setSessionBusy(sid, true);
  try {
    const freshSandbox = no === "T-104" && (st0.diffs[no]?.length ?? 0) === 0;
    if (freshSandbox) {
      await scriptImplement(no, userText, seq);
    } else {
      await scriptAck(no, userText, seq);
    }
  } finally {
    // 运行态清理严格守卫：只有本回合仍是当前回合（alive）时才允许回写——
    // 被中止/被取代的回合不做任何清理，运行态的释放由取代方（demoAbort /
    // 新回合起点）负责，避免过期 finally 把正在运行的新回合误解锁。
    const finished = alive(no, seq);
    if (finished) {
      setBusy(no, false);
      setSessionBusy(sid, false);
      // 与 live 的 done/error 对齐：正常收尾与中断分别打点，供工单列表提醒
      markSessionEnded(no, "done", sid);
    }
  }
}

const ACK_THINKING = "先确认当前沙箱状态：已有待审变更的话，优先建议走预提审把快照锁下来，避免工作区继续漂移……";

async function scriptAck(no: string, _userText: string, seq: number) {
  const ok = () => alive(no, seq);
  const id = pushAssistantPlaceholder(no);
  if (!(await streamThinking(no, id, ACK_THINKING, ok))) return aborted(no, id);
  const reply = [
    "已收到。当前沙箱内已有未提审的变更，建议先点击右侧「预提审 · 锁定快照」：",
    "",
    "· 快照锁定后，审查与发布都以此为准，后续改动不会影响本轮判决",
    "· 如需继续调整代码，直接告诉我具体要求即可",
  ].join("\n");
  if (!(await typeInto(no, id, reply, ok))) return aborted(no, id);
  finishAssistant(no, id);
  addUsage(no, 1240, 210);
}

async function scriptImplement(no: string, _userText: string, seq: number) {
  const ok = () => alive(no, seq);
  const id = pushAssistantPlaceholder(no);
  if (!(await streamThinking(no, id, IMPLEMENT_THINKING, ok))) return aborted(no, id);

  // demo：模拟模型上下文窗口占用（8K 上限，随回合推进增长）
  setContextLimit(no, DEMO_CONTEXT_LIMIT);
  setContextTokens(no, 1400);

  if (
    !(await runTodoWrite(
      no,
      id,
      [
        todo("调研结算路由与错误处理约定", "in_progress"),
        todo("实现令牌桶限流中间件", "pending"),
        todo("挂载至结算路由并接入 429", "pending"),
        todo("本地单测验证限流行为", "pending"),
      ],
      "4 项任务 · 开始执行",
      ok,
    ))
  )
    return aborted(no, id);

  if (
    !(await runTool(
      no,
      id,
      { name: "读取文件", icon: "file", argsSummary: "src/routes/checkout.ts" },
      900,
      { summary: "142 行 · 已定位路由注册点" },
      ok,
    ))
  )
    return aborted(no, id);
  setContextTokens(no, 2600);

  if (
    !(await runTool(
      no,
      id,
      { name: "全局搜索", icon: "search", argsSummary: "rateLimit · throttle" },
      700,
      { summary: "0 处匹配 · 项目内尚无限流实现" },
      ok,
    ))
  )
    return aborted(no, id);
  setContextTokens(no, 3400);

  if (
    !(await runTodoWrite(
      no,
      id,
      [
        todo("调研结算路由与错误处理约定", "completed"),
        todo("实现令牌桶限流中间件", "in_progress"),
        todo("挂载至结算路由并接入 429", "pending"),
        todo("本地单测验证限流行为", "pending"),
      ],
      "1/4 已完成",
      ok,
    ))
  )
    return aborted(no, id);

  if (
    !(await runTool(
      no,
      id,
      { name: "编辑文件", icon: "edit", argsSummary: "新建 src/middleware/rate-limit.ts" },
      1600,
      { summary: "+37 行 · 令牌桶限流中间件" },
      ok,
    ))
  )
    return aborted(no, id);
  applyDiffs(no, [DIFF_T104_R1[0]]);
  setContextTokens(no, 4600);

  if (
    !(await runTodoWrite(
      no,
      id,
      [
        todo("调研结算路由与错误处理约定", "completed"),
        todo("实现令牌桶限流中间件", "completed"),
        todo("挂载至结算路由并接入 429", "in_progress"),
        todo("本地单测验证限流行为", "pending"),
      ],
      "2/4 已完成",
      ok,
    ))
  )
    return aborted(no, id);

  if (
    !(await runTool(
      no,
      id,
      { name: "编辑文件", icon: "edit", argsSummary: "修改 src/routes/checkout.ts" },
      1100,
      { summary: "+2 −1 行 · 挂载至结算路由" },
      ok,
    ))
  )
    return aborted(no, id);
  applyDiffs(no, DIFF_T104_R1);
  setContextTokens(no, 5600);

  if (
    !(await runTodoWrite(
      no,
      id,
      [
        todo("调研结算路由与错误处理约定", "completed"),
        todo("实现令牌桶限流中间件", "completed"),
        todo("挂载至结算路由并接入 429", "completed"),
        todo("本地单测验证限流行为", "in_progress"),
      ],
      "3/4 已完成",
      ok,
    ))
  )
    return aborted(no, id);

  if (
    !(await runTool(
      no,
      id,
      { name: "运行命令", icon: "terminal", argsSummary: "npm test" },
      2600,
      { summary: "47 passed · 2.31s", detail: "Test Files  12 passed (12)\n     Tests  47 passed (47)\n  Duration  2.31s" },
      ok,
    ))
  )
    return aborted(no, id);
  setContextTokens(no, 7100);

  if (
    !(await runTodoWrite(
      no,
      id,
      [
        todo("调研结算路由与错误处理约定", "completed"),
        todo("实现令牌桶限流中间件", "completed"),
        todo("挂载至结算路由并接入 429", "completed"),
        todo("本地单测验证限流行为", "completed"),
      ],
      "4/4 已完成",
      ok,
    ))
  )
    return aborted(no, id);

  if (!(await typeInto(no, id, IMPLEMENT_REPLY, ok))) return aborted(no, id);
  finishAssistant(no, id);
  addUsage(no, 3842, 1204);
  setStage(no, "IN_PROGRESS");
}

async function scriptFix(no: string, seq: number) {
  const ok = () => alive(no, seq);
  const id = pushAssistantPlaceholder(no);
  if (!(await streamThinking(no, id, FIX_THINKING, ok))) return aborted(no, id);

  setContextTokens(no, 7400);

  if (
    !(await runTodoWrite(
      no,
      id,
      [
        todo("为桶表增加容量上限", "in_progress"),
        todo("补充 Retry-After 断言用例", "pending"),
        todo("提取魔法数字为命名常量", "pending"),
      ],
      "3 项修复任务",
      ok,
    ))
  )
    return aborted(no, id);

  if (
    !(await runTool(
      no,
      id,
      { name: "编辑文件", icon: "edit", argsSummary: "src/middleware/rate-limit.ts" },
      1500,
      { summary: "+9 −3 行 · 容量上限与命名常量" },
      ok,
    ))
  )
    return aborted(no, id);
  applyDiffs(no, [DIFF_T104_R2[0], DIFF_T104_R1[1]]);
  setContextTokens(no, 7700);

  if (
    !(await runTodoWrite(
      no,
      id,
      [
        todo("为桶表增加容量上限", "completed"),
        todo("补充 Retry-After 断言用例", "in_progress"),
        todo("提取魔法数字为命名常量", "completed"),
      ],
      "2/3 已完成",
      ok,
    ))
  )
    return aborted(no, id);

  if (
    !(await runTool(
      no,
      id,
      { name: "编辑文件", icon: "edit", argsSummary: "新建 tests/rate-limit.test.ts" },
      1200,
      { summary: "+25 行 · Retry-After 断言用例" },
      ok,
    ))
  )
    return aborted(no, id);
  applyDiffs(no, DIFF_T104_R2);
  setContextTokens(no, 7900);

  if (
    !(await runTool(
      no,
      id,
      { name: "运行命令", icon: "terminal", argsSummary: "npm test" },
      2400,
      { summary: "49 passed · 2.44s", detail: "Test Files  13 passed (13)\n     Tests  49 passed (49)\n  Duration  2.44s" },
      ok,
    ))
  )
    return aborted(no, id);
  setContextTokens(no, 8100);

  if (
    !(await runTodoWrite(
      no,
      id,
      [
        todo("为桶表增加容量上限", "completed"),
        todo("补充 Retry-After 断言用例", "completed"),
        todo("提取魔法数字为命名常量", "completed"),
      ],
      "3/3 已完成",
      ok,
    ))
  )
    return aborted(no, id);

  if (!(await typeInto(no, id, FIX_REPLY, ok))) return aborted(no, id);
  finishAssistant(no, id);
  addUsage(no, 5210, 986);
}

function aborted(no: string, id: string) {
  finishAssistant(no, id);
  pushSystemMessage(no, "生成已中断 · Agent 进程已终止并释放工单锁", "warn");
}

function applyDiffs(no: string, files: DiffFile[]) {
  setDiffs(no, files.map((f) => ({ ...f, hunks: f.hunks.map((h) => ({ ...h })) })));
}

export async function demoReturnWithFindings(no: string) {
  const st = appStore.getState();
  if (st.busy[no]) return;
  const findings = st.findings[no] ?? [];
  if (findings.length === 0) return;
  // 草稿态（无当前会话，如对带 diff 的工单直接走审查→修复）时自动新建会话。
  const sid = ensureCurrentSession(no);
  const seq = currentCancelSeq(no) + 1;
  appStore.setState({ cancelSeq: { ...st.cancelSeq, [no]: seq } });
  const lines = findings.map((f, i) => `${i + 1}. [${f.severity}] ${f.path}${f.lineStart ? ":" + f.lineStart : ""} — ${f.message}`);
  pushUserMessage(no, ["请按以下审查意见逐条修复：", ...lines].join("\n"));
  clearSessionEnded(no);
  setBusy(no, true);
  setSessionBusy(sid, true);
  try {
    await scriptFix(no, seq);
  } finally {
    // 运行态清理严格守卫（同 demoSendPrompt）：过期回合不回写，释放由取代方负责
    const finished = alive(no, seq);
    if (finished) {
      setBusy(no, false);
      setSessionBusy(sid, false);
      markSessionEnded(no, "done", sid);
    }
  }
}

export async function demoPresubmit(no: string) {
  const st = appStore.getState();
  if (st.gateBusy[no]) return;
  const files = st.diffs[no] ?? [];
  if (files.length === 0) {
    showToast("工作区暂无变更，先让 Agent 完成编码");
    return;
  }
  setGateBusy(no, true);
  setFindings(no, []);
  setVerdict(no, null);
  try {
    setTask(no, { kind: "presubmit", percent: 20, label: "正在构建工作区快照", done: false });
    await sleep(850);
    setTask(no, { kind: "presubmit", percent: 62, label: "正在计算快照指纹", done: false });
    await sleep(700);
    setTask(no, { kind: "presubmit", percent: 100, label: "快照已锁定", done: true });
    await sleep(360);

    const round = (st.snapshots[no]?.length ?? 0) + 1;
    const bytes = approxDiffBytes(files);
    const treeHash = fakeSha(`${no}:${round}:${bytes}`);
    addSnapshot(no, {
      round,
      treeHash,
      baseCommit: fakeSha(`base:${no}`),
      targetRef: "refs/heads/main",
      diffBytes: bytes,
      changedPaths: files.map((f) => f.path),
      capturedAt: Date.now(),
    });
    setStage(no, "PRESUBMITTED");
    pushSystemMessage(no, `第 ${round} 轮快照已锁定 · 指纹 ${treeHash.slice(0, 10)}…${treeHash.slice(-6)} · 所见即所审`, "success");
    void import("@/features/gate/api").then((m) => m.loadEvidence(no));
  } finally {
    setTask(no, null);
    setGateBusy(no, false);
  }
}

export async function demoReview(no: string) {
  const st = appStore.getState();
  if (st.gateBusy[no]) return;
  const snaps = st.snapshots[no] ?? [];
  if (snaps.length === 0) {
    showToast("请先预提审，锁定待审快照");
    return;
  }
  const round = snaps[snaps.length - 1].round;
  setGateBusy(no, true);
  setStage(no, "IN_REVIEW");
  try {
    const steps: Array<[number, string, number]> = [
      [12, "准备审核", 500],
      [38, "引擎静态扫描", 1500],
      [66, "策略规则判决", 1300],
      [88, "报告落盘", 800],
      [100, "判决完成", 350],
    ];
    for (const [percent, label, ms] of steps) {
      setTask(no, { kind: "review", percent, label, done: false });
      await sleep(ms);
    }
    if (round <= 1) {
      setFindings(no, FINDINGS_R1.map((f) => ({ ...f })));
      setVerdict(no, {
        verdict: "REJECT",
        reason: "存在 1 项阻断缺陷，驳回重修",
        engineId: "gate-policy/prism",
        round,
      });
      setStage(no, "REJECTED");
      pushSystemMessage(no, `第 ${round} 轮审查驳回 · ${FINDINGS_R1.length} 项发现已回注会话`, "warn");
      setCenterTab("findings");
      void import("@/features/gate/api").then((m) => m.loadEvidence(no));
    } else {
      setFindings(no, []);
      setVerdict(no, {
        verdict: "PASS",
        reason: "全部策略通过，发布授权已签发",
        engineId: "gate-policy/prism",
        round,
        authorizationId: fakeSha(`auth:${no}:${round}`).slice(0, 16),
      });
      setStage(no, "READY_TO_PUBLISH");
      pushSystemMessage(no, `第 ${round} 轮审查通过 · 发布授权已签发，所审即所发`, "success");
      setCenterTab("findings");
      void import("@/features/gate/api").then((m) => m.loadEvidence(no));
    }
  } finally {
    setTask(no, null);
    setGateBusy(no, false);
  }
}

export async function demoPublish(no: string) {
  const st = appStore.getState();
  if (st.gateBusy[no]) return;
  const verdict = st.verdicts[no];
  if (!verdict || verdict.verdict !== "PASS") {
    showToast("尚未取得发布授权，先通过门禁审查");
    return;
  }
  const snaps = st.snapshots[no] ?? [];
  const snap = snaps[snaps.length - 1];
  setGateBusy(no, true);
  try {
    const steps: Array<[number, string, number]> = [
      [18, "校验发布授权", 600],
      [52, "原子推送 refs/heads/main", 1400],
      [84, "追加审计日志", 700],
      [100, "发布完成", 300],
    ];
    for (const [percent, label, ms] of steps) {
      setTask(no, { kind: "publish", percent, label, done: false });
      await sleep(ms);
    }
    const commitSha = fakeSha(`commit:${no}:${snap.round}`);
    setOutcome(no, {
      commitSha,
      refBefore: fakeSha(`before:${no}`),
      refAfter: commitSha,
      targetRef: "refs/heads/main",
      publishedAt: Date.now(),
    });
    setStage(no, "DONE");
    pushSystemMessage(no, `已原子发布至主分支 main · 提交 ${commitSha.slice(0, 8)} · 快照指纹核验一致`, "success");
    showToast("发布成功，主分支已更新");
    void import("@/features/gate/api").then((m) => m.loadEvidence(no));
  } finally {
    setTask(no, null);
    setGateBusy(no, false);
  }
}

export function findingsToPromptText(findings: Finding[]): string {
  return findings
    .map((f, i) => `${i + 1}. [${f.severity}] ${f.path}${f.lineStart ? ":" + f.lineStart : ""} — ${f.message}`)
    .join("\n");
}
