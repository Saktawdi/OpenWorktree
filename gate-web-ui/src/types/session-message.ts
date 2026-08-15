/**
 * 会话消息 — 对齐后端 gate-domain.session.* (后端文档 §5.2).
 *
 * 路线 B 结构化接口 (ADR-F5): chat 视图, 非 PTY.
 * usage 累计是 H1 执行侧度量补齐入口 (后端文档 §5.7).
 */
import type { SessionUsage } from './session';

export type Role = 'USER' | 'ASSISTANT' | 'TOOL' | 'ERROR';

export interface ToolCall {
  name: string;
  argumentsJson: string;
  resultJson: string | null;
}

/** 单条会话消息. content 字段在后端落 blob store, 前端拿到的就是已读出的字符串. */
export interface SessionMessage {
  id: string;
  sessionId: string;
  role: Role;
  content: string;
  toolCalls: ToolCall[];
  /** 本条消息的 usage; 非 LLM 消息为 null. */
  usage: SessionUsage | null;
  /** usage 解析失败标记 (后端文档 §5.9 D7): degraded=true 时 cumulativeUsage 不累加该条. */
  degraded: boolean;
  timestamp: string;
}

/** SSE 事件 (后端 AgentSessionPort.SessionEvent). */
export interface SessionEvent {
  sessionId: string;
  message: SessionMessage;
  /** "message" | "usage" | "tool_call" | "done" | "error" (后端文档 §5.1). */
  kind: string;
}
