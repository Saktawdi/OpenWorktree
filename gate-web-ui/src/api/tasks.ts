/**
 * 异步任务 API — 对齐后端 GET /api/tasks/{id}.
 *
 * SSE 走 GET /api/tasks/{id}/events (在 useSSE 内拼 path, 不在此文件).
 */
import { client } from './client';
import type { GateTask, GateTaskEvent, TaskProgressPayload } from '@/types/task';

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

/** Maps backend snake_case GateTask JSON to frontend shape. */
export function normalizeTask(value: unknown): GateTask {
  const raw = recordOf(value);
  const status = stringOf(raw.status).toUpperCase();
  return {
    id: stringOf(raw.id),
    type: (stringOf(raw.type) === 'publish' || stringOf(raw.type) === 'session-send' ? stringOf(raw.type) : 'review') as GateTask['type'],
    ticketNo: nullableStringOf(raw.ticket_no ?? raw.ticketNo),
    sessionId: nullableStringOf(raw.session_id ?? raw.sessionId),
    status: status === 'SUCCEEDED' || status === 'FAILED' ? status.toLowerCase() as GateTask['status'] : 'running',
    startedAt: stringOf(raw.started_at ?? raw.startedAt),
    finishedAt: nullableStringOf(raw.finished_at ?? raw.finishedAt),
    resultJson: nullableStringOf(raw.result_json ?? raw.resultJson),
    errorJson: nullableStringOf(raw.error_json ?? raw.errorJson),
  };
}

export async function getTask(id: string): Promise<GateTask> {
  const resp = await client.get<unknown>(`/tasks/${encodeURIComponent(id)}`);
  return normalizeTask(resp.data);
}

/** SSE 路径拼接 (供 useSSE 的 path 参数用). */
export function taskEventsPath(id: string): string {
  return `/api/tasks/${encodeURIComponent(id)}/events`;
}

/** 解析后端 task SSE 单条 data (完整 task snapshot JSON). */
export function parseTaskEventData(data: string): GateTaskEvent {
  let raw: RawRecord = {};
  try {
    raw = recordOf(JSON.parse(data));
  } catch {
    // 非 JSON 时保留原文, 让调用方按字符串处理.
  }
  return {
    taskId: stringOf(raw.task_id ?? raw.id),
    kind: stringOf(raw.kind, 'message'),
    payloadJson: stringOf(raw.payload_json ?? raw.result_json ?? data),
    at: stringOf(raw.at ?? raw.started_at ?? new Date().toISOString()),
  };
}

/** 从 task SSE data 中提取进度快照 (progress 事件). */
export function extractTaskProgress(data: string): TaskProgressPayload {
  let raw: RawRecord = {};
  try {
    const parsed: unknown = JSON.parse(data);
    const root = recordOf(parsed);
    // 后端 progress 的 percent/label 放在 result_json 字符串里.
    const inner = root.result_json ? recordOf(JSON.parse(stringOf(root.result_json))) : root;
    raw = inner;
  } catch {
    // 保留空对象.
  }
  const result: TaskProgressPayload = {};
  const percent = nullableNumber(raw.percent);
  if (percent != null) result.percent = percent;
  if (typeof raw.label === 'string') result.label = raw.label;
  return result;
}
