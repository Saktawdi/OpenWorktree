/**
 * 智能体域状态（agent）：AgentConfig/OpenCode 供应商的本地（demo）CRUD
 * 与全局 agent 选择（持久化）。
 */
import { appStore } from "@/store";
import { saveAgentId } from "@/store/prefs";
import type { AgentConfig, OpenCodeProvider } from "@/shared/types";

const set = appStore.setState;

export function setAgentId(id: string) {
  appStore.setState({ agentId: id });
  saveAgentId(id);
}

export function upsertAgentConfig(c: AgentConfig) {
  set((st) => ({
    agents: st.agents.some((x) => x.id === c.id)
      ? st.agents.map((x) => (x.id === c.id ? c : x))
      : [...st.agents, c],
  }));
}

export function removeAgentConfig(id: string) {
  set((st) => ({
    agents: st.agents.filter((a) => a.id !== id),
    agentId: st.agentId === id ? (st.agents.find((a) => a.id !== id)?.id ?? "") : st.agentId,
  }));
}

export function upsertOcProvider(p: OpenCodeProvider) {
  set((st) => ({
    ocProviders: st.ocProviders.some((x) => x.key === p.key)
      ? st.ocProviders.map((x) => (x.key === p.key ? p : x))
      : [...st.ocProviders, p],
  }));
}

export function removeOcProvider(key: string) {
  set((st) => ({ ocProviders: st.ocProviders.filter((p) => p.key !== key) }));
}
