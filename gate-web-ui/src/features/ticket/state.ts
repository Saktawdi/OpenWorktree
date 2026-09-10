/**
 * 工单域状态（ticket）：工单数据变更、弹窗开关、列表/看板筛选与 demo 本地 CRUD。
 */
import { t } from "@/i18n";
import { appStore } from "@/store";
import { showToast } from "@/store/ui";
import { saveKanbanStages, saveVisibleStages } from "@/store/prefs";
import type { DiffContentEntry, DiffFile, Stage, StageChangeRecord, Ticket } from "@/shared/types";
import { diffSig } from "@/shared/diff";
import { ALL_STAGES, KANBAN_DEFAULT_STAGES, KANBAN_LANE_COUNT, KANBAN_STAGE_ORDER, uid } from "@/shared/format";
import { clearSessionEnded } from "@/features/session/state";
import { clearReviewEnded } from "@/features/gate/state";
import { emitPluginEvent } from "@/app/plugins/events";
import type { AppState } from "@/store";

const set = appStore.setState;
const s = () => appStore.getState();

function patch(partial: Partial<AppState>) {
  set((st) => ({ ...st, ...partial }));
}

export function setStage(no: string, stage: Stage) {
  // from→to 在写入点已知，显式 emit（不做快照 diff 还原）；无订阅者时空操作。
  const prev = s().tickets.find((t) => t.ticketNo === no)?.stage;
  set((st) => ({
    tickets: st.tickets.map((t) =>
      t.ticketNo === no ? { ...t, stage, updatedAt: new Date().toISOString() } : t,
    ),
  }));
  if (prev && prev !== stage) {
    emitPluginEvent("ticket.stage-changed", { ticketNo: no, from: prev, to: stage });
  }
}

/**
 * 写入变更对比文件列表：只保留元数据（hunks 置空，内容按需加载）。
 * 带 hunks 的完整文件（demo 种子）同时落入内容缓存；仍在列表中的 path 保留旧内容
 * （过期与否由 DiffView 按 sig 判断），已从列表消失的 path 丢弃。
 */
export function setDiffs(no: string, files: DiffFile[], eolWarning?: string) {
  set((st) => {
    const prev = st.diffContents[no] ?? {};
    const contents: Record<string, DiffContentEntry> = {};
    const metas = files.map((f) => {
      if (f.hunks.length > 0) {
        contents[f.path] = { file: f, sig: diffSig(f) };
      } else if (prev[f.path]) {
        contents[f.path] = prev[f.path];
      }
      return { ...f, hunks: [] };
    });
    return {
      diffs: { ...st.diffs, [no]: metas },
      diffContents: { ...st.diffContents, [no]: contents },
      diffWarnings: { ...st.diffWarnings, [no]: eolWarning ?? "" },
    };
  });
}

/** 写入按需加载的单文件 diff 内容；sig = 加载时刻列表侧的增删行数指纹。 */
export function setDiffContent(no: string, file: DiffFile, sig: string) {
  set((st) => ({
    diffContents: {
      ...st.diffContents,
      [no]: { ...(st.diffContents[no] ?? {}), [file.path]: { file, sig } },
    },
  }));
}

export function selectTicket(no: string) {
  // 打开工单即视为看见"会话已结束"与"审查结果已出"提醒
  clearSessionEnded(no);
  clearReviewEnded(no);
  patch({ selectedNo: no, centerTab: "chat", highlight: null });
}

/**
 * 运行监控面板的快速跳转：切回工作台并打开该工单（可选定位指定会话）。
 * 工单属于其他项目时先切换项目，避免选中后列表里看不到它。
 */
export function jumpToTicketSession(no: string, sessionId?: string) {
  const st = s();
  const ticket = st.tickets.find((t) => t.ticketNo === no);
  if (ticket && ticket.projectId && ticket.projectId !== st.activeProjectId) {
    patch({ activeProjectId: ticket.projectId });
  }
  if (sessionId) {
    patch({
      view: "workbench",
      activeSessionId: { ...st.activeSessionId, [no]: sessionId },
    });
  } else {
    patch({ view: "workbench" });
  }
  selectTicket(no);
}

