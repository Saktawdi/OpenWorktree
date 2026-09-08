/**
 * 插件系统（app/plugins）：后端插件设施的 HTTP 接入。
 * 全部走 net 层同源相对路径（dev 经 vite proxy /plugins、/api → 后端）。
 */
import { appStore } from "@/store";
import type { HostFetchRequest, LlmChatResponse, PluginListItem } from "./types";

function authHeaders(): Record<string, string> {
  const token = appStore.getState().token;
  return token ? { Authorization: `Bearer ${token}` } : {};
}

async function readError(res: Response): Promise<string> {
  let msg = `${res.status}`;
  try {
    const body = (await res.json()) as { error?: { message?: string }; message?: string };
    msg = body?.error?.message ?? body?.message ?? msg;
  } catch {
    /* ignore */
  }
  return msg;
}

/** 与 net/http 的 api() 同构（这里不 import 是为了让插件域的错误文案可自带前缀）。 */
async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: { "Content-Type": "application/json", ...authHeaders(), ...(init?.headers ?? {}) },
  });
  if (!res.ok) {
    throw new Error(await readError(res));
  }
  return (await res.json()) as T;
}

export async function listPlugins(): Promise<PluginListItem[]> {
  const body = await api<{ plugins: unknown[] }>("/api/plugins");
  return body.plugins.map((raw) => {
    const p = raw as Record<string, unknown>;
    return {
      id: String(p.id),
      name: String(p.name),
      version: String(p.version),
      apiVersion: String(p.api_version),
      entry: String(p.entry),
      css: p.css == null ? null : String(p.css),
      description: p.description == null ? null : String(p.description),
      permissions: Array.isArray(p.permissions) ? (p.permissions as string[]) : [],
      enabled: p.enabled !== false,
      cacheTag: String(p.cache_tag ?? ""),
    };
  });
}

export async function setPluginEnabled(id: string, enabled: boolean): Promise<void> {
  await api(`/api/plugins/${id}/${enabled ? "enable" : "disable"}`, { method: "POST" });
}

/** reload = 重算资产指纹；宿主拿到新 cache_tag 后以新 URL 重新 import 实现热重载。 */
export async function requestPluginReload(id: string): Promise<string> {
  const body = await api<{ cache_tag: string }>(`/api/plugins/${id}/reload`, { method: "POST" });
  return body.cache_tag;
}

/* ─── 插件 KV 数据面 ─── */

function kvUrl(pluginId: string, key: string): string {
  return `/api/plugin-capabilities/kv/${pluginId}/${encodeURIComponent(key)}`;
}

export async function kvGet<T>(pluginId: string, key: string): Promise<T | null> {
  const res = await fetch(kvUrl(pluginId, key), { headers: authHeaders() });
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(await readError(res));
  return (await res.json()) as T;
}

export async function kvSet(pluginId: string, key: string, value: unknown): Promise<void> {
  const res = await fetch(kvUrl(pluginId, key), {
    method: "PUT",
    headers: { "Content-Type": "application/json", ...authHeaders() },
    body: JSON.stringify(value),
  });
  if (!res.ok) throw new Error(await readError(res));
}

export async function kvDel(pluginId: string, key: string): Promise<void> {
  const res = await fetch(kvUrl(pluginId, key), { method: "DELETE", headers: authHeaders() });
  if (!res.ok) throw new Error(await readError(res));
}

/** ctx.hostFetch 的实现：仅放行同源 /api/ 相对路径，注入 Web Token。 */
export async function hostFetch<T>(path: string, init: HostFetchRequest = {}): Promise<T> {
  if (!path.startsWith("/api/")) {
    throw new Error("hostFetch 仅允许 /api/ 相对路径");
  }
  const res = await fetch(path, {
    method: init.method ?? "GET",
    headers: { "Content-Type": "application/json", ...authHeaders() },
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
  });
  if (!res.ok) throw new Error(await readError(res));
  return (await res.json()) as T;
}

/* ─── LLM 能力数据面（/api/llm/chat 代理：KMS 解密在后端完成，前端/插件只见业务字段） ─── */

interface LlmChatWireOptions {
  messages: Array<{ role: string; content: string }>;
  provider_id?: string;
  model?: string;
  temperature?: number;
  max_tokens?: number;
  stream?: boolean;
}

/** 单次非流式 chat：POST /api/llm/chat (stream=false)。 */
export async function llmChat(options: LlmChatWireOptions): Promise<LlmChatResponse> {
  return api<LlmChatResponse>("/api/llm/chat", {
    method: "POST",
    body: JSON.stringify({ ...options, stream: false }),
  });
}

/**
 * 流式 chat：POST /api/llm/chat (stream=true)，消费 OpenAI 兼容 SSE。
 * 每个含内容的 data: 帧把 delta.content 喂给 onChunk；[DONE] 或流关闭结束。
 */
export async function llmChatStream(
  options: LlmChatWireOptions,
  onChunk: (chunk: string) => void,
): Promise<string> {
  const res = await fetch("/api/llm/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json", ...authHeaders() },
    body: JSON.stringify({ ...options, stream: true }),
  });
  if (!res.ok) throw new Error(await readError(res));
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
