/**
 * review / publish API — 对齐后端 POST /api/tickets/{no}/review|publish (后端文档 §4.1).
 *
 * 两端点均返回 202 + { task_id }, SSE 跟进度 (前端文档 §4.4).
 */
import { client } from './client';

export interface AsyncTaskAccepted {
  /** 后端 202 响应体: { task_id: "uuid" }. */
  taskId: string;
}

export interface StartReviewRequest {
  /** 指定审核轮次; 缺省使用最新预提审. */
  round?: number;
  /** 未配置引擎时人工审核放行. */
  humanPass?: boolean;
  note?: string;
}

export interface StartPublishRequest {
  round?: number;
}

function acceptedOf(value: unknown): AsyncTaskAccepted {
  const raw = value && typeof value === 'object' ? value as Record<string, unknown> : {};
  return { taskId: String(raw.task_id ?? raw.taskId ?? '') };
}

export async function startReview(ticketNo: string, req: StartReviewRequest = {}): Promise<AsyncTaskAccepted> {
  const resp = await client.post<unknown>(
    `/tickets/${encodeURIComponent(ticketNo)}/review`,
    {
      ...(req.round != null ? { round: req.round } : {}),
      ...(req.humanPass != null ? { human_pass: req.humanPass } : {}),
      ...(req.note ? { note: req.note } : {}),
    },
  );
  return acceptedOf(resp.data);
}

export async function startPublish(ticketNo: string, req: StartPublishRequest = {}): Promise<AsyncTaskAccepted> {
  const resp = await client.post<unknown>(
    `/tickets/${encodeURIComponent(ticketNo)}/publish`,
    {
      ...(req.round != null ? { round: req.round } : {}),
    },
  );
  return acceptedOf(resp.data);
}
