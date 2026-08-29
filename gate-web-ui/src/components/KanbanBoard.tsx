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
import { Funnel, MagnifyingGlass, X } from "@phosphor-icons/react";
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

const OTHER_STATUSES: Stage[] = ["REJECTED", "NEEDS_HUMAN", "CANCELLED"];
const OTHER_LANES: Array<{ key: Stage; title: string }> = [
  { key: "REJECTED", title: "已驳回" },
  { key: "NEEDS_HUMAN", title: "需人工" },
  { key: "CANCELLED", title: "已取消" },
];
const ALL_STAGES: Stage[] = [...LANES.map((lane) => lane.key), ...OTHER_STATUSES];
type StatusFilter = "ALL" | "OTHER" | Stage;
type PriorityFilter = "ALL" | Ticket["priority"];

function groupByStage(tickets: Ticket[], orderMap: Record<string, number>): Map<Stage, Ticket[]> {
  const map = new Map<Stage, Ticket[]>();
  for (const stage of ALL_STAGES) map.set(stage, []);
  for (const ticket of tickets) map.get(ticket.stage)?.push(ticket);
  const cmp = (a: Ticket, b: Ticket) =>
    (orderMap[a.ticketNo] ?? 0) - (orderMap[b.ticketNo] ?? 0);
  for (const stage of ALL_STAGES) map.get(stage)!.sort(cmp);
  return map;
}

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
  const ticket = tickets.find((t) => t.ticketNo === overId);
  return ticket ? displayedStage(ticket) : null;
}

function displayedStage(ticket: Ticket): Stage {
  return ticket.stage === "REJECTED" ? "IN_PROGRESS" : ticket.stage;
}

