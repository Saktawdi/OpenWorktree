import { useMemo, useState } from "react";
import {
  DndContext,
  DragOverlay,
  KeyboardSensor,
  PointerSensor,
  closestCenter,
  useDroppable,
  useSensor,
  useSensors,
  type DragEndEvent,
  type DragStartEvent,
} from "@dnd-kit/core";
import { restrictToWindowEdges } from "@dnd-kit/modifiers";
import {
  SortableContext,
  arrayMove,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { motion } from "motion/react";
import { actions } from "../lib/actions";
import { relativeTime } from "../lib/format";
import { appStore, openTicketCreator, setTicketOrder, setView, showToast, useApp } from "../lib/store";
import type { Stage, Ticket } from "../lib/types";
import { PriorityChip, StageDot } from "./ui";

const LANES: Array<{ key: Stage; title: string }> = [
  { key: "PENDING", title: "待处理" },
  { key: "IN_PROGRESS", title: "进行中" },
  { key: "PRESUBMITTED", title: "已预提交" },
  { key: "IN_REVIEW", title: "审查中" },
  { key: "READY_TO_PUBLISH", title: "可发布" },
  { key: "DONE", title: "已完成" },
];

function CardFace({
  ticket,
  agentName,
  rejected,
  dragging,
}: {
  ticket: Ticket;
  agentName?: string;
  rejected?: boolean;
  dragging?: boolean;
}) {
  return (
    <div
      className={`rounded-xl border p-3 text-left transition-colors ${
        dragging
          ? "border-accent/50 bg-raised shadow-2xl shadow-black/60 rotate-[1.5deg] scale-[1.02]"
          : "border-edge bg-panel hover:border-edge-strong"
      }`}
    >
      {rejected && (
        <div className="mb-2 chip border border-danger/40 bg-danger/10 text-danger">已驳回 · 存在未达标项</div>
      )}
      <div className="flex items-center gap-2">
        <span className="font-mono text-[11.5px] text-faint">{ticket.ticketNo}</span>
        <span className="flex-1" />
        <PriorityChip priority={ticket.priority} muted={ticket.stage === "CANCELLED"} />
      </div>
      <div className="mt-1 text-[13px] leading-snug text-ink line-clamp-2">{ticket.title}</div>
      {ticket.description && (
        <div className="mt-1 text-[11.5px] leading-snug text-dim line-clamp-2">{ticket.description}</div>
      )}
      {ticket.labels.length > 0 && (
        <div className="mt-1.5 flex items-center gap-1">
          <span className="text-[10px] text-faint/50 shrink-0">♯</span>
          <div className="flex flex-wrap gap-1 min-w-0">
            {ticket.labels.slice(0, 3).map((l) => (
              <span
                key={l}
                className="inline-flex items-center h-[18px] rounded-md border border-edge-strong bg-raised px-1.5 text-[10px] leading-none font-medium text-faint"
              >
                {l}
              </span>
            ))}
            {ticket.labels.length > 3 && (
              <span
                className="inline-flex items-center h-[18px] rounded-md px-1.5 text-[10px] leading-none text-faint/60"
                title={ticket.labels.slice(3).join(", ")}
              >
                +{ticket.labels.length - 3}
              </span>
            )}
          </div>
        </div>
      )}
      <div className="mt-2 pt-2 border-t border-edge/50 flex items-center gap-2 text-[11px] text-faint">
        {agentName && (
          <span className="inline-flex items-center gap-1">
            <span className="w-3.5 h-3.5 rounded-full bg-accent-dim grid place-items-center text-[8px] text-accent font-semibold">
              {agentName.slice(0, 1)}
            </span>
            {agentName}
          </span>
        )}
        <span className="flex-1" />
        <span>{relativeTime(ticket.updatedAt)}</span>
      </div>
    </div>
  );
}

function SortableCard({
  ticket,
  agentName,
  rejected,
  shaken,
}: {
  ticket: Ticket;
  agentName?: string;
  rejected?: boolean;
  shaken?: boolean;
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: ticket.ticketNo,
    data: { stage: ticket.stage },
  });

  return (
    <motion.div
      ref={setNodeRef}
      {...attributes}
      {...listeners}
      style={{ transform: CSS.Translate.toString(transform), transition }}
      className={`cursor-grab active:cursor-grabbing touch-none ${
        isDragging ? "opacity-35" : ""
      } ${shaken ? "animate-shake" : ""}`}
      whileHover={!isDragging ? { y: -2 } : undefined}
      transition={{ type: "spring", stiffness: 400, damping: 25 }}
      onClick={() => {
        if (!dragJustEnded()) actions.openTicket(ticket.ticketNo);
      }}
    >
      <CardFace ticket={ticket} agentName={agentName} rejected={rejected} />
    </motion.div>
  );
}

