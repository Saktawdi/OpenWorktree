import {
  addSnapshot,
  addUsage,
  appStore,
  dropLiveTurn,
  finishLiveTurn,
  pushPermissionRequest,
  pushSystemMessage,
  pushUserMessage,
  refreshTicketBusy,
  resolvePermission,
  setBusy,
  setCenterTab,
  setDiffs,
  setFindings,
  setGateBusy,
  setOutcome,
  setSessionBusy,
  setStage,
  setTask,
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
  Severity,
  Snapshot,
  WorkspaceSyncResult,
} from "./types";
import { parseUnifiedDiff } from "./diff";
import { approxDiffBytes } from "./diff";
import { sleep } from "./format";
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
    createdAt: t.created_at,
    updatedAt: t.updated_at,
  };
}

export async function loadTickets() {
  const data = await api<{ tickets: RawTicket[] }>("/api/tickets");
  appStore.setState({ tickets: data.tickets.map(mapTicket) });
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
  const loadDiff = () => loadTicketDiff(no);
  const loadSessions = async () => {
    try {
      await loadTicketSessions(no);
      const st = appStore.getState();
      const target = st.activeSessionId[no] || st.sessions[no]?.[st.sessions[no].length - 1]?.id;
      if (target) {
        await loadSessionMessages(no, target);
        void loadSessionCatalog(no, target);
        // 目标会话为 ACTIVE 时恢复未决的权限询问卡片（若已就绪）。
        const sess = st.sessions[no]?.find((x) => x.id === target);
        if (sess?.status === "active") void loadSessionPermissions(no, target);
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
  findings: string;
}

function parseFindings(raw: string): Finding[] {
  let findings: Finding[] = [];
  try {
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) {
      findings = parsed.map((f: Record<string, unknown>) => ({
        severity: (f.severity as Severity) ?? "INFO",
        path: String(f.path ?? ""),
        lineStart: typeof f.lineStart === "number" ? f.lineStart : undefined,
        ruleId: f.ruleId ? String(f.ruleId) : undefined,
        message: String(f.message ?? ""),
        suggestion: f.suggestion ? String(f.suggestion) : undefined,
      }));
    }
  } catch {
    /* findings 非结构化时忽略 */
  }
  return findings;
}

function verdictReason(verdict: string): string {
  if (verdict === "PASS") return "全部策略通过，发布授权已签发";
  if (verdict === "REQUIRES_HUMAN") return "需人工核准后放行";
  return "存在待处理项，详见审查发现";
}

async function applyReviewResult(no: string) {
  const rr = await api<RawReviewResult>(`/api/tickets/${no}/review-result`);
  setFindings(no, parseFindings(rr.findings));
  setVerdict(no, {
    verdict: rr.verdict as "PASS" | "REJECT" | "REQUIRES_HUMAN",
    reason: verdictReason(rr.verdict),
    engineId: rr.engine_id,
    round: rr.review_round,
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
    // 新建会话为 ACTIVE，预拉未决权限（一般为空，保持路径一致）。
    void loadSessionPermissions(no, created.id);
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
      tools: (m.tool_calls ?? []).map((tc, i) => ({
        id: `${m.id}-${i}`,
        name: "工具调用",
        icon: "terminal" as const,
        // Match the live-streamed row: tool name followed by its full arguments JSON.
        argsSummary: `${tc.name}${tc.arguments_json ?? ""}`,
        resultSummary: tc.result_json?.slice(0, 80),
        resultDetail: tc.result_json,
        status: "ok" as const,
      })),
      ts: Date.parse(m.timestamp),
    };
  }
  return null;
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
}

export async function liveSendPrompt(no: string, userText: string) {
  const st = appStore.getState();
  // 目标永远是「当前查看的会话」（activeSessionId），不再有跨工单/跨会话的全局游标；
  // 同一会话生成中不允许并发追加，其他会话不受影响。
  const sessionId = st.activeSessionId[no];
  if (sessionId && st.sessionBusy[sessionId]) return;
  pushUserMessage(no, userText);
  setBusy(no, true);
  let sid: string | null = sessionId || null;
  try {
    if (!sid) {
      const created = await api<{ id: string }>(`/api/tickets/${no}/sessions`, {
        method: "POST",
        body: JSON.stringify({ agent_config_id: st.agentId, initial_prompt: userText }),
      });
      sid = created.id;
      appStore.setState((s2) => ({ activeSessionId: { ...s2.activeSessionId, [no]: sid! } }));
    } else {
      const sel = st.sessionModelSel[sid];
      await api(`/api/sessions/${sid}/messages`, {
        method: "POST",
        body: JSON.stringify({
          message: userText,
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
}

interface RawCatalogProvider {
  id: string;
  name?: string | null;
  models?: RawCatalogModel[] | null;
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
  } catch {
    /* catalog is best-effort: the picker just stays empty */
  }
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
      updateLiveTurn(sessionId, (a) => {
        const existing = a.tools.find((t) => t.id === d.call_id);
        if (existing) {
          return {
            ...a,
            tools: a.tools.map((t) =>
              t.id === d.call_id
                ? { ...t, argsSummary: t.argsSummary + (d.argument_delta ?? ""), status: d.status === "SUCCESS" ? "ok" : d.status === "FAILED" ? "error" : "running" }
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
              name: "工具调用",
              icon: "terminal" as const,
              argsSummary: d.tool_name ?? "",
              status: "running" as const,
            },
          ],
        };
      });
    });
    es.addEventListener("usage", (ev) => {
      arm();
      const d = JSON.parse((ev as MessageEvent).data);
      if (d.usage) addUsage(no, d.usage.prompt_tokens ?? 0, d.usage.completion_tokens ?? 0);
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

export async function liveReview(no: string, opts?: { humanPass?: boolean; note?: string }) {
  setGateBusy(no, true);
  setStage(no, "IN_REVIEW");
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
    const ok = await pollTask(task_id);
    setTask(no, { kind: "review", percent: 100, label: "判决完成", done: true });
    await sleep(300);
    await loadReviewState(no);
    await refreshTicket(no);
    if (!ok) pushSystemMessage(no, "审查任务异常结束，请重试或人工核准", "warn");
    if (opts?.humanPass === true) pushSystemMessage(no, "人工核准通过 · 发布授权已签发", "success");
    if (opts?.humanPass === false) pushSystemMessage(no, "人工驳回 · 请根据审查意见修复后重新提审", "warn");
    setCenterTab("findings");
  } catch (e) {
    showToast(`审查失败：${(e as Error).message}`);
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
    const ok = await pollTask(task_id);
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
    if (ok) showToast("发布成功，主分支已更新");
  } catch (e) {
    showToast(`发布失败：${(e as Error).message}`);
  } finally {
    setTask(no, null);
    setGateBusy(no, false);
  }
}

async function pollTask(taskId: string): Promise<boolean> {
  for (let i = 0; i < 240; i++) {
    await sleep(500);
    try {
      const t = await api<{ status: string }>(`/api/tasks/${taskId}`);
      if (t.status === "SUCCEEDED") return true;
      if (t.status === "FAILED" || t.status === "CANCELLED") return false;
    } catch {
      return false;
    }
  }
  return false;
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
