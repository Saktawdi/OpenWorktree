import { ArrowRight, ClockCounterClockwise } from "@phosphor-icons/react";
import { useApp } from "@/store";
import { openStageChangesView } from "@/features/ticket/state";
import { STAGE_CHANGE_KIND_LABEL, STAGE_LABEL } from "@/shared/format";
import { useBackdropClose } from "@/shared/components/ui";

/** 状态变更历史弹窗（V19）：重启/强制已完成/取消记录，重启行同表展示。 */
export function StageChangesHistoryDialog({ ticketNo }: { ticketNo: string }) {
  const open = useApp((s) => s.stageChangesViewFor === ticketNo);
  const rows = useApp((s) => s.stageChanges[ticketNo]);
  const backdrop = useBackdropClose(() => openStageChangesView(null));

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-50 grid place-items-center bg-black/55 backdrop-blur-[2px]"
      {...backdrop}
    >
      <div
        className="w-[520px] card shadow-2xl shadow-black/60 animate-rise"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center gap-2.5 px-5 h-12 border-b border-edge">
          <ClockCounterClockwise size={15} className="text-dim" />
          <span className="font-mono text-[12.5px] text-accent">{ticketNo}</span>
          <span className="text-[13.5px] font-semibold">状态变更记录</span>
          <span className="flex-1" />
          <button className="icon-btn" onClick={() => openStageChangesView(null)} aria-label="关闭">
            ✕
          </button>
        </div>

        <div className="p-5 max-h-[60vh] overflow-y-auto">
          {!rows || rows.length === 0 ? (
            <div className="py-8 text-center text-[12.5px] text-faint">
              该工单还没有状态变更记录
            </div>
          ) : (
            <div className="space-y-3">
              {[...rows].reverse().map((r, i) => (
                <div key={i} className="rounded-lg border border-edge bg-sunken/40 p-3.5">
                  <div className="flex items-center gap-2 text-[12px]">
                    <span className="chip border border-edge-strong bg-raised text-dim">
                      {STAGE_CHANGE_KIND_LABEL[r.kind] ?? r.kind}
                    </span>
                    <span className="chip border border-accent/30 bg-accent/10 text-accent font-mono">
                      第 {r.round} 轮
                    </span>
                    <span className="text-dim">{STAGE_LABEL[r.fromStage] ?? r.fromStage}</span>
                    <ArrowRight size={11} className="text-faint" />
                    <span className="text-ink font-medium">{STAGE_LABEL[r.toStage] ?? r.toStage}</span>
                    <span className="flex-1" />
                    <span className="font-mono text-[10.5px] text-faint">
                      {r.createdAt ? new Date(r.createdAt).toLocaleString("zh-CN") : "—"}
                    </span>
                  </div>
                  <div className="mt-2 text-[12.5px] text-dim leading-relaxed whitespace-pre-wrap">
                    {r.reason}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        <div className="flex justify-end gap-2 px-5 py-4 border-t border-edge">
          <button className="btn" onClick={() => openStageChangesView(null)}>
            关闭
          </button>
        </div>
      </div>
    </div>
  );
}
