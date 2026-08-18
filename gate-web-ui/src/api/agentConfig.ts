/**
 * AgentConfig CRUD API — 对齐后端 /api/agent-configs (S3).
 *
 * 后端字段 snake_case; 这里统一映射为前端 camelCase AgentConfig.
 */
import { client } from './client';
import type { AgentCli, AgentConfig } from '@/types/agentConfig';

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

function stringListOf(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.map((item) => stringOf(item)).filter(Boolean);
}

export function normalizeAgentConfig(value: unknown): AgentConfig {
  const raw = recordOf(value);
  const cli = stringOf(raw.cli).toUpperCase() as AgentCli;
  return {
    id: stringOf(raw.id),
    name: stringOf(raw.name),
    cli: cli === 'OPENCODE' || cli === 'CLAUDE' ? cli : 'CLAUDE',
    providerId: nullableStringOf(raw.provider_id ?? raw.providerId),
    model: nullableStringOf(raw.model),
    systemPrompt: nullableStringOf(raw.system_prompt ?? raw.systemPrompt),
    extraFlags: stringListOf(raw.extra_flags ?? raw.extraFlags),
    description: nullableStringOf(raw.description),
  };
}

function rows(value: unknown, key: string): unknown[] {
  if (Array.isArray(value)) return value;
  const raw = recordOf(value);
  return Array.isArray(raw[key]) ? raw[key] : [];
}

export interface SaveAgentConfigRequest {
  id?: string;
  name: string;
  cli: AgentCli;
  providerId?: string | null;
  model?: string | null;
  systemPrompt?: string | null;
  extraFlags?: string[] | null;
  description?: string | null;
}

export async function listAgentConfigs(): Promise<AgentConfig[]> {
  const resp = await client.get<unknown>('/agent-configs');
  return rows(resp.data, 'agent_configs').map(normalizeAgentConfig).filter((config) => config.id);
}

export async function getAgentConfig(id: string): Promise<AgentConfig> {
  const resp = await client.get<unknown>(`/agent-configs/${encodeURIComponent(id)}`);
  return normalizeAgentConfig(resp.data);
}

export async function createAgentConfig(req: SaveAgentConfigRequest): Promise<AgentConfig> {
  const resp = await client.post<unknown>('/agent-configs', payloadOf(req));
  return normalizeAgentConfig(resp.data);
}

export async function updateAgentConfig(id: string, req: SaveAgentConfigRequest): Promise<AgentConfig> {
  const resp = await client.put<unknown>(`/agent-configs/${encodeURIComponent(id)}`, payloadOf(req));
  return normalizeAgentConfig(resp.data);
}

export async function deleteAgentConfig(id: string): Promise<void> {
  await client.delete(`/agent-configs/${encodeURIComponent(id)}`);
}

function payloadOf(req: SaveAgentConfigRequest): Record<string, unknown> {
  return {
    ...(req.id ? { id: req.id } : {}),
    name: req.name,
    cli: req.cli,
    provider_id: req.providerId ?? null,
    model: req.model ?? null,
    ...(req.systemPrompt != null ? { system_prompt: req.systemPrompt } : {}),
    ...(req.extraFlags != null ? { extra_flags: req.extraFlags } : {}),
    ...(req.description != null ? { description: req.description } : {}),
  };
}
