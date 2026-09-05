/**
 * 门禁域状态（gate）：快照/判决/任务进度/发布结果/审查错误的写入，
 * 以及右侧门禁面板的折叠偏好。
 */
import { appStore, type AppState, type GateSections } from "@/store";
import { saveGatePanelCollapsed, saveGateSections } from "@/store/prefs";
import type { Finding, PublishOutcome, Snapshot, TaskProgress, VerdictInfo } from "@/shared/types";

const set = appStore.setState;
const s = () => appStore.getState();

function patch(partial: Partial<AppState>) {
  set((st) => ({ ...st, ...partial }));
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

/** 记录/清除一次审查任务的失败原因（null = 清除）。 */
export function setReviewError(no: string, message: string | null) {
  set((st) => {
    const next = { ...st.reviewErrors };
    if (message === null) delete next[no];
    else next[no] = message;
    return { reviewErrors: next };
  });
}

/* ─── 右侧门禁面板折叠偏好（localStorage 持久化） ─── */

/** 右侧面板整栏收起/展开（持久化，跨会话保留）。 */
export function setGatePanelCollapsed(collapsed: boolean) {
  patch({ gatePanelCollapsed: collapsed });
  saveGatePanelCollapsed(collapsed);
}

/** 展开/收起右侧面板的某一段（持久化，跨会话保留）。 */
export function setGateSection(key: keyof GateSections, expanded: boolean) {
  const gateSections = { ...s().gateSections, [key]: expanded };
  patch({ gateSections });
  saveGateSections(gateSections);
}

/**
 * 进入预提审（快照已锁定、等待审查）时自动收叠「工单信息」与「会话列表」，
 * 把纵向空间让给门禁流水线的快照/判决卡片；用户手动展开后不会被再次压下
 * （该动作只在 stage 变为 PRESUBMITTED 时触发一次）。
 */
export function collapseGateSectionsForPresubmit() {
  const cur = s().gateSections;
  if (!cur.info && !cur.sessions) return;
  const gateSections = { ...cur, info: false, sessions: false };
  patch({ gateSections });
  saveGateSections(gateSections);
}
