import {
  addSnapshot,
  addUsage,
  appStore,
  finishAssistant,
  patchAssistant,
  pushAssistantPlaceholder,
  pushSystemMessage,
  pushUserMessage,
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
import type { ChatItem, DiffFile, Finding, Severity } from "./types";
import { parseUnifiedDiff } from "./diff";
import { approxDiffBytes } from "./diff";
import { sleep } from "./format";

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

export async function selectTicketLive(no: string) {
  appStore.setState({ selectedNo: no, centerTab: "chat", highlight: null });
  try {
    const diffRes = await api<{ diff: string }>(`/api/tickets/${no}/diff`);
    const files: DiffFile[] = diffRes.diff.trim() ? parseUnifiedDiff(diffRes.diff) : [];
    setDiffs(no, files);
  } catch {
    setDiffs(no, []);
  }
  try {
    const sess = await api<{ sessions: Array<{ id: string }> }>(`/api/tickets/${no}/sessions`);
    const latest = sess.sessions[sess.sessions.length - 1];
    if (latest) {
      const hist = await api<{ messages: RawMessage[] }>(`/api/sessions/${latest.id}/messages`);
      const items: ChatItem[] = hist.messages.map(mapHistoryMessage).filter(Boolean) as ChatItem[];
      appStore.setState((st) => ({ chats: { ...st.chats, [no]: items } }));
      liveSessionId = latest.id;
    }
  } catch {
    /* 会话可能尚未创建 */
  }
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
        argsSummary: tc.name,
        resultSummary: tc.result_json?.slice(0, 80),
        status: "ok" as const,
      })),
      ts: Date.parse(m.timestamp),
    };
  }
  return null;
}

let liveSessionId: string | null = null;

export async function liveSendPrompt(no: string, userText: string) {
  const st = appStore.getState();
  if (st.busy[no]) return;
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
        body: JSON.stringify({ message: userText }),
      });
    }
    await consumeSessionStream(no, liveSessionId);
  } catch (e) {
    pushSystemMessage(no, `会话失败：${(e as Error).message}`, "warn");
  } finally {
    setBusy(no, false);
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
      const d = JSON.parse((ev as MessageEvent).data);
      if (d.message?.role === "USER") {
        /* 历史回放中的用户消息已在界面中 */
      } else if (d.message?.role === "ASSISTANT") {
        patchAssistant(no, assistantId, (a) => ({ ...a, text: d.message.content }));
      }
    });
    es.addEventListener("token", (ev) => {
      const d = JSON.parse((ev as MessageEvent).data);
      patchAssistant(no, assistantId, (a) => ({ ...a, text: a.text + (d.text_delta ?? "") }));
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
    es.addEventListener("done", finish);
    es.addEventListener("error", () => {
      patchAssistant(no, assistantId, (a) => ({ ...a, streaming: false }));
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

export async function liveReview(no: string) {
  setGateBusy(no, true);
  setStage(no, "IN_REVIEW");
  try {
    setTask(no, { kind: "review", percent: 30, label: "引擎执行中", done: false });
    const { task_id } = await api<{ task_id: string }>(`/api/tickets/${no}/review`, {
      method: "POST",
      body: "{}",
    });
    const ok = await pollTask(task_id);
    setTask(no, { kind: "review", percent: 100, label: "判决完成", done: true });
    await sleep(300);
    try {
      const rr = await api<{ verdict: string; engine_id: string; review_round: number; findings: string }>(
        `/api/tickets/${no}/review-result`,
      );
      let findings: Finding[] = [];
      try {
        const parsed = JSON.parse(rr.findings);
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
      setFindings(no, findings);
      setVerdict(no, {
        verdict: rr.verdict as "PASS" | "REJECT" | "REQUIRES_HUMAN",
        reason: rr.verdict === "PASS" ? "全部策略通过，发布授权已签发" : "存在待处理项，详见审查发现",
        engineId: rr.engine_id,
        round: rr.review_round,
      });
    } catch {
      /* 无审查结果记录 */
    }
    await refreshTicket(no);
    if (!ok) pushSystemMessage(no, "审查任务异常结束，请重试或人工核准", "warn");
    setCenterTab("findings");
  } catch (e) {
    showToast(`审查失败：${(e as Error).message}`);
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
