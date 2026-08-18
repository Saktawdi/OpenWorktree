/**
 * 工单 API — 全局聚合接口与项目级 /api/projects/{projectId}/tickets 接口共用同一映射层。
 *
 * 看板与详情页共用这组 API, 字段在边界处统一为前端 domain shape.
 */
import { client } from './client';
import type { CreateTicketRequest, ExecTokenSource, Ticket, TicketUpdateRequest } from '@/types/ticket';
import type { TicketStage } from '@/types/stage';

type RawRecord = Record<string, unknown>;

function recordOf(value: unknown): RawRecord {
  return value && typeof value === 'object' ? value as RawRecord : {};
}

function stringOf(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : value == null ? fallback : String(value);
}

function nullableStringOf(value: unknown): string | null {
  const result = stringOf(value).trim();
  return result || null;
}

function nullableNumberOf(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && value.trim()) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}

function stringArrayOf(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.map((item) => stringOf(item).trim()).filter(Boolean);
}

function stageOf(value: unknown): TicketStage {
  const stage = stringOf(value).toUpperCase() as TicketStage;
  return [
    'PENDING',
    'IN_PROGRESS',
    'PRESUBMITTED',
    'IN_REVIEW',
    'REJECTED',
    'READY_TO_PUBLISH',
    'NEEDS_HUMAN',
    'DONE',
    'CANCELLED',
  ].includes(stage)
    ? stage
    : 'PENDING';
}

function tokenSourceOf(value: unknown): ExecTokenSource | null {
  const source = stringOf(value) as ExecTokenSource;
  return ['agent_cli', 'manual', 'unavailable'].includes(source) ? source : null;
}

/** Maps the backend snake_case ticket projection to the frontend domain shape. */
export function normalizeTicket(value: unknown): Ticket {
  const raw = recordOf(value);
  const clonePath = nullableStringOf(raw.clone_path ?? raw.clonePath);
  const priority = raw.priority === 'P0' || raw.priority === 'P1' || raw.priority === 'P2' || raw.priority === 'P3'
    ? raw.priority
    : null;
  return {
    no: stringOf(raw.ticket_no ?? raw.no),
    title: stringOf(raw.title),
    description: nullableStringOf(raw.description),
    note: nullableStringOf(raw.note ?? raw.remark),
    stage: stageOf(raw.stage),
    targetRef: stringOf(raw.target_ref ?? raw.targetRef),
    reviewRound: nullableNumberOf(raw.review_round ?? raw.reviewRound),
    treeHash: nullableStringOf(raw.tree_hash ?? raw.treeHash),
    baseCommit: nullableStringOf(raw.base_commit ?? raw.baseCommit),
    execTokenTotal: nullableNumberOf(raw.exec_token_total ?? raw.execTokenTotal),
    execTokenSource: tokenSourceOf(raw.exec_token_source ?? raw.execTokenSource),
    agentConfigId: nullableStringOf(raw.agent_config_id ?? raw.agentConfigId),
    projectId: nullableStringOf(raw.project_id ?? raw.projectId),
    project: nullableStringOf(raw.project ?? raw.projectName),
    labels: stringArrayOf(raw.labels ?? raw.tags),
    createdAt: stringOf(raw.created_at ?? raw.createdAt),
    updatedAt: stringOf(raw.updated_at ?? raw.updatedAt),
    ...(clonePath ? { clonePath } : {}),
    ...(priority ? { priority } : {}),
  };
}

function ticketRows(value: unknown): unknown[] {
  if (Array.isArray(value)) return value;
  const raw = recordOf(value);
  return Array.isArray(raw.tickets) ? raw.tickets : [];
}

export async function listTickets(projectId?: string): Promise<Ticket[]> {
  const endpoint = projectId
    ? `/projects/${encodeURIComponent(projectId)}/tickets`
    : '/tickets';
  const resp = await client.get<unknown>(endpoint);
  return ticketRows(resp.data).map(normalizeTicket).filter((ticket) => ticket.no);
}

export async function getTicket(no: string, projectId?: string): Promise<Ticket> {
  const endpoint = projectId
    ? `/projects/${encodeURIComponent(projectId)}/tickets/${encodeURIComponent(no)}`
    : `/tickets/${encodeURIComponent(no)}`;
  const resp = await client.get<unknown>(endpoint);
  return normalizeTicket(resp.data);
}

export async function createTicket(req: CreateTicketRequest): Promise<Ticket> {
  // ticket_no 由前端生成, target_ref 使用服务端配置, editable content is optional.
  const payload = {
    ticket_no: req.ticketNo,
    title: req.title,
    ...(req.description !== undefined ? { description: req.description } : {}),
    ...(req.note !== undefined ? { note: req.note } : {}),
    ...(req.labels !== undefined ? { labels: req.labels } : {}),
    ...(req.agentConfigId ? { agent_config_id: req.agentConfigId } : {}),
    ...(req.priority ? { priority: req.priority } : {}),
    ...(req.projectId ? { project_id: req.projectId } : {}),
  };
  const endpoint = req.projectId
    ? `/projects/${encodeURIComponent(req.projectId)}/tickets`
    : '/tickets';
  const resp = await client.post<unknown>(endpoint, payload);
  return normalizeTicket(resp.data);
}

