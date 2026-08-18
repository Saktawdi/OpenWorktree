import { client } from './client';

export interface WebConfigView {
  bind: string;
  port: number;
  allowed_origins: string[];
}

export interface SystemConfigView {
  project: string;
  auth_repo: string;
  clones_root: string;
  target_ref_whitelist: string[];
  gate_home: string;
  engine_configured: boolean;
  web?: WebConfigView;
}

export interface ProviderView {
  id: string;
  name: string;
  base_url: string;
  type: string;
  model_count: number;
  models: string[];
  credential_configured?: boolean;
}

export interface ProviderPayload {
  id?: string;
  name: string;
  base_url: string;
  type: string;
  api_key_ref?: string;
}

export interface RuntimeProbe {
  name: string;
  available: boolean;
  version: string | null;
  /** 已定位到可执行文件但 `--version` 失败时的诊断（区分"未安装"与"已安装·无法运行"）。 */
  note?: string | null;
  models?: string[];
  model_source?: 'cli' | 'cli-hints' | 'cli-default' | 'none' | 'unavailable' | string;
}

export interface RuntimeView {
  service: string;
  started_at: string;
  uptime_seconds: number;
  java: { version: string; vendor: string };
  os: { name: string; arch: string; version: string };
  git: RuntimeProbe;
  engine: RuntimeProbe & { configured: boolean; cmd: string | null };
  agent_clis: RuntimeProbe[];
  database: { path: string };
  web?: { bind: string; port: number };
  gate_home: string;
  auth_repo: string;
  auth_tip: string;
  auth_commit_count: number;
  counts: { tickets: number; active_sessions: number; running_tasks: number };
  session_port_range: { min: number; max: number };
}

export interface AgentRuntimeView extends RuntimeProbe {
  models: string[];
  model_source: 'cli' | 'cli-hints' | 'cli-default' | 'none' | 'unavailable' | string;
}

export async function getSystemConfig(): Promise<SystemConfigView> {
  const response = await client.get<SystemConfigView>('/config', { timeout: 5_000 });
  return response.data;
}

export async function getProviders(): Promise<ProviderView[]> {
  const response = await client.get<{ providers: ProviderView[] }>('/providers', { timeout: 5_000 });
  return response.data.providers;
}

export async function createProvider(payload: ProviderPayload): Promise<ProviderView> {
  const response = await client.post<ProviderView>('/providers', payload);
  return response.data;
}

export async function updateProvider(id: string, payload: ProviderPayload): Promise<ProviderView> {
  const response = await client.put<ProviderView>(`/providers/${encodeURIComponent(id)}`, payload);
  return response.data;
}

export async function deleteProvider(id: string): Promise<void> {
  await client.delete(`/providers/${encodeURIComponent(id)}`);
}

export async function replaceProviderModels(id: string, models: string[]): Promise<ProviderView> {
  const response = await client.put<ProviderView>(`/providers/${encodeURIComponent(id)}/models`, { models });
  return response.data;
}

export async function fetchProviderModels(id: string): Promise<ProviderView> {
  const response = await client.post<ProviderView>(`/providers/${encodeURIComponent(id)}/models/fetch`, {});
  return response.data;
}

export async function getRuntime(): Promise<RuntimeView> {
  const response = await client.get<RuntimeView>('/runtime', { timeout: 15_000 });
  return response.data;
}

export async function getAgentRuntimes(): Promise<AgentRuntimeView[]> {
  const response = await client.get<{ agent_runtimes: AgentRuntimeView[] }>('/agent-runtimes', { timeout: 20_000 });
  return response.data.agent_runtimes ?? [];
}
