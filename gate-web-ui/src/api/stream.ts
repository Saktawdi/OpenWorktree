/**
 * 基于 fetch + ReadableStream 的单次会话流式交互客户端 (执行文档-前端流式重构).
 * 彻底消除常驻 EventSource 和轮询风暴，实现标准的 Token / Thinking / ToolCall 流式消费。
 */

export interface StreamChunkCallbacks {
  onToken?: (delta: string) => void;
  onThinking?: (delta: string) => void;
  onToolCall?: (toolChunk: {
    callId: string;
    toolName: string;
    status: string;
    argumentDelta?: string;
    result?: string;
  }) => void;
  onUsage?: (usage: { promptTokens?: number; completionTokens?: number; totalTokens?: number }) => void;
  onDone?: (payload: { sessionId?: string; fullMessageId?: string }) => void;
  onError?: (err: Error) => void;
}

export async function postSessionStream(
  sessionId: string,
  message: string,
  callbacks: StreamChunkCallbacks,
  signal?: AbortSignal
): Promise<void> {
  const token = localStorage.getItem('gate_token') || sessionStorage.getItem('gate_token') || '';

  const init: RequestInit = {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'text/event-stream',
      ...(token ? { 'Authorization': `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({ message }),
  };
  if (signal) {
    init.signal = signal;
  }

  const resp = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/messages`, init);

  if (!resp.ok) {
    let errMsg = `HTTP ${resp.status} ${resp.statusText}`;
    try {
      const errJson = await resp.json();
      if (errJson?.error?.message) errMsg = errJson.error.message;
    } catch {
      // ignore
    }
    const err = new Error(errMsg);
    callbacks.onError?.(err);
    throw err;
  }

  if (!resp.body) {
    callbacks.onDone?.({ sessionId });
    return;
  }

  const reader = resp.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const frames = buffer.split(/\r?\n\r?\n/);
      buffer = frames.pop() ?? '';

      for (const frame of frames) {
        if (!frame.trim() || frame.startsWith(':')) continue;

        let eventType = 'token';
        let dataLines: string[] = [];

        for (const line of frame.split(/\r?\n/)) {
          if (line.startsWith('event:')) {
            eventType = line.slice(6).trim();
          } else if (line.startsWith('data:')) {
            dataLines.push(line.slice(5).trim());
          }
        }

        if (dataLines.length === 0) continue;
        const dataStr = dataLines.join('\n');
        try {
          const data = JSON.parse(dataStr);
          switch (eventType) {
            case 'token':
            case 'text_delta':
            case 'chunk':
              callbacks.onToken?.(data.text_delta ?? data.textDelta ?? data.content ?? '');
              break;
            case 'thinking':
            case 'reasoning_delta':
            case 'reasoning_chunk':
              callbacks.onThinking?.(data.thinking_delta ?? data.thinkingDelta ?? data.thought ?? '');
              break;
            case 'tool_call':
            case 'tool_start':
              callbacks.onToolCall?.({
                callId: data.call_id ?? data.callId ?? `tool-${Date.now()}`,
                toolName: data.tool_name ?? data.toolName ?? data.name ?? 'tool',
                status: data.status ?? 'RUNNING',
                argumentDelta: data.argument_delta ?? data.argumentDelta ?? data.arguments_json,
                result: data.result ?? data.result_json,
              });
              break;
            case 'usage':
              callbacks.onUsage?.({
                promptTokens: data.prompt_tokens ?? data.promptTokens,
                completionTokens: data.completion_tokens ?? data.completionTokens,
                totalTokens: data.total_tokens ?? data.totalTokens,
              });
              break;
            case 'done':
              callbacks.onDone?.({
                sessionId: data.session_id ?? sessionId,
                fullMessageId: data.full_message_id,
              });
              break;
            case 'error':
              callbacks.onError?.(new Error(data.error_message ?? data.message ?? 'Session error'));
              break;
          }
        } catch {
          // ignore non-json data frames
        }
      }
    }
    callbacks.onDone?.({ sessionId });
  } catch (err: any) {
    if (err.name === 'AbortError') {
      callbacks.onDone?.({ sessionId });
    } else {
      callbacks.onError?.(err);
      throw err;
    }
  } finally {
    reader.releaseLock();
  }
}
