import { useMemo, useRef, useState } from "react";
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
import { Check, Funnel, MagnifyingGlass, X } from "@phosphor-icons/react";
import { motion } from "motion/react";
import { actions } from "../lib/actions";
import {
  KANBAN_DEFAULT_STAGES,
  KANBAN_LANE_COUNT,
  KANBAN_STAGE_ORDER,
  relativeTime,
  STAGE_LABEL,
} from "../lib/format";
import {
  appStore,
  openTicketCreator,
  setKanbanStages,
  setTicketOrder,
  setView,
  showToast,
  useApp,
} from "../lib/store";
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

const OTHER_LANES: Array<{ key: Stage; title: string }> = [
  { key: "REJECTED", title: "已驳回" },
  { key: "NEEDS_HUMAN", title: "需人工" },
  { key: "CANCELLED", title: "已取消" },
];
type PriorityFilter = "ALL" | Ticket["priority"];

function groupByStage(tickets: Ticket[], orderMap: Record<string, number>): Map<Stage, Ticket[]> {
  const map = new Map<Stage, Ticket[]>();
  for (const stage of KANBAN_STAGE_ORDER) map.set(stage, []);
  for (const ticket of tickets) map.get(ticket.stage)?.push(ticket);
  const cmp = (a: Ticket, b: Ticket) =>
    (orderMap[a.ticketNo] ?? 0) - (orderMap[b.ticketNo] ?? 0);
  for (const stage of KANBAN_STAGE_ORDER) map.get(stage)!.sort(cmp);
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
    <section className="flex-1 min-w-[224px] flex flex-col rounded-2xl border border-edge/70 bg-sunken/70 overflow-hidden">
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

/**
 * 看板筛选（样式对齐工单列表的状态筛选面板）：固定 6 个上板甬道 + 优先级筛选。
 * 6 条恰好铺满一行（最多最少都是 6），因此没有自由增减，只有「一上一下」互换：
 * 点选一个已勾选甬道标记换下，再点一个未勾选甬道完成互换（反之亦然），重置恢复默认六甬道。
 */
function KanbanFilterButton({
  open,
  setOpen,
  priorityFilter,
  setPriorityFilter,
}: {
  open: boolean;
  setOpen: (v: boolean) => void;
  priorityFilter: PriorityFilter;
  setPriorityFilter: (p: PriorityFilter) => void;
}) {
  // 面板用 fixed 定位并夹紧到视口内：absolute right-0 在窄窗口会把面板推出屏幕被裁剪
  const [panelPos, setPanelPos] = useState<{ top: number; left: number }>({ top: 0, left: 8 });
  const btnRef = useRef<HTMLButtonElement>(null);
  // 互换第一步标记的甬道：已勾选 = 待换下，未勾选 = 待换上；再点一次取消
  const [markedStage, setMarkedStage] = useState<Stage | null>(null);
  const stages = useApp((s) => s.kanbanStages);
  const ticketsAll = useApp((s) => s.tickets);
  const activeProjectId = useApp((s) => s.activeProjectId);

  const counts = useMemo(() => {
    const m = new Map<Stage, number>();
    for (const t of ticketsAll) {
      if (t.projectId === activeProjectId || t.projectId === "") {
        m.set(t.stage, (m.get(t.stage) ?? 0) + 1);
      }
    }
    return m;
  }, [ticketsAll, activeProjectId]);

  const active = priorityFilter !== "ALL" ||
    stages.length !== KANBAN_DEFAULT_STAGES.length ||
    stages.some((st) => !KANBAN_DEFAULT_STAGES.includes(st));

  const close = () => {
    setMarkedStage(null);
    setOpen(false);
  };

  const pick = (st: Stage) => {
    if (markedStage === null) {
      setMarkedStage(st);
      return;
    }
    if (markedStage === st) {
      setMarkedStage(null);
      return;
    }
    const markedOn = stages.includes(markedStage);
    if (markedOn === stages.includes(st)) {
      // 同侧点击：互换必须一上一下，把标记挪到新点的甬道上
      setMarkedStage(st);
      return;
    }
    const out = markedOn ? markedStage : st;
    const incoming = markedOn ? st : markedStage;
    setKanbanStages(stages.filter((x) => x !== out).concat(incoming));
    setMarkedStage(null);
    showToast(`已用「${STAGE_LABEL[incoming]}」替换「${STAGE_LABEL[out]}」`);
  };

  const reset = () => {
    setMarkedStage(null);
    setKanbanStages([...KANBAN_DEFAULT_STAGES]);
    setPriorityFilter("ALL");
  };

  const PANEL_WIDTH = 252;
  const placePanel = () => {
    const r = btnRef.current?.getBoundingClientRect();
    if (!r) return;
    const left = Math.max(
      8,
      Math.min(r.right - PANEL_WIDTH, window.innerWidth - PANEL_WIDTH - 8),
    );
    setPanelPos({ top: r.bottom + 6, left });
  };

  const row = (st: Stage) => {
    const on = stages.includes(st);
    const marked = markedStage === st;
    const n = counts.get(st) ?? 0;
    return (
      <button
        key={st}
        className={`w-full flex items-center gap-2 px-2 h-8 rounded-lg text-left text-[12.5px] cursor-pointer transition-all ${
          marked
            ? "bg-accent/[0.08] ring-1 ring-inset ring-accent/45 text-ink"
            : on
              ? "text-ink hover:bg-raised"
              : "text-faint hover:bg-raised"
        }`}
        title={
          marked
            ? "再点一次取消，点选状态相反的甬道完成互换"
            : on
              ? "已上板 · 点选后与一个未上板甬道互换"
              : "未上板 · 点选后与一个已上板甬道互换"
        }
        onClick={() => pick(st)}
      >
        <span
          className={`grid place-items-center w-[14px] h-[14px] rounded border transition-colors shrink-0 ${
            on ? "bg-accent/15 border-accent/60 text-accent" : "border-edge-strong text-transparent"
          }`}
        >
          <Check size={10} weight="bold" />
        </span>
        <StageDot stage={st} />
        <span className="flex-1 truncate">{STAGE_LABEL[st]}</span>
        {marked && (
          <span className="inline-flex items-center h-[16px] px-1 rounded-md border border-accent/40 bg-accent/10 text-[9.5px] leading-none font-medium text-accent">
            {on ? "换下" : "换上"}
          </span>
        )}
        <span className={`font-mono text-[10.5px] ${n > 0 && !on ? "text-warn" : "text-faint"}`}>{n}</span>
      </button>
    );
  };

  const markedOn = markedStage !== null && stages.includes(markedStage);

  return (
    <>
      <button
        ref={btnRef}
        className={`btn h-7 px-2 text-[12px] ${active ? "!border-accent/50 !text-accent" : ""}`}
        title="筛选甬道与优先级"
        aria-label="筛选甬道与优先级"
        onClick={() => {
          if (!open) placePanel();
          else setMarkedStage(null);
          setOpen(!open);
        }}
      >
        <Funnel size={13} weight={active ? "fill" : "regular"} />
        筛选
      </button>
      {open && (
        <>
          <div className="fixed inset-0 z-30" onClick={close} />
          <div
            className="fixed z-40 card p-1.5 shadow-2xl shadow-black/50 animate-rise"
            style={{ top: panelPos.top, left: panelPos.left, width: PANEL_WIDTH }}
          >
            <div className="flex items-center gap-2 px-2 h-8">
              <span className="text-[11px] font-medium text-dim flex-1">显示的甬道</span>
              <span
                className="inline-flex items-center h-[18px] px-1.5 rounded-md border border-accent/40 bg-accent/10 font-mono text-[10.5px] text-accent"
                title="甬道数量固定为 6，恰好铺满一行"
              >
                {stages.length}/{KANBAN_LANE_COUNT}
              </span>
              <button
                className="text-[11px] text-faint hover:text-accent cursor-pointer transition-colors"
                onClick={reset}
              >
                重置
              </button>
            </div>
            {markedStage !== null && (
              <div
                className="mx-0.5 mb-1 rounded-lg border border-accent/30 bg-accent/[0.07] px-2 py-1.5 text-[10.5px] leading-snug text-accent truncate"
                title="再点一次标记的甬道可取消"
              >
                再点一个<b>{markedOn ? "未勾选" : "已勾选"}</b>甬道，与「{STAGE_LABEL[markedStage]}
                」互换
              </div>
            )}
            <div className="max-h-[324px] overflow-y-auto">
              <div className="px-2 pt-1 pb-0.5 text-[10px] text-faint/70">默认甬道</div>
              {LANES.map((lane) => row(lane.key))}
              <div className="px-2 pt-1.5 pb-0.5 text-[10px] text-faint/70">其他状态</div>
              {OTHER_LANES.map((lane) => row(lane.key))}
            </div>
            <div className="border-t border-edge mt-1 pt-1.5 px-1.5 pb-1">
              <div className="px-0.5 pb-1 text-[10px] text-faint/70">优先级</div>
              <div className="flex gap-1">
                {(["ALL", "P0", "P1", "P2", "P3"] as const).map((p) => (
                  <button
                    key={p}
                    className={`flex-1 h-6 rounded-md border font-mono text-[11px] cursor-pointer transition-colors ${
                      priorityFilter === p
                        ? "border-accent/50 bg-accent/10 text-accent"
                        : "border-edge text-dim hover:text-ink hover:bg-raised"
                    }`}
                    onClick={() => setPriorityFilter(p)}
                  >
                    {p === "ALL" ? "全部" : p}
                  </button>
                ))}
              </div>
            </div>
          </div>
        </>
      )}
    </>
  );
}

export function KanbanBoard() {
  const ticketsAll = useApp((s) => s.tickets);
  const activeProjectId = useApp((s) => s.activeProjectId);
  const agents = useApp((s) => s.agents);
  const orderMap = useApp((s) => s.order);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [shakenId, setShakenId] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [filterOpen, setFilterOpen] = useState(false);
  const [priorityFilter, setPriorityFilter] = useState<PriorityFilter>("ALL");
  const kanbanStages = useApp((s) => s.kanbanStages);

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
      const matchesPriority = priorityFilter === "ALL" || ticket.priority === priorityFilter;
      return matchesQuery && matchesPriority;
    });
  }, [tickets, query, priorityFilter]);

  // 勾选的甬道决定哪些状态的工单上板；未勾选甬道的工单从看板隐藏
  const visibleTickets = useMemo(
    () => filteredTickets.filter((t) => kanbanStages.includes(t.stage)),
    [filteredTickets, kanbanStages],
  );

  const laneStages = useMemo(
    () => KANBAN_STAGE_ORDER.filter((st) => kanbanStages.includes(st)),
    [kanbanStages],
  );
  // 已驳回默认没有独立甬道：驳回工单内嵌在“进行中”甬道里；勾选“已驳回”后归位到自己的甬道，避免重复
  const rejectEmbedded =
    kanbanStages.includes("IN_PROGRESS") && !kanbanStages.includes("REJECTED");

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

    if (to === displayedFrom || to === from) {
      // 同甬道内排序；已驳回勾出独立甬道后，在自己甬道内拖拽排序也走这里
      reorderInLane(to, activeNo, String(over.id));
      return;
    }
    const maxOrder = Math.max(-1, ...Object.values(appStore.getState().order));
    setTicketOrder(activeNo, maxOrder + 1);
    attemptTransition(activeNo, from, to);
  };

  const activeTicket = activeId ? tickets.find((t) => t.ticketNo === activeId) : null;
  const stagesCustom =
    kanbanStages.length !== KANBAN_DEFAULT_STAGES.length ||
    kanbanStages.some((st) => !KANBAN_DEFAULT_STAGES.includes(st));
  const hasFilters = Boolean(query.trim()) || priorityFilter !== "ALL" || stagesCustom;
  // 未勾选甬道上的工单不会上板（已驳回内嵌进行中时除外）：标题行提示数量，点击打开筛选面板
  const hiddenCount = filteredTickets.filter(
    (t) => !kanbanStages.includes(t.stage) && !(t.stage === "REJECTED" && rejectEmbedded),
  ).length;
  const clearFilters = () => {
    setQuery("");
    setPriorityFilter("ALL");
    setKanbanStages([...KANBAN_DEFAULT_STAGES]);
  };

  return (
    <div className="flex-1 min-h-0 flex flex-col">
      <div className="flex flex-wrap items-center gap-2 px-4 pt-3 pb-2 shrink-0">
        <span className="kicker">看板</span>
        <span className="font-mono text-[11px] text-faint">
          {hasFilters ? `${visibleTickets.length} / ${tickets.length}` : tickets.length} 个工单
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
        <KanbanFilterButton
          open={filterOpen}
          setOpen={setFilterOpen}
          priorityFilter={priorityFilter}
          setPriorityFilter={setPriorityFilter}
        />
        {hiddenCount > 0 && (
          <button
            type="button"
            className="chip border border-warn/40 bg-warn/10 text-warn cursor-pointer hover:border-warn/60 transition-colors"
            onClick={() => setFilterOpen(true)}
            title="点击打开筛选面板，勾选对应甬道查看这些工单"
          >
            另有 {hiddenCount} 条工单未显示
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
            {laneStages.map((key) => (
              <Lane
                key={key}
                stage={key}
                title={STAGE_LABEL[key]}
                tickets={byLane.get(key) ?? []}
                rejectedTickets={
                  key === "IN_PROGRESS" && rejectEmbedded ? byLane.get("REJECTED") ?? [] : []
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
