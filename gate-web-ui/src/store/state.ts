/**
 * 全局状态底座（store）：AppState 的形状定义、appStore 实例与快照持久化。
 * 各业务域的状态变更（mutations）分散在对应 feature 包的 state.ts 中，
 * 这里只负责"状态是什么"与"如何落盘/恢复"，不包含任何业务动作。
 */
import { create } from "zustand";
import type {
  AgentConfig,
  AgentRuntime,
  CatalogProvider,
  ChatItem,
  ChatSession,
  ContextUsageState,
  DiffFile,
  EngineInfo,
  Finding,
  GitRepoView,
  GitTreeEntry,
  OpenCodeProvider,
  Project,
  PublishOutcome,
  PermissionRequestView,
  QuestionRequestView,
  StageChangeRecord,
  SessionModelSel,
  TerminalSessionMeta,
  Snapshot,
  TaskProgress,
  Ticket,
  Stage,
  TodoItem,
  UsageView,
  VerdictInfo,
  QuoteChip,
  EvidenceBundle,
  SessionGroup,
} from "@/shared/types";
import {
  DEMO_AGENTS,
  DEMO_OC_PROVIDERS,
  DEMO_RUNTIMES,
  GIT_ACME,
  GIT_NEXUS,
  TREE_ACME,
  TREE_NEXUS,
} from "@/demo/scenario";
import {
  loadAgentId,
  loadComposerDrafts,
  loadGatePanelCollapsed,
  loadGateSections,
  loadKanbanStages,
  loadPendingQuotes,
  loadSessionGroups,
  loadSessionPinned,
  loadTheme,
  loadVisibleStages,
} from "./prefs";

export type CenterTab = "chat" | "diff" | "findings" | "evidence";

export type Theme = "dark" | "light";

/** 一条生成中的 assistant 回合。item 以 sessionId 为键保真；视图按 itemId 镜像。 */
export interface LiveTurn {
  ticketNo: string;
  itemId: string;
  item: Extract<ChatItem, { kind: "assistant" }>;
}

/** 右侧工单面板三个分段（工单信息 / 门禁流水线 / 会话列表）各自的展开状态。 */
export interface GateSections {
  info: boolean;
  pipeline: boolean;
  sessions: boolean;
}

