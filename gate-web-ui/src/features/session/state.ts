/**
 * 会话域状态（session）：会话/草稿/生成中标记/待决登记，以及按工单键暂存的
 * 用量、任务清单与上下文占用。聊天条目与回合见 chat.ts。
 */
import { getLocale, t as i18nT } from "@/i18n";
import { appStore } from "@/store";
import { saveComposerDrafts, savePendingQuotes, saveSessionGroups, saveSessionPinned } from "@/store/prefs";
import type { CatalogProvider, ChatSession, QuoteChip, SessionModelSel } from "@/shared/types";
import { QUOTE_MAX_CHARS } from "@/shared/quotes";
import { uid } from "@/shared/format";
import { emitPluginEvent } from "@/app/plugins/events";
import { dropSessionTodos } from "./todos";
import { dropSessionTasks } from "./tasks";

const set = appStore.setState;
const s = () => appStore.getState();

/* ─── 会话内实时切换模型 / 推理强度（per-session 选择） ─── */

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

/* ─── 引用片段胶囊（划选页面文字 → 添加到对话框；按工单键暂存 + localStorage 落盘） ─── */

let pendingQuotesTimer: ReturnType<typeof setTimeout> | null = null;

/** 延迟落盘引用胶囊：增删合并写，避免连点时反复刷 localStorage。 */
function schedulePendingQuotesPersist() {
  if (pendingQuotesTimer) clearTimeout(pendingQuotesTimer);
  pendingQuotesTimer = setTimeout(() => {
    pendingQuotesTimer = null;
    try {
      savePendingQuotes(appStore.getState().pendingQuotes);
    } catch {
      /* ignore */
    }
  }, 250);
}

function writePendingQuotes(ticketNo: string, chips: QuoteChip[]) {
  const pendingQuotes = { ...s().pendingQuotes };
  if (chips.length === 0) delete pendingQuotes[ticketNo];
  else pendingQuotes[ticketNo] = chips;
  set({ pendingQuotes });
  schedulePendingQuotesPersist();
}

/** 整表覆盖某工单的引用胶囊（发送失败回滚用）。 */
export function setPendingQuotes(ticketNo: string, chips: QuoteChip[]) {
  writePendingQuotes(ticketNo, chips);
}

/** 单条胶囊的正文上限截断（addPendingQuote / updatePendingQuoteText 共用）。 */
function clipQuoteBody(trimmed: string) {
  return trimmed.length > QUOTE_MAX_CHARS
    ? i18nT("sess.quoteTruncated", { text: trimmed.slice(0, QUOTE_MAX_CHARS) })
    : trimmed;
}

/** 追加一条引用胶囊：超长截断并在原文里注明，避免极端大段选择撑爆消息。 */
export function addPendingQuote(ticketNo: string, text: string, source?: string) {
  const trimmed = text.trim();
  if (!trimmed) return;
  const chip: QuoteChip = source
    ? { id: uid("q"), text: clipQuoteBody(trimmed), source }
    : { id: uid("q"), text: clipQuoteBody(trimmed) };
  writePendingQuotes(ticketNo, [...(s().pendingQuotes[ticketNo] ?? []), chip]);
}

/**
 * 编辑某条引用胶囊的原文（外部粘贴胶囊的「编辑原文」出口）：回写后仍按上限截断；
 * 清空保存视为删除该胶囊（空正文没有发送意义）。
 */
export function updatePendingQuoteText(ticketNo: string, quoteId: string, text: string) {
  const chips = s().pendingQuotes[ticketNo];
  if (!chips?.some((c) => c.id === quoteId)) return;
  const trimmed = text.trim();
  if (!trimmed) {
    writePendingQuotes(ticketNo, chips.filter((c) => c.id !== quoteId));
    return;
  }
  const body = clipQuoteBody(trimmed);
  writePendingQuotes(
    ticketNo,
    chips.map((c) => (c.id === quoteId ? { ...c, text: body } : c)),
  );
}

/** 移除某条引用胶囊（胶囊上的 × 按钮）。 */
export function removePendingQuote(ticketNo: string, quoteId: string) {
  const chips = s().pendingQuotes[ticketNo];
  if (!chips?.some((c) => c.id === quoteId)) return;
  writePendingQuotes(
    ticketNo,
    chips.filter((c) => c.id !== quoteId),
  );
}

