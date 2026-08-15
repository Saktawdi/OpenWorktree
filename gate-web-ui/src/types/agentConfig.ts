/**
 * AgentConfig — 对齐后端 gate-domain.session.AgentConfig / AgentCli (后端文档 §5.2).
 */
export type AgentCli = 'OPENCODE' | 'CLAUDE';

export interface AgentConfig {
  id: string;
  name: string;
  cli: AgentCli;
  /** 关联 provider 表. */
  providerId: string;
  model: string;
  /** 可空; 额外 system prompt. */
  systemPrompt?: string | null;
  /** JSON array of strings; 透传 CLI flag. */
  extraFlags?: string[] | null;
  description?: string | null;
}

/** 模型供应商 (后端 ProviderRepository record 投影). */
export interface Provider {
  id: string;
  name: string;
  /** 旗下模型 id 列表. */
  models: string[];
}
