/**
 * 会话 API — 对齐后端会话路由 (后端文档 §4.1 会话路由约定).
 *
 * 路由:
 *   - 列表/建会话: /api/tickets/{no}/sessions (强制 ticketNo 绑定)
 *   - 单会话操作: 扁平 /api/sessions/{sid}/{messages|events|abort}
 *   - 历史回放: 复用 GET /api/sessions/{sid}/messages (不单设 /history)
 *   - AgentConfig 历史: GET /api/agent-configs/{id}/sessions
 *
 * S0 阶段: 占位, S3 才接入会话页.
 */
import { client } from './client';
import type { Session, SessionMessage } from '@/types';

export async function listTicketSessions(ticketNo: string): Promise<Session[]> {
  const resp = await client.get<Session[]>(`/tickets/${encodeURIComponent(ticketNo)}/sessions`);
  return resp.data;
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
  const resp = await client.post<Session>(
    `/tickets/${encodeURIComponent(ticketNo)}/sessions`,
    req,
  );
  return resp.data;
}

export async function getSession(sid: string): Promise<Session> {
  const resp = await client.get<Session>(`/sessions/${encodeURIComponent(sid)}`);
  return resp.data;
}

export async function getHistory(sid: string): Promise<SessionMessage[]> {
  const resp = await client.get<SessionMessage[]>(
    `/sessions/${encodeURIComponent(sid)}/messages`,
  );
  return resp.data;
}

/** 异步任务 id; SSE 流走 /api/sessions/{sid}/events. */
export async function sendMessage(sid: string, message: string): Promise<{ taskId: string }> {
  const resp = await client.post<{ taskId: string }>(
    `/sessions/${encodeURIComponent(sid)}/messages`,
    { message },
  );
  return resp.data;
}

export async function abortSession(sid: string): Promise<void> {
  await client.post(`/sessions/${encodeURIComponent(sid)}/abort`, {});
}

export async function listAgentConfigSessions(agentConfigId: string): Promise<Session[]> {
  const resp = await client.get<Session[]>(
    `/agent-configs/${encodeURIComponent(agentConfigId)}/sessions`,
  );
  return resp.data;
}
