/**
 * 会话消息 — 对齐后端 gate-domain.session.* 与 Multica 现代 Agent Transcript 架构.
 *
 * 支持：
 * 1. 思考链 (Reasoning / Thinking)
 * 2. CLI 与工具调用 (Tool Call Executions / Stdin / Stdout / ExitCode / 耗时)
 * 3. 步骤流 (Agent Steps)
 * 4. 流式消息状态 (Streaming, Interrupted, Error)
 */
import type { SessionUsage } from './session';

export type Role = 'USER' | 'ASSISTANT' | 'TOOL' | 'ERROR';

export type MessageStatus = 'PENDING' | 'STREAMING' | 'SUCCESS' | 'INTERRUPTED' | 'ERROR';

export type ToolStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'ERROR' | 'ABORTED';

export interface ToolCallExecution {
  id?: string | undefined;
  name: string;
  command?: string | undefined;
  workingDir?: string | undefined;
  argumentsJson: string;
  resultJson: string | null;
  stdout?: string | undefined;
  stderr?: string | undefined;
  exitCode?: number | null | undefined;
  durationMs?: number | undefined;
  status?: ToolStatus | undefined;
  startedAt?: string | undefined;
  finishedAt?: string | undefined;
}

export interface AgentStep {
  id: string;
  type: 'thought' | 'tool' | 'text';
  content?: string;
  toolCall?: ToolCallExecution;
  status: 'running' | 'completed' | 'error';
  durationMs?: number;
}

export type ToolCall = ToolCallExecution;

/** 单条会话消息. */
export interface SessionMessage {
  id: string;
  sessionId: string;
  role: Role;
  content: string;
  /** 思考链推演过程 (Reasoning / Thinking) */
  reasoningContent?: string | null;
  /** 思考耗时 (毫秒) */
  reasoningDurationMs?: number | null;
  /** 结构化工具调用列表 */
  toolCalls: ToolCallExecution[];
  /** 时序化步骤 */
  steps?: AgentStep[];
  /** 消息生成状态 */
  status?: MessageStatus;
  /** 本条消息的 usage; 非 LLM 消息为 null. */
  usage: SessionUsage | null;
  /** usage 解析失败标记: degraded=true 时 cumulativeUsage 不累加该条. */
  degraded: boolean;
  timestamp: string;
}

/** SSE 事件 (后端 AgentSessionPort.SessionEvent). */
export interface SessionEvent {
  sessionId: string;
  message?: SessionMessage;
  /** 增量文本 (流式推流) */
  delta?: string;
  /** 增量思考 (流式推流) */
  reasoningDelta?: string;
  /** 工具调用增量 */
  toolCall?: ToolCallExecution;
  /** "message" | "usage" | "tool_call" | "chunk" | "reasoning_chunk" | "done" | "error" | "interrupted" */
  kind: string;
}
