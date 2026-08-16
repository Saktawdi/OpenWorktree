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
