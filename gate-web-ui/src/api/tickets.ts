/**
 * 工单 API — 对齐后端 GET /api/tickets, GET /api/tickets/{no}, POST /api/tickets.
 *
 * S0 阶段: 仅占位, S1 才接入看板列表.
 */
import { client } from './client';
import type { CreateTicketRequest, Ticket } from '@/types/ticket';

export async function listTickets(): Promise<Ticket[]> {
  const resp = await client.get<Ticket[]>('/tickets');
  return resp.data;
}

export async function getTicket(no: string): Promise<Ticket> {
  const resp = await client.get<Ticket>(`/tickets/${encodeURIComponent(no)}`);
  return resp.data;
}

export async function createTicket(req: CreateTicketRequest): Promise<Ticket> {
  const resp = await client.post<Ticket>('/tickets', req);
  return resp.data;
}
