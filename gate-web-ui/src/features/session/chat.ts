/**
 * 会话域聊天状态（session）：聊天条目的推送/修补、生成中回合（liveTurns）的
 * 保真与镜像，以及权限/提问卡片的入队与结论。
 */
import { appStore } from "@/store";
import type { ChatItem, PermissionRequestView, QuestionRequestView } from "@/shared/types";
import { uid } from "@/shared/format";
import { notePendingPermission, notePendingQuestion, undismissAsk } from "./state";
import { splitModelRef } from "./model";

const set = appStore.setState;
const s = () => appStore.getState();

export function pushChatItem(no: string, item: ChatItem) {
  set((st) => ({
    chats: { ...st.chats, [no]: [...(st.chats[no] ?? []), item] },
  }));
}

export function pushUserMessage(no: string, text: string, images?: string[]): ChatItem {
  const item: ChatItem = { kind: "user", id: uid("u"), text, ts: Date.now() };
  if (images && images.length > 0) item.images = images;
  pushChatItem(no, item);
  return item;
}

/**
 * 用后端落盘的缩略图路径替换乐观渲染的 data URL（发送响应返回后调用）：
 * 数据源从内存切到克隆工作区文件，此后历史重载也能按引用行解析出同一路径。
 */
export function attachUserImages(no: string, id: string, images: string[]) {
  if (images.length === 0) return;
  set((st) => ({
    chats: {
      ...st.chats,
      [no]: (st.chats[no] ?? []).map((m) =>
        m.kind === "user" && m.id === id ? { ...m, images } : m,
      ),
    },
  }));
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
  // 已作答：从「已处理」忽略表移除，允许该 id 后续重新点亮（应答失败 revert 同理）。
  undismissAsk(permissionId);
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
  undismissAsk(permissionId);
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
  // 已作答/已跳过：从「已处理」忽略表移除，允许该 id 后续重新点亮（提交失败 revert 同理）。
  undismissAsk(requestId);
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
  undismissAsk(requestId);
  // 待决登记同步恢复（sessionId 取当前查看的会话——作答就发生在该会话视图里）
  notePendingQuestion(requestId, no, s().activeSessionId[no] ?? "");
}

/* ─── 回复标注（底部 footer 的 model / 推理等级） ─── */

/**
 * 当前会话「本条回复归属」的近似标注来源（openchamber 式底部标注）：
 * - model:   当时请求的模型（口径与发送端一致：会话实时覆盖 → 会话持久覆盖 →
 *            Agent 默认 ref 的 model 段）；解析不出再回退 Agent 名称
 * - variant: session overrideVariant 或 sessionModelSel.variant（推理等级）
 */
function sessionReplyMeta(no: string): { model: string | null; variant: string | null } {
  const st = s();
  const sessionId = st.activeSessionId[no];
  const sess = (st.sessions[no] ?? []).find((x) => x.id === sessionId);
  const cfgId = sess?.agentConfigId ?? st.agentId;
  const cfg =
    st.agents.find((a) => a.id === cfgId) ?? st.agents.find((a) => a.id === st.agentId) ?? st.agents[0];
  const sel = sessionId ? st.sessionModelSel[sessionId] : undefined;
  // || 链而非 ?? 链：清空覆盖（claude「默认」预设）会把 modelId/overrideModel 落成空串，
  // 空串必须跳过继续回退，最终解析不出才回退 Agent 名称。
  const model =
    sel?.modelId || sess?.overrideModel || splitModelRef(cfg?.model).model || cfg?.name || null;
  return {
    model,
    variant: (sess?.overrideVariant ?? (sessionId ? st.sessionModelSel[sessionId]?.variant : null)) ?? null,
  };
}

/** 给历史加载/刷新恢复的 assistant 气泡补齐 model/variant，避免 footer 只剩复制按钮。 */
export function applyReplyMetaDefaults(no: string) {
  const { model, variant } = sessionReplyMeta(no);
  if (!model && !variant) return;
  const list = s().chats[no] ?? [];
  if (!list.some((m) => m.kind === "assistant" && (!m.model || !m.variant))) return;
  set((st) => ({
    chats: {
      ...st.chats,
      [no]: st.chats[no].map((m) =>
        m.kind === "assistant"
          ? { ...m, model: m.model ?? model, variant: m.variant ?? variant }
          : m,
      ),
    },
  }));
}

export function finishAssistant(
  no: string,
  id: string,
  meta?: { model?: string | null; variant?: string | null },
) {
  const fallback = sessionReplyMeta(no);
  patchAssistant(no, id, (a) => ({
    ...a,
    streaming: false,
    endedAt: a.endedAt ?? Date.now(),
    model: meta?.model ?? a.model ?? fallback.model,
    variant: meta?.variant ?? a.variant ?? fallback.variant,
    thinking: a.thinking ? { ...a.thinking, done: true } : a.thinking,
  }));
}

/* ─── 生成中回合（live）：切换工单/会话后返回时保住未落库的流式内容 ─── */

/** 开始一个流式回合：占位 assistant 消息进当前视图，并以 sessionId 记入 liveTurns。 */
export function startLiveTurn(no: string, sessionId: string) {
  const meta = sessionReplyMeta(no);
  const item: Extract<ChatItem, { kind: "assistant" }> = {
    kind: "assistant",
    id: uid("a"),
    text: "",
    streaming: true,
    tools: [],
    ts: Date.now(),
    // V22/V23 逐消息标注：发送端本就知道这次请求的模型与推理档位（sessionReplyMeta
    // 与发送端同解析链），占位即盖戳让 footer 流式期间就有值；回合收尾与历史重载的
    // 兜底链不会覆盖已有值，流式/落库/历史三路同口径。
    model: meta.model,
    variant: meta.variant,
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
    const fallback = sessionReplyMeta(cur.ticketNo);
    const item: Extract<ChatItem, { kind: "assistant" }> = {
      ...cur.item,
      streaming: false,
      endedAt: cur.item.endedAt ?? Date.now(),
      thinking: cur.item.thinking ? { ...cur.item.thinking, done: true } : cur.item.thinking,
      // footer 标注在收尾盖戳：历史重载路径由 applyReplyMetaDefaults 补齐，这里
      // 覆盖不重建视图的正常收场（断流重建路径同样会被 defaults 兜底）。
      model: cur.item.model ?? fallback.model,
      variant: cur.item.variant ?? fallback.variant,
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
