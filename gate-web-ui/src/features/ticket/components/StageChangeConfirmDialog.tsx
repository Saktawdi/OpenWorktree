import { useEffect, useState } from "react";
import { ArrowRight, LockKey, SealCheck, X } from "@phosphor-icons/react";
import { actions } from "@/app/actions";
import { stageChangeKindLabel, stageLabel } from "@/shared/format";
import { closeStageChangeConfirm } from "@/features/ticket";
import { showToast, useApp } from "@/store";
import { Spinner, useBackdropClose } from "@/shared/components/ui";
import { useT } from "@/i18n";

const REASON_MAX = 2000;

/**
 * 终态流转确认弹窗（V19）：看板拖拽到「已完成 / 已取消」、或右侧面板「取消工单」时弹出。
 * 状态变更理由必填——与重启理由同一口径，记入工单的状态变更历史，可在工单信息里回看。
 * 「已完成」的强制收尾跳过门禁（不产生快照/审查/发布），「已取消」则锁定会话操作。
 */
export function StageChangeConfirmDialog() {
  const t = useT();
  const confirm = useApp((s) => s.stageChangeConfirm);
  const ticket = useApp((s) => {
    if (!s.stageChangeConfirm) return undefined;
    return s.tickets.find((tk) => tk.ticketNo === s.stageChangeConfirm?.ticketNo);
  });
  const [reason, setReason] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const backdrop = useBackdropClose(() => close());

  const open = confirm !== null && ticket !== undefined;

  // 目标工单/动作变化时重置输入，避免上一次的草稿串场
  useEffect(() => {
    if (open) setReason("");
  }, [confirm?.ticketNo, confirm?.to, open]);

  if (!open || !ticket) return null;

  const to = confirm.to;
  const complete = to === "DONE";
  const from = ticket.stage;
  const kindLabel = complete ? t("stageChange.force_complete") : stageChangeKindLabel("cancel", t);
  const valid = reason.trim().length > 0 && reason.length <= REASON_MAX;

  const close = () => {
    if (submitting) return;
    closeStageChangeConfirm();
  };

  const submit = async () => {
    if (!valid || submitting) return;
    setSubmitting(true);
    const ok = complete
      ? await actions.completeTicket(ticket.ticketNo, reason.trim(), from)
      : await actions.cancelTicket(ticket.ticketNo, reason.trim(), from);
    setSubmitting(false);
    if (ok) {
      closeStageChangeConfirm();
      showToast(complete ? t("stageChange.doneToast") : t("stageChange.cancelToast"));
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 grid place-items-center bg-black/55 backdrop-blur-[2px]"
      {...backdrop}
    >
      <div
        className="w-[480px] card shadow-2xl shadow-black/60 animate-rise"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center gap-2.5 px-5 h-12 border-b border-edge">
          {complete ? (
            <SealCheck size={15} className="text-accent" weight="fill" />
          ) : (
            <LockKey size={15} className="text-warn" weight="fill" />
          )}
          <span className="font-mono text-[12.5px] text-accent">{ticket.ticketNo}</span>
          <span className="text-[13.5px] font-semibold">{kindLabel}</span>
          <span className="flex-1" />
          <button className="icon-btn" onClick={close} aria-label={t("common.close")}>
            ✕
          </button>
        </div>

        <div className="p-5 space-y-4">
          <div className="rounded-lg border border-edge bg-sunken/60 px-3.5 py-2.5 text-[12px] text-dim leading-relaxed">
            {complete
              ? t("stageChange.confirmLine.complete", {
                  from: stageLabel(from, t),
                  to: stageLabel("DONE", t),
                })
              : t("stageChange.confirmLine.cancel", { from: stageLabel(from, t) })}
          </div>

          <div>
            <label className="field-label">
              {t("stageChange.reasonLabel")}<span className="text-danger">*</span>
            </label>
            <textarea
              className="text-input h-28 py-2 resize-none"
              placeholder={complete ? t("stageChange.reasonPlaceholder.complete") : t("stageChange.reasonPlaceholder.cancel")}
              value={reason}
              maxLength={REASON_MAX}
              onChange={(e) => setReason(e.target.value)}
              autoFocus
            />
            <div className="mt-1 text-right font-mono text-[10.5px] text-faint">
              {reason.length}/{REASON_MAX}
            </div>
          </div>

          <div className="text-[11.5px] text-faint leading-relaxed">
            {t("stageChange.reasonHint")}
            <span className="mx-1 inline-flex items-center gap-1 align-middle">
              <span className="font-medium text-dim">{stageLabel(from, t)}</span>
              <ArrowRight size={10} className="inline" />
              <span className="font-medium text-ink">{stageLabel(to, t)}</span>
            </span>
            {t("stageChange.reasonHintTail")}
          </div>
        </div>

        <div className="flex justify-end gap-2 px-5 py-4 border-t border-edge">
          <button className="btn" onClick={close}>
            {t("common.cancel")}
          </button>
          <button className={`btn btn-primary ${complete ? "" : "!bg-warn !border-warn"}`} disabled={!valid || submitting} onClick={submit}>
            {submitting ? (
              <>
                <Spinner />
                {t("stageChange.submitting")}
              </>
            ) : complete ? (
              <>
                <SealCheck size={14} weight="fill" />
                {t("stageChange.submit.complete")}
              </>
            ) : (
              <>
                <LockKey size={14} weight="fill" />
                {t("stageChange.submit.cancel")}
              </>
            )}
          </button>
        </div>
      </div>
    </div>
  );
}
