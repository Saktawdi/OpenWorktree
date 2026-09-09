/**
 * 引用片段胶囊（QuoteChip）：划选文字「添加到对话框」/外部粘贴成段文字后的胶囊形态，
 * 也是用户消息气泡里引用标记（⟦引用⟧…⟦/引用⟧）渲染回来的形态。
 * 默认单行预览；悬停浮出固定尺寸卡片——内容多行省略，底部一行「引用自 xxx」
 * 来源标注（交互上用户自己框选的内容自己知道，卡片只承担"对上是哪段"的锚定）。
 * tipwrap 是 hover 桥（自带 6px padding 连通胶囊与提示卡，避免间隙处提示闪烁）。
 * 待发送态（Composer）额外提供改动出口：editable 时点胶囊主体=编辑原文，
 * 铅笔=转为正文（原文插回光标处、移除胶囊）；气泡还原态两出口都不渲染。
 * 注意「转为正文/移除」按钮必须是主体按钮的兄弟节点——button 嵌 button 非法。
 */
import { ArrowElbowDownLeft, Quotes, X } from "@phosphor-icons/react";
import { quotePreview } from "@/shared/quotes";

export function QuoteChip({
  text,
  source,
  onRemove,
  onEdit,
  onToText,
  tipRight = false,
}: {
  text: string;
  /** 划选来源标注（提示卡底部「引用自 xxx」）。 */
  source?: string;
  /** 提供时渲染右侧 × 移除按钮（Composer 待发送态）。 */
  onRemove?: () => void;
  /** 与 onRemove 同时提供：点胶囊主体打开原文编辑卡（待发送态的改动出口①）。 */
  onEdit?: () => void;
  /** 提供时渲染「转为正文」按钮：原文插回光标处并移除胶囊（改动出口②）。 */
  onToText?: () => void;
  /** 提示卡改为右对齐：用户气泡靠屏幕右缘，左对齐会探出视口。 */
  tipRight?: boolean;
}) {
  const editable = Boolean(onEdit);
  return (
    <span className={`quote-chip${tipRight ? " quote-chip-tipr" : ""}`}>
      {editable ? (
        <button
          type="button"
          className="quote-chip-body"
          title="编辑原文"
          aria-label="编辑引用原文"
          onClick={onEdit}
        >
          <Quotes size={11} weight="fill" className="quote-chip-icon" />
          <span className="quote-chip-preview">{quotePreview(text)}</span>
        </button>
      ) : (
        <>
          <Quotes size={11} weight="fill" className="quote-chip-icon" />
          <span className="quote-chip-preview">{quotePreview(text)}</span>
        </>
      )}
      {onToText && (
        <button
          type="button"
          className="quote-chip-x"
          title="转为正文：原文插回输入框，可随意改写"
          aria-label="转为正文"
          onClick={onToText}
        >
          <ArrowElbowDownLeft size={10} weight="bold" />
        </button>
      )}
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
        <span className="quote-chip-tip">
          <span className="quote-chip-tip-text">{text}</span>
          {source && (
            <span className="quote-chip-tip-source">
              <Quotes size={10} weight="fill" />
              引用自 {source}
            </span>
          )}
        </span>
      </span>
    </span>
  );
}
