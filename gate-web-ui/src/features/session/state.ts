/**
 * 会话域状态（session）：会话/草稿/生成中标记/待决登记，以及按工单键暂存的
 * 用量、任务清单与上下文占用。聊天条目与回合见 chat.ts。
 */
import { appStore } from "@/store";
import { saveComposerDrafts } from "@/store/prefs";
import type { CatalogProvider, ChatSession, SessionModelSel, TodoItem } from "@/shared/types";
import { uid } from "@/shared/format";
import { emitPluginEvent } from "@/app/plugins/events";

const set = appStore.setState;
const s = () => appStore.getState();

/* ─── 会话内实时切换模型 / 推理强度（OpenChamber 式 per-session 选择） ─── */

export function setSessionModels(sessionId: string, providers: CatalogProvider[]) {
  set((st) => ({ sessionModels: { ...st.sessionModels, [sessionId]: providers } }));
}

export function setSessionModelSel(sessionId: string, sel: SessionModelSel) {
  set((st) => ({ sessionModelSel: { ...st.sessionModelSel, [sessionId]: sel } }));
}

/** 草稿态暂存模型/推理选择；创建会话时持久化为覆盖，切 agent 时清空回退新默认。 */
export function setDraftModelSel(ticketNo: string, sel: SessionModelSel) {
  set((st) => ({ draftModelSel: { ...st.draftModelSel, [ticketNo]: sel } }));
}

export function clearDraftModelSel(ticketNo: string) {
  set((st) => {
    if (!st.draftModelSel[ticketNo]) return st;
    const draftModelSel = { ...st.draftModelSel };
    delete draftModelSel[ticketNo];
    return { draftModelSel };
  });
}

/* ─── 输入框草稿自动保存（按工单键暂存 + localStorage 落盘） ─── */

let composerDraftTimer: ReturnType<typeof setTimeout> | null = null;

/** 延迟落盘输入框草稿：打字期间合并写，避免每个按键都刷 localStorage。 */
function scheduleComposerDraftPersist() {
  if (composerDraftTimer) clearTimeout(composerDraftTimer);
  composerDraftTimer = setTimeout(() => {
    composerDraftTimer = null;
    try {
      saveComposerDrafts(appStore.getState().composerDrafts);
    } catch {
      /* ignore */
    }
  }, 250);
}

/** 暂存某工单输入框的草稿文本（空白/空串即清除）。Composer 每次输入都调用。 */
export function setComposerDraft(ticketNo: string, text: string) {
  const value = text.trim() === "" ? "" : text;
  const drafts = { ...s().composerDrafts };
  if ((drafts[ticketNo] ?? "") === value) return;
  if (value === "") delete drafts[ticketNo];
  else drafts[ticketNo] = value;
  set({ composerDrafts: drafts });
  scheduleComposerDraftPersist();
}

/** 清除某工单的输入框草稿（发送成功/工单进入终态后调用）。 */
export function clearComposerDraft(ticketNo: string) {
  if (!s().composerDrafts[ticketNo]) return;
  const drafts = { ...s().composerDrafts };
  delete drafts[ticketNo];
  set({ composerDrafts: drafts });
  scheduleComposerDraftPersist();
}

/** 草稿发送期间的创建遮罩标记（SessionList 显示「正在创建会话…」）。 */
export function setCreatingSession(no: string, busy: boolean) {
  set((st) => {
    const next = { ...st.creatingSession };
    if (busy) next[no] = true;
    else delete next[no];
    return { creatingSession: next };
  });
}

export function setBusy(no: string, busy: boolean) {
  set((st) => {
    // 运行中重复打点（重连/重复请求）不重置起始时间：仅空闲→运行转变时记录，
    // 否则监控面板的运行时长会被误归零。
    if (busy && st.busy[no]) return st;
    const busySince = { ...st.busySince };
    if (busy) busySince[no] = st.busySince[no] ?? Date.now();
    else delete busySince[no];
    return { busy: { ...st.busy, [no]: busy }, busySince };
  });
}

export function setSessionBusy(sessionId: string, busy: boolean) {
  set((st) => ({ sessionBusy: { ...st.sessionBusy, [sessionId]: busy } }));
}