export function requestCancel(no: string) {
  set((st) => ({ cancelSeq: { ...st.cancelSeq, [no]: (st.cancelSeq[no] ?? 0) + 1 } }));
}

export function currentCancelSeq(no: string): number {
  return s().cancelSeq[no] ?? 0;
}

export function setTicketOrder(no: string, order: number) {
  set((st) => ({ order: { ...st.order, [no]: order } }));
}

export function laneOrders(st: AppState, nos: string[]): number[] {
  return nos.map((n) => st.order[n] ?? 0);
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
  const ticket: Ticket = {
    ticketNo: no,
    title,
    stage: "PENDING",
    priority,
    projectId: st.activeProjectId,
    labels: [],
    targetRef: "refs/heads/main",
    clonePath: `local-run/clones/${no}`,
    execTokenTotal: 0,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  };
  set((st2) => ({
    tickets: [ticket, ...st2.tickets],
    chats: { ...st2.chats, [no]: [
      {
        kind: "system" as const,
        id: uid("sys"),
        tone: "info" as const,
        ts: Date.now(),
        text: t("ticket.sandboxReady"),
      },
    ] },
  }));
  selectTicket(no);
  showToast(t("ticket.createdToast", { no }));
  return no;
}

export function updateTicket(no: string, p: Partial<Ticket>) {
  set((st) => ({
    tickets: st.tickets.map((t) =>
      t.ticketNo === no ? { ...t, ...p, updatedAt: new Date().toISOString() } : t,
    ),
  }));
}

/** 更新状态筛选（持久化到 localStorage，跨会话保留）。 */
export function setVisibleStages(stages: Stage[]) {
  const unique = ALL_STAGES.filter((x) => stages.includes(x));
  patch({ visibleStages: unique });
  saveVisibleStages(unique);
}

/** 更新看板甬道（持久化到 localStorage；按甬道顺序去重，数量必须恰为固定 6 条，否则回退默认六甬道）。 */
export function setKanbanStages(stages: Stage[]) {
  const unique = KANBAN_STAGE_ORDER.filter((x) => stages.includes(x));
  const next = unique.length === KANBAN_LANE_COUNT ? unique : [...KANBAN_DEFAULT_STAGES];
  patch({ kanbanStages: next });
  saveKanbanStages(next);
}

/* ─── 工单相关弹窗（编辑 / 创建 / 终态流转 / 状态变更历史） ─── */

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

/** 重启理由弹窗（T-117）：no 为 null 时关闭。 */
export function openRestartDialog(no: string | null) {
  patch({ restartDialogFor: no });
}

/** 统一的状态变更记录弹窗（V19）：展示重启/强制已完成/取消历史；no 为 null 时关闭。 */
export function openStageChangesView(no: string | null) {
  patch({ stageChangesViewFor: no });
}

/** 拖拽/按钮触发「强制已完成 / 取消工单」确认弹窗（理由必填）。 */
export function openStageChangeConfirm(ticketNo: string, to: Stage) {
  patch({ stageChangeConfirm: { ticketNo, to } });
}

export function closeStageChangeConfirm() {
  patch({ stageChangeConfirm: null });
}

/** demo 模式下本地追加一条状态变更记录，弹窗口径与 live 后端一致。 */
export function appendStageChange(no: string, from: Stage, to: Stage, reason: string) {
  const kind: StageChangeRecord["kind"] =
    to === "DONE" ? "force_complete" : to === "CANCELLED" ? "cancel" : "restart";
  const record: StageChangeRecord = {
    round: (s().stageChanges[no]?.length ?? 0) + 1,
    fromStage: from,
    toStage: to,
    kind,
    reason,
    createdAt: new Date().toISOString(),
  };
  set((st) => ({ stageChanges: { ...st.stageChanges, [no]: [...(st.stageChanges[no] ?? []), record] } }));
  const t = s().tickets.find((x) => x.ticketNo === no);
  if (t) updateTicket(no, { stageChangeCount: (t.stageChangeCount ?? 0) + 1 });
}
