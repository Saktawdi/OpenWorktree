/**
 * 会话 API — 对齐后端会话路由 (后端文档 §4.1 会话路由约定).
 *
 * 路由:
 *   - 列表/建会话: /api/tickets/{no}/sessions (强制 ticketNo 绑定)
 *   - 单会话操作: 扁平 /api/sessions/{sid}/{messages|events|abort}
 *   - 历史回放: 复用 GET /api/sessions/{sid}/messages (不单设 /history)
 *   - AgentConfig 历史: GET /api/agent-configs/{id}/sessions
 *
 * 所有响应在 API 边界统一映射为前端 camelCase domain shape.
 */
import { client } from './client';
import type { Session, SessionMessage, SessionUsage } from '@/types';
import type { Role, ToolCall } from '@/types/session-message';

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

function booleanOf(value: unknown): boolean {
  if (typeof value === 'boolean') return value;
  if (typeof value === 'string') return value.trim().toLowerCase() === 'true';
  if (typeof value === 'number') return value !== 0;
  return false;
}

function usageOf(value: unknown): SessionUsage | null {
  const raw = recordOf(value);
  if (!raw || Object.keys(raw).length === 0) return null;
  return {
    promptTokens: nullableNumber(raw.prompt_tokens ?? raw.promptTokens),
    completionTokens: nullableNumber(raw.completion_tokens ?? raw.completionTokens),
    totalTokens: nullableNumber(raw.total_tokens ?? raw.totalTokens),
  };
}

function toolCallOf(value: unknown): ToolCall {
  const raw = recordOf(value);
  return {
    id: nullableStringOf(raw.id) ?? undefined,
    name: stringOf(raw.name),
    command: nullableStringOf(raw.command) ?? undefined,
    workingDir: nullableStringOf(raw.working_dir ?? raw.workingDir) ?? undefined,
    argumentsJson: stringOf(raw.arguments_json ?? raw.argumentsJson ?? raw.arguments),
    resultJson: nullableStringOf(raw.result_json ?? raw.resultJson ?? raw.result),
    stdout: nullableStringOf(raw.stdout) ?? undefined,
    stderr: nullableStringOf(raw.stderr) ?? undefined,
    exitCode: nullableNumber(raw.exit_code ?? raw.exitCode),
    durationMs: nullableNumber(raw.duration_ms ?? raw.durationMs) ?? undefined,
    status: (nullableStringOf(raw.status)?.toUpperCase() as ToolCall['status']) ?? 'SUCCESS',
    startedAt: nullableStringOf(raw.started_at ?? raw.startedAt) ?? undefined,
    finishedAt: nullableStringOf(raw.finished_at ?? raw.finishedAt) ?? undefined,
  };
}

/** Maps backend snake_case session JSON to the frontend Session domain shape. */
export function normalizeSession(value: unknown): Session {
  const raw = recordOf(value);
  const status = stringOf(raw.status).toUpperCase();
  const cli = stringOf(raw.cli).toUpperCase();
  return {
    id: stringOf(raw.id),
    ticketNo: stringOf(raw.ticket_no ?? raw.ticketNo),
    agentConfigId: stringOf(raw.agent_config_id ?? raw.agentConfigId),
    cli: cli === 'OPENCODE' || cli === 'CLAUDE' ? cli as Session['cli'] : 'CLAUDE',
    status: status === 'ACTIVE' || status === 'ABORTED' || status === 'CLOSED'
      ? status as Session['status']
      : 'CLOSED',
    cliSessionId: nullableStringOf(raw.cli_session_id ?? raw.cliSessionId),
    clonePath: stringOf(raw.clone_path ?? raw.clonePath),
    allocatedPort: nullableNumber(raw.allocated_port ?? raw.allocatedPort),
    startedAt: stringOf(raw.started_at ?? raw.startedAt),
    finishedAt: nullableStringOf(raw.finished_at ?? raw.finishedAt),
    cumulativeUsage: usageOf(raw.cumulative_usage ?? raw.cumulativeUsage),
  };
}

