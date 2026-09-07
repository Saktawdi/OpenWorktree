/**
 * 门禁域状态（gate）：快照/判决/任务进度/发布结果/审查错误的写入，
 * 以及右侧门禁面板的折叠偏好。
 */
import { appStore, type AppState, type GateSections } from "@/store";
import { saveGatePanelCollapsed, saveGateSections } from "@/store/prefs";
import type { EvidenceBundle, Finding, PublishOutcome, Snapshot, TaskProgress, VerdictInfo } from "@/shared/types";

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

/** 写入/清除证据链聚合数据（null = 未加载或加载失败）。 */
export function setEvidence(no: string, bundle: EvidenceBundle | null) {
  set((st) => ({ evidence: { ...st.evidence, [no]: bundle } }));
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

/* ─── 审查结果提醒（工单列表「已审查/驳回」徽标的数据源） ─── */

/**
 * 审查判决落盘时登记：用户正查看该工单则跳过（结果就在面板/会话流里，徽标
 * 是给没盯着这个工单的人的提醒）；打开工单或开启新一轮（预提审/重新审查）时清除。
 */
export function markReviewEnded(no: string, verdict: "PASS" | "REJECT" | "REQUIRES_HUMAN") {
  if (s().selectedNo === no && s().view === "workbench") return;
  set((st) => ({ reviewEnded: { ...st.reviewEnded, [no]: { verdict, at: Date.now() } } }));
}

/** 清除审查结果提醒（打开工单即视为已读）。 */
export function clearReviewEnded(no: string) {
  set((st) => {
    if (!st.reviewEnded[no]) return st;
    const reviewEnded = { ...st.reviewEnded };
    delete reviewEnded[no];
    return { reviewEnded };
  });
}
