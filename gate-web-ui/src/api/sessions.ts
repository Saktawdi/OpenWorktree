/**
 * 会话 API — 对齐后端会话路由 (后端文档 §4.1 会话路由约定).
 *
 * 路由:
 *   - 列表/建会话: /api/tickets/{no}/sessions (强制 ticketNo 绑定)
 *   - 单会话操作: 扁平 /api/sessions/{sid}/{messages|events|abort}
 *   - 历史回放: 复用 GET /api/sessions/{sid}/messages (不单设 /history)
 *   - AgentConfig 历史: GET /api/agent-configs/{id}/sessions
 *
 * 所有响应在 API 边界统一映射为前端 camelCase domain shape.
 */
import { client } from './client';
import type { Session, SessionMessage, SessionUsage } from '@/types';
import type { Role, ToolCall } from '@/types/session-message';

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

function nullableNumber(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && value.trim() && Number.isFinite(Number(value))) return Number(value);
  return null;
}

function booleanOf(value: unknown): boolean {
  if (typeof value === 'boolean') return value;
  if (typeof value === 'string') return value.trim().toLowerCase() === 'true';
  if (typeof value === 'number') return value !== 0;
  return false;
}

function usageOf(value: unknown): SessionUsage | null {
  const raw = recordOf(value);
  if (!raw || Object.keys(raw).length === 0) return null;
  return {
    promptTokens: nullableNumber(raw.prompt_tokens ?? raw.promptTokens),
    completionTokens: nullableNumber(raw.completion_tokens ?? raw.completionTokens),
    totalTokens: nullableNumber(raw.total_tokens ?? raw.totalTokens),
  };
}

function toolCallOf(value: unknown): ToolCall {
  const raw = recordOf(value);
  return {
    name: stringOf(raw.name),
    argumentsJson: stringOf(raw.arguments_json ?? raw.argumentsJson),
    resultJson: nullableStringOf(raw.result_json ?? raw.resultJson),
  };
}

/** Maps backend snake_case session JSON to the frontend Session domain shape. */
export function normalizeSession(value: unknown): Session {
  const raw = recordOf(value);
  const status = stringOf(raw.status).toUpperCase();
  const cli = stringOf(raw.cli).toUpperCase();
  return {
    id: stringOf(raw.id),
    ticketNo: stringOf(raw.ticket_no ?? raw.ticketNo),
    agentConfigId: stringOf(raw.agent_config_id ?? raw.agentConfigId),
    cli: cli === 'OPENCODE' || cli === 'CLAUDE' ? cli as Session['cli'] : 'CLAUDE',
    status: status === 'ACTIVE' || status === 'ABORTED' || status === 'CLOSED'
      ? status as Session['status']
      : 'CLOSED',
    cliSessionId: nullableStringOf(raw.cli_session_id ?? raw.cliSessionId),
    clonePath: stringOf(raw.clone_path ?? raw.clonePath),
    allocatedPort: nullableNumber(raw.allocated_port ?? raw.allocatedPort),
    startedAt: stringOf(raw.started_at ?? raw.startedAt),
    finishedAt: nullableStringOf(raw.finished_at ?? raw.finishedAt),
    cumulativeUsage: usageOf(raw.cumulative_usage ?? raw.cumulativeUsage),
  };
}

/** Maps backend snake_case session message JSON to the frontend SessionMessage shape. */
export function normalizeSessionMessage(value: unknown): SessionMessage {
  const raw = recordOf(value);
  const role = stringOf(raw.role).toUpperCase() as Role;
  const callsRaw = raw.tool_calls ?? raw.toolCalls;
  const calls = Array.isArray(callsRaw) ? callsRaw.map(toolCallOf) : [];
  return {
    id: stringOf(raw.id),
    sessionId: stringOf(raw.session_id ?? raw.sessionId),
    role: ['USER', 'ASSISTANT', 'TOOL', 'ERROR'].includes(role) ? role : 'ERROR',
    content: stringOf(raw.content),
    toolCalls: calls,
    usage: usageOf(raw.usage),
    degraded: booleanOf(raw.degraded),
    timestamp: stringOf(raw.timestamp),
  };
}

function rows(value: unknown, key: string): unknown[] {
  if (Array.isArray(value)) return value;
  const raw = recordOf(value);
  return Array.isArray(raw[key]) ? raw[key] : [];
}

export async function listTicketSessions(ticketNo: string): Promise<Session[]> {
  const resp = await client.get<unknown>(`/tickets/${encodeURIComponent(ticketNo)}/sessions`);
  return rows(resp.data, 'sessions').map(normalizeSession).filter((session) => session.id);
}

export interface StartSessionRequest {
  agentConfigId: string;
  initialPrompt?: string;
}

/** POST /api/tickets/{no}/sessions — 在工单下建会话 (唯一入口). */
export async function startSession(
  ticketNo: string,
  req: StartSessionRequest,
): Promise<Session> {
  const resp = await client.post<unknown>(
    `/tickets/${encodeURIComponent(ticketNo)}/sessions`,
    {
      agent_config_id: req.agentConfigId,
      ...(req.initialPrompt ? { initial_prompt: req.initialPrompt } : {}),
    },
  );
  return normalizeSession(resp.data);
}

export async function getSession(sid: string): Promise<Session> {
  const resp = await client.get<unknown>(`/sessions/${encodeURIComponent(sid)}`);
  return normalizeSession(resp.data);
}

export async function getHistory(sid: string): Promise<SessionMessage[]> {
  const resp = await client.get<unknown>(
    `/sessions/${encodeURIComponent(sid)}/messages`,
  );
  return rows(resp.data, 'messages').map(normalizeSessionMessage).filter((message) => message.id);
}

/** 异步任务 id; SSE 流走 /api/sessions/{sid}/events. */
export async function sendMessage(sid: string, message: string): Promise<{ taskId: string }> {
  const resp = await client.post<{ task_id: string }>(
    `/sessions/${encodeURIComponent(sid)}/messages`,
    { message },
  );
  return { taskId: resp.data.task_id };
}

export async function abortSession(sid: string): Promise<void> {
  await client.post(`/sessions/${encodeURIComponent(sid)}/abort`, {});
}

export async function listAgentConfigSessions(agentConfigId: string): Promise<Session[]> {
  const resp = await client.get<unknown>(
    `/agent-configs/${encodeURIComponent(agentConfigId)}/sessions`,
  );
  return rows(resp.data, 'sessions').map(normalizeSession).filter((session) => session.id);
}

/** 会话 SSE 路径 (供 useSSE 的 path 参数用). */
export function sessionEventsPath(sid: string): string {
  return `/api/sessions/${encodeURIComponent(sid)}/events`;
}
