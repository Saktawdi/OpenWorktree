/**
 * 会话域聊天状态（session）：聊天条目的推送/修补、生成中回合（liveTurns）的
 * 保真与镜像，以及权限/提问卡片的入队与结论。
 */
import { appStore } from "@/store";
import type { ChatItem, PermissionRequestView, QuestionRequestView } from "@/shared/types";
import { uid } from "@/shared/format";
import { notePendingPermission, notePendingQuestion } from "./state";

const set = appStore.setState;
const s = () => appStore.getState();

export function pushChatItem(no: string, item: ChatItem) {
  set((st) => ({
    chats: { ...st.chats, [no]: [...(st.chats[no] ?? []), item] },
  }));
}

export function pushUserMessage(no: string, text: string): ChatItem {
  const item: ChatItem = { kind: "user", id: uid("u"), text, ts: Date.now() };
  pushChatItem(no, item);
  return item;
}

export function pushSystemMessage(
  no: string,
  text: string,
  tone: "info" | "success" | "warn" = "info",
) {
  pushChatItem(no, { kind: "system", id: uid("sys"), tone, text, ts: Date.now() });
}

/** 按 id 移除一条聊天条目（草稿建会话失败时回滚用户消息用）。 */
export function removeChatItem(no: string, id: string) {
  set((st) => ({
    chats: { ...st.chats, [no]: (st.chats[no] ?? []).filter((m) => m.id !== id) },
  }));
}

export function pushAssistantPlaceholder(no: string): string {
  const id = uid("a");
  pushChatItem(no, {
    kind: "assistant",
    id,
    text: "",
    streaming: true,
    tools: [],
    ts: Date.now(),
  });
  return id;
}

export function patchAssistant(
  no: string,
  id: string,
  fn: (a: Extract<ChatItem, { kind: "assistant" }>) => Extract<ChatItem, { kind: "assistant" }>,
) {
  set((st) => ({
    chats: {
      ...st.chats,
      [no]: (st.chats[no] ?? []).map((m) =>
        m.kind === "assistant" && m.id === id ? fn(m) : m,
      ),
    },
  }));
}

/* ─── 权限卡片（opencode permission_asked） ─── */

/** 已应答权限 id 的墓碑：应答即移除卡片（openchamber 语义），墓碑阻止重放的 asked 事件复活卡片。 */
const resolvedPermissions = new Set<string>();

/** 入队一个权限请求卡片；按 permissionId 去重（id 恒为 perm-<permission_id>）。 */
export function pushPermissionRequest(no: string, request: PermissionRequestView) {
  if (resolvedPermissions.has(request.permissionId)) return;
  const id = `perm-${request.permissionId}`;
  set((st) => {
    const list = st.chats[no] ?? [];
    if (list.some((m) => m.kind === "permission" && m.id === id)) return st;
    return {
      chats: {
        ...st.chats,
        [no]: [...list, { kind: "permission" as const, id, request, status: "pending" as const, ts: Date.now() }],
      },
    };
  });
}

/**
 * 权限已有结论（用户点击或服务端自动允许/permission_replied）：移除卡片并记墓碑。
 * 与 openchamber 一致——已应答的询问不再驻留聊天流。
 */
export function resolvePermission(
  no: string,
  permissionId: string,
  _response: "once" | "always" | "reject",
  _auto: boolean,
) {
  resolvedPermissions.add(permissionId);
  const id = `perm-${permissionId}`;
  set((st) => {
    const pendingPermissions = { ...st.pendingPermissions };
    delete pendingPermissions[permissionId];
    return {
      pendingPermissions,
      chats: {
        ...st.chats,
        [no]: (st.chats[no] ?? []).filter((m) => !(m.kind === "permission" && m.id === id)),
      },
    };
  });
}

/** 应答提交失败：清掉墓碑，调用方随后重新 pushPermissionRequest 恢复待决卡片。 */
export function revertPermission(no: string, permissionId: string) {
  resolvedPermissions.delete(permissionId);
  // 待决登记同步恢复（sessionId 取当前查看的会话——应答就发生在该会话视图里）
  notePendingPermission(permissionId, no, s().activeSessionId[no] ?? "");
}

/* ─── 提问卡片（opencode question 工具） ─── */

/** 已作答/已跳过 question id 的墓碑：阻止重放的 asked 事件复活卡片。 */
const resolvedQuestions = new Set<string>();

/** 入队一个 question 请求卡片；按 requestId 去重（id 恒为 ques-<request_id>）。 */
export function pushQuestionRequest(no: string, request: QuestionRequestView) {
  if (resolvedQuestions.has(request.requestId)) return;
  const id = `ques-${request.requestId}`;
  set((st) => {
    const list = st.chats[no] ?? [];
    if (list.some((m) => m.kind === "question" && m.id === id)) return st;
    return {
      chats: {
        ...st.chats,
        [no]: [...list, { kind: "question" as const, id, request, status: "pending" as const, ts: Date.now() }],
      },
    };
  });
}

/** question 已有结论（用户提交/跳过，或上游 replied/rejected）：移除卡片并记墓碑。 */
export function resolveQuestion(no: string, requestId: string, _rejected: boolean) {
  resolvedQuestions.add(requestId);
  const id = `ques-${requestId}`;
  set((st) => {
    const pendingQuestions = { ...st.pendingQuestions };
    delete pendingQuestions[requestId];
    return {
      pendingQuestions,
      chats: {
        ...st.chats,
        [no]: (st.chats[no] ?? []).filter((m) => !(m.kind === "question" && m.id === id)),
      },
    };
  });
}