/** 清空某工单的引用胶囊（发送成功/工单进入终态后调用）。 */
export function clearPendingQuotes(ticketNo: string) {
  if (!s().pendingQuotes[ticketNo]) return;
  writePendingQuotes(ticketNo, []);
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
  // 会话再次进入运行态时熄灭中断红点（T-105 第 6 轮）：红点 → 蓝色呼吸点自然接管
  if (busy) clearSessionInterrupted(sessionId);
  set((st) => {
    const sessionBusySince = { ...st.sessionBusySince };
    if (busy) {
      sessionBusySince[sessionId] = st.sessionBusySince[sessionId] ?? Date.now();
    } else {
      delete sessionBusySince[sessionId];
    }
    return {
      sessionBusy: { ...st.sessionBusy, [sessionId]: busy },
      sessionBusySince,
    };
  });
}

/* ─── T-105 第 6 轮：会话级中断标记（会话列表前置红点的数据源） ───
 * 与工单级 sessionEnded 提醒（用户正在查看则跳过）不同，中断是会话的属性：
 * 回合出错/中止即点亮该会话的前置红点，无论用户当时在看哪里；该会话再次
 * 进入运行态时熄灭（红点 → 蓝色呼吸点，自然接管状态表达）。 */

/** 标记会话中断（回合出错/用户中止时调用；重复标记只刷新时间戳）。 */
export function markSessionInterrupted(sessionId: string) {
  set((st) => ({ sessionInterrupted: { ...st.sessionInterrupted, [sessionId]: Date.now() } }));
}

