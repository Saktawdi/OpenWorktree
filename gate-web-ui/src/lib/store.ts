import { create } from "zustand";
import type {
  AgentConfig,
  AgentRuntime,
  CatalogProvider,
  ChatItem,
  ChatSession,
  DiffFile,
  Finding,
  GitRepoView,
  GitTreeEntry,
  Project,
  PublishOutcome,
  PermissionRequestView,
  SessionModelSel,
  Snapshot,
  TaskProgress,
  Ticket,
  Stage,
  UsageView,
  VerdictInfo,
} from "./types";
import {
  DEMO_AGENTS,
  DEMO_PROJECTS,
  DEMO_RUNTIMES,
  DEMO_TICKETS,
  GIT_ACME,
  GIT_NEXUS,
  TREE_ACME,
  TREE_NEXUS,
  t102Diff,
} from "./scenario";
import { uid } from "./format";

export type CenterTab = "chat" | "diff" | "findings";

export type Theme = "dark" | "light";

/** 一条生成中的 assistant 回合。item 以 sessionId 为键保真；视图按 itemId 镜像。 */
export interface LiveTurn {
  ticketNo: string;
  itemId: string;
  item: Extract<ChatItem, { kind: "assistant" }>;
}

export interface AppState {
  booted: boolean;
  mode: "demo" | "live";
  conn: "ok" | "checking" | "unauth" | "error";
  backendUrl: string;
  token: string;
  connectOpen: boolean;
  theme: Theme;
  view: "workbench" | "kanban" | "projects" | "agents";
  projects: Project[];
  activeProjectId: string;
  tickets: Ticket[];
  selectedNo: string | null;
  chats: Record<string, ChatItem[]>;
  diffs: Record<string, DiffFile[]>;
  /** 变更对比的行尾噪声警告（key = 工单号），来自 /diff 端点的 eol_warning。 */
  diffWarnings: Record<string, string>;
  snapshots: Record<string, Snapshot[]>;
  findings: Record<string, Finding[]>;
  verdicts: Record<string, VerdictInfo>;
  tasks: Record<string, TaskProgress>;
  outcomes: Record<string, PublishOutcome>;
  busy: Record<string, boolean>;
  /** Live 模式按会话粒度的生成中标记（key = gate session id）；按钮状态跟随当前会话。 */
  sessionBusy: Record<string, boolean>;
  gateBusy: Record<string, boolean>;
  creatingSession: Record<string, boolean>;
  usage: Record<string, UsageView>;
  centerTab: CenterTab;
  agents: AgentConfig[];
  runtimes: AgentRuntime[];
  gitViews: Record<string, GitRepoView>;
  treeViews: Record<string, GitTreeEntry[]>;
  editingTicketNo: string | null;
  ticketCreatorOpen: boolean;
  agentId: string;
  toast: { id: number; nonce: number; text: string } | null;
  highlight: { path: string; line: number; nonce: number } | null;
  cancelSeq: Record<string, number>;
  order: Record<string, number>;
  sessions: Record<string, ChatSession[]>;
  activeSessionId: Record<string, string>;
  /** Live model catalog per session (from the session's opencode serve). */
  sessionModels: Record<string, CatalogProvider[]>;
  /** Per-session live model / reasoning-effort selection (会话内实时切换). */
  sessionModelSel: Record<string, SessionModelSel>;
  /** 生成中的回合（key = gate session id）：回合在 idle 前不落库，切走再切回时靠它恢复流式内容。 */
  liveTurns: Record<string, LiveTurn>;
  /** 运行中的智能体数量（GET /api/agents/busy 轮询） */
  runningAgents: { count: number; sessions: Array<{ session_id: string; title: string | null; ticket_no: string | null; cli: string | null }> };
}

