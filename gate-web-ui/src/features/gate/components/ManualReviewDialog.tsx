import { SealCheck, ShieldCheck } from "@phosphor-icons/react";
import { actions } from "@/app/actions";
import { useBackdropClose } from "@/shared/components/ui";

/** 人工审查确认弹窗（ReviewActions 触发；也被 FindingsView 复用）。 */
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
  const backdrop = useBackdropClose(busy ? undefined : onClose);
  return (
    <div
      className="fixed inset-0 z-50 grid place-items-center bg-black/55 backdrop-blur-[2px]"
      {...backdrop}
    >
      <div
        className="w-[420px] card shadow-2xl shadow-black/60 animate-rise"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center gap-2.5 px-5 h-12 border-b border-edge">
          <ShieldCheck size={15} className="text-warn" weight="fill" />
          <span className="text-[13.5px] font-semibold">人工审查确认</span>
          <span className="flex-1" />
          <button className="icon-btn" onClick={onClose} disabled={busy} aria-label="关闭">
            ✕
          </button>
        </div>
        <div className="p-5 space-y-3">
          <div className="text-[13px] leading-relaxed text-dim">
            确认已人工审阅第 {round} 轮快照的全部变更？
          </div>
          <div className="rounded-lg bg-sunken border border-edge px-3 py-2.5 text-[12px] leading-relaxed text-faint">
            确认后本轮判决以人工核准为准，审查引擎不再参与；工单将进入「待发布」，
            可直接一键安全发布。此操作会记入审计日志。
          </div>
        </div>
        <div className="flex justify-end gap-2 px-5 pb-4">
          <button className="btn h-8 text-[12.5px]" onClick={onClose} disabled={busy}>
            取消
          </button>
          <button
            className="btn btn-primary h-8 text-[12.5px]"
            disabled={busy}
            onClick={() => {
              actions.reviewHuman(ticketNo);
              onClose();
            }}
          >
            <SealCheck size={13} weight="fill" />
            确认已审阅并放行
          </button>
        </div>
      </div>
    </div>
  );
}
