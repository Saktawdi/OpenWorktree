/**
 * OpenCode 供应商管理的常量与模型配置解析（opencode presets）。
 * 面向 opencode.json(c) provider 节点的模型条目形状。
 */
import type { OpenCodeModelEntry } from "@/shared/types";

export const NPM_PRESETS = [
  "@ai-sdk/openai-compatible",
  "@ai-sdk/openai",
  "@ai-sdk/anthropic",
  "@ai-sdk/google",
  "@ai-sdk/xai",
];

/** 保存前的上游探测结果（拉取模型 / 连通测试共用一个展示位）。 */
export interface ProbeState {
  kind: "idle" | "fetching" | "testing" | "done" | "error";
  ok?: boolean;
  text?: string;
}

/** opencode 模型配置里已知的结构化字段；其余键作为「额外参数」JSON 透传。 */
export const KNOWN_MODEL_KEYS = new Set([
  "name",
  "limit",
  "modalities",
  "reasoning",
  "tool_call",
  "temperature",
  "attachment",
  "variants",
]);

export const MODALITY_PRESETS = ["text", "image", "pdf", "video", "audio"];

/** 限制档位（模仿 ai-toolbox）：上下文给大档位，输出给小档位，也可手输任意值。 */
export const CONTEXT_LIMIT_PRESETS = ["16K", "32K", "64K", "128K", "200K", "256K", "1M", "2M"];
export const OUTPUT_LIMIT_PRESETS = ["2K", "4K", "8K", "16K", "32K", "64K"];

/** 模型变体一键模板：低/高 + 顶档两档，顶档推理强度分别为 max / xhigh。 */
export const VARIANT_TEMPLATES = [
  {
    label: "max 模板",
    title: "填入 low/high/max 三档变体，最高推理到 max",
    json: `{
  "low": {
    "reasoningEffort": "low"
  },
  "high": {
    "reasoningEffort": "high"
  },
  "max": {
    "reasoningEffort": "max"
  }
}`,
  },
  {
    label: "xhigh 模板",
    title: "填入 low/high/xhigh 三档变体，最高推理到 xhigh",
    json: `{
  "low": {
    "reasoningEffort": "low"
  },
  "high": {
    "reasoningEffort": "high"
  },
  "xhigh": {
    "reasoningEffort": "xhigh"
  }
}`,
  },
];

/** 200000 → "200K"、1000000 → "1M"；非整除的值原样显示。 */
export function formatLimit(v: unknown): string {
  const n = typeof v === "number" ? v : Number(v);
  if (Number.isFinite(n) && n > 0) {
    if (n % 1_000_000 === 0) return `${n / 1_000_000}M`;
    if (n % 1000 === 0) return `${n / 1000}K`;
  }
  return String(v);
}

/** 解析限制输入：支持 "200K" / "1M" 简写与纯数字；非法返回 null。 */
export function parseLimit(raw: string): number | null {
  const s = raw.trim().toUpperCase();
  if (!s) return null;
  const m = s.match(/^(\d+(?:\.\d+)?)([KM])$/);
  if (m) {
    const base = Number(m[1]);
    if (!Number.isFinite(base)) return null;
    return Math.round(base * (m[2] === "K" ? 1000 : 1_000_000));
  }
  return /^\d+$/.test(s) ? Number(s) : null;
}

/** 兼容旧持久化形状（string[]）：统一归一为 {id, config} 条目。 */
export function normalizeModelEntries(models: (OpenCodeModelEntry | string)[] | undefined): OpenCodeModelEntry[] {
  return (models ?? []).map((m) => (typeof m === "string" ? { id: m, config: {} } : m));
}

export function parseModelConfig(cfg: Record<string, unknown>) {
  const limit = (cfg.limit ?? {}) as Record<string, unknown>;
  const mods = (cfg.modalities ?? {}) as Record<string, unknown>;
  const extra: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(cfg)) {
    if (!KNOWN_MODEL_KEYS.has(k)) extra[k] = v;
  }
  return {
    name: typeof cfg.name === "string" ? cfg.name : "",
    context: limit.context != null ? formatLimit(limit.context) : "",
    output: limit.output != null ? formatLimit(limit.output) : "",
    input: Array.isArray(mods.input) ? (mods.input as string[]) : [],
    outputMods: Array.isArray(mods.output) ? (mods.output as string[]) : [],
    reasoning: cfg.reasoning === true,
    toolCall: cfg.tool_call === true,
    temperature: cfg.temperature === true,
    attachment: cfg.attachment === true,
    variants: cfg.variants != null ? JSON.stringify(cfg.variants, null, 2) : "",
    extra: Object.keys(extra).length > 0 ? JSON.stringify(extra, null, 2) : "",
  };
}
