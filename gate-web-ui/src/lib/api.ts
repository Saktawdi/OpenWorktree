import {
  addSnapshot,
  addUsage,
  applyReplyMetaDefaults,
  appStore,
  dropLiveTurn,
  finishAssistant,
  finishLiveTurn,
  patchAssistant,
  pushAssistantPlaceholder,
  pushPermissionRequest,
  pushQuestionRequest,
  pushSystemMessage,
  pushUserMessage,
  refreshTicketBusy,
  resolvePermission,
  resolveQuestion,
  setBusy,
  setCenterTab,
  setContextLimit,
  setContextTokens,
  setDiffs,
  setFindings,
  setGateBusy,
  setAgentId,
  setOutcome,
  setReviewError,
  setSessionBusy,
  setStage,
  setTask,
  setTodos,
  setVerdict,
  showToast,
  startLiveTurn,
  updateLiveTurn,
} from "./store";
import type {
  ChatItem,
  ChatSession,
  CatalogProvider,
  DiffFile,
  Finding,
  GitRepoView,
  GitTreeEntry,
  PendingAttachment,
  QuestionRequestView,
  Severity,
  Snapshot,
  WorkspaceSyncResult,
  GateTomlResponse,
  McpStatus,
  LlmProvider,
} from "./types";
import { parseUnifiedDiff } from "./diff";
import { approxDiffBytes } from "./diff";
import { sleep } from "./format";
import { friendlyToolName, isTodoTool, parseTodos } from "./todoUtils";
import { setSessionModelSel, setSessionModels } from "./store";

function authHeaders(): Record<string, string> {
  const token = appStore.getState().token;
  return token ? { Authorization: `Bearer ${token}` } : {};
}

async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: { "Content-Type": "application/json", ...authHeaders(), ...(init?.headers ?? {}) },
  });
  if (!res.ok) {
    let msg = `${res.status}`;
    try {
      const body = await res.json();
      msg = body?.error?.message ?? body?.message ?? msg;
    } catch {
      /* ignore */
    }
    throw new Error(msg);
  }
  return (await res.json()) as T;
}

export async function detectBackend(): Promise<"ok" | "unauth" | "error"> {
  try {
    const res = await fetch("/api/status", { headers: authHeaders() });
    if (res.ok) return "ok";
    if (res.status === 401) return "unauth";
    return "error";
  } catch {
    return "error";
  }
}

export async function verifyToken(token: string): Promise<boolean> {
  try {
    const res = await fetch("/api/auth/verify", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ token }),
    });
    return res.ok;
  } catch {
    return false;
  }
}

interface RawTicket {
  ticket_no: string;
  title: string;
  stage: string;
  priority?: string | null;
  project_id?: string | null;
  labels?: string[] | null;
  description?: string | null;
  note?: string | null;
  target_ref?: string | null;
  clone_path?: string | null;
  agent_config_id?: string | null;
  exec_token_total?: number | null;
  restart_count?: number | null;
  created_at: string;
  updated_at: string;
}

function mapTicket(t: RawTicket) {
  return {
    ticketNo: t.ticket_no,
    title: t.title || "(未命名工单)",
    stage: t.stage as unknown as import("./types").Stage,
    priority: ((t.priority as import("./types").Priority) ?? "P2") as import("./types").Priority,
    projectId: t.project_id ?? "",
    labels: t.labels ?? [],
    description: t.description ?? undefined,
    note: t.note ?? undefined,
    targetRef: t.target_ref ?? "refs/heads/main",
    clonePath: t.clone_path ?? "",
    agentConfigId: t.agent_config_id ?? null,
    execTokenTotal: t.exec_token_total ?? 0,
    restartCount: t.restart_count ?? 0,
    createdAt: t.created_at,
    updatedAt: t.updated_at,
  };
}

export async function loadTickets() {
  const data = await api<{ tickets: RawTicket[] }>("/api/tickets");
  appStore.setState({ tickets: data.tickets.map(mapTicket) });
}

/**
 * 拉取引擎配置（/api/config 的 engine_configured + engine 节）：
 * AI 审查入口的可用性与按钮文案都依赖它；失败不阻断连接，仅视为未加载。
 */
export async function loadEngineConfig() {
  try {
    const data = await api<{
      engine_configured?: boolean;
      engine?: { provider_id?: string | null; model?: string | null; timeout_seconds?: number | null };
    }>("/api/config");
    appStore.setState({
      engine: {
        configured: data.engine_configured === true,
        providerId: data.engine?.provider_id ?? null,
        model: data.engine?.model ?? null,
        timeoutSeconds: data.engine?.timeout_seconds ?? null,
      },
    });
  } catch {
    /* 配置读取失败不阻断：engine 保持 null，AI 入口按未加载处理 */
  }
}

export async function refreshTicket(no: string) {
  const t = await api<RawTicket>(`/api/tickets/${no}`);
  appStore.setState((st) => ({
    tickets: st.tickets.some((x) => x.ticketNo === no)
      ? st.tickets.map((x) => (x.ticketNo === no ? mapTicket(t) : x))
      : [mapTicket(t), ...st.tickets],
  }));
}

export async function createTicketLive(body: Record<string, unknown>): Promise<string | null> {
  try {
    const t = await api<RawTicket>("/api/tickets", { method: "POST", body: JSON.stringify(body) });
    return t.ticket_no ?? null;
  } catch (e) {
    showToast(`创建工单失败：${(e as Error).message}`);
    return null;
  }
}

/**
 * 拉取工单工作区 diff（/diff 端点 = clone 内 git diff HEAD + untracked）。
 * 除进工单时调用外，会话 done / 编辑类工具完成时也会调用——否则变更对比只在
 * 重新触发 selectTicketLive（切走再切回工单）后才更新。
 */
export async function loadTicketDiff(no: string) {
  try {
    const diffRes = await api<{ diff: string; eol_warning?: string }>(`/api/tickets/${no}/diff`);
    const files: DiffFile[] = diffRes.diff.trim() ? parseUnifiedDiff(diffRes.diff) : [];
    setDiffs(no, files, diffRes.eol_warning);
  } catch {
    setDiffs(no, []);
  }
}

export async function selectTicketLive(no: string) {
  appStore.setState({ selectedNo: no, centerTab: "chat", highlight: null });
  // 工单绑定的 agent 是新会话的默认协作对象：进工单时同步全局选择，
  // 否则选择器停留在全局默认（如 claude），首条消息会建到错误的 agent 上。
  const st0 = appStore.getState();
  const bound = st0.tickets.find((t) => t.ticketNo === no)?.agentConfigId;
  if (bound && st0.agentId !== bound && st0.agents.some((a) => a.id === bound)) {
    setAgentId(bound);
  }
  const loadDiff = () => loadTicketDiff(no);
  const loadSessions = async () => {
    try {
      await loadTicketSessions(no);
      const st = appStore.getState();
      const target = st.activeSessionId[no] || st.sessions[no]?.[st.sessions[no].length - 1]?.id;
      if (target) {
        await loadSessionMessages(no, target);
        void loadSessionCatalog(no, target);
        // 目标会话为 ACTIVE 时恢复未决的权限/提问卡片（若已就绪）。
        const sess = st.sessions[no]?.find((x) => x.id === target);
        if (sess?.status === "active") {
          void loadSessionPermissions(no, target);
          void loadSessionQuestions(no, target);
        }
      }
    } catch {
      /* 会话可能尚未创建 */
    }
  };
  await Promise.all([loadDiff(), loadSessions(), loadPresubmits(no), loadReviewState(no)]);
}

/* ─── 快照与审查结果回填 ─── */

export async function loadPresubmits(no: string) {
  try {
    const data = await api<{
      presubmits: Array<{
        review_round: number;
        tree_hash: string;
        base_commit: string;
        target_ref: string;
        diff_bytes: number;
        changed_count: number;
        created_at: string;
      }>;
    }>(`/api/tickets/${no}/presubmits`);
    const list: Snapshot[] = (data.presubmits ?? []).map((p) => ({
      round: p.review_round,
      treeHash: p.tree_hash,
      baseCommit: p.base_commit,
      targetRef: p.target_ref,
      diffBytes: p.diff_bytes,
      changedPaths: [],
      changedCount: p.changed_count,
      capturedAt: Date.parse(p.created_at),
    }));
    appStore.setState((st) => ({ snapshots: { ...st.snapshots, [no]: list } }));
  } catch {
    /* 尚无快照记录时静默 */
  }
}