/** 工单级 busy = 该工单任一会话仍在生成；仅用于整卡样式等聚合展示，按钮状态走 sessionBusy。 */
export function refreshTicketBusy(no: string) {
  set((st) => {
    const any = (st.sessions[no] ?? []).some((sess) => st.sessionBusy[sess.id] === true);
    const busy = { ...st.busy };
    if (any) busy[no] = true;
    else delete busy[no];
    return { busy };
  });
}

/* ─── T-120 增强：工单列表的会话结束提醒与待决询问/权限徽标 ─── */

/**
 * 记录一次会话回合结束（done=正常完成，failed=出错/中止）供工单列表提醒。
 * 用户当前停留在该工单的工作台时视为"已看见"回合结束，不叠加列表提醒。
 */
export function markSessionEnded(no: string, kind: "done" | "failed", sessionId?: string) {
  // 回合结束是语义写入点：无论用户是否在查看（reminder 可能跳过），事件都 emit。
  emitPluginEvent("session.ended", { ticketNo: no, sessionId: sessionId ?? null, kind });
  set((st) => {
    if (st.selectedNo === no && st.view === "workbench") return st;
    return { sessionEnded: { ...st.sessionEnded, [no]: { kind, at: Date.now() } } };
  });
}

/** 清除会话结束提醒（打开工单或该工单再次运行时调用）。 */
export function clearSessionEnded(no: string) {
  set((st) => {
    if (!st.sessionEnded[no]) return st;
    const sessionEnded = { ...st.sessionEnded };
    delete sessionEnded[no];
    return { sessionEnded };
  });
}

/** 登记一条待决权限（按 permissionId 幂等，重复 asked 事件不叠加）。 */
export function notePendingPermission(permissionId: string, ticketNo: string, sessionId: string) {
  set((st) =>
    st.pendingPermissions[permissionId]
      ? st
      : { pendingPermissions: { ...st.pendingPermissions, [permissionId]: { ticketNo, sessionId } } },
  );
}

/** 登记一条待决提问（按 requestId 幂等）。 */
export function notePendingQuestion(requestId: string, ticketNo: string, sessionId: string) {
  set((st) =>
    st.pendingQuestions[requestId]
      ? st
      : { pendingQuestions: { ...st.pendingQuestions, [requestId]: { ticketNo, sessionId } } },
  );
}

/** 会话被删除时注销其名下全部待决登记（请求随会话消亡，不再可答）。 */
export function dropSessionPendings(sessionId: string) {
  set((st) => {
    const pendingPermissions = { ...st.pendingPermissions };
    const pendingQuestions = { ...st.pendingQuestions };
    let touched = false;
    for (const [id, ref] of Object.entries(pendingPermissions)) {
      if (ref.sessionId === sessionId) {
        delete pendingPermissions[id];
        touched = true;
      }
    }
    for (const [id, ref] of Object.entries(pendingQuestions)) {
      if (ref.sessionId === sessionId) {
        delete pendingQuestions[id];
        touched = true;
      }
    }
    return touched ? { pendingPermissions, pendingQuestions } : st;
  });
}

/* ─── 会话分组管理 ─── */

/** 创建会话分组 */
export function createSessionGroup(ticketNo: string, groupId: string, groupName: string, color: string) {
  // 更新该工单下所有会话的分组信息
  set((st) => ({
    sessions: {
      ...st.sessions,
      [ticketNo]: (st.sessions[ticketNo] ?? []).map(session => ({
        ...session,
        groupId,
        groupName
      })),
    }
  }));
}

/** 将会话移入分组 */
export function moveSessionToGroup(ticketNo: string, sessionId: string, groupId: string, groupName: string) {
  set((st) => ({
    sessions: {
      ...st.sessions,
      [ticketNo]: (st.sessions[ticketNo] ?? []).map(session =>
        session.id === sessionId
          ? { ...session, groupId, groupName }
          : session
      ),
    }
  }));
}

/** 删除会话分组（将会话从分组中移出） */
export function removeSessionGroup(ticketNo: string) {
  set((st) => ({
    sessions: {
      ...st.sessions,
      [ticketNo]: (st.sessions[ticketNo] ?? []).map(session => ({
        ...session,
        groupId: null,
        groupName: null
      })),
    }
  }));
}

/** 更新会话分组信息 */
export function updateSessionGroup(ticketNo: string, sessionId: string, groupId: string | null, groupName: string | null) {
  set((st) => ({
    sessions: {
      ...st.sessions,
      [ticketNo]: (st.sessions[ticketNo] ?? []).map(session =>
        session.id === sessionId
          ? { ...session, groupId, groupName }
          : session
      ),
    }
  }));
}

