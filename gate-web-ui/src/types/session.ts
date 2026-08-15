/**
 * 会话与 usage — 对齐后端 gate-domain.session.Session / SessionUsage / SessionStatus.
 *
 * Token usage 是 H1 复测的核心数据 (后端文档 §5.7).
 */
export type SessionStatus = 'ACTIVE' | 'ABORTED' | 'CLOSED';

/** Token 用量 (OpenAI-style: prompt / completion / total). 后端 SessionUsage record. */
export interface SessionUsage {
  promptTokens: number | null;
  completionTokens: number | null;
  totalTokens: number | null;
}

/** 会话 (≈ multica Task). */
export interface Session {
  id: string;
  ticketNo: string;
  agentConfigId: string;
  /** OPENCODE | CLAUDE — 后端 AgentCli enum name. */
  cli: 'OPENCODE' | 'CLAUDE';
  status: SessionStatus;
  /** claude session-id / opencode session id. */
  cliSessionId: string | null;
  clonePath: string;
  /** opencode serve 端口; claude 为 -1. */
  allocatedPort: number | null;
  startedAt: string;
  finishedAt: string | null;
  /** 累计 usage. */
  cumulativeUsage: SessionUsage | null;
}