interface RawReviewResult {
  verdict: string;
  engine_id: string;
  review_round: number;
  degraded?: boolean;
  findings: string;
}

/**
 * 解析审查证据 blob（EvidenceCodec 的两种对象形态）：
 * - `{"kind":"report","findings":[…snake_case…]}` —— 正常引擎报告；
 * - `{"kind":"failure","failure_kind":…,"detail":…}` —— 引擎未产出判决（超时/崩溃），
 *   合成一条 BLOCKER 发现，让驳回有具体原因可看，而不是"共 0 项发现"。
 * 兼容旧的顶层数组形态（demo 数据）。
 */
function parseFindings(raw: string): Finding[] {
  try {
    const parsed: unknown = JSON.parse(raw);
    if (Array.isArray(parsed)) {
      return parsed.map(mapFinding).filter((f): f is Finding => f !== null);
    }
    if (parsed && typeof parsed === "object") {
      const obj = parsed as Record<string, unknown>;
      if (obj.kind === "failure") {
        const kind = String(obj.failure_kind ?? "CRASH");
        return [
          {
            severity: "BLOCKER",
            path: "",
            ruleId: `engine/${kind}`,
            message: String(obj.detail ?? "审查引擎未能完成本轮判决"),
            suggestion:
              "引擎未产出有效审查（超时/崩溃/上游或凭据问题）。可重试 AI 审查、检查引擎上游可用性，或改用人工审查。",
          },
        ];
      }
      if (Array.isArray(obj.findings)) {
        return (obj.findings as unknown[])
          .map(mapFinding)
          .filter((f): f is Finding => f !== null);
      }
    }
  } catch {
    /* 非结构化时按空处理 */
  }
  return [];
}

/** 兼容 snake_case（EvidenceCodec 落盘）与 camelCase（demo 数据）两种字段名。 */
function mapFinding(f: unknown): Finding | null {
  if (!f || typeof f !== "object") return null;
  const o = f as Record<string, unknown>;
  const num = (v: unknown) => (typeof v === "number" ? v : undefined);
  const lineStart = num(o.lineStart) ?? num(o.line_start);
  const lineEnd = num(o.lineEnd) ?? num(o.line_end);
  const ruleId = o.ruleId ?? o.rule_id;
  return {
    severity: (o.severity as Severity) ?? "INFO",
    path: String(o.path ?? ""),
    lineStart,
    lineEnd,
    ruleId: ruleId ? String(ruleId) : undefined,
    message: String(o.message ?? ""),
    suggestion: o.suggestion ? String(o.suggestion) : undefined,
  };
}

function verdictReason(verdict: string): string {
  if (verdict === "PASS") return "全部策略通过，发布授权已签发";
  if (verdict === "REQUIRES_HUMAN") return "需人工核准后放行";
  return "存在阻断项或引擎未能完成本轮判决，详见下方发现";
}

async function applyReviewResult(no: string) {
  const rr = await api<RawReviewResult>(`/api/tickets/${no}/review-result`);
  setFindings(no, parseFindings(rr.findings));
  setVerdict(no, {
    verdict: rr.verdict as "PASS" | "REJECT" | "REQUIRES_HUMAN",
    reason: verdictReason(rr.verdict),
    engineId: rr.engine_id,
    round: rr.review_round,
    degraded: rr.degraded === true,
  });
}

export async function loadReviewState(no: string) {
  try {
    await applyReviewResult(no);
  } catch {
    /* 尚无审查结果时静默返回 */
  }
}

/* ─── 会话管理 ─── */

function sessionTimeLabel(at?: number): string {
  const t = at == null ? new Date() : new Date(at);
  return `会话 ${t.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" })}`;
}

interface RawSession {
  id: string;
  ticket_no?: string;
  agent_config_id?: string | null;
  cli?: string | null;
  status?: string | null;
  title?: string | null;
  archived?: boolean | null;
  started_at?: string | null;
  updated_at?: string | null;
  override_provider?: string | null;
  override_model?: string | null;
  override_variant?: string | null;
  permission_auto_accept?: boolean | null;
}

function mapSession(no: string, s: RawSession): ChatSession {
  const createdAt = s.started_at ? Date.parse(s.started_at) : Date.now();
  return {
    id: s.id,
    ticketNo: s.ticket_no ?? no,
    title: s.title ?? sessionTimeLabel(createdAt),
    status: s.archived ? "archived" : "active",
    createdAt,
    updatedAt: s.updated_at ? Date.parse(s.updated_at) : createdAt,
    permissionAutoAccept: s.permission_auto_accept ?? false,
    agentConfigId: s.agent_config_id ?? null,
    overrideProvider: s.override_provider ?? null,
    overrideModel: s.override_model ?? null,
    overrideVariant: s.override_variant ?? null,
  };
}

export async function loadTicketSessions(no: string) {
  const data = await api<{ sessions: RawSession[] }>(`/api/tickets/${no}/sessions`);
  const list = (data.sessions ?? []).map((s) => mapSession(no, s));
  let latest: ChatSession | undefined;
  for (const sess of list) {
    if (sess.status === "active" && (!latest || sess.createdAt >= latest.createdAt)) latest = sess;
  }
  const activeId = latest?.id ?? "";
  appStore.setState((st) => ({
    sessions: { ...st.sessions, [no]: list },
    activeSessionId: { ...st.activeSessionId, [no]: activeId },
  }));
}

/**
 * 仅同步会话列表元数据（标题、归档状态等），不改 active 指针。
 * 用于回合结束后的被动刷新（如 opencode 自动生成标题写库后），避免把用户
 * 正在查看的会话强行切走。
 */
export async function refreshTicketSessionsMeta(no: string) {
  try {
    const data = await api<{ sessions: RawSession[] }>(`/api/tickets/${no}/sessions`);
    const list = (data.sessions ?? []).map((s) => mapSession(no, s));
    appStore.setState((st) => ({ sessions: { ...st.sessions, [no]: list } }));
  } catch {
    /* 静默失败：下个常规动作还有一次刷新机会 */
  }
}

export async function createSessionLive(no: string) {
  try {
    // Demo data can leave a stale agent id in the store; fall back to the first
    // backend agent config so session creation cannot fail on it.
    const st = appStore.getState();
    const agentId =
      st.agents.some((a) => a.id === st.agentId) ? st.agentId : (st.agents[0]?.id ?? "");
    const created = await api<{ id: string }>(`/api/tickets/${no}/sessions`, {
      method: "POST",
      body: JSON.stringify({ agent_config_id: agentId, initial_prompt: "" }),
    });
    // 不再强制上以时间命名的占位标题：opencode 会在首个真实用户回合后由 title agent
    // 异步生成正式标题（适配器监听 session.updated 写库），在标题就位前列表用本地时间
    // 标签回退展示（见 mapSession）。
    await loadTicketSessions(no);
    appStore.setState((st) => ({ activeSessionId: { ...st.activeSessionId, [no]: created.id } }));
    await loadSessionMessages(no, created.id).catch(() => {});
    // 新建会话为 ACTIVE，预拉未决权限/提问（一般为空，保持路径一致）。
    void loadSessionPermissions(no, created.id);
    void loadSessionQuestions(no, created.id);
    void loadSessionCatalog(no, created.id);
  } catch (e) {
    showToast(`新建会话失败：${(e as Error).message}`);
  }
}

export async function patchSessionLive(
  id: string,
  patch: { title?: string; archived?: boolean; permission_auto_accept?: boolean },
) {
  try {
    await api(`/api/sessions/${id}`, { method: "PATCH", body: JSON.stringify(patch) });
  } catch (e) {
    showToast(`更新会话失败：${(e as Error).message}`);
    return;
  }
  const no = ticketNoOfSession(id);
  if (no) {
    await loadTicketSessions(no).catch(() => {});
    // loadSessionTickets 会整体回填会话列表（含 permission_auto_accept），此处无需再局部更新
  }
}

/* ─── 权限询问（opencode） ─── */

export type PermissionResponse = "once" | "always" | "reject";

interface RawPermissionAsk {
  session_id?: string;
  timestamp?: string;
  permission_id: string;
  permission?: string;
  patterns?: string[];
  always?: string[];
  metadata?: Record<string, unknown>;
  message_id?: string | null;
  call_id?: string | null;
}

function mapPermissionAsk(p: RawPermissionAsk): import("./types").PermissionRequestView {
  return {
    permissionId: p.permission_id,
    permission: p.permission ?? "",
    patterns: p.patterns ?? [],
    always: p.always ?? [],
    metadata: p.metadata ?? {},
    messageId: p.message_id ?? undefined,
    callId: p.call_id ?? undefined,
  };
}