let dragEndedAt = 0;
function markDragEnded() {
  dragEndedAt = Date.now();
}
function dragJustEnded(): boolean {
  return Date.now() - dragEndedAt < 250;
}

function Lane({
  stage,
  title,
  tickets,
  rejectedTickets,
  shakenId,
  agentNameOf,
}: {
  stage: Stage;
  title: string;
  tickets: Ticket[];
  rejectedTickets: Ticket[];
  shakenId: string | null;
  agentNameOf: (t: Ticket) => string | undefined;
}) {
  const { setNodeRef, isOver } = useDroppable({ id: `lane:${stage}` });
  const all = [...rejectedTickets, ...tickets];

  return (
    <section className="flex-1 min-w-[276px] flex flex-col rounded-2xl border border-edge/70 bg-sunken/70 overflow-hidden">
      <header className="flex items-center gap-2 px-3 pt-3 pb-2">
        <StageDot stage={stage} />
        <span className="text-[12.5px] font-medium">{title}</span>
        <span className="font-mono text-[11px] text-faint">{all.length}</span>
        <span className="flex-1" />
      </header>
      <div
        ref={setNodeRef}
        className={`flex-1 min-h-[120px] space-y-2 px-2 pb-2 transition-colors rounded-b-2xl ${
          isOver ? "bg-accent/[0.05] ring-1 ring-inset ring-accent/25" : ""
        }`}
      >
        <SortableContext items={all.map((t) => t.ticketNo)} strategy={verticalListSortingStrategy}>
          {rejectedTickets.map((t) => (
            <SortableCard key={t.ticketNo} ticket={t} rejected shaken={shakenId === t.ticketNo} agentName={agentNameOf(t)} />
          ))}
          {tickets.map((t) => (
            <SortableCard key={t.ticketNo} ticket={t} shaken={shakenId === t.ticketNo} agentName={agentNameOf(t)} />
          ))}
        </SortableContext>
        {all.length === 0 && (
          <div
            className={`rounded-xl border border-dashed h-20 grid place-items-center text-[12px] transition-colors ${
              isOver ? "border-accent/40 text-accent" : "border-edge text-faint"
            }`}
          >
            {isOver ? "松手流转到此处" : "暂无工单"}
          </div>
        )}
      </div>
    </section>
  );
}

function resolveTargetStage(overId: string, tickets: Ticket[]): Stage | null {
  if (overId.startsWith("lane:")) return overId.slice(5) as Stage;
  return tickets.find((t) => t.ticketNo === overId)?.stage ?? null;
}