export interface AppState {
  booted: boolean;
  mode: "demo" | "live";
  conn: "ok" | "checking" | "unauth" | "error";
  backendUrl: string;
  token: string;
  connectOpen: boolean;
  theme: Theme;
  view: "workbench" | "kanban" | "projects" | "agents" | "plugins" | "settings" | "repo" | "plugin-page";
  /** 仓库视图整页（view="repo"）当前展示的项目；null = 未打开。 */
  repoViewProjectId: string | null;
  /** 插件整页（view="plugin-page"）当前打开的页面贡献 id；null = 未打开。 */
  pluginPageId: string | null;
  /** 终端会话（tab）列表；进程与 WebSocket 随会话存活，最小化不影响运行。 */
  terminalSessions: TerminalSessionMeta[];
  /** 终端工作台当前激活的 tab；null = 无。 */
  activeTerminalId: string | null;
  /** 终端工作台展示状态：closed=无窗口，open=弹窗，minimized=缩入顶栏圆钮。 */
  terminalView: "closed" | "open" | "minimized";
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
  /** 证据链聚合数据（key = 工单号）：证据链 tab 的数据源；null = 未加载/加载失败。 */
  evidence: Record<string, EvidenceBundle | null>;
  /** 证据链 tab 的定位锚：流水线节点点击后跳转并滚动到对应轮次。 */
  evidenceFocus: { ticketNo: string; round: number; nonce: number } | null;
  tasks: Record<string, TaskProgress>;
  outcomes: Record<string, PublishOutcome>;
  /** 审查任务的失败原因（key = 工单号）：审查发现页的错误卡片与重试入口都读它。 */
  reviewErrors: Record<string, string>;
  /** 审查引擎配置（live 来自 /api/config；demo 视为已配置）。null = 尚未加载。 */
  engine: EngineInfo | null;
  busy: Record<string, boolean>;
  /** Live 模式按会话粒度的生成中标记（key = gate session id）；按钮状态跟随当前会话。 */
  sessionBusy: Record<string, boolean>;
  /** Agent 开始运行的时间戳（运行监控面板用于展示运行时长）；空闲时移除条目。 */
  busySince: Record<string, number>;
  /** T-120 增强：会话结束提醒（key = 工单号）。done=回合正常完成；failed=出错/中止。打开工单或再次运行时清除。 */
  sessionEnded: Record<string, { kind: "done" | "failed"; at: number }>;
  gateBusy: Record<string, boolean>;
  /** 草稿态（会话未创建）下的创建遮罩标记：首条消息走「建会话→写覆盖→发消息」三步期间为 true。 */
  creatingSession: Record<string, boolean>;
  usage: Record<string, UsageView>;
  /** 会话最新任务清单（todowrite 快照；V21 起按会话 id 键控——串会话在键位上不可能发生）。 */
  todos: Record<string, TodoItem[]>;
  /** 会话上下文占用（最新一轮窗口 tokens + 模型上限）。 */
  context: Record<string, ContextUsageState>;
  centerTab: CenterTab;
  agents: AgentConfig[];
  runtimes: AgentRuntime[];
  /** 草稿态（会话未创建）暂存的模型/推理选择：随首条消息创建会话时持久化为覆盖。 */
  draftModelSel: Record<string, SessionModelSel>;
  /** 输入框草稿自动保存（按工单号键）：切 tab/工单/页面后回来自动还原，发送成功或工单终态时清除。 */
  composerDrafts: Record<string, string>;
  /** 引用片段胶囊（按工单号键）：划选页面文字「添加到对话框」后暂存，随下一条消息发送。 */
  pendingQuotes: Record<string, QuoteChip[]>;
  /** OpenCode 配置文件的 provider 列表（live 从后端读写文件，demo 为示例数据）。 */
  ocProviders: OpenCodeProvider[];
  ocConfigPath: string | null;
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
  /** 会话分组表（key = 工单号；T-105 端侧软数据，localStorage 持久化，live 后端无分组 API）。 */
  sessionGroups: Record<string, SessionGroup[]>;
  /** 分组归属（key = sessionId → groupId）：独立于会话对象，随列表刷新不丢。 */
  sessionGroupMembers: Record<string, string>;
  /** 置顶会话（key = 工单号；有序 sessionId 数组，列表内置顶段优先展示）。 */
  sessionPinned: Record<string, string[]>;
  /** Live model catalog per session (from the session's opencode serve). */
  sessionModels: Record<string, CatalogProvider[]>;
  /** Per-session live model / reasoning-effort selection (会话内实时切换). */
  sessionModelSel: Record<string, SessionModelSel>;
  /** 生成中的回合（key = gate session id）：回合在 idle 前不落库，切走再切回时靠它恢复流式内容。 */
  liveTurns: Record<string, LiveTurn>;
  /** T-120 增强：待决权限登记（permissionId → 归属工单/会话）。SSE asked 与恢复拉取登记，replied/应答注销。 */
  pendingPermissions: Record<string, { ticketNo: string; sessionId: string }>;
  /** T-120 增强：待决提问登记（requestId → 归属工单/会话）。 */
  pendingQuestions: Record<string, { ticketNo: string; sessionId: string }>;
  /** 运行中的智能体数量（GET /api/agents/busy 轮询） */
  runningAgents: { count: number; sessions: Array<{ session_id: string; title: string | null; ticket_no: string | null; cli: string | null }> };
  /** 工单列表按状态筛选：勾选可见的状态集合。 */
  visibleStages: Stage[];
  /** 看板甬道筛选：勾选显示的甬道状态集合（默认流水线六甬道，上限 6）。 */
  kanbanStages: Stage[];
  /** 重启历史（T-117）：每工单的重启记录列表 */
  /** 工单状态变更记录（V19）：每工单的重启/强制已完成/取消记录列表 */
  stageChanges: Record<string, StageChangeRecord[]>;
  /** 重启理由弹窗目标工单（null = 关闭） */
  restartDialogFor: string | null;
  /** 状态变更记录弹窗目标工单（null = 关闭） */
  stageChangesViewFor: string | null;
  /** 拖拽/按钮触发的终态流转确认弹窗（理由必填）；null = 关闭 */
  stageChangeConfirm: { ticketNo: string; to: Stage } | null;
  /** 右侧工单面板整栏折叠（会话工具条最右侧按钮切换；localStorage 持久化）。 */
  gatePanelCollapsed: boolean;
  /** 右侧面板三段的展开状态（localStorage 持久化）。 */
  gateSections: GateSections;
}