/** 应答一次权限请求：POST /api/sessions/{sid}/permissions/{pid}，body {response}。 */
export async function answerSessionPermission(
  sessionId: string,
  permissionId: string,
  response: PermissionResponse,
): Promise<boolean> {
  try {
    await api(`/api/sessions/${sessionId}/permissions/${permissionId}`, {
      method: "POST",
      body: JSON.stringify({ response }),
    });
    return true;
  } catch (e) {
    showToast(`权限应答失败：${(e as Error).message}`);
    return false;
  }
}

/**
 * 恢复未决的权限卡片（页面刷新后，SSE 不会回放已经过去的 permission_asked）。
 * GET /api/sessions/{sessionId}/permissions → 每条 pending 推入 store，
 * pushPermissionRequest 内部按 permissionId 去重，已存在同 id 的会跳过。
 *
 * 取舍说明：刷新场景下如果当时没有挂着的 EventSource，用户应答后这里不再主动
 * 重挂 ESL 事件流——后端会把应答/后续消息持久化，下一次拉历史即可看到，避免为了
 * 恢复实时流额外引入一整套会话续接逻辑；实时应答仍由已挂着的 consumeSessionStream
 * 通过 permission_replied 事件即时反馈。
 */
export async function loadSessionPermissions(no: string, sessionId: string) {
  try {
    const data = await api<{ permissions: RawPermissionAsk[] }>(
      `/api/sessions/${sessionId}/permissions`,
    );
    for (const p of data.permissions ?? []) {
      pushPermissionRequest(no, mapPermissionAsk(p));
    }
  } catch (e) {
    /* 权限恢复失败不阻断会话打开 */
  }
}

/* ─── 智能体提问（opencode question 工具） ─── */

interface RawQuestionAsk {
  request_id?: string;
  questions?: Array<{
    question?: string;
    header?: string;
    options?: Array<{ label?: string; description?: string }>;
    multiple?: boolean;
    custom?: boolean;
  }>;
  message_id?: string | null;
  call_id?: string | null;
}

function mapQuestionAsk(q: RawQuestionAsk): QuestionRequestView {
  return {
    requestId: q.request_id ?? "",
    questions: (q.questions ?? []).map((x) => ({
      question: x.question ?? "",
      header: x.header ?? "",
      options: (x.options ?? []).map((o) => ({ label: o.label ?? "", description: o.description })),
      multiple: x.multiple === true,
      // opencode 缺省允许自定义输入；仅显式 false 时关闭
      custom: x.custom !== false,
    })),
    messageId: q.message_id ?? undefined,
    callId: q.call_id ?? undefined,
  };
}

/** 恢复未决的提问卡片（页面刷新后 SSE 不会回放已经过去的 question_asked）。 */
export async function loadSessionQuestions(no: string, sessionId: string) {
  try {
    const data = await api<{ questions: RawQuestionAsk[] }>(
      `/api/sessions/${sessionId}/questions`,
    );
    for (const q of data.questions ?? []) {
      if (q.request_id) pushQuestionRequest(no, mapQuestionAsk(q));
    }
  } catch (e) {
    /* 提问恢复失败不阻断会话打开 */
  }
}

/** 提交一次提问回答：POST /api/sessions/{sid}/questions/{rid}/reply，body {answers}。 */
export async function answerSessionQuestion(
  sessionId: string,
  requestId: string,
  answers: string[][],
): Promise<boolean> {
  try {
    await api(`/api/sessions/${sessionId}/questions/${encodeURIComponent(requestId)}/reply`, {
      method: "POST",
      body: JSON.stringify({ answers }),
    });
    return true;
  } catch (e) {
    showToast(`回答提交失败：${(e as Error).message}`);
    return false;
  }
}

/** 跳过一次提问：POST /api/sessions/{sid}/questions/{rid}/reject。 */
export async function rejectSessionQuestion(sessionId: string, requestId: string): Promise<boolean> {
  try {
    await api(`/api/sessions/${sessionId}/questions/${encodeURIComponent(requestId)}/reject`, {
      method: "POST",
      body: "{}",
    });
    return true;
  } catch (e) {
    showToast(`跳过失败：${(e as Error).message}`);
    return false;
  }
}

export async function deleteSessionLive(id: string, ticketNo: string) {
  try {
    await api(`/api/sessions/${id}`, { method: "DELETE" });
  } catch (e) {
    showToast(`删除会话失败：${(e as Error).message}`);
    return;
  }
  dropLiveTurn(id);
  setSessionBusy(id, false);
  await loadTicketSessions(ticketNo).catch(() => {});
  refreshTicketBusy(ticketNo);
}

export async function abortLive(no: string) {
  const sid = appStore.getState().activeSessionId[no];
  if (!sid) return;
  // 乐观翻转按钮：后端的 done 事件可能迟到，用户的点击必须立刻可见。
  setSessionBusy(sid, false);
  refreshTicketBusy(no);
  try {
    await api(`/api/sessions/${sid}/abort`, { method: "POST" });
  } catch (e) {
    showToast(`中断失败：${(e as Error).message}`);
  }
  await loadTicketSessions(no).catch(() => {});
}

function ticketNoOfSession(id: string): string | null {
  for (const [no, list] of Object.entries(appStore.getState().sessions)) {
    if (list.some((s) => s.id === id)) return no;
  }
  return null;
}

interface RawMessage {
  id: string;
  role: string;
  content: string;
  tool_calls?: Array<{ name: string; arguments_json?: string; result_json?: string }>;
  usage?: { prompt_tokens: number; completion_tokens: number } | null;
  timestamp: string;
}

function resolveToolIcon(name: string): import("./types").ToolIconKind {
  const n = name.toLowerCase().trim();
  if (n.includes("bash") || n.includes("exec") || n.includes("shell") || n.includes("terminal") || n.includes("cmd")) {
    return "terminal";
  }
  if (n.includes("read") || n.includes("view") || n.includes("cat") || n.includes("get_file") || n.includes("load")) {
    return "file";
  }
  if (n.includes("edit") || n.includes("write") || n.includes("patch") || n.includes("multiedit") || n.includes("create") || n.includes("modify")) {
    return "edit";
  }
  if (n.includes("grep") || n.includes("glob") || n.includes("search") || n.includes("find") || n.includes("locate")) {
    return "search";
  }
  if (n.includes("test")) {
    return "test";
  }
  if (n.includes("web") || n.includes("fetch") || n.includes("http") || n.includes("browser")) {
    return "web";
  }
  if (n.includes("ask") || n.includes("question") || n.includes("prompt")) {
    return "question";
  }
  return "terminal";
}

function mapHistoryMessage(m: RawMessage): ChatItem | null {
  if (m.role === "USER") {
    return { kind: "user", id: m.id, text: m.content, ts: Date.parse(m.timestamp) };
  }
  if (m.role === "ASSISTANT") {
    return {
      kind: "assistant",
      id: m.id,
      text: m.content,
      streaming: false,
      tools: (m.tool_calls ?? []).map((tc, i) => {
        const toolName = tc.name || "";
        const args = tc.arguments_json ?? "";
        const todo = isTodoTool(toolName);
        return {
          id: `${m.id}-${i}`,
          name: friendlyToolName(toolName),
          toolName,
          args,
          icon: todo ? ("todo" as const) : resolveToolIcon(toolName),
          // Match the live-streamed row: tool name followed by its full arguments JSON
          // (todo rows use the compact summary instead of the full todos JSON).
          argsSummary: todo ? todoArgsSummary(args) : `${toolName}${args}`,
          resultSummary: tc.result_json?.slice(0, 80),
          resultDetail: tc.result_json,
          status: "ok" as const,
        };
      }),
      ts: Date.parse(m.timestamp),
    };
  }
  return null;
}

/** todo 类工具行参数列的简短摘要（避免整段 JSON 刷屏）。 */
function todoArgsSummary(argsJson?: string): string {
  const todos = parseTodos(argsJson);
  if (!todos) return "任务清单";
  const done = todos.filter((t) => t.status === "completed").length;
  return `${todos.length} 项任务 · 已完成 ${done}`;
}

