import { AnimatePresence, motion } from "motion/react";
import { ClockCounterClockwise, FileText, GitBranch } from "@phosphor-icons/react";
import { useApp } from "@/store";
import { setGateSection } from "@/features/gate/state";
import { loadStageChanges } from "@/features/ticket/api";
import { openStageChangesView } from "@/features/ticket/state";

/** 工单信息段（目标分支 / 需求描述 / 备注 + 状态变更记录入口）。 */
export function TicketInfo({ ticketNo }: { ticketNo: string }) {
  const ticket = useApp((s) => s.tickets.find((t) => t.ticketNo === ticketNo));
  const expanded = useApp((s) => s.gateSections.info);

  if (!ticket) return null;

  return (
    <div className="border-b border-edge">
      <button
        className="w-full sticky top-0 z-10 bg-canvas flex items-center gap-2 px-4 py-2.5 text-left hover:bg-raised transition-colors cursor-pointer"
        onClick={() => setGateSection("info", !expanded)}
      >
        <FileText size={14} className="text-faint shrink-0" />
        <span className="text-[12px] font-medium text-dim">工单信息</span>
        {/* 状态记录入口（V19）：重启/强制已完成/取消的完整历史，弹窗展示、不占原信息位 */}
        {(ticket.stageChangeCount ?? 0) > 0 && (
          <span
            role="button"
            tabIndex={0}
            className="inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[11px] text-dim hover:text-accent hover:bg-raised transition-colors cursor-pointer shrink-0"
            title="查看状态变更记录（重启 / 强制已完成 / 取消的理由）"
            aria-label="查看状态变更记录"
            onClick={(e) => {
              e.stopPropagation();
              void loadStageChanges(ticketNo);
              openStageChangesView(ticketNo);
            }}
            onKeyDown={(e) => {
              if (e.key === "Enter" || e.key === " ") {
                e.stopPropagation();
                void loadStageChanges(ticketNo);
                openStageChangesView(ticketNo);
              }
            }}
          >
            <ClockCounterClockwise size={12} />
            状态记录
            <span className="font-mono text-[10px] text-faint">{ticket.stageChangeCount}</span>
          </span>
        )}
        <span className="flex-1" />
        <span
          className={`text-[11px] text-faint transition-transform duration-150 ${expanded ? "rotate-0" : "-rotate-90"}`}
        >
          ▾
        </span>
      </button>
      <AnimatePresence>
        {expanded && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: "auto", opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.2, ease: [0.16, 1, 0.3, 1] }}
            className="overflow-hidden"
          >
            <div className="px-4 pb-3 space-y-2.5">
              <div className="flex items-center gap-2" title="目标分支在创建时锁定，不可编辑切换">
                <GitBranch size={12} className="text-faint shrink-0" />
                <span className="text-[11px] text-faint">目标分支（锁定）</span>
                <span className="flex-1" />
                <span className="font-mono text-[11.5px] text-dim">
                  {ticket.targetRef.replace("refs/heads/", "")}
                </span>
              </div>
              {ticket.description && (
                <div>
                  <div className="text-[10.5px] uppercase tracking-wider text-faint mb-1">需求描述</div>
                  <div className="text-[12.5px] text-dim leading-relaxed whitespace-pre-wrap">{ticket.description}</div>
                </div>
              )}
              {ticket.note && (
                <div>
                  <div className="text-[10.5px] uppercase tracking-wider text-faint mb-1">备注</div>
                  <div className="text-[12.5px] text-dim leading-relaxed whitespace-pre-wrap">{ticket.note}</div>
                </div>
              )}
              {!ticket.description && !ticket.note && (
                <div className="text-[12px] text-faint text-center py-2">暂无描述信息</div>
              )}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
