import {
  addSnapshot,
  addUsage,
  appStore,
  finishAssistant,
  patchAssistant,
  pushAssistantPlaceholder,
  pushPermissionRequest,
  pushSystemMessage,
  pushUserMessage,
  resolvePermission,
  setBusy,
  setCenterTab,
  setDiffs,
  setFindings,
  setGateBusy,
  setOutcome,
  setStage,
  setTask,
  setVerdict,
  showToast,
} from "./store";
import type { ChatItem, ChatSession, CatalogProvider, DiffFile, Finding, Severity, Snapshot } from "./types";
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

export async function selectTicketLive(no: string) {
  appStore.setState({ selectedNo: no, centerTab: "chat", highlight: null });
  const loadDiff = async () => {
    try {
      const diffRes = await api<{ diff: string }>(`/api/tickets/${no}/diff`);
      const files: DiffFile[] = diffRes.diff.trim() ? parseUnifiedDiff(diffRes.diff) : [];
      setDiffs(no, files);
    } catch {
      setDiffs(no, []);
    }
  };
  const loadSessions = async () => {
    try {
      await loadTicketSessions(no);
      const st = appStore.getState();
      const target = st.activeSessionId[no] || st.sessions[no]?.[st.sessions[no].length - 1]?.id;
      if (target) {
        liveSessionId = target;
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

function sessionTimeLabel(): string {
  return `会话 ${new Date().toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" })}`;
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
    title: s.title ?? sessionTimeLabel(),
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
  liveSessionId = activeId || null;
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
    try {
      await api(`/api/sessions/${created.id}`, {
        method: "PATCH",
        body: JSON.stringify({ title: sessionTimeLabel() }),
      });
    } catch {
      /* 标题设置失败不阻断 */
    }
    await loadTicketSessions(no);
    appStore.setState((st) => ({ activeSessionId: { ...st.activeSessionId, [no]: created.id } }));
    liveSessionId = created.id;
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
  if (liveSessionId === id) liveSessionId = null;
  await loadTicketSessions(ticketNo).catch(() => {});
}

export async function abortLive(no: string) {
  const sid = liveSessionId;
  if (sid) {
    try {
      await api(`/api/sessions/${sid}/abort`, { method: "POST" });
    } catch (e) {
      showToast(`中断失败：${(e as Error).message}`);
    }
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

let liveSessionId: string | null = null;

export function setLiveSessionId(id: string | null) {
  liveSessionId = id;
}

export async function loadSessionMessages(no: string, sessionId: string) {
  const hist = await api<{ messages: RawMessage[] }>(`/api/sessions/${sessionId}/messages`);
  const items: ChatItem[] = hist.messages.map(mapHistoryMessage).filter(Boolean) as ChatItem[];
  appStore.setState((st) => ({ chats: { ...st.chats, [no]: items } }));
}

export async function liveSendPrompt(no: string, userText: string) {
  const st = appStore.getState();
  if (st.busy[no]) return;
  const sessionId = st.activeSessionId[no] || liveSessionId;
  const sel = sessionId ? st.sessionModelSel[sessionId] : undefined;
  pushUserMessage(no, userText);
  setBusy(no, true);
  try {
    if (!liveSessionId) {
      const created = await api<{ id: string }>(`/api/tickets/${no}/sessions`, {
        method: "POST",
        body: JSON.stringify({ agent_config_id: st.agentId, initial_prompt: userText }),
      });
      liveSessionId = created.id;
    } else {
      await api(`/api/sessions/${liveSessionId}/messages`, {
        method: "POST",
        body: JSON.stringify({
          message: userText,
          provider_id: sel?.providerId ?? undefined,
          model_id: sel?.modelId ?? undefined,
          variant: sel?.variant ?? undefined,
        }),
      });
    }
    await consumeSessionStream(no, liveSessionId);
  } catch (e) {
    pushSystemMessage(no, `会话失败：${(e as Error).message}`, "warn");
  } finally {
    setBusy(no, false);
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

async function consumeSessionStream(no: string, sessionId: string) {
  const token = appStore.getState().token;
  const url = `/api/sessions/${sessionId}/events${token ? `?token=${encodeURIComponent(token)}` : ""}`;
  const es = new EventSource(url);
  const assistantId = pushAssistantPlaceholder(no);

  await new Promise<void>((resolve) => {
    const finish = () => {
      es.close();
      resolve();
    };
    es.addEventListener("message", (ev) => {
      // History replay (event: message) must not touch the live placeholder: the chat is
      // already rendered from GET /messages when the session opens, and replaying past
      // assistant messages here would overwrite the streaming reply with the previous one.
      void ev;
    });
    es.addEventListener("token", (ev) => {
      const d = JSON.parse((ev as MessageEvent).data);
      patchAssistant(no, assistantId, (a) => ({
        ...a,
        text: a.text + (d.text_delta ?? ""),
        thinking: a.thinking && !a.thinking.done ? { ...a.thinking, done: true } : a.thinking,
      }));
    });
    es.addEventListener("thinking", (ev) => {
      const d = JSON.parse((ev as MessageEvent).data);
      patchAssistant(no, assistantId, (a) => ({
        ...a,
        thinking: {
          text: (a.thinking?.text ?? "") + (d.thinking_delta ?? ""),
          startedAt: a.thinking?.startedAt ?? Date.now(),
          done: false,
        },
      }));
    });
    es.addEventListener("tool_call", (ev) => {
      const d = JSON.parse((ev as MessageEvent).data);
      patchAssistant(no, assistantId, (a) => {
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
      const d = JSON.parse((ev as MessageEvent).data);
      if (d.usage) addUsage(no, d.usage.prompt_tokens ?? 0, d.usage.completion_tokens ?? 0);
    });
    es.addEventListener("permission_asked", (ev) => {
      const d = JSON.parse((ev as MessageEvent).data);
      pushPermissionRequest(no, mapPermissionAsk(d));
    });
    es.addEventListener("permission_replied", (ev) => {
      const d = JSON.parse((ev as MessageEvent).data);
      resolvePermission(no, d.permission_id, d.response ?? "once", !!d.auto);
    });
    es.addEventListener("done", finish);
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
      patchAssistant(no, assistantId, (a) => ({ ...a, streaming: false }));
      pushSystemMessage(no, msg, "warn");
      finish();
    });
    setTimeout(() => finish(), 300_000);
  });
  finishAssistant(no, assistantId);
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
