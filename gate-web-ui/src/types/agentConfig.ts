/**
 * AgentConfig — 对齐后端 gate-domain.session.AgentConfig / AgentCli (后端文档 §5.2).
 */
export type AgentCli = 'OPENCODE' | 'CLAUDE';

export interface AgentConfig {
  id: string;
  name: string;
  cli: AgentCli;
  /** API runtime 的可选关联；本机 CLI agent 通常为空。 */
  providerId?: string | null;
  /** 可选模型覆盖；为空时由 CLI 使用自身默认模型。 */
  model?: string | null;
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