export async function loadSessionMessages(no: string, sessionId: string) {
  // 生成中的回合要到 idle 才整回合落库，历史里看不到；切走再切回时把 liveTurns
  // 里的流式条目接回视图（EventSource 仍在推流，itemId 对上后增量自动续上）。
  const stashedAtFetch = appStore.getState().liveTurns[sessionId] !== undefined;
  const hist = await api<{ messages: RawMessage[] }>(`/api/sessions/${sessionId}/messages`);
  if (stashedAtFetch && !appStore.getState().liveTurns[sessionId]) {
    // 回合在请求飞行途中结束：后端发出 done 前已落库，重拉一次必然包含完整回复。
    return loadSessionMessages(no, sessionId);
  }
  const items: ChatItem[] = hist.messages.map(mapHistoryMessage).filter(Boolean) as ChatItem[];
  const stash = appStore.getState().liveTurns[sessionId];
  if (stash) items.push(stash.item);
  appStore.setState((st) => ({ chats: { ...st.chats, [no]: items } }));
  // 会话隔离：任务清单与上下文占用都以"当前会话"为准重建——
  // 本会话没有 todowrite 就清空，不允许上一会话的侧栏状态泄漏过来。
  restoreTodosFromHistory(no, hist.messages);
  setContextTokens(no, 0);
  // 后端历史消息不带 model/agent 标注，这里用当前会话的 agent/推理等级补齐底部 footer。
  applyReplyMetaDefaults(no);
}

/** 以该会话历史中最后一条合法 todowrite 参数重建任务清单；没有则清空。 */
function restoreTodosFromHistory(no: string, messages: RawMessage[]) {
  let latest: string | null = null;
  for (const m of messages) {
    for (const tc of m.tool_calls ?? []) {
      if (isTodoTool(tc.name) && tc.arguments_json) latest = tc.arguments_json;
    }
  }
  const todos = latest ? parseTodos(latest) : null;
  setTodos(no, todos ?? []);
}

export async function liveSendPrompt(no: string, userText: string, attachments: PendingAttachment[] = []) {
  const st = appStore.getState();
  // 目标永远是「当前查看的会话」（activeSessionId），不再有跨工单/跨会话的全局游标；
  // 同一会话生成中不允许并发追加，其他会话不受影响。
  // userText 已含 [图片 #n] 引用（Composer 粘贴时插入），原样推送与发送。
  const sessionId = st.activeSessionId[no];
  if (sessionId && st.sessionBusy[sessionId]) return;
  pushUserMessage(no, userText);
  setBusy(no, true);
  let sid: string | null = sessionId || null;
  try {
    if (!sid) {
      // 会话列表为空 → 首条消息自动创建会话并把首句作为 initial_prompt 直接开跑。
      const agentId = st.agents.some((a) => a.id === st.agentId) ? st.agentId : (st.agents[0]?.id ?? "");
      const created = await api<{ id: string }>(`/api/tickets/${no}/sessions`, {
        method: "POST",
        body: JSON.stringify({ agent_config_id: agentId, initial_prompt: userText }),
      });
      sid = created.id;
      // 立即把新会话同步进侧栏列表并固定 active 指针，否则整个流式回合期间
      // 会话面板仍显示「暂无活跃会话」（回合结束的 done 刷新太晚）。
      await loadTicketSessions(no).catch(() => {});
      appStore.setState((s2) => ({ activeSessionId: { ...s2.activeSessionId, [no]: sid! } }));
      // 模型目录按会话加载：让模型/推理强度选择器在首个回合就能用。
      void loadSessionCatalog(no, sid);
    } else {
      const sel = st.sessionModelSel[sid];
      await api(`/api/sessions/${sid}/messages`, {
        method: "POST",
        body: JSON.stringify({
          message: userText,
          attachments: attachments.map((a) => ({
            filename: a.filename,
            mime: a.mime,
            data_base64: a.dataBase64,
          })),
          provider_id: sel?.providerId ?? undefined,
          model_id: sel?.modelId ?? undefined,
          variant: sel?.variant ?? undefined,
        }),
      });
    }
    setSessionBusy(sid, true);
    await consumeSessionStream(no, sid);
  } catch (e) {
    pushSystemMessage(no, `会话失败：${(e as Error).message}`, "warn");
  } finally {
    if (sid) setSessionBusy(sid, false);
    refreshTicketBusy(no);
  }
}

/* ─── 会话内实时切换模型 / 推理强度 ─── */

interface RawCatalogModel {
  id: string;
  name?: string | null;
  variants?: string[] | null;
  image_input?: boolean | null;
  limit?: { context?: unknown; output?: unknown } | null;
}

interface RawCatalogProvider {
  id: string;
  name?: string | null;
  models?: RawCatalogModel[] | null;
}

function numericLimit(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) && value > 0 ? value : null;
}

/**
 * Loads the live model catalog for the session's opencode serve and seeds the
 * picker selection: persisted session override first, then the AgentConfig
 * default (provider/model), mirroring OpenChamber's restore order.
 */
export async function loadSessionCatalog(no: string, sessionId: string) {
  const st = appStore.getState();
  try {
    const data = await api<{ providers: RawCatalogProvider[] }>(`/api/sessions/${sessionId}/models`);
    const providers: CatalogProvider[] = (data.providers ?? []).map((p) => ({
      id: p.id,
      name: p.name ?? p.id,
      models: (p.models ?? []).map((m) => ({
        id: m.id,
        name: m.name ?? m.id,
        variants: m.variants ?? [],
        imageInput: m.image_input === true,
        contextLimit: numericLimit(m.limit?.context),
        outputLimit: numericLimit(m.limit?.output),
      })),
    }));
    setSessionModels(sessionId, providers);
    // Seed the selection from the persisted session override when present.
    const sess = (st.sessions[no] ?? []).find((s) => s.id === sessionId);
    if (sess?.overrideProvider && sess.overrideModel) {
      setSessionModelSel(sessionId, {
        providerId: sess.overrideProvider,
        modelId: sess.overrideModel,
        variant: sess.overrideVariant ?? null,
      });
    }
    applySessionContextLimit(no, sessionId, providers);
  } catch {
    /* catalog is best-effort: the picker just stays empty */
  }
}

/**
 * 从目录中解析当前会话生效模型的上下文窗口上限并写入 store；
 * 找不到生效模型时保持 null（前端回退默认窗口）。
 */
function applySessionContextLimit(no: string, sessionId: string, providers: CatalogProvider[]) {
  const st = appStore.getState();
  const sel = st.sessionModelSel[sessionId];
  const sess = (st.sessions[no] ?? []).find((s) => s.id === sessionId);
  const agent = st.agents.find((a) => a.id === (sess?.agentConfigId ?? st.agentId));
  const providerId = sel?.providerId ?? sess?.overrideProvider ?? agent?.providerId ?? null;
  const modelId = sel?.modelId ?? sess?.overrideModel ?? agent?.model ?? null;
  if (!providerId || !modelId) return;
  const model = providers
    .find((p) => p.id === providerId)
    ?.models.find((m) => m.id === modelId);
  if (model?.contextLimit) setContextLimit(no, model.contextLimit);
}

/** Live switch: persists the override server-side; takes effect on the NEXT turn. */
export async function switchSessionModelLive(
  sessionId: string,
  sel: { providerId: string | null; modelId: string | null; variant: string | null },
): Promise<boolean> {
  try {
    await api(`/api/sessions/${sessionId}/model`, {
      method: "POST",
      body: JSON.stringify({
        provider_id: sel.providerId ?? undefined,
        model_id: sel.modelId ?? undefined,
        variant: sel.variant ?? undefined,
      }),
    });
    setSessionModelSel(sessionId, sel);
    // 模型切换后上下文窗口随之变化：从目录解析新上限（找不到则回退 null）。
    const no = ticketNoOfSession(sessionId);
    if (no) {
      const model = appStore
        .getState()
        .sessionModels[sessionId]?.find((p) => p.id === sel.providerId)
        ?.models.find((m) => m.id === sel.modelId);
      setContextLimit(no, model?.contextLimit ?? null);
    }
    return true;
  } catch (e) {
    showToast(`切换失败：${(e as Error).message}`);
    return false;
  }
}

// 编辑类工具在一个回合里往往连续完成多次；逐次全量拉 diff 既慢也毫无增益，合并成一次。
const diffRefreshTimers: Record<string, ReturnType<typeof setTimeout>> = {};
function scheduleDiffRefresh(no: string) {
  if (diffRefreshTimers[no]) clearTimeout(diffRefreshTimers[no]);
  diffRefreshTimers[no] = setTimeout(() => {
    delete diffRefreshTimers[no];
    void loadTicketDiff(no);
  }, 800);
}

