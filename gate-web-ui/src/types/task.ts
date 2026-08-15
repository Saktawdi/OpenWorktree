/**
 * 异步任务 — 对齐后端 gate-domain.task.GateTask (后端文档 §4.3).
 *
 * 后端是契约方: review / publish / session-send 三类长操作统一抽象为 GateTask.
 * 前端 taskStore 维护任务状态机 (idle/running/succeeded/failed), TaskProgress 复用.
 *
 * 注意:
 *   - 后端 status 仅 RUNNING / SUCCEEDED / FAILED; 前端 idle 是未发起或重置后的初始态.
 *   - SSE 事件流 (GateTaskEvent.kind) 含 progress / done / error 等; 见 TaskEvent.
 */
export type GateTaskType = 'review' | 'publish' | 'session-send';

/** 后端 GateTaskStatus = RUNNING | SUCCEEDED | FAILED; idle 为前端补的初始态. */
export type TaskStatus = 'idle' | 'running' | 'succeeded' | 'failed';

/** 对齐后端 GateTask (record). */
export interface GateTask {
  id: string;
  type: GateTaskType;
  ticketNo: string | null;
  sessionId: string | null;
  status: Exclude<TaskStatus, 'idle'>;
  startedAt: string;
  finishedAt: string | null;
  resultJson: string | null;
  errorJson: string | null;
}

/** 对齐后端 GateTaskEvent (record). */
export interface GateTaskEvent {
  taskId: string;
  /** "progress" | "done" | "error" 等; payloadJson 决定细节. */
  kind: string;
  payloadJson: string;
  at: string;
}

/** SSE 事件 payload (progress kind) 的前端约定结构, 用于审核台 / 发布台进度条. */
export interface TaskProgressPayload {
  /** 0..100, 缺省视为不确定. */
  percent?: number;
  /** 人可读的阶段描述. */
  label?: string;
}
