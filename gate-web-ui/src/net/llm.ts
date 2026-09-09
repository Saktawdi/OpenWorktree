/**
 * LLM 对话数据面（net）：/api/llm/chat 的唯一前端实现（KMS 解密在后端完成，
 * 前端只见业务字段）。原生 LLM 小助手（features/assistant）与插件宿主
 * ctx.llm（app/plugins）共用，避免 SSE 解析两处镜像。
 */
import { api, authHeaders } from "./http";

/** 一条对话消息（wire 与 SDK 契约同形）。 */
export interface LlmChatMessage {
  role: "system" | "user" | "assistant";
  content: string;
}

/** chat 请求（camelCase 业务参数；provider_id/max_tokens 的 wire 映射在本层完成）。 */
export interface LlmChatRequest {
  messages: LlmChatMessage[];
  providerId?: string;
  model?: string;
  temperature?: number;
  maxTokens?: number;
}

export interface LlmChatResponse {
  id?: string;
  choices: Array<{
    message: {
      role: string;
      content: string;
    };
    finish_reason?: string;
  }>;
}

function toWire(req: LlmChatRequest): Record<string, unknown> {
  return {
    messages: req.messages,
    provider_id: req.providerId,
    model: req.model,
    temperature: req.temperature,
    max_tokens: req.maxTokens,
  };
}

/** 单次非流式 chat：POST /api/llm/chat (stream=false)。 */
export async function llmChat(req: LlmChatRequest): Promise<LlmChatResponse> {
  return api<LlmChatResponse>("/api/llm/chat", {
    method: "POST",
    body: JSON.stringify({ ...toWire(req), stream: false }),
  });
}

/**
 * 流式 chat：POST /api/llm/chat (stream=true)，消费 OpenAI 兼容 SSE。
 * 每个含内容的 data: 帧把 delta.content 喂给 onChunk；[DONE] 或流关闭结束。
 * signal 中止时抛 AbortError（调用方区分"用户停止"与真实失败）。
 */
export async function llmChatStream(
  req: LlmChatRequest,
  onChunk: (chunk: string) => void,
  signal?: AbortSignal,
): Promise<string> {
  const res = await fetch("/api/llm/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json", ...authHeaders() },
    body: JSON.stringify({ ...toWire(req), stream: true }),
    signal,
  });
  if (!res.ok) {
    let msg = `${res.status}`;
    try {
      const body = await res.json();
      msg = body?.error?.message ?? body?.message ?? msg;
    } catch {
      /* ignore */
    }
    throw new Error(msg);
  }
  if (!res.body) throw new Error("响应无正文，无法流式读取");
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let full = "";
  let buf = "";
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buf += decoder.decode(value, { stream: true });
    // SSE 帧以空行分隔；逐行解析 data: 前缀，行残缺时留在 buf 等下一片。
    let idx: number;
    while ((idx = buf.indexOf("\n")) >= 0) {
      const line = buf.slice(0, idx).replace(/\r$/, "");
      buf = buf.slice(idx + 1);
      if (!line.startsWith("data:")) continue;
      const payload = line.slice(5).trim();
      if (!payload || payload === "[DONE]") continue;
      try {
        const frame = JSON.parse(payload) as {
          choices?: Array<{ delta?: { content?: string } }>;
        };
        const chunk = frame.choices?.[0]?.delta?.content ?? "";
        if (chunk) {
          full += chunk;
          onChunk(chunk);
        }
      } catch {
        /* 非 JSON 帧忽略（keepalive 注释等） */
      }
    }
  }
  return full;
}

/** 判断异常是否为"用户主动中止"（fetch/reader 两条路径的 AbortError 变体）。 */
export function isAbortError(e: unknown): boolean {
  return e instanceof DOMException ? e.name === "AbortError" : (e as Error)?.name === "AbortError";
}