export const appStore = create<AppState>(() => ({
  booted: false,
  mode: "demo",
  conn: "ok",
  backendUrl: "",
  token: "",
  connectOpen: false,
  theme: (typeof window !== "undefined" && localStorage.getItem("gate-theme") as Theme) || "dark",
  view: "workbench",
  projects: [],
  activeProjectId: "",
  tickets: [],
  selectedNo: null,
  chats: {},
  diffs: {},
  diffWarnings: {},
  snapshots: {},
  findings: {},
  verdicts: {},
  tasks: {},
  outcomes: {},
  busy: {},
  sessionBusy: {},
  gateBusy: {},
  creatingSession: {},
  usage: {},
  centerTab: "chat",
  agents: DEMO_AGENTS,
  runtimes: DEMO_RUNTIMES,
  gitViews: { "acme-checkout": GIT_ACME, "nexus-docs": GIT_NEXUS },
  treeViews: { "acme-checkout": TREE_ACME, "nexus-docs": TREE_NEXUS },
  editingTicketNo: null,
  ticketCreatorOpen: false,
  agentId: DEMO_AGENTS[0].id,
  toast: null,
  highlight: null,
  cancelSeq: {},
  order: {},
  sessions: {},
  activeSessionId: {},
  sessionModels: {},
  sessionModelSel: {},
  liveTurns: {},
  runningAgents: { count: 0, sessions: [] },
}));

const s = () => appStore.getState();
const set = appStore.setState;

function patch(partial: Partial<AppState>) {
  set((st) => ({ ...st, ...partial }));
}

function nowIso() {
  return new Date().toISOString();
}

export function seedDemo(force = false) {
  if (!force) {
    const restored = tryRestore();
    if (restored) return;
  }
  const chats: Record<string, ChatItem[]> = {};
  const diffs: Record<string, DiffFile[]> = {};
  chats["T-104"] = [
    {
      kind: "system",
      id: uid("sys"),
      tone: "info",
      ts: Date.now(),
      text: "工单沙箱已就绪 · 独立克隆已创建，Agent 的全部改动不会触碰主分支",
    },
  ];
  chats["T-102"] = [
    {
      kind: "system",
      id: uid("sys"),
      tone: "info",
      ts: Date.now(),
      text: "上一轮会话已归档 · 继续对话将追加到本工单",
    },
  ];
  chats["T-201"] = [
    {
      kind: "system",
      id: uid("sys"),
      tone: "info",
      ts: Date.now(),
      text: "工单沙箱已就绪 · 独立克隆已创建，Agent 的全部改动不会触碰主分支",
    },
  ];
  diffs["T-102"] = t102Diff();
  patch({
    booted: true,
    mode: "demo",
    conn: "ok",
    projects: DEMO_PROJECTS.map((p) => ({ ...p })),
    activeProjectId: "acme-checkout",
    tickets: DEMO_TICKETS.map((t) => ({ ...t })),
    selectedNo: "T-104",
    chats,
    diffs,
    snapshots: {},
    findings: {},
    verdicts: {},
    tasks: {},
    outcomes: {},
    busy: {},
    gateBusy: {},
    usage: {},
    liveTurns: {},
    runningAgents: { count: 0, sessions: [] },
    centerTab: "chat",
    agents: DEMO_AGENTS.map((a) => ({ ...a })),
    runtimes: DEMO_RUNTIMES.map((r) => ({ ...r })),
    gitViews: { "acme-checkout": GIT_ACME, "nexus-docs": GIT_NEXUS },
    treeViews: { "acme-checkout": TREE_ACME, "nexus-docs": TREE_NEXUS },
    editingTicketNo: null,
  agentId:
    (typeof window !== "undefined" && localStorage.getItem("gate-agent-id")) ||
    DEMO_AGENTS[0].id,
  });
}

const SNAPSHOT_KEY = "gate-ui-state-v2";