const FILE_EDIT_TOOLS = ["edit", "write", "patch", "multiedit"];

async function consumeSessionStream(no: string, sessionId: string) {
  const token = appStore.getState().token;
  const url = `/api/sessions/${sessionId}/events${token ? `?token=${encodeURIComponent(token)}` : ""}`;
  const es = new EventSource(url);
  startLiveTurn(no, sessionId);
  // Per-call argument accumulation: argument_delta fragments concatenate into the
  // full arguments JSON, which todo tools parse into the sidebar task list.
  const argsBuf = new Map<string, { name: string; args: string }>();

  await new Promise<void>((resolve) => {
    // 看门狗只在 30 分钟无任何事件时判流悬挂（原固定 5 分钟截断会误杀长工具回合）。
    let watchdog: ReturnType<typeof setTimeout> | null = null;
    const finish = () => {
      if (watchdog) clearTimeout(watchdog);
      es.close();
      resolve();
    };
    const arm = () => {
      if (watchdog) clearTimeout(watchdog);
      watchdog = setTimeout(finish, 1_800_000);
    };
    arm();
    es.addEventListener("message", (ev) => {
      // History replay (event: message) must not touch the live placeholder: the chat is
      // already rendered from GET /messages when the session opens, and replaying past
      // assistant messages here would overwrite the streaming reply with the previous one.
      void ev;
    });
    es.addEventListener("token", (ev) => {
      arm();
      const d = JSON.parse((ev as MessageEvent).data);
      updateLiveTurn(sessionId, (a) => ({
        ...a,
        text: a.text + (d.text_delta ?? ""),
        thinking: a.thinking && !a.thinking.done ? { ...a.thinking, done: true } : a.thinking,
      }));
    });
    es.addEventListener("thinking", (ev) => {
      arm();
      const d = JSON.parse((ev as MessageEvent).data);
      updateLiveTurn(sessionId, (a) => ({
        ...a,
        thinking: {
          text: (a.thinking?.text ?? "") + (d.thinking_delta ?? ""),
          startedAt: a.thinking?.startedAt ?? Date.now(),
          done: false,
        },
      }));
    });
    es.addEventListener("tool_call", (ev) => {
      arm();
      const d = JSON.parse((ev as MessageEvent).data);
      // 编辑类工具落盘成功 → 防抖刷新该工单的变更对比，兑现"每次编辑实时反映"的文案；
      // bash 等其它工具可能改文件但太噪，回合结束的 done 刷新兜底。
      if (d.status === "SUCCESS" && FILE_EDIT_TOOLS.includes(String(d.tool_name ?? "").toLowerCase())) {
        scheduleDiffRefresh(no);
      }
      const callId: string = d.call_id ?? "";
      const toolName: string = d.tool_name ?? "";
      const todo = isTodoTool(toolName);
      const entry = argsBuf.get(callId) ?? { name: toolName, args: "" };
      entry.name = toolName || entry.name;
      if (d.argument_delta) entry.args += d.argument_delta;
      argsBuf.set(callId, entry);
      // todo 类工具在终态时把累积的完整参数解析进侧栏任务清单。
      if (todo && (d.status === "SUCCESS" || d.status === "FAILED")) {
        const todos = parseTodos(entry.args);
        if (todos) setTodos(no, todos);
      }
      const argsSummary = todo ? todoArgsSummary(entry.args) : `${entry.name}${entry.args}`;
      updateLiveTurn(sessionId, (a) => {
        const existing = a.tools.find((t) => t.id === d.call_id);
        if (existing) {
          return {
            ...a,
            tools: a.tools.map((t) =>
              t.id === d.call_id
                ? {
                    ...t,
                    name: friendlyToolName(entry.name),
                    icon: todo ? ("todo" as const) : t.icon,
                    args: entry.args,
                    argsSummary,
                    status: d.status === "SUCCESS" ? "ok" : d.status === "FAILED" ? "error" : "running",
                  }
                : t,
            ),
          };
        }
        return {
          ...a,
          tools: [
            ...a.tools,
            {
              id: d.call_id,
              name: friendlyToolName(toolName),
              toolName,
              args: entry.args,
              icon: todo ? ("todo" as const) : resolveToolIcon(toolName),
              argsSummary,
              status: "running" as const,
            },
          ],
        };
      });
    });
    es.addEventListener("usage", (ev) => {
      arm();
      const d = JSON.parse((ev as MessageEvent).data);
      if (d.usage) {
        addUsage(no, d.usage.prompt_tokens ?? 0, d.usage.completion_tokens ?? 0);
        // 最新一轮的窗口占用（prompt+completion），非逐轮累加 —— 上下文环数据源。
        const total =
          typeof d.usage.total_tokens === "number" && d.usage.total_tokens > 0
            ? d.usage.total_tokens
            : (d.usage.prompt_tokens ?? 0) + (d.usage.completion_tokens ?? 0);
        if (total > 0) setContextTokens(no, total);
      }
    });
    es.addEventListener("permission_asked", (ev) => {
      arm();
      const d = JSON.parse((ev as MessageEvent).data);
      // 卡片只挂当前查看的会话视图；用户已切去别的会话时不挂（避免误挂 + 应答发错
      // session），切回时 loadSessionPermissions 会重新拉取 pending 卡片。
      if (appStore.getState().activeSessionId[no] === sessionId) {
        pushPermissionRequest(no, mapPermissionAsk(d));
      }
    });
    es.addEventListener("permission_replied", (ev) => {
      arm();
      const d = JSON.parse((ev as MessageEvent).data);
      resolvePermission(no, d.permission_id, d.response ?? "once", !!d.auto);
    });
    es.addEventListener("question_asked", (ev) => {
      arm();
      const d = JSON.parse((ev as MessageEvent).data);
      // 与权限卡片同策略：只挂当前查看的会话视图，切回时 loadSessionQuestions 重新拉取。
      if (d.request_id && appStore.getState().activeSessionId[no] === sessionId) {
        pushQuestionRequest(no, mapQuestionAsk(d));
      }
    });
    es.addEventListener("question_replied", (ev) => {
      arm();
      const d = JSON.parse((ev as MessageEvent).data);
      if (d.request_id) resolveQuestion(no, d.request_id, !!d.rejected);
    });
    es.addEventListener("session_title", (ev) => {
      // 后端把 opencode 自动生成的标题上抛（HTTP Server: SessionSseHandler）。
      // 顺手同步当前 store 里的会话条目；回合结束再拉一次可确保一致。
      const d = JSON.parse((ev as MessageEvent).data);
      const sid = String(d.session_id ?? "");
      const title = String(d.title ?? "");
      if (!sid || !title) return;
      appStore.setState((st) => ({
        sessions: {
          ...st.sessions,
          [no]: (st.sessions[no] ?? []).map((s) => (s.id === sid ? { ...s, title } : s)),
        },
      }));
    });
    es.addEventListener("done", () => {
      // 后端此刻已把 opencode 的自动生成标题写库（session.updated → sessions.update）。
      // 只刷新列表数据，不动 activeSessionId，避免把用户在查看的会话顶走。
      void refreshTicketSessionsMeta(no);
      // 回合结束：刷新变更对比的最终状态（本回合内 bash 等未跟踪的文件改动也一并覆盖）。
      void loadTicketDiff(no);
      finish();
    });
    es.addEventListener("error", (ev) => {
      let msg = "会话连接中断";
      const data = (ev as MessageEvent).data;
      if (data) {
        try {
          const d = JSON.parse(data);
          if (d.error_message) {
            let detail = String(d.error_message);
            try {
              const inner = JSON.parse(detail);
              if (inner?.data?.message) detail = inner.data.message;
              else if (inner?.message) detail = inner.message;
            } catch {
              /* 非结构化错误体，原样展示 */
            }
            msg = `Agent 出错：${detail}`;
          } else if (d.error_code) {
            msg = `Agent 出错：${d.error_code}`;
          }
        } catch {
          /* ignore */
        }
      }
      updateLiveTurn(sessionId, (a) => ({ ...a, streaming: false }));
      pushSystemMessage(no, msg, "warn");
      finish();
    });
  });
  finishLiveTurn(sessionId);
}