/** 清除会话中断标记（该会话再次开始运行时调用）。 */
export function clearSessionInterrupted(sessionId: string) {
  set((st) => {
    if (!st.sessionInterrupted[sessionId]) return st;
    const sessionInterrupted = { ...st.sessionInterrupted };
    delete sessionInterrupted[sessionId];
    return { sessionInterrupted };
  });
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
 * 携带 sessionId 且 kind=failed 时同步打会话级中断标记（T-105 第 6 轮）：
 * 该会话的前置小圆点转为红色（静态），不因用户正在查看而被跳过。
 */
export function markSessionEnded(no: string, kind: "done" | "failed", sessionId?: string) {
  // 回合结束是语义写入点：无论用户是否在查看（reminder 可能跳过），事件都 emit。
  emitPluginEvent("session.ended", { ticketNo: no, sessionId: sessionId ?? null, kind });
  if (kind === "failed" && sessionId) markSessionInterrupted(sessionId);
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

/* ─── T-110：待回答/待授权提醒的「已处理」忽略表 ───
 * 用户从运行监控点击某会话的待回答/待授权条目并跳转处理：该会话名下的待决登记
 * 立即清除（chip 黄组/工单徽标/会话黄点同步消退），其 id 同时记入忽略表——
 * live 后端在打开工单与慢节拍会重拉仍未决的权限/提问，note 层看到被忽略的 id
 * 便不再重新点亮提醒。作答（resolve）时移除忽略、agent 发起新 id 的待决时自然
 * 恢复提醒。内存级集合（同页面会话），生命周期与 answered 墓碑一致：刷新页面
 * 后仍挂在服务端的未决项会随重拉再次恢复提醒。 */

const dismissedAskIds = new Set<string>();

/** 作答/撤销作答后移除忽略标记，允许同 id 的待决重新点亮（幂等）。 */
export function undismissAsk(id: string) {
  dismissedAskIds.delete(id);
}

/** 用户点击运行监控的待回答/待授权条目跳转处理：清除该会话名下全部待决登记并记入忽略表。 */
export function dismissSessionAsks(sessionId: string) {
  set((st) => {
    const pendingPermissions = { ...st.pendingPermissions };
    const pendingQuestions = { ...st.pendingQuestions };
    let touched = false;
    for (const [id, ref] of Object.entries(pendingPermissions)) {
      if (ref.sessionId === sessionId) {
        dismissedAskIds.add(id);
        delete pendingPermissions[id];
        touched = true;
      }
    }
    for (const [id, ref] of Object.entries(pendingQuestions)) {
      if (ref.sessionId === sessionId) {
        dismissedAskIds.add(id);
        delete pendingQuestions[id];
        touched = true;
      }
    }
    return touched ? { pendingPermissions, pendingQuestions } : st;
  });
}

/** 登记一条待决权限（按 permissionId 幂等，重复 asked 事件不叠加；被忽略的 id 不再点亮）。 */
export function notePendingPermission(permissionId: string, ticketNo: string, sessionId: string) {
  if (dismissedAskIds.has(permissionId)) return;
  set((st) =>
    st.pendingPermissions[permissionId]
      ? st
      : { pendingPermissions: { ...st.pendingPermissions, [permissionId]: { ticketNo, sessionId } } },
  );
}

/** 登记一条待决提问（按 requestId 幂等；被忽略的 id 不再点亮）。 */
export function notePendingQuestion(requestId: string, ticketNo: string, sessionId: string) {
  if (dismissedAskIds.has(requestId)) return;
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

/* ─── 会话分组与置顶（T-105） ───
 * 分组是端侧软数据（live 后端暂无分组 API）：分组表 sessionGroups、会话归属表
 * sessionGroupMembers（sessionId → groupId）都独立于会话对象存放，列表刷新覆盖时
 * 归属不丢；置顶是有序 sessionId 数组，渲染时置顶段优先。三者持久化到 localStorage
 * （见 store/prefs.ts），写操作经 250ms 防抖合并落盘，避免连点反复刷存储。 */

/** 分组名称长度上限（新建/编辑分组对话框用）。 */
export const GROUP_NAME_MAX = 24;

export const GROUP_COLOR_PALETTE = [
  "#35d99e", // accent 绿
  "#67e8f9", // 青
  "#7cc7f7", // 信息蓝
  "#b4a3f7", // 紫
  "#f5b84f", // 琥珀
  "#fb7185", // 玫红
  "#f472b6", // 粉
  "#a3e635", // 草绿
] as const;

let sessionGroupsPersistTimer: ReturnType<typeof setTimeout> | null = null;
let sessionPinnedPersistTimer: ReturnType<typeof setTimeout> | null = null;

function scheduleSessionGroupsPersist() {
  if (sessionGroupsPersistTimer) clearTimeout(sessionGroupsPersistTimer);
  sessionGroupsPersistTimer = setTimeout(() => {
    sessionGroupsPersistTimer = null;
    const st = appStore.getState();
    saveSessionGroups({ groups: st.sessionGroups, members: st.sessionGroupMembers });
  }, 250);
}

function scheduleSessionPinnedPersist() {
  if (sessionPinnedPersistTimer) clearTimeout(sessionPinnedPersistTimer);
  sessionPinnedPersistTimer = setTimeout(() => {
    sessionPinnedPersistTimer = null;
    saveSessionPinned(appStore.getState().sessionPinned);
  }, 250);
}

/** 创建分组（返回新分组 id），名称必填、颜色取调色板。 */
export function createSessionGroup(ticketNo: string, name: string, color: string): string {
  const id = uid("sgrp");
  set((st) => ({
    sessionGroups: {
      ...st.sessionGroups,
      [ticketNo]: [...(st.sessionGroups[ticketNo] ?? []), { id, name, color }],
    },
  }));
  scheduleSessionGroupsPersist();
  return id;
}

/** 更新分组（改名/换色）；分组不存在时是 no-op。 */
export function updateSessionGroup(
  ticketNo: string,
  groupId: string,
  patch: { name?: string; color?: string },
) {
  set((st) => {
    const current = st.sessionGroups[ticketNo] ?? [];
    if (!current.some((g) => g.id === groupId)) return st;
    const next = current.map((g) =>
      g.id === groupId
        ? { ...g, ...(patch.name !== undefined ? { name: patch.name } : {}), ...(patch.color ? { color: patch.color } : {}) }
        : g,
    );
    return { sessionGroups: { ...st.sessionGroups, [ticketNo]: next } };
  });
  scheduleSessionGroupsPersist();
}

/** 删除分组：其归属的会话回到未分组（归属映射按 groupId 全局清除，无跨工单串扰）。 */
export function deleteSessionGroup(ticketNo: string, groupId: string) {
  set((st) => {
    const current = st.sessionGroups[ticketNo] ?? [];
    if (!current.some((g) => g.id === groupId)) return st;
    const sessionGroups = { ...st.sessionGroups };
    if (current.every((g) => g.id === groupId)) delete sessionGroups[ticketNo];
    else sessionGroups[ticketNo] = current.filter((g) => g.id !== groupId);
    const sessionGroupMembers = { ...st.sessionGroupMembers };
    let touched = false;
    for (const [sid, gid] of Object.entries(sessionGroupMembers)) {
      if (gid === groupId) {
        delete sessionGroupMembers[sid];
        touched = true;
      }
    }
    return touched ? { sessionGroups, sessionGroupMembers } : { sessionGroups };
  });
  scheduleSessionGroupsPersist();
}

/** 会话移入分组（groupId 传 null = 移出分组）；目标组不存在时 no-op。 */
export function moveSessionToGroup(ticketNo: string, sessionId: string, groupId: string | null) {
  set((st) => {
    if (groupId !== null && groupId !== "ungrouped"
      && !(st.sessionGroups[ticketNo] ?? []).some((g) => g.id === groupId)) {
      return st;
    }
    const sessionGroupMembers = { ...st.sessionGroupMembers };
    if (groupId === null || groupId === "ungrouped") delete sessionGroupMembers[sessionId];
    else sessionGroupMembers[sessionId] = groupId;
    return { sessionGroupMembers };
  });
  scheduleSessionGroupsPersist();
}

/** 置顶/取消置顶会话（在其状态与分段内置顶；置顶段优先展示）。 */
export function setSessionPinned(ticketNo: string, sessionId: string, pinned: boolean) {
  set((st) => {
    const current = st.sessionPinned[ticketNo] ?? [];
    const has = current.includes(sessionId);
    if (pinned === has) return st;
    const sessionPinned = { ...st.sessionPinned };
    sessionPinned[ticketNo] = pinned ? [sessionId, ...current] : current.filter((x) => x !== sessionId);
    return { sessionPinned };
  });
  scheduleSessionPinnedPersist();
}

/** 覆写某工单的置顶会话序列（渲染层分区编辑后的规范化写回；未知 id 原样保留）。 */
export function setSessionPinnedOrder(ticketNo: string, orderedIds: string[]) {
  set((st) => {
    const current = st.sessionPinned[ticketNo] ?? [];
    if (current.length === orderedIds.length && current.every((id, i) => orderedIds[i] === id)) return st;
    return { sessionPinned: { ...st.sessionPinned, [ticketNo]: [...orderedIds] } };
  });
  scheduleSessionPinnedPersist();
}

/** 重命名会话标题 */
export function renameSession(ticketNo: string, sessionId: string, newTitle: string) {
  const title = newTitle.trim();
  if (!title) return;
  set((st) => ({
    sessions: {
      ...st.sessions,
      [ticketNo]: (st.sessions[ticketNo] ?? []).map((session) =>
        session.id === sessionId ? { ...session, title, updatedAt: Date.now() } : session,
      ),
    },
  }));
}

/* ─── 用量 / 上下文占用（按工单键暂存，会话切换时重建；任务清单已独立到 todos.ts） ─── */

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
    title: i18nT("sess.defaultTitle", { time: new Date().toLocaleTimeString(getLocale(), { hour: "2-digit", minute: "2-digit" }) }),
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
  // 草稿归属分组落地：分组头 + 进入的草稿，创建即归组并清暂存。
  applyDraftGroup(ticketNo, id);
  // 会话创建语义写入点（demo 路径）；live 路径在 stream.ts 建会话成功后单独 emit。
  emitPluginEvent("session.created", {
    ticketNo,
    sessionId: id,
    agentConfigId: session.agentConfigId ?? null,
  });
  return id;
}


/**
 * 「新建会话」进入空白草稿态：活跃指针置空（Composer 的 Agent 选择随之解锁），
 * 聊天区替换为一条草稿提示；真正的会话在发出首条消息时才创建并固化 agent。
 * 草稿不占会话列表、不持久任何输入——切走再切回即丢弃。
 * 任务清单/上下文占用随草稿清空：清单投影按会话 id 键控（V21），草稿态本就没有
 * 键位可命中，这里清上下文占用与活跃指针即可；清单残留由会话切换的附带查询覆盖。
 * 可选 groupId：分组头 + 进入的草稿——归属分组暂存到 draftGroupId，首条消息
 * 建会话时自动归入（applyDraftGroup）；不带 groupId 的普通新建清除该暂存。
 */
export function startSessionDraft(ticketNo: string, groupId?: string) {
  set((st) => {
    const draftModelSel = { ...st.draftModelSel };
    delete draftModelSel[ticketNo];
    const draftGroupId = { ...st.draftGroupId };
    const group = groupId ? (st.sessionGroups[ticketNo] ?? []).find((g) => g.id === groupId) : undefined;
    if (group) draftGroupId[ticketNo] = group.id;
    else delete draftGroupId[ticketNo];
    return {
      activeSessionId: { ...st.activeSessionId, [ticketNo]: "" },
      context: { ...st.context, [ticketNo]: { tokens: 0, limit: st.context[ticketNo]?.limit ?? null } },
      draftModelSel,
      draftGroupId,
      chats: {
        ...st.chats,
        [ticketNo]: [
          {
            kind: "system" as const,
            id: uid("sys"),
            tone: "info" as const,
            ts: Date.now(),
            text: group
              ? i18nT("sess.draftInGroup", { name: group.name })
              : i18nT("sess.draftHint"),
          },
        ],
      },
    };
  });
}

/**
 * 草稿归属分组落地：会话创建成功后归入草稿时所选分组并清除暂存（demo/live 建会话
 * 路径共用）。分组在草稿期间被删则仅清暂存、不归组（moveSessionToGroup 同样兜底）。
 */
export function applyDraftGroup(ticketNo: string, sessionId: string) {
  const groupId = s().draftGroupId[ticketNo];
  if (!groupId) return;
  set((st) => {
    if (!st.draftGroupId[ticketNo]) return st;
    const draftGroupId = { ...st.draftGroupId };
    delete draftGroupId[ticketNo];
    return { draftGroupId };
  });
  if ((s().sessionGroups[ticketNo] ?? []).some((g) => g.id === groupId)) {
    moveSessionToGroup(ticketNo, sessionId, groupId);
  }
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
  set((st) => {
    // 草稿随切换丢弃：草稿归属分组暂存一并清除，防残留误归组
    const draftGroupId = { ...st.draftGroupId };
    delete draftGroupId[ticketNo];
    return {
      activeSessionId: {
        ...st.activeSessionId,
        [ticketNo]: sessionId,
      },
      draftGroupId,
    };
  });
}

/**
 * 会话消亡后的端侧抹除（demo 删除、live 删除、列表收敛三条路径共用）：
 * 从所有工单的置顶表剔除该会话，并删除分组归属映射——归属是一种「按 sessionId
 * 键控的软数据」，会话删除后若不收敛，残留会让后端重建/重放的同 id 新会话被
 * 静默归入旧分组，也会顺着分组持久化不断写进 localStorage。空入参是 no-op。
 * 任务清单投影（todos.ts）同属按 sessionId 键控的会话软数据，一并抹除。
 */
export function dropSessionExtras(sessionIds: string[]) {
  if (sessionIds.length === 0) return;
  const kill = new Set(sessionIds);
  set((st) => {
    const sessionPinned = { ...st.sessionPinned };
    let pinnedTouched = false;
    for (const [no, ids] of Object.entries(sessionPinned)) {
      const next = ids.filter((x) => !kill.has(x));
      if (next.length !== ids.length) {
        if (next.length > 0) sessionPinned[no] = next;
        else delete sessionPinned[no];
        pinnedTouched = true;
      }
    }
    const sessionGroupMembers = { ...st.sessionGroupMembers };
    let memberTouched = false;
    for (const sid of kill) {
      if (sid in sessionGroupMembers) {
        delete sessionGroupMembers[sid];
        memberTouched = true;
      }
    }
    const sessionInterrupted = { ...st.sessionInterrupted };
    let interruptedTouched = false;
    for (const sid of kill) {
      if (sid in sessionInterrupted) {
        delete sessionInterrupted[sid];
        interruptedTouched = true;
      }
    }
    return memberTouched || pinnedTouched || interruptedTouched
      ? { sessionPinned, sessionGroupMembers, sessionInterrupted }
      : st;
  });
  dropSessionTodos(sessionIds);
  dropSessionTasks(sessionIds);
  scheduleSessionGroupsPersist();
  scheduleSessionPinnedPersist();
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
  // 置顶与分组归属随会话消亡一并抹除（归属残留会让重建/重放的同 id 会话被静默归入旧分组）
  dropSessionExtras([sessionId]);
}