/** 重命名会话标题 */
export function renameSession(ticketNo: string, sessionId: string, newTitle: string) {
  set((st) => ({
    sessions: {
      ...st.sessions,
      [ticketNo]: (st.sessions[ticketNo] ?? []).map(session =>
        session.id === sessionId
          ? { ...session, title: newTitle }
          : session
      ),
    }
  }));
}

/** 将会话移动到列表顶部（在其状态分类中） */
export function moveSessionToTop(ticketNo: string, sessionId: string) {
  set((st) => {
    const sessions = st.sessions[ticketNo] ?? [];
    // 找到会话索引
    const index = sessions.findIndex(s => s.id === sessionId);
    if (index === -1) return st;
    
    const session = sessions[index];
    // 移除会话
    const newSessions = [...sessions.slice(0, index), ...sessions.slice(index + 1)];
    // 根据状态确定插入位置（活跃会话在前，归档在后）
    const activeCount = newSessions.filter(s => s.status === "active").length;
    const insertPos = session.status === "active" ? 0 : activeCount;
    // 插入到指定位置
    const finalSessions = [...newSessions.slice(0, insertPos), session, ...newSessions.slice(insertPos)];
    
    return {
      ...st,
      sessions: {
        ...st.sessions,
        [ticketNo]: finalSessions
      }
    };
  });
}

/* ─── 用量 / 任务清单 / 上下文占用（按工单键暂存，会话切换时重建） ─── */

export function addUsage(no: string, promptTokens: number, completionTokens: number) {
  set((st) => {
    const cur = st.usage[no] ?? { promptTokens: 0, completionTokens: 0 };
    return {
      usage: {
        ...st.usage,
        [no]: {
          promptTokens: cur.promptTokens + promptTokens,
          completionTokens: cur.completionTokens + completionTokens,
        },
      },
    };
  });
}

/** 覆写工单任务清单（todowrite 每次调用都是全量数组）。 */
export function setTodos(no: string, todos: TodoItem[]) {
  set((st) => ({ todos: { ...st.todos, [no]: todos } }));
}

/** 回写最新一轮的上下文窗口占用（prompt+completion，非逐轮累加）。 */
export function setContextTokens(no: string, tokens: number) {
  set((st) => {
    const cur = st.context[no] ?? { tokens: 0, limit: null };
    return { context: { ...st.context, [no]: { ...cur, tokens: Math.max(0, tokens) } } };
  });
}

/** 记录模型上下文窗口上限（来自模型目录；null 表示未知）。 */
export function setContextLimit(no: string, limit: number | null) {
  set((st) => {
    const cur = st.context[no] ?? { tokens: 0, limit: null };
    return { context: { ...st.context, [no]: { ...cur, limit } } };
  });
}

/* ─── 会话生命周期（demo 模式本地版；live 走后端 + loadTicketSessions 回填） ─── */

export function createSession(ticketNo: string) {
  const id = uid("sess");
  const session: ChatSession = {
    id,
    ticketNo,
    title: `会话 ${new Date().toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" })}`,
    status: "active",
    permissionAutoAccept: false,
    // 会话创建时的协作 Agent 固化（demo 路径同 live）：会话 item 徽标与回复标注的数据源。
    agentConfigId: s().agentId,
    createdAt: Date.now(),
    updatedAt: Date.now(),
  };
  set((st) => ({
    sessions: {
      ...st.sessions,
      [ticketNo]: [...(st.sessions[ticketNo] ?? []), session],
    },
    activeSessionId: {
      ...st.activeSessionId,
      [ticketNo]: id,
    },
  }));
  // 会话创建语义写入点（demo 路径）；live 路径在 stream.ts 建会话成功后单独 emit。
  emitPluginEvent("session.created", {
    ticketNo,
    sessionId: id,
    agentConfigId: session.agentConfigId ?? null,
  });
  return id;
}

/** 创建会话分组 */
export function createSessionGroup(ticketNo: string, groupId: string, groupName: string, color: string) {
  // 更新该工单下所有会话的分组信息
  set((st) => ({
    sessions: {
      ...st.sessions,
      [ticketNo]: (st.sessions[ticketNo] ?? []).map(session => ({
        ...session,
        groupId,
        groupName
      })),
    }
  }));
}