function tryRestore(): boolean {
  try {
    const raw = sessionStorage.getItem(SNAPSHOT_KEY);
    if (!raw) return false;
    const saved = JSON.parse(raw) as AppState & { _v?: number };
    if (saved._v !== 1 || !saved.tickets?.length) return false;
    const cur = appStore.getState();
    const clean: AppState = {
      ...cur,
      ...saved,
      booted: true,
      toast: null,
      connectOpen: false,
      highlight: null,
      busy: {},
      sessionBusy: {},
      gateBusy: {},
      creatingSession: {},
      tasks: {},
      runtimes: saved.runtimes ?? cur.runtimes,
      gitViews: saved.gitViews ?? cur.gitViews,
      treeViews: saved.treeViews ?? cur.treeViews,
      editingTicketNo: null,
      ticketCreatorOpen: false,
      // 恢复时没有 EventSource，生成中的回合无法续流：丢弃 stash 并定格视图里的流式标记。
      liveTurns: {},
      runningAgents: { count: 0, sessions: [] },
    };
    // 旧版本会把已应答的权限卡片留在 chats 里（永久挂在底部）；恢复时只保留待决的，
    // 并把残留的 streaming 占位定格（否则光标会永久闪烁）。
    for (const [no, items] of Object.entries(clean.chats)) {
      const filtered = items
        .filter((m) => m.kind !== "permission" || m.status === "pending")
        .map((m) => (m.kind === "assistant" && m.streaming ? { ...m, streaming: false } : m));
      clean.chats[no] = filtered;
    }
    appStore.setState(clean);
    return true;
  } catch {
    return false;
  }
}

let saveTimer: ReturnType<typeof setTimeout> | null = null;
appStore.subscribe(() => {
  if (saveTimer) clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    try {
      const st = appStore.getState();
      const data = JSON.stringify({ ...st, _v: 1, toast: null, connectOpen: false, highlight: null });
      sessionStorage.setItem(SNAPSHOT_KEY, data);
    } catch {
      /* 存储满或不可用时忽略 */
    }
  }, 250);
});

export function wipePersisted() {
  try {
    sessionStorage.removeItem(SNAPSHOT_KEY);
  } catch {
    /* ignore */
  }
}

export function ticketByNo(no: string | null): Ticket | undefined {
  if (!no) return undefined;
  return s().tickets.find((t) => t.ticketNo === no);
}

export function setView(view: AppState["view"]) {
  patch({ view });
}

export function setCenterTab(tab: CenterTab) {
  patch({ centerTab: tab });
}

export function setAgentId(id: string) {
  patch({ agentId: id });
  try {
    localStorage.setItem("gate-agent-id", id);
  } catch {
    /* ignore */
  }
}

/* ─── 会话内实时切换模型 / 推理强度（OpenChamber 式 per-session 选择） ─── */

export function setSessionModels(sessionId: string, providers: CatalogProvider[]) {
  set((st) => ({ sessionModels: { ...st.sessionModels, [sessionId]: providers } }));
}

export function setSessionModelSel(sessionId: string, sel: SessionModelSel) {
  set((st) => ({ sessionModelSel: { ...st.sessionModelSel, [sessionId]: sel } }));
}

export function selectTicket(no: string) {
  patch({ selectedNo: no, centerTab: "chat", highlight: null });
}

export function openConnect() {
  patch({ connectOpen: true });
}
export function closeConnect() {
  patch({ connectOpen: false });
}

let toastNonce = 0;
export function showToast(text: string) {
  toastNonce += 1;
  patch({ toast: { id: Date.now(), nonce: toastNonce, text } });
}
export function clearToast() {
  patch({ toast: null });
}

export function jumpToFinding(path: string, line: number) {
  patch({ centerTab: "diff", highlight: { path, line, nonce: Date.now() } });
}

export function setStage(no: string, stage: Stage) {
  set((st) => ({
    tickets: st.tickets.map((t) =>
      t.ticketNo === no ? { ...t, stage, updatedAt: nowIso() } : t,
    ),
  }));
}

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
  set((st) => ({
    chats: {
      ...st.chats,
      [no]: (st.chats[no] ?? []).filter((m) => !(m.kind === "permission" && m.id === id)),
    },
  }));
}

