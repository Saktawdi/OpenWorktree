import { useEffect } from "react";
import { motion } from "motion/react";
import { ArrowClockwise, ArrowCounterClockwise, Check, LockKey, RocketLaunch } from "@phosphor-icons/react";
import { actions } from "@/app/actions";
import { useApp } from "@/store";
import { collapseGateSectionsForPresubmit } from "@/features/gate/state";
import { openRestartDialog, openStageChangeConfirm } from "@/features/ticket/state";
import { SessionSection } from "@/features/session/components/SessionList";
import { Spinner } from "@/shared/components/ui";
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

  // 进入预提审（快照已锁定）时自动收叠工单信息与会话列表，把纵向空间让给快照/判决卡片。
  useEffect(() => {
    if (stage === "PRESUBMITTED") collapseGateSectionsForPresubmit();
  }, [stage, ticketNo]);

  let action: { label: string; icon: React.ReactNode; onClick?: () => void; disabled?: boolean; hint?: string; primary?: boolean } | null = null;

  if (stage === "IN_PROGRESS" || stage === "REJECTED" || stage === "PENDING") {
    action = {
      label: "预提审 · 锁定快照",
      icon: <LockKey size={15} weight="fill" />,
      onClick: () => actions.presubmit(ticketNo),
      disabled: gateBusy || diffCount === 0,
      hint:
        diffCount === 0
          ? "沙箱内暂无变更，先让 Agent 完成编码"
          : "生成不可变快照指纹，作为审查与发布的唯一凭据",
      primary: true,
    };
  } else if (stage === "PRESUBMITTED") {
    // 快照已锁定：渲染专用的 AI 审查 / 人工审查 双入口（见下方 ReviewActions）。
    action = null;
  } else if (stage === "IN_REVIEW") {
    action = {
      label: "审查执行中…",
      icon: <Spinner />,
      disabled: true,
      hint: "判决落盘前不会触碰主分支",
    };
  } else if (stage === "READY_TO_PUBLISH") {
    action = {
      label: "一键安全发布",
      icon: <RocketLaunch size={15} weight="fill" />,
      onClick: () => actions.publish(ticketNo),
      disabled: gateBusy,
      hint: "原子推送至主分支；发布内容与快照指纹强一致",
      primary: true,
    };
  } else if (stage === "DONE" || stage === "CANCELLED") {
    // T-117: 终态工单可重启 —— 底部主按钮位变为「重启工单」，弹窗填写理由。
    action = {
      label: "重启工单",
      icon: <ArrowCounterClockwise size={15} weight="fill" />,
      onClick: () => openRestartDialog(ticketNo),
      hint:
        stage === "DONE"
          ? "重新开启该工单的编码协作，轮次自动加一"
          : "已取消的工单可重新开启，轮次自动加一",
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
              title="把工单分支快进到主分支最新 tip；沙箱内未提交的改动会原样保留（T-118 基座同步）"
            >
              <ArrowClockwise size={15} weight="fill" />
              同步基座 · 追平主分支
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
              title="取消工单（理由必填，记入状态变更记录，可在工单信息里回看）"
            >
              <LockKey size={15} weight="fill" />
              取消工单…
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
            工单已完成归档
          </button>
        </div>
      )}
      {stage === "CANCELLED" && (
        <div className="shrink-0 border-t border-edge bg-surface p-3.5">
          <button className="btn btn-lg w-full" disabled title="已取消的工单已锁定，不可再操作">
            <LockKey size={15} weight="fill" />
            工单已取消 · 已锁定
          </button>
          <div className="mt-2 text-center text-[11.5px] text-faint">会话与门禁操作均已停用</div>
        </div>
      )}
      {/* ─── T-117 重启理由弹窗 / V19 状态变更记录弹窗 ─── */}
      <RestartDialog ticketNo={ticketNo} />
      <StageChangesHistoryDialog ticketNo={ticketNo} />
    </aside>
  );
}
