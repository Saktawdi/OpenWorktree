/**
 * 引用片段胶囊（QuoteChip）的内联标记协议：
 * 选中文本通过「添加到对话框」变成待发送胶囊，发送时以 ⟦引用⟧…⟦/引用⟧ 包裹内联进
 * 消息纯文本——网关/Agent 侧看到的仍是普通文字（Agent 读到原文，无任何协议开销），
 * 前端渲染用户气泡时再由 parseQuotedText 把标记还原成胶囊。
 *
 * 之所以选 ⟦⟧ 这类成对罕见符号而非 markdown 引用：用户消息走 Markdown 渲染，
 * 常见符号（**、>、~~ 等）会被 markdown 吞掉或转义变形，标记必须在
 * Markdown 渲染前可被原样解析回来；⟦⟧ 不属于任何 markdown 语法且与正常行文冲突概率极低。
 */

export const QUOTE_OPEN = "⟦引用⟧";
export const QUOTE_CLOSE = "⟦/引用⟧";

/** 引用片段在气泡/胶囊上展示的最大字符数（超过截断加 …，完整文本靠 hover 提示）。 */
export const QUOTE_PREVIEW_MAX = 42;

/** 单条引用片段的原文上限（字符）：极端大段选择在入口处截断，防止撑爆消息体。 */
export const QUOTE_MAX_CHARS = 2000;

/** 把一段选中文本包成内联标记；原样保留换行（渲染层负责折叠显示）。 */
export function wrapQuote(text: string): string {
  return `${QUOTE_OPEN}${text}${QUOTE_CLOSE}`;
}

/** 去掉文本中的所有引用标记外壳（保留引用的原文），用于复制/字数统计等纯文本场景。 */
export function stripQuoteMarkers(text: string): string {
  return text.split(QUOTE_OPEN).join("").split(QUOTE_CLOSE).join("");
}

/** 胶囊/hover 上的短预览：压平空白后按字符数截断。 */
export function quotePreview(text: string, max = QUOTE_PREVIEW_MAX): string {
  const flat = text.replace(/\s+/g, " ").trim();
  return flat.length > max ? `${flat.slice(0, max)}…` : flat;
}

export type QuoteSegment =
  | { kind: "text"; text: string }
  | { kind: "quote"; text: string };

/**
 * 把含引用标记的文本切成段落流（无标记时返回 null，调用方按普通文本渲染）。
 * 只认完整成对的标记；未闭合的 QUOTE_OPEN 按普通文本处理，避免误吞后文。
 */
export function parseQuotedText(text: string): QuoteSegment[] | null {
  if (!text.includes(QUOTE_OPEN)) return null;
  const segments: QuoteSegment[] = [];
  let rest = text;
  let sawQuote = false;
  while (rest.length > 0) {
    const open = rest.indexOf(QUOTE_OPEN);
    if (open < 0) {
      segments.push({ kind: "text", text: rest });
      break;
    }
    if (open > 0) segments.push({ kind: "text", text: rest.slice(0, open) });
    rest = rest.slice(open + QUOTE_OPEN.length);
    const close = rest.indexOf(QUOTE_CLOSE);
    if (close < 0) {
      // 未闭合：开标记退还为普通文本，剩余内容原样输出
      segments.push({ kind: "text", text: QUOTE_OPEN + rest });
      break;
    }
    segments.push({ kind: "quote", text: rest.slice(0, close) });
    sawQuote = true;
    rest = rest.slice(close + QUOTE_CLOSE.length);
  }
  return sawQuote ? segments : null;
}
