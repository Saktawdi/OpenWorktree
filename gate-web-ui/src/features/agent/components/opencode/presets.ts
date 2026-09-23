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

/** 限制档位：上下文给大档位，输出给小档位，也可手输任意值。 */
export const CONTEXT_LIMIT_PRESETS = ["16K", "32K", "64K", "128K", "200K", "256K", "1M", "2M"];
export const OUTPUT_LIMIT_PRESETS = ["2K", "4K", "8K", "16K", "32K", "64K"];

/** 模型变体一键模板：低/高 + 顶档两档，顶档推理强度分别为 max / xhigh。 */
export const VARIANT_TEMPLATES = [
  {
    labelKey: "ocm.presetMax",
    titleKey: "ocm.presetMaxTip",
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
    labelKey: "ocm.presetXhigh",
    titleKey: "ocm.presetXhighTip",
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
] as const;

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

/** 模型行上渲染的小标签。 */
export interface ModelTag {
  kind: "context" | "vision";
  /** 展示文本：context 是量级缩写（1M，与语言无关）；vision 由组件按语言取词，此处为空。 */
  label: string;
}

/**
 * 从模型配置里推导展示用小标签（对应 zcode 模型列表里 1M / 视觉 那种标）。
 *
 * 只标「值得一眼看到」的能力，不做全量罗列：上下文到百万级才给标的位数（1M），够不到就不标——
 * 每个模型都挂一串标反而让列表没法扫。图片输入即「视觉」。
 */
export function modelTags(cfg: Record<string, unknown>): ModelTag[] {
  const tags: ModelTag[] = [];
  const limit = (cfg.limit ?? {}) as Record<string, unknown>;
  const ctx = typeof limit.context === "number" ? limit.context : Number(limit.context);
  if (Number.isFinite(ctx) && ctx >= 1_000_000) {
    // 1048576 / 1050000 这类非整百万的上下文，标成 1M 才是它想表达的量级。
    tags.push({ kind: "context", label: `${Math.floor(ctx / 1_000_000)}M` });
  }
  const mods = (cfg.modalities ?? {}) as Record<string, unknown>;
  const inputs = Array.isArray(mods.input) ? (mods.input as unknown[]).map(String) : [];
  if (inputs.includes("image")) {
    tags.push({ kind: "vision", label: "" });
  }
  return tags;
}

function isPlainObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

/**
 * 用线上匹配结果补空缺：已有键（无论多深）一律保持不动，只补上没有的。
 *
 * 这样「智能匹配」对已手改过的模型是幂等的——重复匹配不会把用户调过的 limit 或模态冲掉，
 * 对新勾选的空配置模型则等于整份填入。
 */
export function fillGaps(
  target: Record<string, unknown>,
  defaults: Record<string, unknown>,
): Record<string, unknown> {
  const out: Record<string, unknown> = { ...target };
  for (const [k, v] of Object.entries(defaults)) {
    const cur = out[k];
    if (cur === undefined) {
      out[k] = v;
    } else if (isPlainObject(cur) && isPlainObject(v)) {
      out[k] = fillGaps(cur, v);
    }
    // 其余情况（标量/数组已有值）以用户现有的为准。
  }
  return out;
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
