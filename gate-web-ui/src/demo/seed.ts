/**
 * 演示模式种子（demo seed）：无后端时的初始工作台状态与快照恢复。
 * demo 引擎（脚本化回合模拟）见 engine.ts；示例数据见 scenario.ts。
 */
import { t } from "@/i18n";
import { appStore, readSnapshotRaw, type AppState } from "@/store";
import { uid } from "@/shared/format";
import type { ChatItem, DiffFile } from "@/shared/types";
import {
  DEMO_AGENTS,
  DEMO_OC_PROVIDERS,
  DEMO_PROJECTS,
  DEMO_RUNTIMES,
  DEMO_TICKETS,
  GIT_ACME,
  GIT_NEXUS,
  TREE_ACME,
  TREE_NEXUS,
  t102Diff,
} from "./scenario";
import { applyReplyMetaDefaults } from "@/features/session/chat";

function patch(partial: Partial<AppState>) {
  appStore.setState((st) => ({ ...st, ...partial }));
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
      text: t("ticket.sandboxReady"),
    },
  ];
  chats["T-102"] = [
    {
      kind: "system",
      id: uid("sys"),
      tone: "info",
      ts: Date.now(),
      text: t("demo.prevArchived"),
    },
  ];
  chats["T-201"] = [
    {
      kind: "system",
      id: uid("sys"),
      tone: "info",
      ts: Date.now(),
      text: t("ticket.sandboxReady"),
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
    reviewErrors: {},
    // demo 模式没有真实引擎配置，AI 审查入口始终可用（走本地演示脚本）。
    engine: { configured: true, providerId: "demo", model: "demo-engine" },
    busy: {},
    sessionBusy: {},
    sessionEnded: {},
    sessionInterrupted: {},
    gateBusy: {},
    usage: {},
    liveTurns: {},
    pendingPermissions: {},
    pendingQuestions: {},
    runningAgents: { count: 0, sessions: [] },
    centerTab: "chat",
    repoViewProjectId: null,
    terminalSessions: [],
    activeTerminalId: null,
    terminalView: "closed",
    agents: DEMO_AGENTS.map((a) => ({ ...a })),
    runtimes: DEMO_RUNTIMES.map((r) => ({ ...r })),
    ocProviders: DEMO_OC_PROVIDERS.map((p) => ({ ...p })),
    gitViews: { "acme-checkout": GIT_ACME, "nexus-docs": GIT_NEXUS },
    treeViews: { "acme-checkout": TREE_ACME, "nexus-docs": TREE_NEXUS },
    editingTicketNo: null,
    stageChanges: {},
    restartDialogFor: null,
    stageChangesViewFor: null,
    stageChangeConfirm: null,
    agentId:
      (typeof window !== "undefined" && localStorage.getItem("gate-agent-id")) ||
      DEMO_AGENTS[0].id,
  });
}

function tryRestore(): boolean {
  try {
    const raw = readSnapshotRaw();
    if (!raw) return false;
    const saved = JSON.parse(raw) as AppState & { _v?: number };
    if (saved._v !== 2 || !saved.tickets?.length) return false;
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
      // 终端会话依赖活的 WebSocket/进程，快照无法延续：恢复时全部作废。
      terminalSessions: [],
      activeTerminalId: null,
      terminalView: "closed",
      // 恢复时没有 EventSource，生成中的回合无法续流：丢弃 stash 并定格视图里的流式标记。
      liveTurns: {},
      runningAgents: { count: 0, sessions: [] },
      restartDialogFor: null,
      stageChangesViewFor: null,
      stageChangeConfirm: null,
      busySince: {},
      // 小助手的流式中转态同理无法跨刷新延续（无活 fetch）：恢复时清空，历史与布局照常还原。
      assistantLoading: false,
      assistantStreaming: "",
      assistantDraft: "",
    };
    // 旧版本快照没有 engine 字段：demo 模式视为已配置，live 交给 loadEngineConfig 回填。
    if (!clean.engine) {
      clean.engine = clean.mode === "demo" ? { configured: true, providerId: "demo", model: "demo-engine" } : null;
    }
    // 旧版本会把已应答的权限/提问卡片留在 chats 里（永久挂在底部）；恢复时只保留待决的，
    // 并把残留的 streaming 占位定格（否则光标会永久闪烁）。
    for (const [no, items] of Object.entries(clean.chats)) {
      const filtered = items
        .filter(
          (m) =>
            (m.kind !== "permission" && m.kind !== "question") ||
            m.status === "pending",
        )
        .map((m) => (m.kind === "assistant" && m.streaming ? { ...m, streaming: false } : m));
      clean.chats[no] = filtered;
    }
    appStore.setState(clean);
    // 旧快照的 assistant 气泡可能没有 agent/variant 标注，恢复后统一补齐。
    for (const no of Object.keys(clean.chats)) applyReplyMetaDefaults(no);
    return true;
  } catch {
    return false;
  }
}
