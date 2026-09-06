/**
 * 会话域 API（session）：会话列表/元数据、消息历史与本地会话操作。
 * SSE 流消费见 stream.ts；模型目录见 catalog.ts；权限/提问见 permissions.ts。
 */
import { api } from "@/net";
import { appStore, showToast } from "@/store";
import { isTodoTool, parseTodos } from "@/shared/todoUtils";
import type { ChatItem } from "@/shared/types";
import { mapHistoryMessage, mapSession, type RawMessage, type RawSession } from "./model";
import { dropLiveTurn, applyReplyMetaDefaults } from "./chat";
import { dropSessionExtras, dropSessionPendings, refreshTicketBusy, setContextTokens, setSessionBusy, setTodos } from "./state";

export function ticketNoOfSession(id: string): string | null {
  for (const [no, list] of Object.entries(appStore.getState().sessions)) {
    if (list.some((s) => s.id === id)) return no;
  }
  return null;
}

export async function loadTicketSessions(no: string) {
  const prevIds = new Set((appStore.getState().sessions[no] ?? []).map((s) => s.id));
  const data = await api<{ sessions: RawSession[] }>(`/api/tickets/${no}/sessions`);
  const list = (data.sessions ?? []).map((s) => mapSession(no, s));
  appStore.setState((st) => {
    // 保留「上次查看的会话」：从工单列表切走再点回来不跳回最新会话。
    // 仅当记忆的会话已不存在或已归档时才回退到最近活跃会话。
    const prev = st.activeSessionId[no] ?? "";
    const remembered = prev !== "" && list.some((sess) => sess.id === prev && sess.status === "active");
    let activeId = remembered ? prev : "";
    if (!activeId) {
      let latest: import("@/shared/types").ChatSession | undefined;
      for (const sess of list) {
        if (sess.status === "active" && (!latest || sess.createdAt >= latest.createdAt)) latest = sess;
      }
      activeId = latest?.id ?? "";
    }
    return {
      sessions: { ...st.sessions, [no]: list },
      activeSessionId: { ...st.activeSessionId, [no]: activeId },
    };
  });
  // 列表收敛：本工单从上次列表里消失的会话，其置顶/分组归属随刷新抹除，
  // 避免带外删除（他端/后端）后归属残留被同 id 重建会话静默继承
  const gone = [...prevIds].filter((id) => !list.some((s) => s.id === id));
  if (gone.length > 0) dropSessionExtras(gone);
}

/**
 * 仅同步会话列表元数据（标题、归档状态等），不改 active 指针。
 * 用于回合结束后的被动刷新（如 opencode 自动生成标题写库后），避免把用户
 * 正在查看的会话强行切走。
 */
export async function refreshTicketSessionsMeta(no: string) {
  try {
    const prevIds = new Set((appStore.getState().sessions[no] ?? []).map((s) => s.id));
    const data = await api<{ sessions: RawSession[] }>(`/api/tickets/${no}/sessions`);
    const list = (data.sessions ?? []).map((s) => mapSession(no, s));
    appStore.setState((st) => ({ sessions: { ...st.sessions, [no]: list } }));
    // 与 loadTicketSessions 同口径的归属收敛（被动刷新路径也要收敛消失会话的残留）
    const gone = [...prevIds].filter((id) => !list.some((s) => s.id === id));
    if (gone.length > 0) dropSessionExtras(gone);
  } catch {
    /* 静默失败：下个常规动作还有一次刷新机会 */
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

export async function deleteSessionLive(id: string, ticketNo: string) {
  try {
    await api(`/api/sessions/${id}`, { method: "DELETE" });
  } catch (e) {
    showToast(`删除会话失败：${(e as Error).message}`);
    return;
  }
  dropLiveTurn(id);
  setSessionBusy(id, false);
  // 会话删除后其未决权限/提问随会话消亡，工单列表的待决徽标同步注销
  dropSessionPendings(id);
  // 置顶/分组归属一并抹除——live 与 demo 走同一条收敛路径，归属残留不让同 id 重建会话复用
  dropSessionExtras([id]);
  await loadTicketSessions(ticketNo).catch(() => {});
  refreshTicketBusy(ticketNo);
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
  // 例外：该会话仍有 SSE 直连的生成中回合（liveTurns）时跳过历史重建——服务端
  // 整回合 idle 才落库，历史里看不到本回合已写的 todo，按历史重建会把流式事件
  // 刚更新的任务环清空（agent 仍在输出时图标消失）。此时 store 里的清单由
  // 后续 tool 事件继续增量刷新，切走再切回也不中断。
  if (!stash) restoreTodosFromHistory(no, hist.messages);
  setContextTokens(no, 0);
  // 后端历史消息不带 model/agent 标注，这里用当前会话的 agent/推理等级补齐底部 footer。
  applyReplyMetaDefaults(no);
}

/** 以该会话历史中最后一条合法 todowrite 参数重建任务清单；没有则清空。 */
export function restoreTodosFromHistory(no: string, messages: RawMessage[]) {
  let latest: string | null = null;
  for (const m of messages) {
    for (const tc of m.tool_calls ?? []) {
      if (isTodoTool(tc.name) && tc.arguments_json) latest = tc.arguments_json;
    }
  }
  const todos = latest ? parseTodos(latest) : null;
  setTodos(no, todos ?? []);
}

/**
 * 轻量收敛：仅用服务端已落库的消息历史回算该会话的任务清单（不触碰聊天视图与
 * 上下文占用）。供回合结束与「无 SSE 直连」的后台会话轮询兜底——流式事件一旦
 * 丢失（断流/刷新/后台运行），任务清单在此与持久化数据最终一致，不再只靠
 * 手动切会话/切工单才会刷新。
 */
export async function syncSessionTodos(no: string, sessionId: string) {
  try {
    const hist = await api<{ messages: RawMessage[] }>(`/api/sessions/${sessionId}/messages`);
    restoreTodosFromHistory(no, hist.messages);
  } catch {
    /* 静默失败：下一次同步时机（下一拍轮询/下一回合结束）再试 */
  }
}