const persistedGroups = loadSessionGroups();

export const appStore = create<AppState>(() => ({
  booted: false,
  mode: "demo",
  conn: "ok",
  backendUrl: "",
  token: "",
  connectOpen: false,
  theme: loadTheme() as Theme,
  view: "workbench",
  repoViewProjectId: null,
  pluginPageId: null,
  terminalSessions: [],
  activeTerminalId: null,
  terminalView: "closed",
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
  evidence: {},
  evidenceFocus: null,
  tasks: {},
  outcomes: {},
  reviewErrors: {},
  engine: null,
  busy: {},
  sessionBusy: {},
  busySince: {},
  sessionEnded: {},
  gateBusy: {},
  creatingSession: {},
  usage: {},
  todos: {},
  context: {},
  centerTab: "chat",
  agents: DEMO_AGENTS,
  runtimes: DEMO_RUNTIMES,
  ocProviders: DEMO_OC_PROVIDERS,
  ocConfigPath: null,
  gitViews: { "acme-checkout": GIT_ACME, "nexus-docs": GIT_NEXUS },
  treeViews: { "acme-checkout": TREE_ACME, "nexus-docs": TREE_NEXUS },
  editingTicketNo: null,
  ticketCreatorOpen: false,
  agentId: loadAgentId() || DEMO_AGENTS[0].id,
  toast: null,
  highlight: null,
  cancelSeq: {},
  order: {},
  sessions: {},
  activeSessionId: {},
  sessionGroups: persistedGroups.groups,
  sessionGroupMembers: persistedGroups.members,
  sessionPinned: loadSessionPinned(),
  draftModelSel: {},
  composerDrafts: loadComposerDrafts(),
  pendingQuotes: loadPendingQuotes(),
  sessionModels: {},
  sessionModelSel: {},
  liveTurns: {},
  pendingPermissions: {},
  pendingQuestions: {},
  runningAgents: { count: 0, sessions: [] },
  visibleStages: loadVisibleStages(),
  kanbanStages: loadKanbanStages(),
  stageChanges: {},
  restartDialogFor: null,
  stageChangesViewFor: null,
  stageChangeConfirm: null,
  gatePanelCollapsed: loadGatePanelCollapsed(),
  gateSections: loadGateSections(),
}));

export function useApp<T>(selector: (st: AppState) => T): T {
  return appStore(selector);
}

export const NO_CHAT: ChatItem[] = [];
export const NO_QUOTES: QuoteChip[] = [];
export const NO_DIFF: DiffFile[] = [];
export const NO_FINDINGS: Finding[] = [];
export const NO_SESSIONS: ChatSession[] = [];

/* ─── 快照持久化（sessionStorage；demo 模式下跨刷新延续工作台状态） ─── */

const SNAPSHOT_KEY = "gate-ui-state-v3";

/** 读取快照原文（demo 恢复用）；存储不可用时返回 null。 */
export function readSnapshotRaw(): string | null {
  try {
    return sessionStorage.getItem(SNAPSHOT_KEY);
  } catch {
    return null;
  }
}

let saveTimer: ReturnType<typeof setTimeout> | null = null;
appStore.subscribe(() => {
  if (saveTimer) clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    try {
      const st = appStore.getState();
      const data = JSON.stringify({ ...st, _v: 2, toast: null, connectOpen: false, highlight: null, evidenceFocus: null });
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
