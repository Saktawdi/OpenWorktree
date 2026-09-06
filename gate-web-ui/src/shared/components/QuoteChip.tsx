/**
 * 引用片段胶囊（QuoteChip）：划选文字「添加到对话框」后的胶囊形态，也是
 * 用户消息气泡里引用标记（⟦引用⟧…⟦/引用⟧）渲染回来的形态。
 * 默认单行预览，悬停/聚焦（focus-within）浮出完整原文；纯 CSS 提示，随主题换肤。
 * tipwrap 是 hover 桥（自带 6px padding 连通胶囊与提示框，避免间隙处提示闪烁）。
 */
import { Quotes, X } from "@phosphor-icons/react";
import { quotePreview } from "@/shared/quotes";

export function QuoteChip({
  text,
  onRemove,
  tipRight = false,
}: {
  text: string;
  /** 提供时渲染右侧 × 移除按钮（Composer 待发送态）。 */
  onRemove?: () => void;
  /** tooltip 改为右对齐：用户气泡靠屏幕右缘，左对齐会探出视口。 */
  tipRight?: boolean;
}) {
  return (
    <span className={`quote-chip${tipRight ? " quote-chip-tipr" : ""}`}>
      <Quotes size={11} weight="fill" className="quote-chip-icon" />
      <span className="quote-chip-preview">{quotePreview(text)}</span>
      {onRemove && (
        <button
          type="button"
          className="quote-chip-x"
          title="移除引用"
          aria-label="移除引用"
          onClick={onRemove}
        >
          <X size={10} weight="bold" />
        </button>
      )}
      <span className="quote-chip-tipwrap" role="tooltip">
        <span className="quote-chip-tip">{text}</span>
      </span>
    </span>
  );
}
