import { useEffect, useState } from "react";
import { ArrowRight, LockKey, SealCheck, X } from "@phosphor-icons/react";
import { actions } from "../lib/actions";
import { STAGE_LABEL, STAGE_CHANGE_KIND_LABEL } from "../lib/format";
import { closeStageChangeConfirm, showToast, useApp } from "../lib/store";
import { Spinner } from "./ui";

const REASON_MAX = 2000;

/**
 * 终态流转确认弹窗（V19）：看板拖拽到「已完成 / 已取消」、或右侧面板「取消工单」时弹出。
 * 状态变更理由必填——与重启理由同一口径，记入工单的状态变更历史，可在工单信息里回看。
 * 「已完成」的强制收尾跳过门禁（不产生快照/审查/发布），「已取消」则锁定会话操作。
 */
export function StageChangeConfirmDialog() {
  const confirm = useApp((s) => s.stageChangeConfirm);
  const ticket = useApp((s) => {
    if (!s.stageChangeConfirm) return undefined;
    return s.tickets.find((t) => t.ticketNo === s.stageChangeConfirm?.ticketNo);
  });
  const [reason, setReason] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const open = confirm !== null && ticket !== undefined;

  // 目标工单/动作变化时重置输入，避免上一次的草稿串场
  useEffect(() => {
    if (open) setReason("");
  }, [confirm?.ticketNo, confirm?.to, open]);

  if (!open || !ticket) return null;

  const to = confirm.to;
  const complete = to === "DONE";
  const from = ticket.stage;
  const kindLabel = complete ? "强制已完成" : STAGE_CHANGE_KIND_LABEL.cancel;
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
      showToast(complete ? "工单已强制完成" : "工单已取消");
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 grid place-items-center bg-black/55 backdrop-blur-[2px]"
      onClick={close}
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
          <button className="icon-btn" onClick={close} aria-label="关闭">
            ✕
          </button>
        </div>

        <div className="p-5 space-y-4">
          <div className="rounded-lg border border-edge bg-sunken/60 px-3.5 py-2.5 text-[12px] text-dim leading-relaxed">
            该工单当前处于
            <span className="mx-1 font-medium text-ink">{STAGE_LABEL[from]}</span>
            状态，即将
            {complete ? (
              <>
                跳过门禁（不经过快照/审查/发布）直接标记为
                <span className="mx-1 font-medium text-accent">已完成</span>
              </>
            ) : (
              <>
                强制流转到
                <span className="mx-1 font-medium text-warn">已取消</span>
                ，会话与门禁操作将停用
              </>
            )}
            。
          </div>

          <div>
            <label className="field-label">
              状态变更理由<span className="text-danger">*</span>
            </label>
            <textarea
              className="text-input h-28 py-2 resize-none"
              placeholder={
                complete
                  ? "为什么要跳过门禁强制收尾？后续可在工单信息里回看…（必填）"
                  : "为什么要取消该工单？后续可在工单信息里回看…（必填）"
              }
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
            理由将记入工单的状态变更记录
            <span className="mx-1 inline-flex items-center gap-1 align-middle">
              <span className="font-medium text-dim">{STAGE_LABEL[from]}</span>
              <ArrowRight size={10} className="inline" />
              <span className="font-medium text-ink">{STAGE_LABEL[to]}</span>
            </span>
            ，与重启理由同一口径，可在工作台右侧「工单信息 → 状态记录」里查看。
          </div>
        </div>

        <div className="flex justify-end gap-2 px-5 py-4 border-t border-edge">
          <button className="btn" onClick={close}>
            取消
          </button>
          <button className={`btn btn-primary ${complete ? "" : "!bg-warn !border-warn"}`} disabled={!valid || submitting} onClick={submit}>
            {submitting ? (
              <>
                <Spinner />
                流转中…
              </>
            ) : complete ? (
              <>
                <SealCheck size={14} weight="fill" />
                确认强制完成
              </>
            ) : (
              <>
                <LockKey size={14} weight="fill" />
                确认取消工单
              </>
            )}
          </button>
        </div>
      </div>
    </div>
  );
}
