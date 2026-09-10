/**
 * 工单域 API（ticket）：工单 CRUD、工作区 diff、状态变更历史。
 * 门禁流水线（presubmit/review/publish）见 features/gate；打开工单的编排见 flows.ts。
 */
import { t as i18nT } from "@/i18n";
import { api } from "@/net";
import { appStore, showToast } from "@/store";
import { parseUnifiedDiff, diffSig } from "@/shared/diff";
import type { DiffFile, Stage, StageChangeRecord, Ticket, Priority } from "@/shared/types";
import { setDiffs, setDiffContent } from "./state";

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
  review_round?: number | null;
  is_super?: boolean | null;
  created_at: string;
  updated_at: string;
}

function mapTicket(t: RawTicket): Ticket {
  return {
    ticketNo: t.ticket_no,
    title: t.title || i18nT("ticket.untitled"),
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
    round: Math.max(1, t.review_round ?? 1),
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
    showToast(i18nT("ticketapi.createFailed", { err: (e as Error).message }));
    return null;
  }
}

/**
 * 拉取工单工作区变更列表（/diff/list 端点 = clone 内 git diff + untracked 的元数据，
 * 只含 path/状态/±行数，不含 diff 内容）。除进工单时调用外，会话 done / 编辑类工具
 * 完成时也会调用——否则变更对比只在重新触发 selectTicketLive（切走再切回工单）后才更新。
 * 文件内容按需：DiffView 点开文件条时经 loadDiffFile 单独拉取。
 */
export async function loadTicketDiff(no: string) {
  try {
    const res = await api<{
      files: Array<{ path: string; status: DiffFile["status"]; additions: number; deletions: number }>;
      eol_warning?: string;
    }>(`/api/tickets/${no}/diff/list`);
    const files: DiffFile[] = (res.files ?? []).map((f) => ({
      path: f.path,
      status: f.status,
      additions: f.additions,
      deletions: f.deletions,
      hunks: [],
    }));
    setDiffs(no, files, res.eol_warning);
  } catch {
    setDiffs(no, []);
  }
}

/**
 * 按需拉取单个文件的 diff 内容（/diff/file 端点），写入内容缓存。
 * 列表刷新后指纹过期 / 首次展开时由 DiffView 调用；仓库存在行尾噪声告警时带 eol=1，
 * 让后端对该文件同样做 --ignore-cr-at-eol 归一化。失败返回 null（DiffView 显示重试）。
 */
export async function loadDiffFile(no: string, path: string): Promise<DiffFile | null> {
  const st = appStore.getState();
  const meta = st.diffs[no]?.find((f) => f.path === path);
  if (!meta) {
    return null;
  }
  const eol = st.diffWarnings[no] ? "&eol=1" : "";
  try {
    const res = await api<{ diff: string }>(
      `/api/tickets/${no}/diff/file?path=${encodeURIComponent(path)}${eol}`,
    );
    const parsed = parseUnifiedDiff(res.diff ?? "");
    const file = parsed[0] ?? { ...meta, hunks: [] };
    setDiffContent(no, file, diffSig(meta));
    return file;
  } catch {
    return null;
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
    showToast(i18nT("ticketapi.updateFailed", { err: (e as Error).message }));
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

/** 变更体量粗估（元数据级：路径 + 增删行数 × 均值），无需保有 diff 全文。 */
export function liveDiffBytes(no: string): number {
  const st = appStore.getState();
  let n = 0;
  for (const f of st.diffs[no] ?? []) {
    n += f.path.length + 40 + (f.additions + f.deletions) * 40;
  }
  return n;
}
