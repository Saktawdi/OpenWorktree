/**
 * 设置域 API（settings）：gate.toml 读写、MCP 状态、LLM Provider 管理。
 * 应用信息/更新检查见 app.ts。
 */
import { api } from "@/net";
import type { GateTomlResponse, LlmProvider, McpStatus } from "@/shared/types";

export async function fetchGateToml(): Promise<GateTomlResponse> {
  return api<GateTomlResponse>("/api/settings/gate-toml");
}

export async function updateGateToml(updates: Record<string, unknown>): Promise<{ ok: boolean; updated: string[]; restart_required: boolean }> {
  return api<{ ok: boolean; updated: string[]; restart_required: boolean }>("/api/settings/gate-toml", {
    method: "PUT",
    body: JSON.stringify({ updates }),
  });
}

export async function fetchMcpStatus(): Promise<McpStatus> {
  return api<McpStatus>("/api/mcp/status");
}

export async function fetchProviders(): Promise<LlmProvider[]> {
  const data = await api<{ providers: LlmProvider[] }>("/api/providers");
  return data.providers ?? [];
}

export async function createProvider(body: { id: string; name: string; base_url: string; type: string }): Promise<LlmProvider> {
  return api<LlmProvider>("/api/providers", { method: "POST", body: JSON.stringify(body) });
}

export async function updateProvider(id: string, body: { name: string; base_url: string; type: string }): Promise<LlmProvider> {
  return api<LlmProvider>(`/api/providers/${encodeURIComponent(id)}`, { method: "PUT", body: JSON.stringify(body) });
}

export async function deleteProvider(id: string): Promise<void> {
  await api<void>(`/api/providers/${encodeURIComponent(id)}`, { method: "DELETE" });
}

/** 保存 Provider 的 API Key（设置中心直填）：明文仅在请求体出现一次，后端 KMS 加密落库。 */
export async function setProviderCredential(id: string, apiKey: string): Promise<LlmProvider> {
  return api<LlmProvider>(`/api/providers/${encodeURIComponent(id)}/credential`, {
    method: "PUT",
    body: JSON.stringify({ api_key: apiKey }),
  });
}

/** 清除 Provider 的已存密钥（api_key_ref 置为 unconfigured）。 */
export async function clearProviderCredential(id: string): Promise<LlmProvider> {
  return api<LlmProvider>(`/api/providers/${encodeURIComponent(id)}/credential`, { method: "DELETE" });
}

export async function updateProviderModels(id: string, models: string[]): Promise<LlmProvider> {
  return api<LlmProvider>(`/api/providers/${encodeURIComponent(id)}/models`, {
    method: "PUT",
    body: JSON.stringify({ models }),
  });
}

export async function fetchUpstreamModels(id: string): Promise<LlmProvider> {
  return api<LlmProvider>(`/api/providers/${encodeURIComponent(id)}/models/fetch`, { method: "POST", body: "{}" });
}
