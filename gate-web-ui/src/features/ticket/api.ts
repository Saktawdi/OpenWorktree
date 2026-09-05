/**
 * 工单域 API（ticket）：工单 CRUD、工作区 diff、状态变更历史。
 * 门禁流水线（presubmit/review/publish）见 features/gate；打开工单的编排见 flows.ts。
 */
import { api } from "@/net";
import { appStore, showToast } from "@/store";
import { parseUnifiedDiff, approxDiffBytes } from "@/shared/diff";
import type { DiffFile, Stage, StageChangeRecord, Ticket, Priority } from "@/shared/types";
import { setDiffs } from "./state";

interface RawTicket {
  ticket_no: string;
  title: string;
  stage: string;
  priority?: string | null;
  project_id?: string | null;
  labels?: string[] | null;
  description?: string | null;
  note?: string | null;
  target_ref?: string | null;
  clone_path?: string | null;
  agent_config_id?: string | null;
  exec_token_total?: number | null;
  restart_count?: number | null;
  stage_change_count?: number | null;
  is_super?: boolean | null;
  created_at: string;
  updated_at: string;
}

function mapTicket(t: RawTicket): Ticket {
  return {
    ticketNo: t.ticket_no,
    title: t.title || "(未命名工单)",
    stage: t.stage as unknown as Stage,
    priority: ((t.priority as Priority) ?? "P2") as Priority,
    projectId: t.project_id ?? "",
    labels: t.labels ?? [],
    description: t.description ?? undefined,
    note: t.note ?? undefined,
    targetRef: t.target_ref ?? "refs/heads/main",
    clonePath: t.clone_path ?? "",
    agentConfigId: t.agent_config_id ?? null,
    execTokenTotal: t.exec_token_total ?? 0,
    restartCount: t.restart_count ?? 0,
    stageChangeCount: t.stage_change_count ?? 0,
    isSuper: t.is_super ?? false,
    createdAt: t.created_at,
    updatedAt: t.updated_at,
  };
}

export async function loadTickets() {
  const data = await api<{ tickets: RawTicket[] }>("/api/tickets");
  appStore.setState({ tickets: data.tickets.map(mapTicket) });
}

export async function refreshTicket(no: string) {
  const t = await api<RawTicket>(`/api/tickets/${no}`);
  appStore.setState((st) => ({
    tickets: st.tickets.some((x) => x.ticketNo === no)
      ? st.tickets.map((x) => (x.ticketNo === no ? mapTicket(t) : x))
      : [mapTicket(t), ...st.tickets],
  }));
}

export async function createTicketLive(body: Record<string, unknown>): Promise<string | null> {
  try {
    const t = await api<RawTicket>("/api/tickets", { method: "POST", body: JSON.stringify(body) });
    return t.ticket_no ?? null;
  } catch (e) {
    showToast(`创建工单失败：${(e as Error).message}`);
    return null;
  }
}

/**
 * 拉取工单工作区 diff（/diff 端点 = clone 内 git diff HEAD + untracked）。
 * 除进工单时调用外，会话 done / 编辑类工具完成时也会调用——否则变更对比只在
 * 重新触发 selectTicketLive（切走再切回工单）后才更新。
 */
export async function loadTicketDiff(no: string) {
  try {
    const diffRes = await api<{ diff: string; eol_warning?: string }>(`/api/tickets/${no}/diff`);
    const files: DiffFile[] = diffRes.diff.trim() ? parseUnifiedDiff(diffRes.diff) : [];
    setDiffs(no, files, diffRes.eol_warning);
  } catch {
    setDiffs(no, []);
  }
}

export async function updateTicketLive(
  no: string,
  body: Record<string, unknown>,
): Promise<boolean> {
  try {
    await api(`/api/tickets/${no}`, { method: "PATCH", body: JSON.stringify(body) });
    await refreshTicket(no);
    return true;
  } catch (e) {
    showToast(`更新工单失败：${(e as Error).message}`);
    return false;
  }
}

/** 拉取工单状态变更记录（V19：重启/强制已完成/取消），落盘到 store；失败静默（旧后端无此接口）。 */
export async function loadStageChanges(no: string) {
  try {
    const data = await api<{
      stage_changes: Array<{
        round: number;
        from_stage: string;
        to_stage: string;
        kind: StageChangeRecord["kind"];
        reason: string;
        created_at: string | null;
      }>;
    }>(`/api/tickets/${no}/stage-changes`);
    const list: StageChangeRecord[] = (data.stage_changes ?? []).map((r) => ({
      round: r.round,
      fromStage: r.from_stage as Stage,
      toStage: r.to_stage as Stage,
      kind: r.kind,
      reason: r.reason,
      createdAt: r.created_at,
    }));
    appStore.setState((st) => ({ stageChanges: { ...st.stageChanges, [no]: list } }));
  } catch {
    /* 后端不支持或尚无记录时静默 */
  }
}

/** 重启终态工单（T-117）：填理由 → PATCH 转回 IN_PROGRESS，后端记录历史并开新轮次。 */
export async function restartTicketLive(no: string, reason: string): Promise<boolean> {
  const ok = await updateTicketLive(no, {
    stage: "IN_PROGRESS",
    restart_reason: reason,
  });
  if (ok) {
    await loadStageChanges(no).catch(() => {});
  }
  return ok;
}

/** 强制已完成（V19）：任意非终态拖到已完成，理由必填，跳过门禁收尾。 */
export async function completeTicketLive(no: string, reason: string): Promise<boolean> {
  const ok = await updateTicketLive(no, { stage: "DONE", reason });
  if (ok) {
    await loadStageChanges(no).catch(() => {});
  }
  return ok;
}

/** 取消工单（V19）：任意非终态取消，理由必填。 */
export async function cancelTicketLive(no: string, reason: string): Promise<boolean> {
  const ok = await updateTicketLive(no, { stage: "CANCELLED", reason });
  if (ok) {
    await loadStageChanges(no).catch(() => {});
  }
  return ok;
}

export function liveDiffBytes(no: string): number {
  const st = appStore.getState();
  return approxDiffBytes(st.diffs[no] ?? []);
}
