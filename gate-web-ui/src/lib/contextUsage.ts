import type { ChatItem, ContextUsageState } from "./types";

/** openchamber 同款兜底：模型未暴露上下文上限时的默认窗口。 */
export const DEFAULT_CONTEXT_LIMIT = 200_000;

/** 上下文环颜色分档阈值（60/85）：充足绿、过渡黄、快满红。 */
export const CONTEXT_WARN_PERCENT = 60;
export const CONTEXT_CRITICAL_PERCENT = 85;

export type UsageTone = "ok" | "warn" | "critical";

export function usageTone(percent: number): UsageTone {
  if (percent >= CONTEXT_CRITICAL_PERCENT) return "critical";
  if (percent >= CONTEXT_WARN_PERCENT) return "warn";
  return "ok";
}

export function toneColor(tone: UsageTone): string {
  return tone === "critical"
    ? "var(--color-danger)"
    : tone === "warn"
      ? "var(--color-warn)"
      : "var(--color-accent)";
}

/**
 * 粗略 token 估算：CJK 字符按 ~1 token/字，其余按 ~1 token/4 字符。
 * 仅用于无 usage 数据时的兜底展示，不追求精确。
 */
export function estimateTokens(text: string): number {
  if (!text) return 0;
  let cjk = 0;
  for (const ch of text) {
    const code = ch.codePointAt(0) ?? 0;
    if (code > 0x2e7f) cjk += 1;
  }
  const other = text.length - cjk;
  return Math.ceil(cjk + other / 4);
}

export interface ContextBreakdown {
  user: number;
  assistant: number;
  tool: number;
  other: number;
}

/** 按角色估算会话上下文构成（token 近似值）。 */
export function computeContextBreakdown(items: ChatItem[]): ContextBreakdown {
  const breakdown: ContextBreakdown = { user: 0, assistant: 0, tool: 0, other: 0 };
  for (const item of items) {
    if (item.kind === "user") {
      breakdown.user += estimateTokens(item.text);
    } else if (item.kind === "assistant") {
      let sum = estimateTokens(item.text);
      if (item.thinking?.text) sum += estimateTokens(item.thinking.text);
      breakdown.assistant += sum;
      for (const t of item.tools) {
        breakdown.tool +=
          estimateTokens(t.argsSummary) + estimateTokens(t.resultSummary ?? "") +
          estimateTokens(t.resultDetail ?? "");
      }
    } else {
      // system / permission 等辅助内容归入「其他」
      breakdown.other += estimateTokens(item.kind === "system" ? item.text : "");
      if (item.kind === "permission") {
        breakdown.other += estimateTokens(JSON.stringify(item.request));
      }
    }
  }
  return breakdown;
}

export function breakdownPercents(b: ContextBreakdown): ContextBreakdown {
  const total = b.user + b.assistant + b.tool + b.other;
  if (total <= 0) return { user: 0, assistant: 0, tool: 0, other: 0 };
  const pct = (v: number) => Math.round((v / total) * 100);
  return { user: pct(b.user), assistant: pct(b.assistant), tool: pct(b.tool), other: pct(b.other) };
}

/**
 * 上下文环百分比：token 实测优先（最新一轮窗口占用 ÷ 上限），
 * 无 usage 时回退为按会话字符估算 tokens 再除以上限。
 */
export function computeContextPercent(
  ctx: ContextUsageState | undefined,
  items: ChatItem[],
): { percent: number; tokens: number; limit: number; estimated: boolean } | null {
  const limit = ctx?.limit && ctx.limit > 0 ? ctx.limit : DEFAULT_CONTEXT_LIMIT;
  const measured = ctx?.tokens ?? 0;
  if (measured > 0) {
    return { percent: (measured / limit) * 100, tokens: measured, limit, estimated: false };
  }
  const estimated = Object.values(computeContextBreakdown(items)).reduce((a, b) => a + b, 0);
  if (estimated <= 0) return null;
  return { percent: (estimated / limit) * 100, tokens: estimated, limit, estimated: true };
}

export function formatTokens(value: number): string {
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`;
  if (value >= 1_000) return `${(value / 1_000).toFixed(1)}K`;
  return String(Math.round(value));
}
