/**
 * 工单 API — 对齐后端 GET /api/tickets, GET /api/tickets/{no}, POST /api/tickets.
 *
 * 看板与详情页共用这组 API, 字段在边界处统一为前端 domain shape.
 */
import { client } from './client';
import type { CreateTicketRequest, ExecTokenSource, Ticket } from '@/types/ticket';
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
    stage: stageOf(raw.stage),
    targetRef: stringOf(raw.target_ref ?? raw.targetRef),
    reviewRound: nullableNumberOf(raw.review_round ?? raw.reviewRound),
    treeHash: nullableStringOf(raw.tree_hash ?? raw.treeHash),
    baseCommit: nullableStringOf(raw.base_commit ?? raw.baseCommit),
    execTokenTotal: nullableNumberOf(raw.exec_token_total ?? raw.execTokenTotal),
    execTokenSource: tokenSourceOf(raw.exec_token_source ?? raw.execTokenSource),
    agentConfigId: nullableStringOf(raw.agent_config_id ?? raw.agentConfigId),
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

export async function listTickets(): Promise<Ticket[]> {
  const resp = await client.get<unknown>('/tickets');
  return ticketRows(resp.data).map(normalizeTicket).filter((ticket) => ticket.no);
}

export async function getTicket(no: string): Promise<Ticket> {
  const resp = await client.get<unknown>(`/tickets/${encodeURIComponent(no)}`);
  return normalizeTicket(resp.data);
}

export async function createTicket(req: CreateTicketRequest): Promise<Ticket> {
  const payload = {
    ticket_no: req.ticketNo,
    title: req.title,
    ...(req.description ? { description: req.description } : {}),
    ...(req.targetRef ? { target_ref: req.targetRef } : {}),
    ...(req.agentConfigId ? { agent_config_id: req.agentConfigId } : {}),
  };
  const resp = await client.post<unknown>('/tickets', payload);
  return normalizeTicket(resp.data);
}
