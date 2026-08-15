/**
 * 异步任务 API — 对齐后端 GET /api/tasks/{id}.
 *
 * SSE 走 GET /api/tasks/{id}/events (在 useSSE 内拼 path, 不在此文件).
 */
import { client } from './client';
import type { GateTask } from '@/types/task';

export async function getTask(id: string): Promise<GateTask> {
  const resp = await client.get<GateTask>(`/tasks/${encodeURIComponent(id)}`);
  return resp.data;
}

/** SSE 路径拼接 (供 useSSE 的 path 参数用). */
export function taskEventsPath(id: string): string {
  return `/api/tasks/${encodeURIComponent(id)}/events`;
}
