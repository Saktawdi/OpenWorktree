/**
 * review / publish API — 对齐后端 POST /api/tickets/{no}/review|publish (后端文档 §4.1).
 *
 * 两端点均返回 202 + { task_id }, SSE 跟进度 (前端文档 §4.4).
 * S0 阶段: 占位, S2 才接入审核台.
 */
import { client } from './client';

export interface AsyncTaskAccepted {
  /** 后端 202 响应体: { task_id: "uuid" }. */
  taskId: string;
}

export async function startReview(ticketNo: string): Promise<AsyncTaskAccepted> {
  const resp = await client.post<AsyncTaskAccepted>(
    `/tickets/${encodeURIComponent(ticketNo)}/review`,
    {},
  );
  return resp.data;
}

export async function startPublish(ticketNo: string): Promise<AsyncTaskAccepted> {
  const resp = await client.post<AsyncTaskAccepted>(
    `/tickets/${encodeURIComponent(ticketNo)}/publish`,
    {},
  );
  return resp.data;
}