/** Maps backend snake_case session message JSON to the frontend SessionMessage shape. */
export function normalizeSessionMessage(value: unknown): SessionMessage {
  let raw = recordOf(value);
  
  // 若传入的 raw 包含嵌套 JSON 字符串在 content 字段中，尝试解析
  if (typeof raw.content === 'string' && raw.content.trim().startsWith('{') && raw.content.trim().endsWith('}')) {
    try {
      const inner = JSON.parse(raw.content.trim()) as RawRecord;
      if (inner && (Array.isArray(inner.parts) || inner.info)) {
        raw = { ...raw, ...inner };
      }
    } catch {
      // ignore
    }
  }

  const info = recordOf(raw.info);
  // 优先取内层真实 role (info.role)，若外层由于降级被设为 ERROR 但内层为 assistant 则纠正为 ASSISTANT
  const innerRole = stringOf(info.role).toUpperCase();
  const rawRole = stringOf(raw.role).toUpperCase();
  const role = (innerRole === 'ASSISTANT' || innerRole === 'USER' || innerRole === 'TOOL'
    ? innerRole
    : (rawRole || 'ASSISTANT')) as Role;

  const callsRaw = raw.tool_calls ?? raw.toolCalls ?? raw.tools;
  let calls = Array.isArray(callsRaw) ? callsRaw.map(toolCallOf) : [];
  
  let reasoningContent = nullableStringOf(raw.reasoning_content ?? raw.reasoningContent ?? raw.thought);
  let content = stringOf(raw.content ?? '');
  let usage = usageOf(raw.usage ?? info.tokens ?? raw.tokens);

  // 检查是否有错误结构 (例如 MessageAbortedError)
  const errObj = recordOf(info.error ?? raw.error);
  const errName = stringOf(errObj.name ?? errObj.code);
  const errData = recordOf(errObj.data);
  const errMsg = stringOf(errData.message ?? errObj.message);
  if (errName === 'MessageAbortedError' || errMsg === 'Aborted') {
    if (!content) {
      content = '⚠️ 会话生成已被用户中断。';
    }
  } else if (errName && !content) {
    content = `⚠️ 执行错误: ${errName}${errMsg ? ` (${errMsg})` : ''}`;
  }
  
  // 支持 Opencode / Sisyphus / Multica 标准的多部件 (parts) 协议
  const parts = Array.isArray(raw.parts) ? raw.parts : [];
  if (parts.length > 0) {
    const textParts: string[] = [];
    const reasoningParts: string[] = [];
    
    for (const part of parts) {
      if (!part || typeof part !== 'object') continue;
      const p = part as RawRecord;
      const pType = stringOf(p.type).toLowerCase();
      
      if (pType === 'reasoning' || pType === 'thinking' || pType === 'thought') {
        const text = stringOf(p.text ?? p.content ?? p.reasoning);
        if (text) reasoningParts.push(text);
      } else if (pType === 'text') {
        const text = stringOf(p.text ?? p.content);
        if (text) textParts.push(text);
      } else if (pType === 'tool' || pType === 'tool_use' || pType === 'tool_call' || pType === 'step-start' || pType === 'patch') {
        if (p.name || p.tool || p.command) {
          calls.push(toolCallOf(p));
        }
      }
    }
    
    if (reasoningParts.length > 0) {
      reasoningContent = reasoningParts.join('\n\n');
    }
    if (textParts.length > 0) {
      content = textParts.join('\n\n');
    }
  }
  
  // 提取思考内容 (若在 content 中内嵌 <think>...</think> 或 <thought>...</thought>)
  if (!reasoningContent && (content.includes('<think>') || content.includes('<thought>'))) {
    const thinkMatch = content.match(/<think>([\s\S]*?)<\/think>/) || content.match(/<thought>([\s\S]*?)<\/thought>/);
    if (thinkMatch && thinkMatch[1]) {
      reasoningContent = thinkMatch[1].trim();
      content = content.replace(/<think>[\s\S]*?<\/think>/, '').replace(/<thought>[\s\S]*?<\/thought>/, '').trim();
    }
  }

  // 若 content 仍然残留完整的复合 JSON payload，做最后一道防线解构
  if (content.startsWith('{"info":') && content.includes('"parts":')) {
    try {
      const parsed = JSON.parse(content) as { parts?: Array<{ type?: string; text?: string; content?: string }> };
      if (Array.isArray(parsed.parts)) {
        const extractedTexts = parsed.parts.filter((p) => p.type === 'text' && p.text).map((p) => p.text!);
        const extractedThoughts = parsed.parts.filter((p) => (p.type === 'reasoning' || p.type === 'thinking') && (p.text || p.content)).map((p) => (p.text || p.content)!);
        if (extractedTexts.length) content = extractedTexts.join('\n\n');
        if (extractedThoughts.length && !reasoningContent) reasoningContent = extractedThoughts.join('\n\n');
      }
    } catch {
      // ignore
    }
  }

  const time = recordOf(raw.time ?? info.time);

  return {
    id: stringOf(raw.id ?? info.id ?? `msg-${Date.now()}`),
    sessionId: stringOf(raw.session_id ?? raw.sessionId ?? info.sessionID ?? info.sessionId),
    role: ['USER', 'ASSISTANT', 'TOOL', 'ERROR'].includes(role) ? role : 'ASSISTANT',
    content,
    reasoningContent,
    reasoningDurationMs: nullableNumber(raw.reasoning_duration_ms ?? raw.reasoningDurationMs),
    toolCalls: calls,
    status: (nullableStringOf(raw.status)?.toUpperCase() as SessionMessage['status']) ?? 'SUCCESS',
    usage,
    degraded: usage == null ? false : booleanOf(raw.degraded),
    timestamp: stringOf(raw.timestamp ?? time.created ?? new Date().toISOString()),
  };
}