/** 提交失败：清掉墓碑，调用方随后重新 pushQuestionRequest 恢复待决卡片。 */
export function revertQuestion(no: string, requestId: string) {
  resolvedQuestions.delete(requestId);
  // 待决登记同步恢复（sessionId 取当前查看的会话——作答就发生在该会话视图里）
  notePendingQuestion(requestId, no, s().activeSessionId[no] ?? "");
}

/* ─── 回复标注（底部 footer 的 agent / 推理等级） ─── */

/**
 * 当前会话「本条回复归属」的近似标注来源（openchamber 式底部标注）：
 * - agent:   会话绑定的 AgentConfig.name（缺省回退全局 agentId、再回退首个配置）
 * - variant: session overrideVariant 或 sessionModelSel.variant（推理等级）
 */
function sessionReplyMeta(no: string): { agent: string | null; variant: string | null } {
  const st = s();
  const sessionId = st.activeSessionId[no];
  const sess = (st.sessions[no] ?? []).find((x) => x.id === sessionId);
  const cfgId = sess?.agentConfigId ?? st.agentId;
  const cfg =
    st.agents.find((a) => a.id === cfgId) ?? st.agents.find((a) => a.id === st.agentId) ?? st.agents[0];
  return {
    agent: cfg?.name ?? cfg?.model ?? null,
    variant: (sess?.overrideVariant ?? (sessionId ? st.sessionModelSel[sessionId]?.variant : null)) ?? null,
  };
}

/** 给历史加载/刷新恢复的 assistant 气泡补齐 agent/variant，避免 footer 只剩复制按钮。 */
export function applyReplyMetaDefaults(no: string) {
  const { agent, variant } = sessionReplyMeta(no);
  if (!agent && !variant) return;
  const list = s().chats[no] ?? [];
  if (!list.some((m) => m.kind === "assistant" && (!m.agent || !m.variant))) return;
  set((st) => ({
    chats: {
      ...st.chats,
      [no]: st.chats[no].map((m) =>
        m.kind === "assistant"
          ? { ...m, agent: m.agent ?? agent, variant: m.variant ?? variant }
          : m,
      ),
    },
  }));
}

export function finishAssistant(
  no: string,
  id: string,
  meta?: { agent?: string | null; variant?: string | null },
) {
  const fallback = sessionReplyMeta(no);
  patchAssistant(no, id, (a) => ({
    ...a,
    streaming: false,
    agent: meta?.agent ?? a.agent ?? fallback.agent,
    variant: meta?.variant ?? a.variant ?? fallback.variant,
    thinking: a.thinking ? { ...a.thinking, done: true } : a.thinking,
  }));
}

/* ─── 生成中回合（live）：切换工单/会话后返回时保住未落库的流式内容 ─── */

/** 开始一个流式回合：占位 assistant 消息进当前视图，并以 sessionId 记入 liveTurns。 */
export function startLiveTurn(no: string, sessionId: string) {
  const item: Extract<ChatItem, { kind: "assistant" }> = {
    kind: "assistant",
    id: uid("a"),
    text: "",
    streaming: true,
    tools: [],
    ts: Date.now(),
  };
  set((st) => ({
    liveTurns: { ...st.liveTurns, [sessionId]: { ticketNo: no, itemId: item.id, item } },
    chats: { ...st.chats, [no]: [...(st.chats[no] ?? []), item] },
  }));
}

/**
 * 流式事件更新：liveTurns 是事实来源；再按 itemId 镜像到所属工单的 chats 视图。
 * 视图当前展示的是其他会话（找不到该 itemId）时跳过镜像——切回时
 * loadSessionMessages 会用 liveTurns 重建条目，EventSource 仍在推流，恢复后自动续上。
 */
export function updateLiveTurn(
  sessionId: string,
  fn: (a: Extract<ChatItem, { kind: "assistant" }>) => Extract<ChatItem, { kind: "assistant" }>,
) {
  set((st) => {
    const cur = st.liveTurns[sessionId];
    if (!cur) return st;
    const item = fn(cur.item);
    return {
      liveTurns: { ...st.liveTurns, [sessionId]: { ...cur, item } },
      chats: mirrorIntoChats(st.chats, cur.ticketNo, item),
    };
  });
}

/**
 * 回合结束：定格 streaming/thinking 并移出 liveTurns。后端在发出 done 之前已把整回合
 * 落库（idle → flushTurn → done），此后历史接口必然包含完整回复，无需再靠 stash。
 */
export function finishLiveTurn(sessionId: string) {
  set((st) => {
    const cur = st.liveTurns[sessionId];
    if (!cur) return st;
    const item: Extract<ChatItem, { kind: "assistant" }> = {
      ...cur.item,
      streaming: false,
      thinking: cur.item.thinking ? { ...cur.item.thinking, done: true } : cur.item.thinking,
    };
    const liveTurns = { ...st.liveTurns };
    delete liveTurns[sessionId];
    return { liveTurns, chats: mirrorIntoChats(st.chats, cur.ticketNo, item) };
  });
}

/** 会话被删除时丢弃其生成中状态。 */
export function dropLiveTurn(sessionId: string) {
  set((st) => {
    if (!st.liveTurns[sessionId]) return st;
    const liveTurns = { ...st.liveTurns };
    delete liveTurns[sessionId];
    return { liveTurns };
  });
}

/** 条目存在于该工单视图时原位替换（跨会话视图不含此 itemId，保持不动）。 */
function mirrorIntoChats(
  chats: Record<string, ChatItem[]>,
  ticketNo: string,
  item: Extract<ChatItem, { kind: "assistant" }>,
): Record<string, ChatItem[]> {
  const view = chats[ticketNo];
  if (!view || !view.some((m) => m.id === item.id)) return chats;
  return { ...chats, [ticketNo]: view.map((m) => (m.id === item.id ? item : m)) };
}
