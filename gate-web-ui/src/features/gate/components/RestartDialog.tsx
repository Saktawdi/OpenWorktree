import { useState } from "react";
import { ArrowCounterClockwise } from "@phosphor-icons/react";
import { actions } from "@/app/actions";
import { useApp } from "@/store";
import { openRestartDialog } from "@/features/ticket/state";
import { STAGE_LABEL } from "@/shared/format";
import { Spinner, useBackdropClose } from "@/shared/components/ui";

const RESTART_REASON_MAX = 2000;

/** 重启理由弹窗（T-117）：终态工单重启，理由必填并注入后续会话上下文。 */
export function RestartDialog({ ticketNo }: { ticketNo: string }) {
  const open = useApp((s) => s.restartDialogFor === ticketNo);
  const ticket = useApp((s) => s.tickets.find((t) => t.ticketNo === ticketNo));
  const round = useApp((s) => s.snapshots[ticketNo]?.length ?? 0);
  const [reason, setReason] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const backdrop = useBackdropClose(() => close());

  if (!open) return null;

  const valid = reason.trim().length > 0 && reason.length <= RESTART_REASON_MAX;

  const close = () => {
    openRestartDialog(null);
    setReason("");
  };

  const submit = async () => {
    if (!valid || submitting) return;
    setSubmitting(true);
    const ok = await actions.restartTicket(ticketNo, reason.trim());
    setSubmitting(false);
    if (ok) {
      close();
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
          <ArrowCounterClockwise size={15} className="text-accent" weight="fill" />
          <span className="font-mono text-[12.5px] text-accent">{ticketNo}</span>
          <span className="text-[13.5px] font-semibold">重启工单</span>
          <span className="flex-1" />
          <button className="icon-btn" onClick={close} aria-label="关闭">
            ✕
          </button>
        </div>

        <div className="p-5 space-y-4">
          <div className="rounded-lg border border-edge bg-sunken/60 px-3.5 py-2.5 text-[12px] text-dim leading-relaxed">
            该工单当前处于
            <span className="mx-1 font-medium text-ink">
              {ticket && <>{STAGE_LABEL[ticket.stage]}</>}
            </span>
            状态。重启后将进入
            <span className="mx-1 font-medium text-accent">进行中</span>
            ，预提审轮次自动进入第
            <span className="mx-0.5 font-mono text-ink">{round + 1}</span>轮。
          </div>

          <div>
            <label className="field-label">
              重启理由<span className="text-danger">*</span>
            </label>
            <textarea
              className="text-input h-28 py-2 resize-none"
              placeholder="为什么要重启该工单？本轮要达成什么目标…（必填）"
              value={reason}
              maxLength={RESTART_REASON_MAX}
              onChange={(e) => setReason(e.target.value)}
              autoFocus
            />
            <div className="mt-1 text-right font-mono text-[10.5px] text-faint">
              {reason.length}/{RESTART_REASON_MAX}
            </div>
          </div>

          <div className="text-[11.5px] text-faint leading-relaxed">
            重启理由将记入工单的重启历史，并注入后续会话的系统上下文。
          </div>
        </div>

        <div className="flex justify-end gap-2 px-5 py-4 border-t border-edge">
          <button className="btn" onClick={close}>
            取消
          </button>
          <button className="btn btn-primary" disabled={!valid || submitting} onClick={submit}>
            {submitting ? (
              <>
                <Spinner />
                重启中…
              </>
            ) : (
              <>
                <ArrowCounterClockwise size={14} weight="fill" />
                确认重启
              </>
            )}
          </button>
        </div>
      </div>
    </div>
  );
}
