/**
 * 快捷语录数据模型：类型/显隐条件/内置种子/插件贡献点映射。
 * 原 Composer 硬编码 quick 数组的完整迁移（含原有显隐语义），外加 MCP 工具语录类型。
 */
import type { ChatInputActionContribution, ChatInputState } from "@gate/plugin-sdk";

export type QuoteKind = "message" | "presubmit" | "findings" | "mcp";
export type QuoteWhen = "always" | "has_diffs" | "has_diffs_active" | "rejected_findings" | "has_restarts";

export interface QuoteItem {
  id: string;
  label: string;
  kind: QuoteKind;
  when: QuoteWhen;
  /** message/mcp 的发送文案；功能类（presubmit/findings）不需要 */
  prompt?: string;
  /** kind === "mcp" 绑定的 MCP 工具名（agent 域工具，如 presubmit_create） */
  mcpTool?: string;
  icon?: string;
  /** 内置条目：可编辑可删除，可通过「恢复内置」找回 */
  builtin?: boolean;
}

const KINDS: QuoteKind[] = ["message", "presubmit", "findings", "mcp"];
const WHENS: QuoteWhen[] = ["always", "has_diffs", "has_diffs_active", "rejected_findings", "has_restarts"];

export const KIND_LABEL: Record<QuoteKind, string> = {
  message: "消息",
  presubmit: "功能 · 预提审",
  findings: "功能 · 按意见修复",
  mcp: "MCP 工具",
};

export const WHEN_LABEL: Record<QuoteWhen, string> = {
  always: "始终显示",
  has_diffs: "有工作区变更",
  has_diffs_active: "有变更且工单进行中",
  rejected_findings: "审查驳回且有意见",
  has_restarts: "工单重启过",
};

/** 宿主图标白名单中适合语录的可选值。 */
export const ICON_CHOICES = [
  "ChatText",
  "LockKey",
  "Eye",
  "TerminalWindow",
  "CheckCircle",
  "Wrench",
  "PlugsConnected",
  "Lightning",
  "Brain",
  "Bug",
  "Rocket",
  "ShieldCheck",
] as const;

/* 与 Composer 原逻辑逐条对应的显隐判定 */
const WHEN_PREDICATE: Record<QuoteWhen, (s: ChatInputState) => boolean> = {
  always: () => true,
  has_diffs: (s) => s.diffs > 0,
  has_diffs_active: (s) => s.diffs > 0 && !s.terminal,
  rejected_findings: (s) => s.findingsCount > 0 && s.stage === "REJECTED",
  has_restarts: (s) => s.restartCount > 0,
};

export function mcpPromptOf(q: QuoteItem): string {
  const p = (q.prompt ?? "").trim();
  return p || `请调用 MCP 工具 ${q.mcpTool ?? ""} 完成相应操作。`;
}

/** 语录 → composer 快捷 chip 贡献点。 */
export function toAction(q: QuoteItem): ChatInputActionContribution {
  return {
    id: `quote-${q.id}`,
    label: q.label,
    icon: q.icon,
    when: WHEN_PREDICATE[q.when] ?? WHEN_PREDICATE.always,
    run: (api) => {
      if (q.kind === "presubmit") return api.presubmit();
      if (q.kind === "findings") return api.returnWithFindings();
      if (q.kind === "mcp") return api.sendPrompt(mcpPromptOf(q));
      api.sendPrompt(q.prompt ?? "");
    },
  };
}

/**
 * 原生 chip 回迁宿主后被移除的内置种子 id（round 2）。
 * 存量用户 KV 里还留着这五条，activate 时按此清单过滤回写，避免与宿主原生 chip 重复。
 */
export const NATIVE_BUILTIN_IDS = [
  "builtin-presubmit",
  "builtin-explain-diff",
  "builtin-unit-tests",
  "builtin-finish",
  "builtin-fix-findings",
] as const;

/**
 * 内置种子：原生五条已回迁宿主（见 NATIVE_BUILTIN_IDS），仅保留 MCP 工具语录示例。
 * 「恢复内置」按 mergeBuiltins 合并本清单。
 */
export const BUILTIN_QUOTES: QuoteItem[] = [
  {
    id: "builtin-mcp-presubmit",
    label: "MCP 预提审",
    kind: "mcp",
    when: "has_diffs_active",
    mcpTool: "presubmit_create",
    prompt: "请调用 MCP 工具 presubmit_create：把当前工单工作区冻结为不可变树并启动一轮审查。",
    icon: "PlugsConnected",
    builtin: true,
  },
];

/* ─── 外部数据（KV / 导入 JSON）清洗 ─── */

const text = (v: unknown, max: number): string | null => {
  if (typeof v !== "string") return null;
  const s = v.trim();
  return s ? s.slice(0, max) : null;
};

function sanitizeItem(v: unknown): QuoteItem | null {
  if (!v || typeof v !== "object") return null;
  const r = v as Record<string, unknown>;
  const id = text(r.id, 64);
  const label = text(r.label, 32);
  if (!id || !label) return null;
  const kind = KINDS.includes(r.kind as QuoteKind) ? (r.kind as QuoteKind) : "message";
  const when = WHENS.includes(r.when as QuoteWhen) ? (r.when as QuoteWhen) : "always";
  const icon = typeof r.icon === "string" && (ICON_CHOICES as readonly string[]).includes(r.icon) ? r.icon : undefined;
  const mcpTool = text(r.mcpTool, 64) ?? undefined;
  return {
    id,
    label,
    kind,
    when,
    prompt: typeof r.prompt === "string" ? r.prompt.slice(0, 2000) : "",
    mcpTool: kind === "mcp" ? mcpTool : undefined,
    icon,
    builtin: r.builtin === true,
  };
}

/** 数组级清洗：丢弃非法条目与重复 id，保留顺序。 */
export function sanitizeQuotes(raw: unknown): QuoteItem[] {
  if (!Array.isArray(raw)) return [];
  const seen = new Set<string>();
  const out: QuoteItem[] = [];
  for (const item of raw) {
    const q = sanitizeItem(item);
    if (!q || seen.has(q.id)) continue;
    seen.add(q.id);
    out.push(q);
  }
  return out;
}

/** 合并回缺失的内置条目（「恢复内置」：按 id 去重，追加在末尾）。 */
export function mergeBuiltins(current: QuoteItem[]): QuoteItem[] {
  const ids = new Set(current.map((q) => q.id));
  const missing = BUILTIN_QUOTES.filter((b) => !ids.has(b.id));
  return missing.length > 0 ? [...current, ...missing] : current;
}

export function newQuoteId(): string {
  return "q-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2, 6);
}
