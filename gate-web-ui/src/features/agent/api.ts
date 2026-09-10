/**
 * 智能体域 API（agent）：AgentConfig CRUD 与运行时探测。
 * OpenCode 供应商（opencode.json provider 节点）见 oc.ts；运行中轮询见 busy.ts。
 */
import { t } from "@/i18n";
import { api } from "@/net";
import { appStore, showToast } from "@/store";
import type { AgentConfig, AgentRuntime } from "@/shared/types";

interface RawAgentConfig {
  id: string;
  name: string;
  cli: string;
  provider_id?: string | null;
  model?: string | null;
  system_prompt?: string | null;
  extra_flags?: string[] | null;
  description?: string | null;
  inject_context?: boolean | null;
}

function mapAgentConfig(c: RawAgentConfig): AgentConfig {
  return {
    id: c.id,
    name: c.name,
    cli: c.cli.toLowerCase() as "claude" | "opencode",
    providerId: c.provider_id ?? null,
    model: c.model ?? "",
    systemPrompt: c.system_prompt ?? null,
    extraFlags: c.extra_flags ?? [],
    description: c.description ?? null,
    injectContext: c.inject_context !== false,
  };
}

export async function loadAgentConfigs() {
  const data = await api<{ agent_configs: RawAgentConfig[] }>("/api/agent-configs");
  appStore.setState((st) => ({
    agents: data.agent_configs.map(mapAgentConfig),
    // Snap an invalid (e.g. demo-era or deleted) selection to a real config so the
    // composer picker always shows what a new session will actually use.
    agentId: data.agent_configs.some((c) => c.id === st.agentId)
      ? st.agentId
      : (data.agent_configs[0]?.id ?? ""),
  }));
}

export async function upsertAgentConfigLive(c: AgentConfig): Promise<boolean> {
  try {
    const body = JSON.stringify({
      id: c.id,
      name: c.name,
      cli: c.cli.toUpperCase(),
      provider_id: c.providerId,
      model: c.model,
      system_prompt: c.systemPrompt,
      extra_flags: c.extraFlags,
      description: c.description,
      inject_context: c.injectContext,
    });
    const exists = appStore.getState().agents.some((x) => x.id === c.id);
    await api(`/api/agent-configs${exists ? `/${c.id}` : ""}`, {
      method: exists ? "PUT" : "POST",
      body,
    });
    await loadAgentConfigs();
    return true;
  } catch (e) {
    showToast(t("agentapi.saveFailed", { err: (e as Error).message }));
    return false;
  }
}

export async function deleteAgentConfigLive(id: string): Promise<boolean> {
  try {
    await api(`/api/agent-configs/${id}`, { method: "DELETE" });
    await loadAgentConfigs();
    return true;
  } catch (e) {
    showToast(t("agentapi.deleteFailed", { err: (e as Error).message }));
    return false;
  }
}

export async function loadRuntimes(): Promise<boolean> {
  try {
    const data = await api<{ agent_runtimes: AgentRuntime[] }>("/api/agent-runtimes");
    appStore.setState({ runtimes: data.agent_runtimes });
    return true;
  } catch {
    return false;
  }
}
