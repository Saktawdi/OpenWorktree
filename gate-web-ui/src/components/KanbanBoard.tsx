import { actions } from "../lib/actions";
import { relativeTime, STAGE_LABEL } from "../lib/format";
import { useApp } from "../lib/store";
import type { Stage, Ticket } from "../lib/types";
import { PriorityChip, StageDot } from "./ui";

const LANES: Array<{ key: Stage; title: string }> = [
  { key: "IN_PROGRESS", title: "进行中" },
  { key: "PRESUBMITTED", title: "已预提交" },
  { key: "IN_REVIEW", title: "审查中" },
  { key: "READY_TO_PUBLISH", title: "可发布" },
  { key: "DONE", title: "已完成" },
];

function Card({ ticket, rejected }: { ticket: Ticket; rejected?: boolean }) {
  return (
    <button
      className="w-full text-left card p-3 hover:border-edge-strong hover:-translate-y-px transition-all cursor-pointer"
      onClick={() => actions.openTicket(ticket.ticketNo)}
    >
      {rejected && (
        <div className="mb-2 chip border border-danger/40 bg-danger/10 text-danger">已驳回 · 存在未达标项</div>
      )}
      <div className="flex items-center gap-2">
        <span className="font-mono text-[11.5px] text-faint">{ticket.ticketNo}</span>
        <span className="flex-1" />
        <PriorityChip priority={ticket.priority} />
      </div>
      <div className="mt-1 text-[13px] leading-snug text-ink line-clamp-2">{ticket.title}</div>
      <div className="mt-2 flex items-center gap-2 text-[11px] text-faint">
        {ticket.agentName && <span>{ticket.agentName}</span>}
        <span className="flex-1" />
        <span>{relativeTime(ticket.updatedAt)}</span>
      </div>
    </button>
  );
}

export function KanbanBoard() {
  const tickets = useApp((s) => s.tickets);

  return (
    <div className="flex-1 min-h-0 overflow-x-auto overflow-y-hidden p-4">
      <div className="h-full flex gap-3 min-w-max">
        {LANES.map(({ key, title }) => {
          const inLane = tickets.filter((t) => t.stage === key);
          const rejected = key === "IN_PROGRESS" ? tickets.filter((t) => t.stage === "REJECTED") : [];
          return (
            <section key={key} className="w-[264px] shrink-0 flex flex-col">
              <header className="flex items-center gap-2 px-1 pb-2">
                <StageDot stage={key} />
                <span className="text-[12.5px] font-medium">{title}</span>
                <span className="font-mono text-[11px] text-faint">{inLane.length + rejected.length}</span>
              </header>
              <div className="flex-1 overflow-y-auto space-y-2 pr-0.5">
                {rejected.map((t) => (
                  <Card key={t.ticketNo} ticket={t} rejected />
                ))}
                {inLane.map((t) => (
                  <Card key={t.ticketNo} ticket={t} />
                ))}
                {inLane.length === 0 && rejected.length === 0 && (
                  <div className="rounded-xl border border-dashed border-edge h-20 grid place-items-center text-[12px] text-faint">
                    暂无工单
                  </div>
                )}
              </div>
            </section>
          );
        })}
      </div>
    </div>
  );
}

export function laneTitle(stage: Stage) {
  return STAGE_LABEL[stage];
}