export function KanbanBoard() {
  const ticketsAll = useApp((s) => s.tickets);
  const activeProjectId = useApp((s) => s.activeProjectId);
  const agents = useApp((s) => s.agents);
  const orderMap = useApp((s) => s.order);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [shakenId, setShakenId] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("ALL");
  const [priorityFilter, setPriorityFilter] = useState<PriorityFilter>("ALL");

  const agentNameOf = (t: Ticket) =>
    agents.find((a) => a.id === t.agentConfigId)?.name;

  const tickets = useMemo(
    () => ticketsAll.filter((t) => t.projectId === activeProjectId || t.projectId === ""),
    [ticketsAll, activeProjectId],
  );

  const filteredTickets = useMemo(() => {
    const q = query.trim().toLowerCase();
    return tickets.filter((ticket) => {
      const matchesQuery =
        !q ||
        [ticket.ticketNo, ticket.title, ticket.description ?? "", ...ticket.labels].some((value) =>
          value.toLowerCase().includes(q),
        );
      const matchesStatus =
        statusFilter === "ALL"
          ? true
          : statusFilter === "OTHER"
            ? OTHER_STATUSES.includes(ticket.stage)
            : ticket.stage === statusFilter;
      const matchesPriority = priorityFilter === "ALL" || ticket.priority === priorityFilter;
      return matchesQuery && matchesStatus && matchesPriority;
    });
  }, [tickets, query, statusFilter, priorityFilter]);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  const allByStage = useMemo(() => groupByStage(tickets, orderMap), [tickets, orderMap]);
  const byLane = useMemo(() => groupByStage(filteredTickets, orderMap), [filteredTickets, orderMap]);

  const bounce = (no: string, reason: string) => {
    showToast(reason);
    setShakenId(no);
    setTimeout(() => setShakenId((cur) => (cur === no ? null : cur)), 520);
  };

  const reorderInLane = (stage: Stage, activeNo: string, overNo: string) => {
    const laneTickets = (source: Map<Stage, Ticket[]>) =>
      stage === "IN_PROGRESS"
        ? [...(source.get("REJECTED") ?? []), ...(source.get("IN_PROGRESS") ?? [])]
        : source.get(stage) ?? [];
    const lane = laneTickets(allByStage).map((t) => t.ticketNo);
    const visibleLane = laneTickets(byLane).map((t) => t.ticketNo);
    const oldIdx = visibleLane.indexOf(activeNo);
    const newIdx = visibleLane.indexOf(overNo);
    if (oldIdx < 0 || newIdx < 0 || oldIdx === newIdx) return;
    const nextVisible = arrayMove(visibleLane, oldIdx, newIdx);
    const visible = new Set(visibleLane);
    let visibleIndex = 0;
    const next = lane.map((no) => (visible.has(no) ? nextVisible[visibleIndex++] : no));
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
    const displayedFrom = displayedStage(activeTicket);
    const to = resolveTargetStage(String(over.id), tickets);
    if (!to) return;

    if (to === displayedFrom) {
      reorderInLane(displayedFrom, activeNo, String(over.id));
      return;
    }
    const maxOrder = Math.max(-1, ...Object.values(appStore.getState().order));
    setTicketOrder(activeNo, maxOrder + 1);
    attemptTransition(activeNo, from, to);
  };

  const activeTicket = activeId ? tickets.find((t) => t.ticketNo === activeId) : null;
  const otherView =
    statusFilter === "OTHER" ||
    statusFilter === "REJECTED" ||
    statusFilter === "NEEDS_HUMAN" ||
    statusFilter === "CANCELLED";
  const hasFilters = Boolean(query.trim()) || statusFilter !== "ALL" || priorityFilter !== "ALL";
  // 默认泳道不包含 NEEDS_HUMAN/CANCELLED：仅搜索/优先级筛选命中这些工单时，
  // 在标题行提示命中数，并可一键切换到“其他状态”泳道，避免筛选结果静默缺失。
  const hiddenOtherMatches =
    statusFilter === "ALL"
      ? filteredTickets.filter((t) => t.stage === "NEEDS_HUMAN" || t.stage === "CANCELLED").length
      : 0;
  const clearFilters = () => {
    setQuery("");
    setStatusFilter("ALL");
    setPriorityFilter("ALL");
  };

  return (
    <div className="flex-1 min-h-0 flex flex-col">
      <div className="flex flex-wrap items-center gap-2 px-4 pt-3 pb-2 shrink-0">
        <span className="kicker">看板</span>
        <span className="font-mono text-[11px] text-faint">
          {hasFilters ? `${filteredTickets.length} / ${tickets.length}` : tickets.length} 个工单
        </span>
        <div className="relative w-[190px]">
          <MagnifyingGlass
            size={13}
            className="absolute left-2.5 top-1/2 -translate-y-1/2 text-faint"
          />
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="搜索工单"
            aria-label="搜索工单"
            className="w-full h-7 rounded-lg border border-edge bg-sunken pl-7 pr-7 text-[12px] placeholder:text-faint focus:border-accent/50 focus:outline-none transition-colors"
          />
          {query && (
            <button
              type="button"
              className="icon-btn absolute right-0 top-0"
              onClick={() => setQuery("")}
              title="清除搜索"
              aria-label="清除搜索"
            >
              <X size={12} />
            </button>
          )}
        </div>
        <label className="inline-flex items-center gap-1.5 h-7 rounded-lg border border-edge bg-sunken px-2 text-[12px] text-dim">
          <Funnel size={12} className="text-faint" />
          <span className="sr-only">状态筛选</span>
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value as StatusFilter)}
            className="bg-transparent text-ink outline-none cursor-pointer"
            aria-label="按状态筛选"
          >
            <option value="ALL">全部状态</option>
            <optgroup label="默认甬道">
              {LANES.map((lane) => (
                <option key={lane.key} value={lane.key}>
                  {lane.title}
                </option>
              ))}
            </optgroup>
            <optgroup label="其他状态">
              <option value="OTHER">其他状态</option>
              <option value="REJECTED">已驳回</option>
              <option value="NEEDS_HUMAN">需人工</option>
              <option value="CANCELLED">已取消</option>
            </optgroup>
          </select>
        </label>
        <label className="inline-flex items-center h-7 rounded-lg border border-edge bg-sunken px-2 text-[12px] text-dim">
          <span className="sr-only">优先级筛选</span>
          <select
            value={priorityFilter}
            onChange={(e) => setPriorityFilter(e.target.value as PriorityFilter)}
            className="bg-transparent text-ink outline-none cursor-pointer"
            aria-label="按优先级筛选"
          >
            <option value="ALL">全部优先级</option>
            <option value="P0">P0</option>
            <option value="P1">P1</option>
            <option value="P2">P2</option>
            <option value="P3">P3</option>
          </select>
        </label>
        {hiddenOtherMatches > 0 && (
          <button
            type="button"
            className="chip border border-warn/40 bg-warn/10 text-warn cursor-pointer hover:border-warn/60 transition-colors"
            onClick={() => setStatusFilter("OTHER")}
            title="点击切换到其他状态泳道查看这些工单"
          >
            另有 {hiddenOtherMatches} 条在其他状态
          </button>
        )}
        {hasFilters && (
          <button type="button" className="btn btn-sm btn-ghost text-faint" onClick={clearFilters}>
            <X size={12} />
            清除筛选
          </button>
        )}
        <span className="flex-1" />
        <span className="hidden lg:inline text-[11.5px] text-faint">
          拖拽卡片即可流转 · 门禁泳道会执行对应操作
        </span>
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
            {(otherView ? OTHER_LANES : LANES).map(({ key, title }) => (
              <Lane
                key={key}
                stage={key}
                title={title}
                tickets={otherView && key === "REJECTED" ? [] : byLane.get(key) ?? []}
                rejectedTickets={
                  otherView
                    ? key === "REJECTED"
                      ? byLane.get("REJECTED") ?? []
                      : []
                    : key === "IN_PROGRESS"
                      ? byLane.get("REJECTED") ?? []
                      : []
                }
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