/** 应答提交失败：清掉墓碑，调用方随后重新 pushPermissionRequest 恢复待决卡片。 */
export function revertPermission(_no: string, permissionId: string) {
  resolvedPermissions.delete(permissionId);
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

export function patchAssistant(no: string, id: string, fn: (a: Extract<ChatItem, { kind: "assistant" }>) => Extract<ChatItem, { kind: "assistant" }>) {
  set((st) => ({
    chats: {
      ...st.chats,
      [no]: (st.chats[no] ?? []).map((m) =>
        m.kind === "assistant" && m.id === id ? fn(m) : m,
      ),
    },
  }));
}

export function finishAssistant(
  no: string,
  id: string,
  meta?: { agent?: string | null; variant?: string | null },
) {
  patchAssistant(no, id, (a) => ({
    ...a,
    streaming: false,
    agent: meta?.agent ?? a.agent,
    variant: meta?.variant ?? a.variant,
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

export function setBusy(no: string, busy: boolean) {
  set((st) => ({ busy: { ...st.busy, [no]: busy } }));
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

export function setGateBusy(no: string, busy: boolean) {
  set((st) => ({ gateBusy: { ...st.gateBusy, [no]: busy } }));
}

export function setCreatingSession(no: string, busy: boolean) {
  set((st) => {
    const next = { ...st.creatingSession };
    if (busy) next[no] = true;
    else delete next[no];
    return { creatingSession: next };
  });
}

export function setTask(no: string, task: TaskProgress | null) {
  set((st) => {
    const next = { ...st.tasks };
    if (task === null) delete next[no];
    else next[no] = task;
    return { tasks: next };
  });
}

export function setDiffs(no: string, files: DiffFile[], eolWarning?: string) {
  set((st) => ({
    diffs: { ...st.diffs, [no]: files },
    diffWarnings: { ...st.diffWarnings, [no]: eolWarning ?? "" },
  }));
}

export function addSnapshot(no: string, snap: Snapshot) {
  set((st) => ({ snapshots: { ...st.snapshots, [no]: [...(st.snapshots[no] ?? []), snap] } }));
}

export function setFindings(no: string, findings: Finding[]) {
  set((st) => ({ findings: { ...st.findings, [no]: findings } }));
}

export function setVerdict(no: string, verdict: VerdictInfo | null) {
  set((st) => {
    const next = { ...st.verdicts };
    if (verdict === null) delete next[no];
    else next[no] = verdict;
    return { verdicts: next };
  });
}

export function setOutcome(no: string, outcome: PublishOutcome) {
  set((st) => ({ outcomes: { ...st.outcomes, [no]: outcome } }));
}

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

export function requestCancel(no: string) {
  set((st) => ({ cancelSeq: { ...st.cancelSeq, [no]: (st.cancelSeq[no] ?? 0) + 1 } }));
}

export function setTicketOrder(no: string, order: number) {
  set((st) => ({ order: { ...st.order, [no]: order } }));
}

export function laneOrders(st: AppState, nos: string[]): number[] {
  return nos.map((n) => st.order[n] ?? 0);
}

export function currentCancelSeq(no: string): number {
  return s().cancelSeq[no] ?? 0;
}

export function createTicket(title: string, priority: Ticket["priority"]) {
  const st = s();
  const projectTickets = st.tickets.filter((t) => t.projectId === st.activeProjectId);
  const maxNum = Math.max(
    100,
    ...st.tickets.map((t) => parseInt(t.ticketNo.replace(/\D/g, ""), 10) || 100),
  );
  const no = `T-${maxNum + 1}`;
  void projectTickets;
  const t: Ticket = {
    ticketNo: no,
    title,
    stage: "PENDING",
    priority,
    projectId: st.activeProjectId,
    labels: [],
    targetRef: "refs/heads/main",
    clonePath: `local-run/clones/${no}`,
    agentConfigId: st.agentId,
    execTokenTotal: 0,
    createdAt: nowIso(),
    updatedAt: nowIso(),
  };
  set((st2) => ({
    tickets: [t, ...st2.tickets],
    chats: { ...st2.chats, [no]: [
      {
        kind: "system" as const,
        id: uid("sys"),
        tone: "info" as const,
        ts: Date.now(),
        text: "工单沙箱已就绪 · 独立克隆已创建，Agent 的全部改动不会触碰主分支",
      },
    ] },
  }));
  selectTicket(no);
  showToast(`工单 ${no} 已创建`);
  return no;
}

export function updateTicket(no: string, p: Partial<Ticket>) {
  set((st) => ({
    tickets: st.tickets.map((t) =>
      t.ticketNo === no ? { ...t, ...p, updatedAt: nowIso() } : t,
    ),
  }));
}

export function cancelTicket(no: string) {
  setStage(no, "CANCELLED");
}

export function switchProject(id: string) {
  patch({ activeProjectId: id });
  const first = s().tickets.find((t) => t.projectId === id && !isTerminalStage(t.stage));
  patch({ selectedNo: first?.ticketNo ?? null });
}

function isTerminalStage(stage: Stage): boolean {
  return stage === "DONE" || stage === "CANCELLED";
}

export function upsertProject(p: Project) {
  set((st) => {
    const exists = st.projects.some((x) => x.id === p.id);
    return {
      projects: exists
        ? st.projects.map((x) => (x.id === p.id ? p : x))
        : [...st.projects, p],
      activeProjectId: exists ? st.activeProjectId : p.id,
    };
  });
}

export function removeProject(id: string) {
  set((st) => ({
    projects: st.projects.filter((p) => p.id !== id),
    tickets: st.tickets.map((t) =>
      t.projectId === id ? { ...t, projectId: "" } : t,
    ),
    activeProjectId: st.activeProjectId === id ? (st.projects.find((p) => p.id !== id)?.id ?? "") : st.activeProjectId,
  }));
}

export function upsertAgentConfig(c: AgentConfig) {
  set((st) => ({
    agents: st.agents.some((x) => x.id === c.id)
      ? st.agents.map((x) => (x.id === c.id ? c : x))
      : [...st.agents, c],
  }));
}

export function removeAgentConfig(id: string) {
  set((st) => ({
    agents: st.agents.filter((a) => a.id !== id),
    agentId: st.agentId === id ? (st.agents.find((a) => a.id !== id)?.id ?? "") : st.agentId,
  }));
}

export function openTicketEditor(no: string | null) {
  patch({ editingTicketNo: no });
}

/** Opens the new-ticket form from anywhere (empty workbench, kanban toolbar). */
export function openTicketCreator() {
  patch({ ticketCreatorOpen: true });
}

export function closeTicketCreator() {
  patch({ ticketCreatorOpen: false });
}

export function toggleTheme() {
  const next: Theme = s().theme === "dark" ? "light" : "dark";
  document.documentElement.setAttribute("data-theme", next);
  localStorage.setItem("gate-theme", next);
  patch({ theme: next });
}

export function applyThemeFromStorage() {
  const stored = localStorage.getItem("gate-theme") as Theme | null;
  const theme = stored || "dark";
  document.documentElement.setAttribute("data-theme", theme);
  patch({ theme });
}

export function createSession(ticketNo: string) {
  const id = uid("sess");
  const session: ChatSession = {
    id,
    ticketNo,
    title: `会话 ${new Date().toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" })}`,
    status: "active",
    permissionAutoAccept: false,
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
  return id;
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
    const newActiveId = activeId === sessionId ? (sessions.find((s) => s.status === "active")?.id ?? "") : activeId;
    return {
      sessions: { ...st.sessions, [ticketNo]: sessions },
      activeSessionId: { ...st.activeSessionId, [ticketNo]: newActiveId },
    };
  });
}

export function useApp<T>(selector: (st: AppState) => T): T {
  return appStore(selector);
}

export const NO_CHAT: ChatItem[] = [];
export const NO_DIFF: DiffFile[] = [];
export const NO_FINDINGS: Finding[] = [];
export const NO_SESSIONS: ChatSession[] = [];