export function KanbanBoard() {
  const ticketsAll = useApp((s) => s.tickets);
  const activeProjectId = useApp((s) => s.activeProjectId);
  const agents = useApp((s) => s.agents);
  const orderMap = useApp((s) => s.order);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [shakenId, setShakenId] = useState<string | null>(null);

  const agentNameOf = (t: Ticket) =>
    agents.find((a) => a.id === t.agentConfigId)?.name;

  const tickets = useMemo(
    () => ticketsAll.filter((t) => t.projectId === activeProjectId || t.projectId === ""),
    [ticketsAll, activeProjectId],
  );

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  const byLane = useMemo(() => {
    const cmp = (a: Ticket, b: Ticket) =>
      (orderMap[a.ticketNo] ?? 0) - (orderMap[b.ticketNo] ?? 0);
    const map = new Map<Stage, Ticket[]>();
    for (const lane of LANES) map.set(lane.key, []);
    for (const t of tickets) {
      map.get(t.stage)?.push(t);
    }
    for (const lane of LANES) map.get(lane.key)!.sort(cmp);
    return map;
  }, [tickets, orderMap]);

  const bounce = (no: string, reason: string) => {
    showToast(reason);
    setShakenId(no);
    setTimeout(() => setShakenId((cur) => (cur === no ? null : cur)), 520);
  };

  const reorderInLane = (stage: Stage, activeNo: string, overNo: string) => {
    const st = appStore.getState();
    const lane = (byLane.get(stage) ?? []).map((t) => t.ticketNo);
    const oldIdx = lane.indexOf(activeNo);
    const newIdx = lane.indexOf(overNo);
    if (oldIdx < 0 || newIdx < 0 || oldIdx === newIdx) return;
    const next = arrayMove(lane, oldIdx, newIdx);
    next.forEach((no, i) => setTicketOrder(no, i));
  };

  const attemptTransition = (no: string, from: Stage, to: Stage) => {
    const st = appStore.getState();
    const t = st.tickets.find((x) => x.ticketNo === no);
    if (!t) return;

    if (st.gateBusy[no]) {
      bounce(no, "门禁操作执行中，请稍候");
      return;
    }

    const allowed: Partial<Record<Stage, Stage[]>> = {
      PENDING: ["IN_PROGRESS"],
      IN_PROGRESS: ["PRESUBMITTED"],
      PRESUBMITTED: ["IN_REVIEW"],
      READY_TO_PUBLISH: ["DONE"],
    };

    if (from === "DONE") {
      bounce(no, "工单已归档，状态不可再变更");
      return;
    }
    if (!(allowed[from] ?? []).includes(to)) {
      bounce(no, "该流转由门禁驱动：工单必须先完成预提审与门禁审查");
      return;
    }

    if (to === "IN_PROGRESS") {
      showToast("工单已开始，进入编码协作");
      void actions.startTicket(no);
      return;
    }
    if (to === "PRESUBMITTED") {
      if ((st.diffs[no]?.length ?? 0) === 0) {
        bounce(no, "沙箱内暂无变更，先让 Agent 完成编码");
        return;
      }
      showToast("正在锁定快照…");
      void actions.presubmit(no);
      return;
    }
    if (to === "IN_REVIEW") {
      showToast("门禁审查已触发…");
      void actions.review(no);
      return;
    }
    if (to === "DONE") {
      showToast("正在发布至权威库主分支…");
      void actions.publish(no);
    }
  };

  const onDragStart = (e: DragStartEvent) => {
    setActiveId(String(e.active.id));
  };

  const onDragEnd = (e: DragEndEvent) => {
    setActiveId(null);
    markDragEnded();
    const { active, over } = e;
    if (!over) return;
    const activeNo = String(active.id);
    const activeTicket = tickets.find((t) => t.ticketNo === activeNo);
    if (!activeTicket) return;
    const from = activeTicket.stage;
    const to = resolveTargetStage(String(over.id), tickets);
    if (!to) return;

    if (to === from) {
      reorderInLane(from, activeNo, String(over.id));
      return;
    }
    const maxOrder = Math.max(-1, ...Object.values(appStore.getState().order));
    setTicketOrder(activeNo, maxOrder + 1);
    attemptTransition(activeNo, from, to);
  };

  const activeTicket = activeId ? tickets.find((t) => t.ticketNo === activeId) : null;

  return (
    <div className="flex-1 min-h-0 flex flex-col">
      <div className="flex items-center gap-3 px-4 pt-3 pb-2 shrink-0">
        <span className="kicker">看板</span>
        <span className="font-mono text-[11px] text-faint">{tickets.length} 个工单</span>
        <span className="flex-1" />
        <span className="text-[11.5px] text-faint">拖拽卡片即可流转 · 门禁泳道会执行对应操作</span>
        <button
          className="btn btn-sm btn-primary"
          onClick={() => {
            setView("workbench");
            openTicketCreator();
          }}
        >
          新建工单
        </button>
      </div>
      <DndContext
        sensors={sensors}
        collisionDetection={closestCenter}
        modifiers={[restrictToWindowEdges]}
        onDragStart={onDragStart}
        onDragEnd={onDragEnd}
        onDragCancel={() => {
          setActiveId(null);
          markDragEnded();
        }}
      >
        <div className="flex-1 min-h-0 overflow-x-auto overflow-y-hidden px-4 pb-4">
          <div className="h-full flex gap-3 w-full">
            {LANES.map(({ key, title }) => (
              <Lane
                key={key}
                stage={key}
                title={title}
                tickets={byLane.get(key) ?? []}
                rejectedTickets={key === "IN_PROGRESS" ? tickets.filter((t) => t.stage === "REJECTED") : []}
                shakenId={shakenId}
                agentNameOf={agentNameOf}
              />
            ))}
          </div>
        </div>
        <DragOverlay dropAnimation={{ duration: 180, easing: "cubic-bezier(0.18, 0.67, 0.6, 1.22)" }}>
          {activeTicket && (
            <div className="w-[260px] cursor-grabbing">
              <CardFace
                ticket={activeTicket}
                agentName={agentNameOf(activeTicket)}
                rejected={activeTicket.stage === "REJECTED"}
                dragging
              />
            </div>
          )}
        </DragOverlay>
      </DndContext>
    </div>
  );
}
