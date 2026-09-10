/**
 * 插件系统（app/plugins）：后端插件设施的 HTTP 接入。
 * 全部走 net 层同源相对路径（dev 经 vite proxy /plugins、/api → 后端）。
 */
import { t } from "@/i18n";
import { appStore } from "@/store";
import type { HostFetchRequest, PluginListItem } from "./types";

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
    throw new Error(t("plugins.hostFetchRelOnly"));
  }
  const res = await fetch(path, {
    method: init.method ?? "GET",
    headers: { "Content-Type": "application/json", ...authHeaders() },
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
  });
  if (!res.ok) throw new Error(await readError(res));
  return (await res.json()) as T;
}

/* ─── LLM 能力数据面 ───
 * 实现收敛在 net/llm（原生 LLM 小助手与插件宿主共用），这里保留历史 import 路径的转发。 */

export { llmChat, llmChatStream } from "@/net";
export type { LlmChatMessage, LlmChatRequest, LlmChatResponse } from "@/net";