export async function updateTicket(ticketNo: string, request: TicketUpdateRequest, projectId?: string): Promise<Ticket> {
  const payload: Record<string, unknown> = {};
  if (request.title !== undefined) payload.title = request.title;
  if (request.description !== undefined) payload.description = request.description;
  if (request.note !== undefined) payload.note = request.note;
  if (request.labels !== undefined) payload.labels = request.labels;
  if (request.priority !== undefined) payload.priority = request.priority;
  if (request.stage !== undefined) payload.stage = request.stage;
  const endpoint = projectId
    ? `/projects/${encodeURIComponent(projectId)}/tickets/${encodeURIComponent(ticketNo)}`
    : `/tickets/${encodeURIComponent(ticketNo)}`;
  const resp = await client.patch<unknown>(endpoint, payload);
  return normalizeTicket(resp.data);
}

export interface WorkingDiffResult {
  ticketNo: string;
  source: 'working' | string;
  baseCommit: string | null;
  diff: string;
}

export async function getWorkingDiff(ticketNo: string): Promise<WorkingDiffResult> {
  const resp = await client.get<unknown>(`/tickets/${encodeURIComponent(ticketNo)}/diff`);
  const raw = recordOf(resp.data);
  return {
    ticketNo: stringOf(raw.ticket_no ?? raw.ticketNo),
    source: stringOf(raw.source, 'working'),
    baseCommit: nullableStringOf(raw.base_commit ?? raw.baseCommit),
    diff: stringOf(raw.diff),
  };
}

export interface PresubmitResult {
  ticketNo: string;
  reviewRound: number;
  treeHash: string;
  baseCommit: string;
  targetRef: string;
  diffBytes: number;
  changedPaths: string[];
  integrityWarnings: string[];
}

export interface PresubmitDiffResult {
  ticketNo: string;
  reviewRound: number;
  treeHash: string;
  baseCommit: string;
  diff: string;
}

export interface ReviewResultView {
  ticketNo: string;
  reviewRound: number;
  verdict: string;
  engineId: string;
  coveredOk: boolean;
  degraded: boolean;
  findings: string;
}

function normalizePresubmit(value: unknown): PresubmitResult {
  const raw = recordOf(value);
  return {
    ticketNo: stringOf(raw.ticket_no ?? raw.ticketNo),
    reviewRound: nullableNumberOf(raw.review_round ?? raw.reviewRound) ?? 0,
    treeHash: stringOf(raw.tree_hash ?? raw.treeHash),
    baseCommit: stringOf(raw.base_commit ?? raw.baseCommit),
    targetRef: stringOf(raw.target_ref ?? raw.targetRef),
    diffBytes: nullableNumberOf(raw.diff_bytes ?? raw.diffBytes) ?? 0,
    changedPaths: (() => {
      const changed = raw.changed_paths ?? raw.changedPaths;
      return Array.isArray(changed) ? changed.map(String) : [];
    })(),
    integrityWarnings: (() => {
      const warnings = raw.integrity_warnings ?? raw.integrityWarnings;
      return Array.isArray(warnings) ? warnings.map(String) : [];
    })(),
  };
}

function normalizeDiff(value: unknown): PresubmitDiffResult {
  const raw = recordOf(value);
  return {
    ticketNo: stringOf(raw.ticket_no ?? raw.ticketNo),
    reviewRound: nullableNumberOf(raw.review_round ?? raw.reviewRound) ?? 0,
    treeHash: stringOf(raw.tree_hash ?? raw.treeHash),
    baseCommit: stringOf(raw.base_commit ?? raw.baseCommit),
    diff: stringOf(raw.diff),
  };
}

function normalizeReviewResult(value: unknown): ReviewResultView {
  const raw = recordOf(value);
  return {
    ticketNo: stringOf(raw.ticket_no ?? raw.ticketNo),
    reviewRound: nullableNumberOf(raw.review_round ?? raw.reviewRound) ?? 0,
    verdict: stringOf(raw.verdict),
    engineId: stringOf(raw.engine_id ?? raw.engineId),
    coveredOk: raw.covered_ok === true || raw.covered_ok === 'true' || raw.covered_ok === 1,
    degraded: raw.degraded === true || raw.degraded === 'true' || raw.degraded === 1,
    findings: stringOf(raw.findings),
  };
}

/** POST /api/tickets/{no}/presubmit — 同步固化 tree 锚点. */
export async function presubmitTicket(ticketNo: string): Promise<PresubmitResult> {
  const resp = await client.post<unknown>(`/tickets/${encodeURIComponent(ticketNo)}/presubmit`, {});
  return normalizePresubmit(resp.data);
}

/** GET /api/tickets/{no}/presubmit/{round}/diff — 读取指定轮次 diff. */
export async function getPresubmitDiff(ticketNo: string, round: number): Promise<PresubmitDiffResult> {
  const resp = await client.get<unknown>(
    `/tickets/${encodeURIComponent(ticketNo)}/presubmit/${encodeURIComponent(String(round))}/diff`,
  );
  return normalizeDiff(resp.data);
}

/** GET /api/tickets/{no}/review-result — 读取最近审核结果 (reject feedback). */
export async function getReviewResult(ticketNo: string): Promise<ReviewResultView> {
  const resp = await client.get<unknown>(`/tickets/${encodeURIComponent(ticketNo)}/review-result`);
  return normalizeReviewResult(resp.data);
}