/** 将会话移入分组 */
export function moveSessionToGroup(ticketNo: string, sessionId: string, groupId: string, groupName: string) {
  set((st) => ({
    sessions: {
      ...st.sessions,
      [ticketNo]: (st.sessions[ticketNo] ?? []).map(session =>
        session.id === sessionId
          ? { ...session, groupId, groupName }
          : session
      ),
    }
  }));
}

/** 删除会话分组（将会话从分组中移出） */
export function removeSessionGroup(ticketNo: string) {
  set((st) => ({
    sessions: {
      ...st.sessions,
      [ticketNo]: (st.sessions[ticketNo] ?? []).map(session => ({
        ...session,
        groupId: null,
        groupName: null
      })),
    }
  }));
}

/** 更新会话分组信息 */
export function updateSessionGroup(ticketNo: string, sessionId: string, groupId: string | null, groupName: string | null) {
  set((st) => ({
    sessions: {
      ...st.sessions,
      [ticketNo]: (st.sessions[ticketNo] ?? []).map(session =>
        session.id === sessionId
          ? { ...session, groupId, groupName }
          : session
      ),
    }
  }));
}

/**
 * 「新建会话」进入空白草稿态：活跃指针置空（Composer 的 Agent 选择随之解锁），
 * 聊天区替换为一条草稿提示；真正的会话在发出首条消息时才创建并固化 agent。
 * 草稿不占会话列表、不持久任何输入——切走再切回即丢弃。
 * 任务清单/上下文占用随草稿清空：二者按工单键暂存，残留会以旧会话的进度冒充草稿。
 */
export function startSessionDraft(ticketNo: string) {
  set((st) => {
    const draftModelSel = { ...st.draftModelSel };
    delete draftModelSel[ticketNo];
    return {
      activeSessionId: { ...st.activeSessionId, [ticketNo]: "" },
      todos: { ...st.todos, [ticketNo]: [] },
      context: { ...st.context, [ticketNo]: { tokens: 0, limit: st.context[ticketNo]?.limit ?? null } },
      draftModelSel,
      chats: {
        ...st.chats,
        [ticketNo]: [
          {
            kind: "system" as const,
            id: uid("sys"),
            tone: "info" as const,
            ts: Date.now(),
            text: "新会话草稿 · 在下方选择协作 Agent，发送首条消息后创建会话",
          },
        ],
      },
    };
  });
}

/**
 * 确保消息发到「当前查看的会话」（与 live 的 liveSendPrompt 同语义）：
 * 草稿态（活跃指针为空，或指针指向的会话已不存在）时新建会话并固化
 * 草稿里所选的 agent；否则沿用当前会话，不劫持其它活跃会话。
 */
export function ensureCurrentSession(ticketNo: string): string {
  const cur = s().activeSessionId[ticketNo] ?? "";
  if (cur && (s().sessions[ticketNo] ?? []).some((sess) => sess.id === cur)) {
    return cur;
  }
  return createSession(ticketNo);
}

export function archiveSession(ticketNo: string, sessionId: string) {
  set((st) => ({
    sessions: {
      ...st.sessions,
      [ticketNo]: (st.sessions[ticketNo] ?? []).map((sess) =>
        sess.id === sessionId ? { ...sess, status: "archived" as const, updatedAt: Date.now() } : sess,
      ),
    },
  }));
}

export function restoreSession(ticketNo: string, sessionId: string) {
  set((st) => ({
    sessions: {
      ...st.sessions,
      [ticketNo]: (st.sessions[ticketNo] ?? []).map((sess) =>
        sess.id === sessionId ? { ...sess, status: "active" as const, updatedAt: Date.now() } : sess,
      ),
    },
  }));
}

export function switchSession(ticketNo: string, sessionId: string) {
  set((st) => ({
    activeSessionId: {
      ...st.activeSessionId,
      [ticketNo]: sessionId,
    },
  }));
}

export function deleteSession(ticketNo: string, sessionId: string) {
  set((st) => {
    const sessions = (st.sessions[ticketNo] ?? []).filter((sess) => sess.id !== sessionId);
    const activeId = st.activeSessionId[ticketNo];
    const newActiveId = activeId === sessionId ? (sessions.find((x) => x.status === "active")?.id ?? "") : activeId;
    return {
      sessions: { ...st.sessions, [ticketNo]: sessions },
      activeSessionId: { ...st.activeSessionId, [ticketNo]: newActiveId },
    };
  });
}