export async function livePresubmit(no: string) {
  setGateBusy(no, true);
  setFindings(no, []);
  setVerdict(no, null);
  try {
    setTask(no, { kind: "presubmit", percent: 50, label: "正在锁定快照", done: false });
    const r = await api<{
      ticket_no: string;
      review_round: number;
      tree_hash: string;
      base_commit: string;
      target_ref: string;
      diff_bytes: number;
      changed_paths: string[];
    }>(`/api/tickets/${no}/presubmit`, { method: "POST", body: "{}" });
    addSnapshot(no, {
      round: r.review_round,
      treeHash: r.tree_hash,
      baseCommit: r.base_commit,
      targetRef: r.target_ref,
      diffBytes: r.diff_bytes,
      changedPaths: r.changed_paths,
      capturedAt: Date.now(),
    });
    await refreshTicket(no);
    pushSystemMessage(no, `第 ${r.review_round} 轮快照已锁定 · 指纹 ${r.tree_hash.slice(0, 10)}…`, "success");
  } catch (e) {
    showToast(`预提审失败：${(e as Error).message}`);
  } finally {
    setTask(no, null);
    setGateBusy(no, false);
  }
}

/**
 * T-118 基座同步：把工单 clone 与权威分支快进到主分支最新 tip，未提交改动 stash 后原样重放。
 */
export async function liveSyncBase(no: string) {
  setGateBusy(no, true);
  try {
    const r = await api<{
      ticket_no: string;
      status: "synced" | "healed" | "up_to_date" | "skipped";
      behind: number;
      from_tip: string | null;
      to_tip: string | null;
      branch_moved: boolean;
      conflicts: string[];
      stash_kept: boolean;
      skipped_reason?: string;
    }>(`/api/tickets/${no}/sync-base`, { method: "POST", body: JSON.stringify({ allow_dirty: true }) });
    await refreshTicket(no);
    if (r.status === "skipped") {
      showToast(`基座同步已跳过：${r.skipped_reason ?? "未知原因"}`);
    } else if (r.status === "up_to_date") {
      pushSystemMessage(no, "基座已是最新，无需同步", "info");
    } else if (r.conflicts.length > 0) {
      pushSystemMessage(
        no,
        `基座已同步（前进 ${r.behind} 个提交），重放你的改动时出现冲突：${r.conflicts.join("、")}。请在沙箱中解决冲突标记后继续编码。`,
        "warn",
      );
    } else {
      pushSystemMessage(
        no,
        `基座已同步：工单分支快进 ${r.behind} 个提交，未提交改动已原样保留` +
          (r.stash_kept ? "（部分改动仍留在 stash 中）" : ""),
        "success",
      );
    }
  } catch (e) {
    showToast(`基座同步失败：${(e as Error).message}`);
  } finally {
    setGateBusy(no, false);
  }
}

export async function liveReview(no: string, opts?: { humanPass?: boolean; note?: string }) {
  setGateBusy(no, true);
  setStage(no, "IN_REVIEW");
  setReviewError(no, null);
  try {
    setTask(no, {
      kind: "review",
      percent: 30,
      label: opts ? "正在提交人工判决" : "引擎执行中",
      done: false,
    });
    const body: Record<string, unknown> = {};
    if (opts) {
      if (opts.humanPass !== undefined) body.human_pass = opts.humanPass;
      if (opts.note !== undefined) body.note = opts.note;
    }
    const { task_id } = await api<{ task_id: string }>(`/api/tickets/${no}/review`, {
      method: "POST",
      body: JSON.stringify(body),
    });
    // 轮询期间同步进度到审查发现页：用户被自动切到该页后能看到推进而不是"无事发生"。
    const outcome = await pollTask(task_id, (percent, label) => {
      setTask(no, { kind: "review", percent, label, done: false });
    });
    if (!outcome.ok) {
      // 失败原因必须可见：审查发现页挂错误卡片 + 会话流追加同文，替代笼统的"异常结束"。
      const detail = outcome.error?.message || "未知错误";
      setReviewError(no, detail);
      setTask(no, { kind: "review", percent: 100, label: "审查失败", done: true, failed: true });
      pushSystemMessage(no, `审查任务失败：${detail}`, "warn");
      showToast(`审查失败：${detail}`);
      await refreshTicket(no).catch(() => {});
      setCenterTab("findings");
      return;
    }
    setTask(no, { kind: "review", percent: 100, label: "判决完成", done: true });
    await sleep(300);
    await loadReviewState(no);
    await refreshTicket(no);
    if (opts?.humanPass === true) pushSystemMessage(no, "人工核准通过 · 发布授权已签发", "success");
    if (opts?.humanPass === false) pushSystemMessage(no, "人工驳回 · 请根据审查意见修复后重新提审", "warn");
    setCenterTab("findings");
  } catch (e) {
    const detail = (e as Error).message;
    setReviewError(no, detail);
    showToast(`审查失败：${detail}`);
    pushSystemMessage(no, `审查任务失败：${detail}`, "warn");
    await refreshTicket(no).catch(() => {
      /* 状态回刷失败忽略 */
    });
  } finally {
    setTask(no, null);
    setGateBusy(no, false);
  }
}

export async function livePublish(no: string) {
  setGateBusy(no, true);
  try {
    setTask(no, { kind: "publish", percent: 40, label: "原子推送中", done: false });
    const { task_id } = await api<{ task_id: string }>(`/api/tickets/${no}/publish`, {
      method: "POST",
      body: "{}",
    });
    const outcome = await pollTask(task_id, (percent, label) => {
      setTask(no, { kind: "publish", percent, label, done: false });
    });
    if (!outcome.ok) {
      const detail = outcome.error?.message || "未知错误";
      showToast(`发布失败：${detail}`);
      await refreshTicket(no).catch(() => {});
      return;
    }
    setTask(no, { kind: "publish", percent: 100, label: "发布完成", done: true });
    await sleep(250);
    try {
      const t = await api<{ result_json?: string }>(`/api/tasks/${task_id}`);
      if (t.result_json) {
        const r = JSON.parse(t.result_json);
        setOutcome(no, {
          commitSha: r.commit_sha ?? "",
          refBefore: r.ref_before ?? "",
          refAfter: r.ref_after ?? "",
          targetRef: r.target_ref ?? "refs/heads/main",
          publishedAt: Date.now(),
          workspaceSyncStatus: r.workspace_sync_status ?? null,
          workspaceSyncNote: r.workspace_sync_note ?? null,
        });
      }
    } catch {
      /* 忽略结果解析失败 */
    }
    await refreshTicket(no);
    if (outcome.ok) showToast("发布成功，主分支已更新");
  } catch (e) {
    showToast(`发布失败：${(e as Error).message}`);
  } finally {
    setTask(no, null);
    setGateBusy(no, false);
  }
}

/** 任务失败时后端写入 error_json 的结构（TaskRunner.fail）。 */
interface TaskError {
  error_code: number;
  error: string;
  message: string;
}

interface TaskOutcome {
  ok: boolean;
  error?: TaskError;
}

async function pollTask(taskId: string, onProgress?: (percent: number, label: string) => void): Promise<TaskOutcome> {
  // 轮询查询本身是脆弱链路：任一单次 GET 抖动（代理重启、超时）若直接判死，
  // 会出现"未知错误"卡片而任务其实在后端正常跑完。连续失败 N 次才算查询不可用；
  // 期间任务照常 RUNNING，终态才按 FAILED/CANCELLED 处理。
  const MAX_CONSECUTIVE_ERRORS = 6;
  // 轮询上限跟随引擎配置：gate-engine 慢模型一次要几分钟，没人能把写死的 240×500ms 等完。
  const engine = appStore.getState().engine;
  const engineTimeoutSec = engine?.timeoutSeconds ?? 120;
  // 轮询上限 = 引擎超时 + 心跳缓冲（30s），再加 6 个连续错误的兜底防线
  const maxIterations = Math.ceil((engineTimeoutSec + 30) / 0.5);
  let consecutiveErrors = 0;
  for (let i = 0; i < maxIterations; i++) {
    await sleep(500);
    let t: { status: string; result_json?: string | null; error_json?: string | null };
    try {
      t = await api<{ status: string; result_json?: string | null; error_json?: string | null }>(
        `/api/tasks/${taskId}`,
      );
    } catch {
      consecutiveErrors++;
      if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
        return {
          ok: false,
          error: { error_code: 0, error: "POLL", message: "任务状态查询连续失败（网络或后端抖动），任务可能仍在后台执行——稍后重新打开工单查看结果" },
        };
      }
      continue;
    }
    consecutiveErrors = 0;
    if (t.status === "SUCCEEDED") return { ok: true };
    if (t.status === "RUNNING" || t.status === "QUEUED" || t.status === "RETRY_WAIT") {
      // 后端只在 10%/30%/90% 几个锚点写进度（引擎期心跳为 30% + 耗时），原样透传。
      try {
        const p = t.result_json ? (JSON.parse(t.result_json) as { percent?: number; label?: string }) : null;
        if (p && typeof p.percent === "number") onProgress?.(p.percent, p.label ?? "");
      } catch {
        /* 进度体解析失败忽略 */
      }
      continue;
    }
    if (t.status === "FAILED" || t.status === "CANCELLED") {
      let error: TaskError | undefined;
      try {
        if (t.error_json) error = JSON.parse(t.error_json) as TaskError;
      } catch {
        /* error_json 非结构化时按未知错误处理 */
      }
      return { ok: false, error };
    }
  }
  return {
    ok: false,
    error: { error_code: 0, error: "TIMEOUT",
      message: `任务超过 ${engineTimeoutSec}s 仍未完成（引擎超时上限），请稍后重新打开工单查看结果` },
  };
}

