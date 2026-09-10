import { useState } from "react";
import { SealCheck, ShieldCheck } from "@phosphor-icons/react";
import { actions } from "@/app/actions";
import { useBackdropClose } from "@/shared/components/ui";
import { useT } from "@/i18n";

/**
 * 人工审查确认弹窗（ReviewActions 触发；也被 FindingsView 复用）。
 * 理由必填（需求文档 §五.5）：人工核准是证据链上唯一"人说了算"的节点，
 * 不记录基于什么判断，"谁批准了发布"就只能回答"某个人"。
 */
export function ManualReviewDialog({
  ticketNo,
  round,
  busy,
  onClose,
}: {
  ticketNo: string;
  round: number;
  busy: boolean;
  onClose: () => void;
}) {
  const t = useT();
  const [note, setNote] = useState("");
  const backdrop = useBackdropClose(busy ? undefined : onClose);
  return (
    <div
      className="fixed inset-0 z-50 grid place-items-center bg-black/55 backdrop-blur-[2px]"
      {...backdrop}
    >
      <div
        className="w-[440px] card shadow-2xl shadow-black/60 animate-rise"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center gap-2.5 px-5 h-12 border-b border-edge">
          <ShieldCheck size={15} className="text-warn" weight="fill" />
          <span className="text-[13.5px] font-semibold">{t("manual.title")}</span>
          <span className="flex-1" />
          <button className="icon-btn" onClick={onClose} disabled={busy} aria-label={t("common.close")}>
            ✕
          </button>
        </div>
        <div className="p-5 space-y-3">
          <div className="text-[13px] leading-relaxed text-dim">
            {t("manual.confirmLine", { n: round })}
          </div>
          <div>
            <div className="field-label">{t("manual.reasonLabel")}</div>
            <textarea
              className="textarea mt-1 h-[64px] resize-none"
              placeholder={t("manual.reasonPlaceholder")}
              value={note}
              onChange={(e) => setNote(e.target.value)}
              disabled={busy}
            />
          </div>
          <div className="rounded-lg bg-sunken border border-edge px-3 py-2.5 text-[12px] leading-relaxed text-faint">
            {t("manual.note")}
          </div>
        </div>
        <div className="flex justify-end gap-2 px-5 pb-4">
          <button className="btn h-8 text-[12.5px]" onClick={onClose} disabled={busy}>
            {t("common.cancel")}
          </button>
          <button
            className="btn btn-primary h-8 text-[12.5px]"
            disabled={busy || note.trim().length === 0}
            title={note.trim() ? undefined : t("manual.reasonRequired")}
            onClick={() => {
              actions.reviewHuman(ticketNo, note.trim());
              onClose();
            }}
          >
            <SealCheck size={13} weight="fill" />
            {t("manual.confirm")}
          </button>
        </div>
      </div>
    </div>
  );
}