function rows(value: unknown, key: string): unknown[] {
  if (Array.isArray(value)) return value;
  const raw = recordOf(value);
  return Array.isArray(raw[key]) ? raw[key] : [];
}

export async function listTicketSessions(ticketNo: string): Promise<Session[]> {
  const resp = await client.get<unknown>(`/tickets/${encodeURIComponent(ticketNo)}/sessions`);
  return rows(resp.data, 'sessions').map(normalizeSession).filter((session) => session.id);
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
  const resp = await client.post<unknown>(
    `/tickets/${encodeURIComponent(ticketNo)}/sessions`,
    {
      agent_config_id: req.agentConfigId,
      ...(req.initialPrompt ? { initial_prompt: req.initialPrompt } : {}),
    },
  );
  return normalizeSession(resp.data);
}

export async function getSession(sid: string): Promise<Session> {
  const resp = await client.get<unknown>(`/sessions/${encodeURIComponent(sid)}`);
  return normalizeSession(resp.data);
}

export async function getHistory(sid: string): Promise<SessionMessage[]> {
  const resp = await client.get<unknown>(
    `/sessions/${encodeURIComponent(sid)}/messages`,
  );
  return rows(resp.data, 'messages').map(normalizeSessionMessage).filter((message) => message.id);
}

/** 异步任务 id; SSE 流走 /api/sessions/{sid}/events. */
export async function sendMessage(sid: string, message: string): Promise<{ taskId: string }> {
  const resp = await client.post<{ task_id: string }>(
    `/sessions/${encodeURIComponent(sid)}/messages`,
    { message },
  );
  return { taskId: resp.data.task_id };
}

export async function abortSession(sid: string): Promise<void> {
  await client.post(`/sessions/${encodeURIComponent(sid)}/abort`, {});
}

export async function listAgentConfigSessions(agentConfigId: string): Promise<Session[]> {
  const resp = await client.get<unknown>(
    `/agent-configs/${encodeURIComponent(agentConfigId)}/sessions`,
  );
  return rows(resp.data, 'sessions').map(normalizeSession).filter((session) => session.id);
}

/** 会话 SSE 路径 (供 useSSE 的 path 参数用). */
export function sessionEventsPath(sid: string): string {
  return `/api/sessions/${encodeURIComponent(sid)}/events`;
}