export function liveDiffBytes(no: string): number {
  const st = appStore.getState();
  return approxDiffBytes(st.diffs[no] ?? []);
}

interface RawProject {
  id: string;
  name: string;
  workspace_path: string;
  target_ref?: string | null;
  auth_repo?: string | null;
  priority?: string | null;
  size?: string | null;
  tags?: string[] | null;
  ticket_count?: number;
  active_ticket_count?: number;
  created_at: string;
  updated_at: string;
}

function mapProject(p: RawProject): import("./types").Project {
  return {
    id: p.id,
    name: p.name,
    workspacePath: p.workspace_path,
    targetRef: p.target_ref ?? "refs/heads/main",
    authRepo: p.auth_repo ?? "",
    priority: (p.priority as import("./types").Priority | null) ?? null,
    size: (p.size as "small" | "medium" | "large" | null) ?? null,
    tags: p.tags ?? [],
    ticketCount: p.ticket_count ?? 0,
    activeTicketCount: p.active_ticket_count ?? 0,
    createdAt: p.created_at,
    updatedAt: p.updated_at,
  };
}

export async function loadProjects() {
  const data = await api<{ projects: RawProject[] }>("/api/projects");
  appStore.setState({ projects: data.projects.map(mapProject) });
}

/* ─── 项目 → 仓库视图（分支图 + 文件树，读工作区仓库） ─── */

interface RawGitBranch {
  name: string;
  tip: string;
  lane: number;
}

interface RawGitCommit {
  sha: string;
  parents?: string[] | null;
  message: string;
  author: string;
  time: string;
  refs?: string[] | null;
  lane: number;
}

interface RawGitRepoView {
  repo_path?: string | null;
  head?: string | null;
  branches?: RawGitBranch[] | null;
  commits?: RawGitCommit[] | null;
  truncated?: boolean | null;
  auth?: { repo: string | null; target_ref: string | null; tip: string | null } | null;
}

/** GET /api/projects/{id}/repo — branches + topo commits with lane numbers. */
export async function loadProjectRepoView(projectId: string): Promise<void> {
  const data = await api<RawGitRepoView>(`/api/projects/${projectId}/repo`);
  const view: GitRepoView = {
    branches: (data.branches ?? []).map((b) => ({ name: b.name, tip: b.tip, lane: b.lane })),
    commits: (data.commits ?? []).map((c) => ({
      sha: c.sha,
      parents: c.parents ?? [],
      message: c.message,
      author: c.author,
      time: c.time,
      refs: c.refs ?? [],
      lane: c.lane,
    })),
    truncated: data.truncated ?? false,
    auth: data.auth ?? undefined,
  };
  appStore.setState((st) => ({ gitViews: { ...st.gitViews, [projectId]: view } }));
}

interface RawTreeEntry {
  path: string;
  type: string;
  size?: number | null;
  last_commit_short?: string | null;
  last_message?: string | null;
}

/**
 * GET /api/projects/{id}/tree[/{dir…}] — direct children of one directory with last-commit
 * attribution. The root listing lands in treeViews; callers expanding deeper keep the
 * children themselves (they refetch on reopen anyway).
 */
export async function loadProjectTree(projectId: string, dir = ""): Promise<GitTreeEntry[]> {
  const suffix = dir
    ? "/" + dir.split("/").map(encodeURIComponent).join("/")
    : "";
  const data = await api<{ entries?: RawTreeEntry[] }>(`/api/projects/${projectId}/tree${suffix}`);
  const entries: GitTreeEntry[] = (data.entries ?? []).map((e) => ({
    path: e.path,
    type: e.type === "dir" ? "dir" : "file",
    size: e.size ?? undefined,
    lastCommitShort: e.last_commit_short ?? "",
    lastMessage: e.last_message ?? "",
  }));
  if (!dir) {
    appStore.setState((st) => ({ treeViews: { ...st.treeViews, [projectId]: entries } }));
  }
  return entries;
}

export async function createProjectLive(body: {
  name: string;
  workspace_path: string;
  target_branch?: string;
  init_git?: boolean;
  priority?: string | null;
  size?: string | null;
  tags?: string[];
}): Promise<boolean> {
  try {
    // The response is the created project row; switch the workspace context to it so
    // the freshly onboarded (usually empty) project is immediately usable.
    const created = await api<{ id: string }>("/api/projects", {
      method: "POST",
      body: JSON.stringify(body),
    });
    await loadProjects();
    if (created?.id) {
      appStore.setState({ activeProjectId: created.id });
    }
    return true;
  } catch (e) {
    showToast(`创建项目失败：${(e as Error).message}`);
    return false;
  }
}

export async function updateProjectLive(id: string, body: Record<string, unknown>): Promise<boolean> {
  try {
    await api(`/api/projects/${id}`, { method: "PUT", body: JSON.stringify(body) });
    await loadProjects();
    return true;
  } catch (e) {
    showToast(`更新项目失败：${(e as Error).message}`);
    return false;
  }
}

export async function deleteProjectLive(id: string): Promise<boolean> {
  try {
    await api(`/api/projects/${id}`, { method: "DELETE" });
    await loadProjects();
    await loadTickets();
    return true;
  } catch (e) {
    showToast(`删除项目失败：${(e as Error).message}`);
    return false;
  }
}

export async function syncProjectWorkspace(projectId: string): Promise<WorkspaceSyncResult | null> {
  try {
    const res = await api<WorkspaceSyncResult>(`/api/projects/${projectId}/workspace-sync`, {
      method: "POST",
      body: "{}",
    });
    if (res.status === "SYNCED") {
      showToast("工作区已同步");
    } else if (res.status === "ALREADY") {
      showToast("工作区已是最新");
    } else if (res.status === "DEFERRED") {
      showToast(res.note ? `工作区待同步：${res.note}` : "工作区待同步");
    }
    return res;
  } catch (e) {
    showToast(`工作区同步失败：${(e as Error).message}`);
    return null;
  }
}

export async function updateTicketLive(
  no: string,
  body: Record<string, unknown>,
): Promise<boolean> {
  try {
    await api(`/api/tickets/${no}`, { method: "PATCH", body: JSON.stringify(body) });
    await refreshTicket(no);
    return true;
  } catch (e) {
    showToast(`更新工单失败：${(e as Error).message}`);
    return false;
  }
}

/** 拉取工单重启历史（T-117），落盘到 store；失败静默（旧后端无此接口）。 */
export async function loadRestarts(no: string) {
  try {
    const data = await api<{
      restarts: Array<{
        round: number;
        from_stage: string;
        reason: string;
        created_at: string | null;
      }>;
    }>(`/api/tickets/${no}/restarts`);
    const list: import("./types").RestartRecord[] = (data.restarts ?? []).map((r) => ({
      round: r.round,
      fromStage: r.from_stage as import("./types").Stage,
      reason: r.reason,
      createdAt: r.created_at,
    }));
    appStore.setState((st) => ({ restarts: { ...st.restarts, [no]: list } }));
  } catch {
    /* 后端不支持或尚无记录时静默 */
  }
}

/** 重启终态工单（T-117）：填理由 → PATCH 转回 IN_PROGRESS，后端记录历史并开新轮次。 */
export async function restartTicketLive(no: string, reason: string): Promise<boolean> {
  const ok = await updateTicketLive(no, {
    stage: "IN_PROGRESS",
    restart_reason: reason,
  });
  if (ok) {
    await loadRestarts(no).catch(() => {});
  }
  return ok;
}

