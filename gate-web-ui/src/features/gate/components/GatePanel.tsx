import { motion } from "motion/react";
import { ArrowClockwise, ArrowCounterClockwise, Check, LockKey, RocketLaunch } from "@phosphor-icons/react";
import { actions } from "@/app/actions";
import { useApp } from "@/store";
import { openRestartDialog, openStageChangeConfirm } from "@/features/ticket/state";
import { SessionSection } from "@/features/session/components/SessionList";
import { Spinner } from "@/shared/components/ui";
import { useT } from "@/i18n";
import { TicketInfo } from "./TicketInfo";
import { GatePipeline } from "./GatePipeline";
import { QuickModeCard } from "./QuickModeCard";
import { ReviewActions } from "./ReviewActions";
import { RestartDialog } from "./RestartDialog";
import { StageChangesHistoryDialog } from "./StageChangesHistoryDialog";

/**
 * 右侧门禁面板（主组装件）：工单信息 + 门禁流水线/快速模式 + 会话列表 + 底部动作位。
 * 各段实现见同目录分文件；会话列表属于 session 域组件。
 */
export function GatePanel({ ticketNo }: { ticketNo: string }) {
  const t = useT();
  const stage = useApp((s) => s.tickets.find((t) => t.ticketNo === ticketNo)?.stage);
  const isSuperTicket = useApp((s) => s.tickets.find((t) => t.ticketNo === ticketNo)?.isSuper) ?? false;
  const snaps = useApp((s) => s.snapshots[ticketNo]);
  const task = useApp((s) => s.tasks[ticketNo]);
  const verdict = useApp((s) => s.verdicts[ticketNo]);
  const findingsCount = useApp((s) => s.findings[ticketNo]?.length ?? 0);
  const gateBusy = useApp((s) => s.gateBusy[ticketNo] ?? false);
  const diffCount = useApp((s) => s.diffs[ticketNo]?.length ?? 0);
  const outcome = useApp((s) => s.outcomes[ticketNo]);
  const sessionsExpanded = useApp((s) => s.gateSections.sessions);

  const snap = snaps?.[snaps.length - 1];
  const round = snaps?.length ?? 0;

  let action: { label: string; icon: React.ReactNode; onClick?: () => void; disabled?: boolean; hint?: string; primary?: boolean } | null = null;

  if (stage === "IN_PROGRESS" || stage === "REJECTED" || stage === "PENDING") {
    action = {
      label: t("gate.action.presubmit"),
      icon: <LockKey size={15} weight="fill" />,
      onClick: () => actions.presubmit(ticketNo),
      disabled: gateBusy || diffCount === 0,
      hint:
        diffCount === 0
          ? t("kanban.block.noChanges")
          : t("gate.action.presubmitHint"),
      primary: true,
    };
  } else if (stage === "PRESUBMITTED") {
    // 快照已锁定：渲染专用的 AI 审查 / 人工审查 双入口（见下方 ReviewActions）。
    action = null;
  } else if (stage === "IN_REVIEW") {
    action = {
      label: t("gate.action.reviewing"),
      icon: <Spinner />,
      disabled: true,
      hint: t("gate.action.reviewingHint"),
    };
  } else if (stage === "READY_TO_PUBLISH") {
    action = {
      label: t("gate.action.publish"),
      icon: <RocketLaunch size={15} weight="fill" />,
      onClick: () => actions.publish(ticketNo),
      disabled: gateBusy,
      hint: t("gate.action.publishHint"),
      primary: true,
    };
  } else if (stage === "DONE" || stage === "CANCELLED") {
    // T-117: 终态工单可重启 —— 底部主按钮位变为「重启工单」，弹窗填写理由。
    action = {
      label: t("gate.restart.title"),
      icon: <ArrowCounterClockwise size={15} weight="fill" />,
      onClick: () => openRestartDialog(ticketNo),
      hint:
        stage === "DONE"
          ? t("gate.restartHint.done")
          : t("gate.restartHint.cancelled"),
      primary: true,
    };
  } else if (stage === "NEEDS_HUMAN") {
    action = null;
  }
  // V19 快速模式超级工单：没有门禁动作位，底部只保留会话入口
  if (isSuperTicket) {
    action = null;
  }

  return (
    <aside className="w-[400px] shrink-0 border-l border-edge flex flex-col bg-canvas">
      {/* ─── Scrollable Content ─── */}
      <div className="flex-1 min-h-0 flex flex-col">
        {/* 工单信息 + 门禁流水线：会话列表收起时撑满整个上部（滚动视口用足空白，
            段头被自然推到底部）；展开时退回自然高度 + 65% 滚动上限，让位给会话列表。
            收展用 flexGrow/maxHeight 动画做空间重分配（basis auto、比例分配无钳制），
            与 SessionSection 的同一过渡曲线同步，消除瞬移 */}
        <motion.div
          className="min-h-0 overflow-y-auto"
          animate={{ flexGrow: sessionsExpanded ? 0 : 1, maxHeight: sessionsExpanded ? "65%" : "100%" }}
          transition={{ duration: 0.25, ease: [0.16, 1, 0.3, 1] }}
        >
          {/* Ticket Info */}
          <TicketInfo ticketNo={ticketNo} />

          {/* Gate Pipeline (collapsible) / V19 超级工单的快速模式说明 */}
          {isSuperTicket ? (
            <QuickModeCard />
          ) : (
            <GatePipeline
              ticketNo={ticketNo}
              stage={stage ?? "PENDING"}
              round={round}
              snap={snap}
              task={task}
              verdict={verdict}
              findingsCount={findingsCount}
              gateBusy={gateBusy}
              outcome={outcome}
            />
          )}
        </motion.div>

        {/* Session List (collapsible, fills the rest when expanded) */}
        <SessionSection ticketNo={ticketNo} locked={stage === "CANCELLED"} />
      </div>

      {/* ─── Action Button (sticky bottom) ─── */}
      {action && (
        <div className="shrink-0 border-t border-edge bg-surface p-3.5">
          {(stage === "IN_PROGRESS" || stage === "REJECTED" || stage === "PENDING") && (
            <button
              className="btn w-full mb-2"
              disabled={gateBusy}
              onClick={() => actions.syncBase(ticketNo)}
              title={t("gate.action.syncBaseTip")}
            >
              <ArrowClockwise size={15} weight="fill" />
              {t("gate.action.syncBase")}
            </button>
          )}
          <button
            className={`btn btn-lg w-full ${action.primary ? "btn-primary" : ""}`}
            disabled={action.disabled}
            onClick={action.onClick}
            title={action.hint}
          >
            {action.icon}
            {action.label}
          </button>
          {/* V19: 取消工单的常驻入口（拖拽之外的第二条路），理由必填 */}
          {!isSuperTicket && (stage === "IN_PROGRESS" || stage === "REJECTED" || stage === "PENDING") && (
            <button
              className="btn w-full mt-2"
              disabled={gateBusy}
              onClick={() => openStageChangeConfirm(ticketNo, "CANCELLED")}
              title={t("stageChange.cancelEntryTip")}
            >
              <LockKey size={15} weight="fill" />
              {t("stageChange.cancelEntry")}
            </button>
          )}
          {action.hint && <div className="mt-2 text-center text-[11.5px] text-faint">{action.hint}</div>}
        </div>
      )}
      {stage === "PRESUBMITTED" && (
        <ReviewActions ticketNo={ticketNo} gateBusy={gateBusy} round={round} />
      )}
      {stage === "DONE" && (
        <div className="shrink-0 border-t border-edge bg-surface p-3.5">
          <button className="btn btn-lg w-full" disabled>
            <Check size={15} weight="bold" />
            {t("gate.action.archived")}
          </button>
        </div>
      )}
      {stage === "CANCELLED" && (
        <div className="shrink-0 border-t border-edge bg-surface p-3.5">
          <button className="btn btn-lg w-full" disabled title={t("gate.action.cancelledTip")}>
            <LockKey size={15} weight="fill" />
            {t("gate.action.cancelledLocked")}
          </button>
          <div className="mt-2 text-center text-[11.5px] text-faint">{t("gate.action.cancelledNote")}</div>
        </div>
      )}
      {/* ─── T-117 重启理由弹窗 / V19 状态变更记录弹窗 ─── */}
      <RestartDialog ticketNo={ticketNo} />
      <StageChangesHistoryDialog ticketNo={ticketNo} />
    </aside>
  );
}
