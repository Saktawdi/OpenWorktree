import { create } from "zustand";
import type {
  AgentConfigOption,
  ChatItem,
  DiffFile,
  Finding,
  Project,
  PublishOutcome,
  Snapshot,
  TaskProgress,
  Ticket,
  Stage,
  UsageView,
  VerdictInfo,
} from "./types";
import {
  DEMO_AGENTS,
  DEMO_PROJECT,
  DEMO_TICKETS,
  t102Diff,
} from "./scenario";
import { uid } from "./format";

export type CenterTab = "chat" | "diff" | "findings";

export interface AppState {
  booted: boolean;
  mode: "demo" | "live";
  conn: "ok" | "checking" | "unauth" | "error";
  backendUrl: string;
  token: string;
  connectOpen: boolean;
  view: "workbench" | "kanban";
  projects: Project[];
  activeProjectId: string;
  tickets: Ticket[];
  selectedNo: string | null;
  chats: Record<string, ChatItem[]>;
  diffs: Record<string, DiffFile[]>;
  snapshots: Record<string, Snapshot[]>;
  findings: Record<string, Finding[]>;
  verdicts: Record<string, VerdictInfo>;
  tasks: Record<string, TaskProgress>;
  outcomes: Record<string, PublishOutcome>;
  busy: Record<string, boolean>;
  gateBusy: Record<string, boolean>;
  usage: Record<string, UsageView>;
  centerTab: CenterTab;
  agents: AgentConfigOption[];
  agentId: string;
  toast: { id: number; nonce: number; text: string } | null;
  highlight: { path: string; line: number; nonce: number } | null;
  cancelSeq: Record<string, number>;
}

export const appStore = create<AppState>(() => ({
  booted: false,
  mode: "demo",
  conn: "ok",
  backendUrl: "",
  token: "",
  connectOpen: false,
  view: "workbench",
  projects: [],
  activeProjectId: "",
  tickets: [],
  selectedNo: null,
  chats: {},
  diffs: {},
  snapshots: {},
  findings: {},
  verdicts: {},
  tasks: {},
  outcomes: {},
  busy: {},
  gateBusy: {},
  usage: {},
  centerTab: "chat",
  agents: DEMO_AGENTS,
  agentId: DEMO_AGENTS[0].id,
  toast: null,
  highlight: null,
  cancelSeq: {},
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
  diffs["T-102"] = t102Diff();
  patch({
    booted: true,
    mode: "demo",
    conn: "ok",
    projects: [DEMO_PROJECT],
    activeProjectId: DEMO_PROJECT.id,
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
    centerTab: "chat",
    agents: DEMO_AGENTS,
    agentId: DEMO_AGENTS[0].id,
  });
}

const SNAPSHOT_KEY = "gate-ui-state-v1";

function tryRestore(): boolean {
  try {
    const raw = sessionStorage.getItem(SNAPSHOT_KEY);
    if (!raw) return false;
    const saved = JSON.parse(raw) as AppState & { _v?: number };
    if (saved._v !== 1 || !saved.tickets?.length) return false;
    const clean: AppState = {
      ...saved,
      booted: true,
      toast: null,
      connectOpen: false,
      highlight: null,
      busy: {},
      gateBusy: {},
      tasks: {},
    };
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

export function finishAssistant(no: string, id: string) {
  patchAssistant(no, id, (a) => ({ ...a, streaming: false }));
}

export function setBusy(no: string, busy: boolean) {
  set((st) => ({ busy: { ...st.busy, [no]: busy } }));
}

export function setGateBusy(no: string, busy: boolean) {
  set((st) => ({ gateBusy: { ...st.gateBusy, [no]: busy } }));
}

export function setTask(no: string, task: TaskProgress | null) {
  set((st) => {
    const next = { ...st.tasks };
    if (task === null) delete next[no];
    else next[no] = task;
    return { tasks: next };
  });
}

export function setDiffs(no: string, files: DiffFile[]) {
  set((st) => ({ diffs: { ...st.diffs, [no]: files } }));
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

export function currentCancelSeq(no: string): number {
  return s().cancelSeq[no] ?? 0;
}

export function createTicket(title: string, priority: Ticket["priority"]) {
  const existing = s().tickets.length;
  const no = `T-${105 + existing}`;
  const t: Ticket = {
    ticketNo: no,
    title,
    stage: "IN_PROGRESS",
    priority,
    projectId: s().activeProjectId,
    labels: [],
    createdAt: nowIso(),
    updatedAt: nowIso(),
  };
  set((st) => ({
    tickets: [t, ...st.tickets],
    chats: { ...st.chats, [no]: [
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

export function useApp<T>(selector: (st: AppState) => T): T {
  return appStore(selector);
}

export const NO_CHAT: ChatItem[] = [];
export const NO_DIFF: DiffFile[] = [];
export const NO_FINDINGS: Finding[] = [];