interface RawAgentConfig {
  id: string;
  name: string;
  cli: string;
  provider_id?: string | null;
  model?: string | null;
  system_prompt?: string | null;
  extra_flags?: string[] | null;
  description?: string | null;
  inject_context?: boolean | null;
}

function mapAgentConfig(c: RawAgentConfig): import("./types").AgentConfig {
  return {
    id: c.id,
    name: c.name,
    cli: c.cli.toLowerCase() as "claude" | "opencode",
    providerId: c.provider_id ?? null,
    model: c.model ?? "",
    systemPrompt: c.system_prompt ?? null,
    extraFlags: c.extra_flags ?? [],
    description: c.description ?? null,
    injectContext: c.inject_context !== false,
  };
}

export async function loadAgentConfigs() {
  const data = await api<{ agent_configs: RawAgentConfig[] }>("/api/agent-configs");
  appStore.setState((st) => ({
    agents: data.agent_configs.map(mapAgentConfig),
    // Snap an invalid (e.g. demo-era or deleted) selection to a real config so the
    // composer picker always shows what a new session will actually use.
    agentId: data.agent_configs.some((c) => c.id === st.agentId)
      ? st.agentId
      : (data.agent_configs[0]?.id ?? ""),
  }));
}

export async function upsertAgentConfigLive(c: import("./types").AgentConfig): Promise<boolean> {
  try {
    const body = JSON.stringify({
      id: c.id,
      name: c.name,
      cli: c.cli.toUpperCase(),
      provider_id: c.providerId,
      model: c.model,
      system_prompt: c.systemPrompt,
      extra_flags: c.extraFlags,
      description: c.description,
      inject_context: c.injectContext,
    });
    const exists = appStore.getState().agents.some((x) => x.id === c.id);
    await api(`/api/agent-configs${exists ? `/${c.id}` : ""}`, {
      method: exists ? "PUT" : "POST",
      body,
    });
    await loadAgentConfigs();
    return true;
  } catch (e) {
    showToast(`保存智能体配置失败：${(e as Error).message}`);
    return false;
  }
}

export async function deleteAgentConfigLive(id: string): Promise<boolean> {
  try {
    await api(`/api/agent-configs/${id}`, { method: "DELETE" });
    await loadAgentConfigs();
    return true;
  } catch (e) {
    showToast(`删除智能体配置失败：${(e as Error).message}`);
    return false;
  }
}

export async function loadRuntimes(): Promise<boolean> {
  try {
    const data = await api<{ agent_runtimes: import("./types").AgentRuntime[] }>("/api/agent-runtimes");
    appStore.setState({ runtimes: data.agent_runtimes });
    return true;
  } catch {
    return false;
  }
}

/* ─── 运行中智能体轮询（GET /api/agents/busy） ─── */

interface RawBusyAgent {
  session_id: string;
  title: string | null;
  ticket_no: string | null;
  cli: string | null;
}

export async function fetchBusyAgents(): Promise<void> {
  const st = appStore.getState();
  // 非 live 或未连接时清空并停止轮询（按契约失败时静默）
  if (st.mode !== "live" || st.conn !== "ok") {
    appStore.setState({ runningAgents: { count: 0, sessions: [] } });
    stopAgentBusyPolling();
    return;
  }
  try {
    const data = await api<{ count: number; running: RawBusyAgent[] }>("/api/agents/busy");
    appStore.setState({
      runningAgents: {
        count: typeof data.count === "number" ? data.count : 0,
        sessions: Array.isArray(data.running)
          ? data.running.map((r) => ({
              session_id: r.session_id,
              title: r.title ?? null,
              ticket_no: r.ticket_no ?? null,
              cli: r.cli ?? null,
            }))
          : [],
      },
    });
  } catch {
    // 请求失败按现有 fetch 封装行为处理：静默，不改状态
  }
}

let busyPollTimer: ReturnType<typeof setInterval> | null = null;
let busyPollVisibilityAttached = false;

function handleBusyVisibility() {
  // 切回可见时立即拉一次
  if (document.hidden) return;
  const st = appStore.getState();
  if (st.mode !== "live" || st.conn !== "ok") {
    appStore.setState({ runningAgents: { count: 0, sessions: [] } });
    stopAgentBusyPolling();
    return;
  }
  void fetchBusyAgents();
}

export function startAgentBusyPolling() {
  // 重复调用不得叠加定时器
  if (busyPollTimer !== null) return;
  const st = appStore.getState();
  if (st.mode !== "live" || st.conn !== "ok") {
    appStore.setState({ runningAgents: { count: 0, sessions: [] } });
    return;
  }
  // 绑定 visibilitychange（仅一次）
  if (!busyPollVisibilityAttached) {
    document.addEventListener("visibilitychange", handleBusyVisibility);
    busyPollVisibilityAttached = true;
  }
  // 立即拉一次，再按 3 秒轮询
  void fetchBusyAgents();
  busyPollTimer = setInterval(() => {
    // document.hidden 时暂停
    if (document.hidden) return;
    const cur = appStore.getState();
    if (cur.mode !== "live" || cur.conn !== "ok") {
      appStore.setState({ runningAgents: { count: 0, sessions: [] } });
      stopAgentBusyPolling();
      return;
    }
    void fetchBusyAgents();
  }, 3000);
}

export function stopAgentBusyPolling() {
  if (busyPollTimer !== null) {
    clearInterval(busyPollTimer);
    busyPollTimer = null;
  }
  if (busyPollVisibilityAttached) {
    document.removeEventListener("visibilitychange", handleBusyVisibility);
    busyPollVisibilityAttached = false;
  }
  // 停轮询时若已不在 live/ok 也清零（调用方可能已清，这里兜底）
  const st = appStore.getState();
  if (st.mode !== "live" || st.conn !== "ok") {
    // 避免无谓 setState 触发订阅：仅在非空时清
    if (st.runningAgents.count !== 0 || st.runningAgents.sessions.length !== 0) {
      appStore.setState({ runningAgents: { count: 0, sessions: [] } });
    }
  }
}

/* ─── 设置中心 ── */


export async function fetchGateToml(): Promise<GateTomlResponse> {
  return api<GateTomlResponse>("/api/settings/gate-toml");
}

export async function updateGateToml(updates: Record<string, unknown>): Promise<{ ok: boolean; updated: string[]; restart_required: boolean }> {
  return api<{ ok: boolean; updated: string[]; restart_required: boolean }>("/api/settings/gate-toml", {
    method: "PUT",
    body: JSON.stringify({ updates }),
  });
}

export async function fetchMcpStatus(): Promise<McpStatus> {
  return api<McpStatus>("/api/mcp/status");
}

export async function fetchProviders(): Promise<LlmProvider[]> {
  const data = await api<{ providers: LlmProvider[] }>("/api/providers");
  return data.providers ?? [];
}

export async function createProvider(body: { id: string; name: string; base_url: string; type: string }): Promise<LlmProvider> {
  return api<LlmProvider>("/api/providers", { method: "POST", body: JSON.stringify(body) });
}

export async function updateProvider(id: string, body: { name: string; base_url: string; type: string }): Promise<LlmProvider> {
  return api<LlmProvider>(`/api/providers/${encodeURIComponent(id)}`, { method: "PUT", body: JSON.stringify(body) });
}

export async function deleteProvider(id: string): Promise<void> {
  await api<void>(`/api/providers/${encodeURIComponent(id)}`, { method: "DELETE" });
}

/** 保存 Provider 的 API Key（设置中心直填）：明文仅在请求体出现一次，后端 KMS 加密落库。 */
export async function setProviderCredential(id: string, apiKey: string): Promise<LlmProvider> {
  return api<LlmProvider>(`/api/providers/${encodeURIComponent(id)}/credential`, {
    method: "PUT",
    body: JSON.stringify({ api_key: apiKey }),
  });
}

/** 清除 Provider 的已存密钥（api_key_ref 置为 unconfigured）。 */
export async function clearProviderCredential(id: string): Promise<LlmProvider> {
  return api<LlmProvider>(`/api/providers/${encodeURIComponent(id)}/credential`, { method: "DELETE" });
}

export async function updateProviderModels(id: string, models: string[]): Promise<LlmProvider> {
  return api<LlmProvider>(`/api/providers/${encodeURIComponent(id)}/models`, {
    method: "PUT",
    body: JSON.stringify({ models }),
  });
}

export async function fetchUpstreamModels(id: string): Promise<LlmProvider> {
  return api<LlmProvider>(`/api/providers/${encodeURIComponent(id)}/models/fetch`, { method: "POST", body: "{}" });
}
