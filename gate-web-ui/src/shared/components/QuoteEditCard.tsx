/**
 * 引用胶囊的原文编辑卡（QuoteEditCard）：待发送胶囊点「编辑原文」后浮出的小卡，
 * 挂在 Composer 输入卡顶部——textarea 预填全文，保存回写胶囊（清空保存=删除胶囊），
 * Esc 取消回到胶囊态。编辑是「贴过来改两个词再发」的就地出口；想大改/当普通文字
 * 用则走胶囊上的「转为正文」。
 */
import { useEffect, useRef, useState } from "react";
import { PencilSimpleLine, X } from "@phosphor-icons/react";
import { quotePreview } from "@/shared/quotes";
import { useT } from "@/i18n";

export function QuoteEditCard({
  initialText,
  onSave,
  onClose,
}: {
  initialText: string;
  /** 保存：传出编辑后的全文（未改动则原样），由调用方回写胶囊。 */
  onSave: (text: string) => void;
  onClose: () => void;
}) {
  const t = useT();
  const [text, setText] = useState(initialText);
  const taRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    const ta = taRef.current;
    if (!ta) return;
    ta.focus();
    // 光标置于文末，便于直接续写；长文粘进小卡时不被光标跳到开头打断。
    ta.setSelectionRange(ta.value.length, ta.value.length);
  }, []);

  /** 高度自适应：与 Composer textarea 同策略（下限 2 行，上限 160px）。 */
  const resize = () => {
    const ta = taRef.current;
    if (!ta) return;
    ta.style.height = "0px";
    ta.style.height = Math.min(160, Math.max(52, ta.scrollHeight)) + "px";
  };

  useEffect(() => {
    resize();
  }, [text]);

  const save = () => {
    onSave(text);
    onClose();
  };

  return (
    <div className="quote-edit-card">
      <div className="flex items-center gap-1.5">
        <PencilSimpleLine size={12} className="text-info shrink-0" weight="fill" />
        <span className="text-[11px] font-medium text-dim">{t("quote.editAria")}</span>
        <span className="text-[10.5px] text-faint truncate max-w-[300px]">
          {quotePreview(initialText, 28)}
        </span>
        <span className="flex-1" />
        <span className="font-mono text-[10.5px] text-faint">{t("edit.charCount", { n: text.length })}</span>
        <button
          type="button"
          className="quote-chip-x"
          title={t("quote.editCancelTip")}
          aria-label={t("common.cancel")}
          onClick={onClose}
        >
          <X size={10} weight="bold" />
        </button>
      </div>
      <textarea
        ref={taRef}
        value={text}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          // Enter 保留换行（编辑的正是多行原文）；Ctrl/Cmd+Enter 保存，Esc 取消。
          if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
            e.preventDefault();
            save();
          } else if (e.key === "Escape") {
            e.preventDefault();
            e.stopPropagation();
            onClose();
          }
        }}
        rows={2}
        className="quote-edit-ta"
      />
      <div className="flex items-center gap-2">
        <span className="flex-1 text-[10.5px] text-faint">{t("quote.editHint")}</span>
        <button
          type="button"
          className="btn h-7 px-3 text-[12px]"
          onClick={onClose}
        >
          {t("common.cancel")}
        </button>
        <button
          type="button"
          className="btn h-7 px-3 text-[12px] !border-accent/50 !text-accent disabled:opacity-50"
          disabled={text.trim() === ""}
          onClick={save}
        >
          {t("common.save")}
        </button>
      </div>
    </div>
  );
}
