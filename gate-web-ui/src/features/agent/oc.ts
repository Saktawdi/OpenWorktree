/**
 * 智能体域 OpenCode 供应商（agent oc）：opencode.json(c) provider 节点的
 * CRUD、上游模型探测与连通测试。
 */
import { t } from "@/i18n";
import { api } from "@/net";
import { appStore, showToast } from "@/store";
import type { OpenCodeProvider } from "@/shared/types";

interface RawOcModel {
  id: string;
  config?: Record<string, unknown> | null;
}

interface RawOcProvider {
  key: string;
  name: string;
  npm?: string | null;
  base_url?: string | null;
  api_key?: string | null;
  models?: RawOcModel[] | null;
  model_count?: number | null;
}

function mapOcProvider(p: RawOcProvider): OpenCodeProvider {
  return {
    key: p.key,
    name: p.name,
    npm: p.npm ?? null,
    baseURL: p.base_url ?? null,
    apiKey: p.api_key ?? null,
    models: (p.models ?? []).map((m) => ({ id: m.id, config: m.config ?? {} })),
    modelCount: p.model_count ?? (p.models?.length ?? 0),
  };
}

export async function loadOcProviders(): Promise<boolean> {
  try {
    const data = await api<{ config_path: string; config_exists: boolean; providers: RawOcProvider[] }>(
      "/api/opencode/providers",
    );
    appStore.setState({
      ocProviders: (data.providers ?? []).map(mapOcProvider),
      ocConfigPath: data.config_path ?? null,
    });
    return true;
  } catch {
    return false;
  }
}

export async function upsertOcProviderLive(
  p: OpenCodeProvider,
): Promise<boolean> {
  try {
    const exists = appStore.getState().ocProviders.some((x) => x.key === p.key);
    await api(`/api/opencode/providers/${encodeURIComponent(p.key)}`, {
      method: exists ? "PUT" : "POST",
      body: JSON.stringify({
        name: p.name,
        npm: p.npm || null,
        base_url: p.baseURL || null,
        api_key: p.apiKey ?? "",
        models: Object.fromEntries(p.models.map((m) => [m.id, m.config])),
      }),
    });
    await loadOcProviders();
    return true;
  } catch (e) {
    showToast(t("oc.saveFailed", { err: (e as Error).message }));
    return false;
  }
}

export async function deleteOcProviderLive(key: string): Promise<boolean> {
  try {
    await api(`/api/opencode/providers/${encodeURIComponent(key)}`, { method: "DELETE" });
    await loadOcProviders();
    return true;
  } catch (e) {
    showToast(t("oc.deleteFailed", { err: (e as Error).message }));
    return false;
  }
}

/** 拉取上游模型列表（保存前即可探测）；失败抛错由调用方展示。 */
export async function fetchOcModelsLive(baseUrl: string, apiKey: string): Promise<string[]> {
  const data = await api<{ models: string[] }>("/api/opencode/models/fetch", {
    method: "POST",
    body: JSON.stringify({ base_url: baseUrl, api_key: apiKey || null }),
  });
  return data.models ?? [];
}

export interface OcModelTestResult {
  ok: boolean;
  status_code: number;
  latency_ms: number;
  reply?: string;
  error?: string;
}

/** 连通测试：对单个模型发一条最小 chat completion，结果作为数据返回（不抛错）。 */
export async function testOcModelLive(
  baseUrl: string,
  apiKey: string,
  model: string,
): Promise<OcModelTestResult> {
  return api<OcModelTestResult>("/api/opencode/models/test", {
    method: "POST",
    body: JSON.stringify({ base_url: baseUrl, api_key: apiKey || null, model }),
  });
}

/** 智能匹配里命中的一条线上目录行。 */
export interface OcModelMatchCandidate {
  /** 目录里实际的模型 id（本地 id 归一化后可能对到它）。 */
  matched_id: string;
  /** 目录里给出这一行的供应商（如 deepseek、zhipuai）。 */
  provider: string;
  /** 可直接写进 opencode.json 的模型配置。 */
  config: Record<string, unknown>;
}

export interface OcModelMatchResult {
  catalog_ok: boolean;
  catalog_url?: string;
  /** 目录拉取失败的原因（catalog_ok=false 时才有）。 */
  catalog_error?: string;
  /** baseURL 被认成了目录里的哪一家；认不出为 null（此时全库匹配）。 */
  provider_hint?: string | null;
  matched: Record<string, OcModelMatchCandidate>;
  unmatched: string[];
}

/** 智能匹配：把模型 id 对到线上目录（models.dev）的最新配置项。结果作为数据返回。 */
export async function matchOcModelsLive(
  baseUrl: string,
  models: string[],
): Promise<OcModelMatchResult> {
  return api<OcModelMatchResult>("/api/opencode/models/match", {
    method: "POST",
    body: JSON.stringify({ base_url: baseUrl || null, models }),
  });
}
