import { useCallback, useMemo, useState, type CSSProperties, type ReactNode } from "react";
import { AnimatePresence, motion } from "motion/react";
import {
  Archive,
  ArrowUUpLeft,
  ArrowsInSimple,
  ArrowsOutSimple,
  CaretDown,
  Chats,
  CircleNotch,
  Copy,
  DotsSixVertical,
  Folder,
  FolderOpen,
  FolderPlus,
  LockKey,
  NotePencil,
  Plus,
  PushPin,
  PushPinSlash,
  Trash,
} from "@phosphor-icons/react";
import { actions } from "@/app/actions";
import { appStore, NO_SESSIONS, showToast, useApp } from "@/store";
import { setGateSection } from "@/features/gate/state";
import type { ChatSession, SessionGroup } from "@/shared/types";
import { copyText } from "@/shared/components/ui";
import {
  DndContext,
  DragOverlay,
  PointerSensor,
  closestCenter,
  useDroppable,
  useSensor,
  useSensors,
  type DragEndEvent,
  type DragStartEvent,
} from "@dnd-kit/core";
import { SortableContext, arrayMove, useSortable, verticalListSortingStrategy } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { SessionDialogs, sessionDialogKey, type SessionDialogState } from "./SessionDialogs";
import { getLocale, useT, type Translate } from "@/i18n";

/**
 * 会话列表段（collapsible）：活跃/归档两 tab + 新建会话草稿 + 分组管理。
 * 渲染在右侧门禁面板底部，但职权属于会话域（数据与动作都来自 session）。
 * T-105 增强：分组（名称/颜色，端侧软数据持久化于 localStorage）、右键菜单
 * （重命名/置顶/复制 ID/移入分组/归档/删除均为真实动作 + in-app 弹窗）、拖拽
 * （段内重排 + 拖入分组）、置顶、动效统一走 motion/react（项目既有的动效库）。
 * 第 6 轮重构（运行态呈现）：
 * · 会话前置小圆点承载运行态——蓝色呼吸=运行中、红色静态=中断、
 *   黄色呼吸=待问答/待授权、灰点=空闲/归档；不再复用工单 item 的「运行中」徽标；
 * · 分组前置小圆点移除，改为文件夹图标（中断/待决/运行中统由组内会话点表达）；
 *   文件夹图标恒用分组自定义色，不再被运行态染成状态色——组内运行/待问答时仅以
 *   同色呼吸（透明度 + 光晕）提示，具体状态看组内各会话行的小圆点（第 11 轮修正）。
 * 分组收展 + 归组新建：
 * · 收展箭头统一 SVG（CaretDown 随收展旋转）；tab 栏新增「全部收叠/展开」按钮（仅分组模式）；
 * · 分组头（含未分组段）hover 出「+」：进入新建会话草稿并暂存归属分组（draftGroupId），
 *   首条消息建会话时自动归入该分组（demo/live 建会话路径共用 applyDraftGroup 落地）。
 */

const FLAT_ID = "flat";
const UNGROUPED_ID = "ungrouped";
const GRP_PREFIX = "grp:";

interface SessionSegment {
  /** 段 id：分组 id / 未分组 / 无分组时的扁平列表 */
  id: string;
  group: SessionGroup | null;
  /** 置顶区（按照顶序列展示；独立 SortableContext，区内重排直接写置顶序列） */
  pinned: ChatSession[];
  /** 普通区（原相对顺序；区内重排写基础顺序） */
  rest: ChatSession[];
}

const NO_GROUPS: SessionGroup[] = [];
const NO_PINNED: string[] = [];

/* ─── 会话前置小圆点的运行态（T-105 第 6 轮重构） ───
 * 三态语义：蓝色呼吸=运行中、红色静态=中断、黄色呼吸=待问答/待授权；灰点=空闲/归档。
 * 不再复用工单 item 的「运行中」徽标——状态全部收敛到前置小圆点。
 * 优先级：待问答/待授权 > 运行中（等待用户的会话比"闲着"更值得注意）> 中断 > 空闲；
 * 中断红点由 sessionInterrupted 标记驱动（回合出错/中止点亮，该会话再次运行时熄灭）；
 * 归档会话不携带运行态——残留的中断标记/待决登记不点亮灰点，也不参与分组聚合，
 * 否则归档会话会让文件夹持续呼吸（第 6 轮审查修正）。
 * 呼吸关键帧走 currentColor（styles.css .session-run-dot），蓝色/黄色共用同一动画。 */
type SessionDotState = "running" | "interrupted" | "ask" | "idle";

const DOT_COLORS: Record<Exclude<SessionDotState, "idle">, string> = {
  running: "var(--color-info)",
  interrupted: "var(--color-danger)",
  ask: "var(--color-warn)",
};

function dotStateOf(
  running: boolean,
  pendingAsk: boolean,
  interrupted: boolean,
  archived: boolean,
): SessionDotState {
  if (archived) return "idle";
  if (pendingAsk) return "ask";
  if (running) return "running";
  if (interrupted) return "interrupted";
  return "idle";
}

/** 状态 → 小圆点呈现（呼吸类挂 .session-run-dot 并写入 currentColor）。 */
function dotPresentation(
  state: SessionDotState,
  archived: boolean,
): { className: string; style: CSSProperties } {
  if (state === "idle") {
    return {
      className: "",
      style: { backgroundColor: archived ? "var(--color-faint)" : "var(--color-dim)" },
    };
  }
  const color = DOT_COLORS[state];
  const breathing = state === "running" || state === "ask";
  return {
    className: breathing ? "session-run-dot" : "",
    style: { backgroundColor: color, color },
  };
}

const DOT_TITLES: Record<SessionDotState, "sess.dot.running" | "sess.dot.interrupted" | "sess.dot.ask" | undefined> = {
  running: "sess.dot.running",
  interrupted: "sess.dot.interrupted",
  ask: "sess.dot.ask",
  idle: undefined,
};

/** 前置状态小圆点：会话行与拖拽浮层共用。选择器只返回状态字符串（原语），避免引用抖动。 */
function RunDot({ session }: { session: ChatSession }) {
  const state = useApp((s) => {
    const pendingAsk =
      Object.values(s.pendingPermissions).some((p) => p.sessionId === session.id) ||
      Object.values(s.pendingQuestions).some((q) => q.sessionId === session.id);
    // 运行态统一按会话粒度读取 sessionBusy（live = SSE 流打点；demo = 回合起止同步
    // 打点）：不回退工单级 busy + activeSessionId——运行中切换会话会把蓝点错挂到新会话。
    const running = s.sessionBusy[session.id] === true;
    const interrupted = s.sessionInterrupted[session.id] !== undefined;
    return dotStateOf(running, pendingAsk, interrupted, session.status === "archived");
  });
  const { className, style } = dotPresentation(state, session.status === "archived");
  return (
    <RunDotInner className={className} style={style} state={state} />
  );
}

function RunDotInner({ className, style, state }: { className: string; style: CSSProperties; state: SessionDotState }) {
  const t = useT();
  const key = DOT_TITLES[state];
  return (
    <span
      className={`w-1.5 h-1.5 rounded-full shrink-0 ${className}`}
      style={style}
      title={key ? t(key) : undefined}
    />
  );
}

/** 分组聚合状态（仅驱动文件夹图标是否同色呼吸 + hover 提示文案，不参与改色）：
 *  组内任一活跃会话待决/运行中 → 呼吸（透明度 + 光晕，颜色即分组自定义色）；
 *  仅中断 → 不呼吸，hover 提示。优先级：待问答/待授权 > 运行中 > 中断 > 空闲。
 *  归档会话整体跳过：其残留的中断标记/待决登记不参与聚合（与单会话灰点口径一致）。
 *  返回原语，规避选择器引用抖动。 */
function useSegDotState(seg: SessionSegment): SessionDotState {
  return useApp((s) => {
    let seen: SessionDotState = "idle";
    for (const sess of [...seg.pinned, ...seg.rest]) {
      if (sess.status === "archived") continue;
      const pendingAsk =
        Object.values(s.pendingPermissions).some((p) => p.sessionId === sess.id) ||
        Object.values(s.pendingQuestions).some((q) => q.sessionId === sess.id);
      const running = s.sessionBusy[sess.id] === true;
      const interrupted = s.sessionInterrupted[sess.id] !== undefined;
      const st = dotStateOf(running, pendingAsk, interrupted, false);
      if (st === "ask") return "ask";
      if (st === "running") seen = "running";
      else if (st === "interrupted" && seen === "idle") seen = "interrupted";
    }
    return seen;
  });
}

/** 当前 tab 的分段视图：有分组时按分组列出（未分组殿后），无分组时退化为单段。
 *  置顶约束由渲染层分 bucket 表达（置顶区/普通区各自成 SortableContext），
 *  不在写回时做全局 pinnedFirst 覆盖——避免吞掉用户在段内的显式排序。 */
function buildSegments(
  pool: ChatSession[],
  groups: SessionGroup[],
  members: Record<string, string>,
  pinned: string[],
): SessionSegment[] {
  // 按置顶序列切置顶区，其后剩余会话按池内原顺序切普通区
  const withZones = (id: string, group: SessionGroup | null, list: ChatSession[]): SessionSegment => {
    const inList = new Set(list.map((x) => x.id));
    const zonePinned = pinned.filter((pid) => inList.has(pid));
    const pinnedSet = new Set(zonePinned);
    return {
      id,
      group,
      pinned: zonePinned.map((pid) => list.find((x) => x.id === pid) as ChatSession),
      rest: list.filter((x) => !pinnedSet.has(x.id)),
    };
  };
  if (groups.length === 0) {
    return [withZones(FLAT_ID, null, pool)];
  }
  const buckets = new Map<string, ChatSession[]>();
  const loose: ChatSession[] = [];
  for (const s of pool) {
    const gid = members[s.id];
    if (gid && groups.some((g) => g.id === gid)) {
      const list = buckets.get(gid) ?? [];
      list.push(s);
      buckets.set(gid, list);
    } else {
      loose.push(s);
    }
  }
  const segs: SessionSegment[] = groups.map((g) => withZones(g.id, g, buckets.get(g.id) ?? []));
  segs.push(withZones(UNGROUPED_ID, null, loose));
  return segs;
}

/** 编辑后的分区视图 → 规范化置顶序列：当前 tab 的置顶区序（编辑结果）在前，
 *  其余已存在但不在当前视图的置顶条目（另一 tab 的会话）原样保留在后；已删除会话的僵尸 id 剔除。 */
function canonicalPinnedOrder(
  view: SessionSegment[],
  globalPinned: string[],
  ticketSessions: ChatSession[],
): string[] {
  const shown = new Set(view.flatMap((seg) => [...seg.pinned, ...seg.rest]).map((x) => x.id));
  const exists = new Set(ticketSessions.map((x) => x.id));
  return [
    ...view.flatMap((seg) => seg.pinned).map((x) => x.id),
    ...globalPinned.filter((id) => !shown.has(id) && exists.has(id)),
  ];
}

export function SessionSection({ ticketNo, locked = false }: { ticketNo: string; locked?: boolean }) {
  const t = useT();
  const expanded = useApp((s) => s.gateSections.sessions);
  const activeCount = useApp(
    (s) => (s.sessions[ticketNo] ?? NO_SESSIONS).filter((x) => x.status === "active").length,
  );

  return (
    <>
      <button
        className="w-full shrink-0 flex items-center gap-2 px-4 py-2.5 text-left hover:bg-raised/50 transition-colors cursor-pointer"
        onClick={() => setGateSection("sessions", !expanded)}
      >
        <Chats size={14} className="text-faint shrink-0" />
        <span className="text-[12px] font-medium text-dim">{t("sess.list.title")}</span>
        {activeCount > 0 && (
          <span className="chip border border-edge-strong bg-raised text-dim font-mono">{t("sess.activeCount", { n: activeCount })}</span>
        )}
        <span className="flex-1" />
        <CaretDown
          size={12}
          weight="bold"
          className={`text-faint transition-transform duration-150 ${expanded ? "rotate-0" : "-rotate-90"}`}
        />
      </button>
      {/* 收展过渡：flexGrow 在 0↔1 间与上方信息区反向重分配剩余空间（basis 0，无钳制、
          单调平滑），列表内容随 AnimatePresence 淡入淡出，替代原先的瞬移 */}
      <motion.div
        className="flex min-h-0 flex-col overflow-hidden"
        style={{ flexBasis: 0 }}
        animate={{ flexGrow: expanded ? 1 : 0 }}
        transition={{ duration: 0.25, ease: [0.16, 1, 0.3, 1] }}
        aria-hidden={!expanded}
      >
        <AnimatePresence initial={false}>
          {expanded && (
            <motion.div
              key="session-list"
              className="flex flex-1 min-h-0 flex-col"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1, transition: { duration: 0.18 } }}
              exit={{ opacity: 0, transition: { duration: 0.12 } }}
            >
              <SessionList ticketNo={ticketNo} locked={locked} />
            </motion.div>
          )}
        </AnimatePresence>
      </motion.div>
    </>
  );
}

function SessionList({ ticketNo, locked = false }: { ticketNo: string; locked?: boolean }) {
  const t = useT();
  const sessions = useApp((s) => s.sessions[ticketNo] ?? NO_SESSIONS);
  const groups = useApp((s) => s.sessionGroups[ticketNo] ?? NO_GROUPS);
  const members = useApp((s) => s.sessionGroupMembers);
  const pinnedIds = useApp((s) => s.sessionPinned[ticketNo] ?? NO_PINNED);
  const activeSessionId = useApp((s) => s.activeSessionId[ticketNo]);
  const creating = useApp((s) => s.creatingSession[ticketNo] ?? false);

  const [tab, setTab] = useState<"active" | "archived">("active");
  /** 右键菜单目标（fixed 定位坐标），列表级持有：同一时刻最多一个打开的菜单 */
  const [menu, setMenu] = useState<{ sessionId: string; x: number; y: number } | null>(null);
  const [dialog, setDialog] = useState<SessionDialogState | null>(null);
  /** 分组段的收展状态（key = 分组 id / 未分组） */
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  /** 拖拽浮层内容：onDragStart 记录被拖会话，结束/取消时清空 */
  const [dragPreview, setDragPreview] = useState<ChatSession | null>(null);

  const openDialog = useCallback((d: SessionDialogState) => setDialog(d), []);
  const closeDialog = useCallback(() => setDialog(null), []);
  const openMenu = useCallback((sessionId: string, x: number, y: number) => setMenu({ sessionId, x, y }), []);

  const activeTotal = useMemo(() => sessions.filter((s) => s.status === "active").length, [sessions]);
  const archivedTotal = useMemo(() => sessions.filter((s) => s.status === "archived").length, [sessions]);
  const pool = useMemo(() => sessions.filter((s) => s.status === tab), [sessions, tab]);
  const grouped = groups.length > 0;
  const segments = useMemo(
    () => buildSegments(pool, groups, members, pinnedIds),
    [pool, groups, members, pinnedIds],
  );

  /** 会话当前所属段的 id（归属映射有跨工单/失联残留时按未分组处理，渲染与拖拽共用口径） */
  const segIdOf = useCallback(
    (sessionId: string): string => {
      if (grouped) {
        const gid = members[sessionId];
        if (gid && groups.some((g) => g.id === gid)) return gid;
        return UNGROUPED_ID;
      }
      return FLAT_ID;
    },
    [grouped, groups, members],
  );

  // distance 约束让「按下并点击」与「按住拖动」区分开，点击切换会话不受影响
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
  );

  /** 编辑后的分区视图 → 基础顺序写回：当前 tab 按区序（置顶区→普通区→段序）展开，
   *  另一 tab 的会话原样保留在原相对位置；置顶不再参与持久化展开（约束由分 bucket 渲染表达）。 */
  const writeTabBase = (view: SessionSegment[]) => {
    const zoneOrdered = view.flatMap((seg) => [...seg.pinned, ...seg.rest]);
    const activeNext = tab === "active" ? zoneOrdered : sessions.filter((x) => x.status === "active");
    const archivedNext = tab === "archived" ? zoneOrdered : sessions.filter((x) => x.status === "archived");
    appStore.setState((st) => ({
      sessions: { ...st.sessions, [ticketNo]: [...activeNext, ...archivedNext] },
    }));
  };

  const handleDragStart = (event: DragStartEvent) => {
    const sid = String(event.active.id);
    setDragPreview(sessions.find((x) => x.id === sid) ?? null);
  };

  const handleDragEnd = (event: DragEndEvent) => {
    setDragPreview(null);
    const { over } = event;
    const activeId = String(event.active.id);
    if (!over || String(over.id) === activeId) return;
    const overRaw = String(over.id);

    // 拖到分组头/段区域：移入该分组（未分组段 = 移出分组）
    if (overRaw.startsWith(GRP_PREFIX)) {
      const segId = overRaw.slice(GRP_PREFIX.length);
      if (segId === FLAT_ID) return;
      if (segIdOf(activeId) !== segId) {
        void actions.moveSessionToGroup(ticketNo, activeId, segId === UNGROUPED_ID ? null : segId);
      }
      return;
    }

    // 拖到另一个会话上：按「段 × 置顶区」两轴解释落点
    const overSess = sessions.find((x) => x.id === overRaw);
    if (!overSess) return;
    const locate = (sessionId: string) => {
      const segIdx = segments.findIndex(
        (seg) => seg.pinned.some((x) => x.id === sessionId) || seg.rest.some((x) => x.id === sessionId),
      );
      if (segIdx < 0) return null;
      const seg = segments[segIdx];
      const pinnedIdx = seg.pinned.findIndex((x) => x.id === sessionId);
      if (pinnedIdx >= 0) return { segIdx, zone: "pinned" as const, index: pinnedIdx };
      const restIdx = seg.rest.findIndex((x) => x.id === sessionId);
      return restIdx >= 0 ? { segIdx, zone: "rest" as const, index: restIdx } : null;
    };
    const from = locate(activeId);
    const to = locate(overSess.id);
    if (!from || !to) return;

    // 跨段（不同分组/未分组）：移入对方所在分段，置顶状态原样保留
    if (from.segIdx !== to.segIdx) {
      const overSeg = segments[to.segIdx].id;
      void actions.moveSessionToGroup(ticketNo, activeId, overSeg === UNGROUPED_ID ? null : overSeg);
      return;
    }

    // 同段编辑：克隆当前分区视图，改完一次性规范化写回（基础顺序 + 置顶序列）
    const view = segments.map((seg) => ({ ...seg, pinned: [...seg.pinned], rest: [...seg.rest] }));
    const segEdit = view[from.segIdx];

    if (from.zone === to.zone) {
      // 同区重排：置顶区重排即重写置顶序列（区序=序列），普通区只动区内相对顺序
      if (from.zone === "pinned") segEdit.pinned = arrayMove(segEdit.pinned, from.index, to.index);
      else segEdit.rest = arrayMove(segEdit.rest, from.index, to.index);
    } else if (to.zone === "pinned") {
      // 普通区 → 置顶区：以释放位置显式置顶（插入到目标会话前）
      const movedSess = segEdit.rest.find((x) => x.id === activeId) ?? overSess;
      segEdit.rest = segEdit.rest.filter((x) => x.id !== activeId);
      segEdit.pinned = segEdit.pinned.filter((x) => x.id !== activeId);
      segEdit.pinned.splice(Math.min(to.index, segEdit.pinned.length), 0, movedSess);
    } else {
      // 置顶区 → 普通区：以释放位置显式取消置顶（插入到目标会话前的普通位）
      const movedSess = segEdit.pinned.find((x) => x.id === activeId) ?? overSess;
      segEdit.pinned = segEdit.pinned.filter((x) => x.id !== activeId);
      const nextRest = segEdit.rest.filter((x) => x.id !== activeId);
      nextRest.splice(Math.min(to.index, nextRest.length), 0, movedSess);
      segEdit.rest = nextRest;
    }
    void actions.setSessionPinnedOrder(ticketNo, canonicalPinnedOrder(view, pinnedIds, sessions));
    writeTabBase(view);
  };

  const menuSession = menu ? sessions.find((x) => x.id === menu.sessionId) : undefined;
  const menuPinned = menuSession ? pinnedIds.includes(menuSession.id) : false;

  const toggleCollapse = useCallback(
    (segId: string) => setCollapsed((prev) => ({ ...prev, [segId]: !prev[segId] })),
    [],
  );
  /** 分组头 +：进入草稿并暂存归属分组（未分组段 = 不归组）；目标段收起时顺手展开，
   *  让首条消息创建的会话落位可见。 */
  const createInGroup = useCallback(
    (segId: string) => {
      if (collapsed[segId]) toggleCollapse(segId);
      actions.startSessionDraft(ticketNo, segId === UNGROUPED_ID ? undefined : segId);
    },
    [ticketNo, collapsed, toggleCollapse],
  );

  /** 全部收叠/展开（仅分组模式）：任一段展开即整体收叠，全收起时一键展开。 */
  const allCollapsed = segments.every((seg) => collapsed[seg.id] ?? false);
  const toggleAllSegments = () => {
    const next: Record<string, boolean> = {};
    if (!allCollapsed) for (const seg of segments) next[seg.id] = true;
    setCollapsed(next);
  };

  return (
    <div className="relative flex flex-col flex-1 min-h-0">
      {/* Tab bar */}
      <div className="flex items-center gap-1 px-4 pt-2.5 pb-1">
        <button
          className={`px-2.5 py-1 rounded-md text-[11.5px] font-medium transition-colors cursor-pointer ${
            tab === "active"
              ? "bg-raised text-ink border border-edge"
              : "text-dim hover:text-ink border border-transparent"
          }`}
          onClick={() => setTab("active")}
        >
          {t("sess.tab.active")}
          {activeTotal > 0 && <span className="ml-1 font-mono text-[10px] text-faint">{activeTotal}</span>}
        </button>
        <button
          className={`px-2.5 py-1 rounded-md text-[11.5px] font-medium transition-colors cursor-pointer ${
            tab === "archived"
              ? "bg-raised text-ink border border-edge"
              : "text-dim hover:text-ink border border-transparent"
          }`}
          onClick={() => setTab("archived")}
        >
          {t("sess.tab.archived")}
          {archivedTotal > 0 && <span className="ml-1 font-mono text-[10px] text-faint">{archivedTotal}</span>}
        </button>
        <span className="flex-1" />
        {grouped && (
          <button
            className="icon-btn !w-6 !h-6"
            onClick={toggleAllSegments}
            title={allCollapsed ? t("sess.expandAllGroups") : t("sess.collapseAllGroups")}
            aria-label={allCollapsed ? t("sess.expandAllGroups") : t("sess.collapseAllGroups")}
          >
            {allCollapsed ? <ArrowsOutSimple size={13} weight="bold" /> : <ArrowsInSimple size={13} weight="bold" />}
          </button>
        )}
        {!locked && (
          <>
            <button
              className="icon-btn !w-6 !h-6"
              onClick={() => openDialog({ kind: "group-create" })}
              title={t("sess.group.create")}
              aria-label={t("sess.group.create")}
            >
              <FolderPlus size={13} weight="bold" />
            </button>
            <button
              className="icon-btn !w-6 !h-6 disabled:opacity-50 disabled:pointer-events-none"
              onClick={() => actions.startSessionDraft(ticketNo)}
              disabled={(activeSessionId ?? "") === ""}
              title={(activeSessionId ?? "") === "" ? t("sess.alreadyDrafting") : t("sess.newSession")}
              aria-label={t("sess.newSession")}
            >
              <Plus size={13} weight="bold" />
            </button>
          </>
        )}
        {locked && (
          <span className="inline-flex items-center gap-1 text-[10.5px] text-faint" title={t("sess.lockedTip")}>
            <LockKey size={11} weight="fill" />
            {t("sess.locked")}
          </span>
        )}
      </div>

      {/* Session list */}
      <div className="flex-1 min-h-0 overflow-y-auto px-3 pb-2">
        {pool.length === 0 && !grouped ? (
          <SessionEmptyState
            tab={tab}
            locked={locked}
            drafting={(activeSessionId ?? "") === ""}
            onCreateDraft={() => actions.startSessionDraft(ticketNo)}
          />
        ) : (
          <DndContext
            sensors={sensors}
            collisionDetection={closestCenter}
            onDragStart={handleDragStart}
            onDragCancel={() => setDragPreview(null)}
            onDragEnd={handleDragEnd}
          >
            <div className="space-y-1">
              {segments.map((seg) => (
                <Segment
                  key={seg.id}
                  seg={seg}
                  ticketNo={ticketNo}
                  locked={locked}
                  grouped={grouped}
                  collapsed={collapsed}
                  activeSessionId={activeSessionId}
                  onToggleCollapse={toggleCollapse}
                  onCreateInGroup={createInGroup}
                  onOpenMenu={openMenu}
                  onOpenDialog={openDialog}
                />
              ))}
            </div>
            {dragPreview && <SessionDragOverlay session={dragPreview} groups={groups} members={members} />}
          </DndContext>
        )}
      </div>

      {/* 右键菜单：fixed 定位 + 全屏透明背板，点外部/再右键即关闭 */}
      <AnimatePresence>
        {menu && !locked && menuSession && (
          <ContextMenu
            session={menuSession}
            pinned={menuPinned}
            archived={menuSession.status === "archived"}
            position={menu}
            onClose={() => setMenu(null)}
            onOpenDialog={openDialog}
          />
        )}
      </AnimatePresence>

      {/* 重命名/删除/移入分组/分组管理 对话框群（动效：AnimatePresence 弹入弹出）。
          AnimatePresence 常驻、条件挂在子元素上：置 dialog=null 时退出动画才能播放，
          否则整个 AnimatePresence 连子树同帧卸载，弹出方向永远不可见（第 5 轮 INFO）。 */}
      <AnimatePresence>
        {!locked && dialog && (
          <SessionDialogs
            key={sessionDialogKey(dialog)}
            ticketNo={ticketNo}
            dialog={dialog}
            onOpenDialog={openDialog}
            onClose={closeDialog}
          />
        )}
      </AnimatePresence>

      {/* Creating overlay — 草稿首条消息触发的「建会话→写覆盖→发消息」三步期间阻断重复操作 */}
      {creating && (
        <div className="absolute inset-0 z-20 grid place-items-center bg-canvas/70 backdrop-blur-[1.5px] rounded-lg">
          <div className="flex items-center gap-2 rounded-lg border border-edge bg-raised px-3.5 py-2 shadow-lg shadow-black/30 animate-rise">
            <CircleNotch size={14} className="text-accent animate-[spin_0.9s_linear_infinite]" />
            <span className="text-[12px] text-dim">{t("composer.placeholder.creating")}</span>
          </div>
        </div>
      )}
    </div>
  );
}

/** 空状态：无会话且无分组时的引导（草稿创建入口 / 锁定提示）。 */
function SessionEmptyState({
  tab,
  locked,
  drafting,
  onCreateDraft,
}: {
  tab: "active" | "archived";
  locked: boolean;
  drafting: boolean;
  onCreateDraft: () => void;
}) {
  const t = useT();
  return (
    <div className="py-6 text-center">
      <div className="text-[12px] text-faint">
        {tab === "active"
          ? drafting
            ? t("sess.empty.drafting")
            : t("sess.empty.active")
          : t("sess.empty.archived")}
      </div>
      {tab === "active" && !locked && !drafting && (
        <button className="btn btn-sm mt-2 text-[11px]" onClick={onCreateDraft}>
          <Plus size={12} />
          {t("sess.newSession")}
        </button>
      )}
      {tab === "active" && locked && (
        <div className="mt-2 inline-flex items-center gap-1 text-[11px] text-faint">
          <LockKey size={11} weight="fill" />
          {t("sess.empty.locked")}
        </div>
      )}
    </div>
  );
}

/* ─── 分段视图 ─── */

function Segment({
  seg,
  ticketNo,
  locked,
  grouped,
  collapsed,
  activeSessionId,
  onToggleCollapse,
  onCreateInGroup,
  onOpenMenu,
  onOpenDialog,
}: {
  seg: SessionSegment;
  ticketNo: string;
  locked: boolean;
  grouped: boolean;
  collapsed: Record<string, boolean>;
  activeSessionId: string | undefined;
  onToggleCollapse: (segId: string) => void;
  onCreateInGroup: (segId: string) => void;
  onOpenMenu: (sessionId: string, x: number, y: number) => void;
  onOpenDialog: (d: SessionDialogState) => void;
}) {
  const t = useT();
  // 分组的整段区域（含分组头与空隙）都是落点：拖拽会话至此即移入该分组。
  // flat 模式没有分组语义，落点禁用。
  const { setNodeRef, isOver } = useDroppable({
    id: `${GRP_PREFIX}${seg.id}`,
    disabled: locked || !grouped || seg.id === FLAT_ID,
  });
  const isCollapsed = collapsed[seg.id] ?? false;

  return (
    <section
      ref={setNodeRef}
      className={`group/seg rounded-lg transition-colors duration-150 ${
        seg.id !== FLAT_ID && isOver ? "bg-raised/60 ring-1 ring-accent/40" : grouped ? "hover:bg-panel/50" : ""
      }`}
    >
      {grouped && (
        <SegmentHeader
          seg={seg}
          collapsed={isCollapsed}
          locked={locked}
          count={seg.pinned.length + seg.rest.length}
          onToggle={() => onToggleCollapse(seg.id)}
          onCreate={() => onCreateInGroup(seg.id)}
          onEdit={() => onOpenDialog({ kind: "group-edit", groupId: seg.id })}
          onDelete={() => onOpenDialog({ kind: "group-delete", groupId: seg.id })}
        />
      )}
      {!isCollapsed && (
        <>
          {/* 置顶区：独立 SortableContext，区内拖拽直接重写置顶序列 */}
          {seg.pinned.length > 0 && (
            <SortableContext items={seg.pinned.map((x) => x.id)} strategy={verticalListSortingStrategy}>
              <div className="flex items-center gap-1 px-2.5 pb-0.5 pt-1 select-none" title={t("sess.pinnedZoneTip")}>
                <PushPin size={9} weight="fill" className="text-faint/80" />
                <span className="text-[10px] font-medium text-faint/80">{t("sess.pinned")}</span>
                <span className="ml-1 h-px flex-1 bg-edge/60" />
              </div>
              <div className={grouped ? "pl-1" : ""}>
                <AnimatePresence initial={false}>
                  {seg.pinned.map((sess) => (
                    <SessionItem
                      key={sess.id}
                      session={sess}
                      ticketNo={ticketNo}
                      isActive={sess.id === activeSessionId}
                      locked={locked}
                      onOpenMenu={onOpenMenu}
                      onOpenDialog={onOpenDialog}
                    />
                  ))}
                </AnimatePresence>
              </div>
            </SortableContext>
          )}
          {/* 普通区：区内重排直接写基础顺序；跨区投放 = 显式置顶/取消置顶 */}
          <SortableContext items={seg.rest.map((x) => x.id)} strategy={verticalListSortingStrategy}>
            <div className={grouped ? "pl-1" : ""}>
              <AnimatePresence initial={false}>
                {seg.rest.map((sess) => (
                  <SessionItem
                    key={sess.id}
                    session={sess}
                    ticketNo={ticketNo}
                    isActive={sess.id === activeSessionId}
                    locked={locked}
                    onOpenMenu={onOpenMenu}
                    onOpenDialog={onOpenDialog}
                  />
                ))}
              </AnimatePresence>
            </div>
          </SortableContext>
          {grouped && seg.pinned.length + seg.rest.length === 0 && (
            <div className="px-2.5 pb-2 pt-0.5 text-[11px] text-faint/80 italic">
              {t("sess.groupEmpty")}
            </div>
          )}
        </>
      )}
    </section>
  );
}

/** 分组头：文件夹图标 + 名称 + 数量 + 收展箭头；悬停时提供「+ 新建会话」（归入本分组，
 *  未分组段 = 不归组）与编辑/删除分组入口（编辑/删除仅自定义分组）。
 *  第 6 轮：前置小圆点移除，改为文件夹图标（随收展切换闭合/打开）。
 *  第 11 轮修正：文件夹图标恒用分组自定义色，不再被聚合运行态覆盖成状态色；
 *  组内运行中/待问答/待授权时仅以同色呼吸（透明度 + 光晕）提示，具体状态看组内
 *  各会话行的小圆点；未分组段沿用 Chats 图标。 */
function SegmentHeader({
  seg,
  collapsed,
  locked,
  count,
  onToggle,
  onCreate,
  onEdit,
  onDelete,
}: {
  seg: SessionSegment;
  collapsed: boolean;
  locked: boolean;
  count: number;
  onToggle: () => void;
  onCreate: () => void;
  onEdit: () => void;
  onDelete: () => void;
}) {
  const t = useT();
  const g = seg.group;
  // 分组聚合运行态（原语选择器）：只决定文件夹图标是否同色呼吸，不参与改色。
  // 呼吸动画走 currentColor（透明度 + 光晕），颜色恒为分组自定义色（第 11 轮修正：
  // 文件夹图标不被运行态染成状态色；box-shadow 光晕需要圆形容器）。
  const segState = useSegDotState(seg);
  const FolderIcon = collapsed ? Folder : FolderOpen;
  const folderColor = g?.color ?? "var(--color-faint)";
  const folderBreathing = segState === "running" || segState === "ask";
  return (
    <div
      className="flex h-[26px] items-center gap-1.5 px-2.5 cursor-pointer select-none rounded-md hover:bg-raised/60 transition-colors"
      onClick={onToggle}
      role="button"
      title={collapsed ? t("common.expand") : t("common.collapse")}
    >
      <CaretDown
        size={10}
        weight="bold"
        className={`text-faint shrink-0 transition-transform duration-150 ${collapsed ? "-rotate-90" : "rotate-0"}`}
      />
      {g ? (
        <span
          className={`grid place-items-center w-4 h-4 rounded-full shrink-0 ${
            folderBreathing ? "session-run-dot" : ""
          }`}
          style={{ color: folderColor }}
          title={
            segState === "idle"
              ? undefined
              : segState === "ask"
                ? t("sess.groupState.ask")
                : segState === "running"
                  ? t("sess.groupState.running")
                  : t("sess.groupState.interrupted")
          }
        >
          <FolderIcon size={11} weight="fill" />
        </span>
      ) : (
        <Chats size={11} className="text-faint shrink-0" />
      )}
      <span className="text-[11.5px] font-medium text-faint truncate max-w-[140px]">{g?.name ?? t("sess.ungrouped")}</span>
      <span className="font-mono text-[10px] text-faint/70">{count}</span>
      <span className="flex-1" />
      {!locked && (
        <span
          className="flex items-center gap-0.5 opacity-0 transition-opacity duration-150 group-hover/seg:opacity-100"
          onClick={(e) => e.stopPropagation()}
        >
          <button
            className="icon-btn !w-5 !h-5"
            title={g ? t("sess.createInGroup", { name: g.name }) : t("sess.createNoGroup")}
            aria-label={g ? t("sess.createInGroup", { name: g.name }) : t("sess.newSession")}
            onClick={onCreate}
          >
            <Plus size={11} weight="bold" />
          </button>
          {g && (
            <>
              <button className="icon-btn !w-5 !h-5" title={t("sess.group.edit")} aria-label={t("sess.group.edit")} onClick={onEdit}>
                <NotePencil size={11} />
              </button>
              <button
                className="icon-btn !w-5 !h-5 text-danger/70 hover:text-danger"
                title={t("sess.group.delete")}
                aria-label={t("sess.group.delete")}
                onClick={onDelete}
              >
                <Trash size={11} />
              </button>
            </>
          )}
        </span>
      )}
    </div>
  );
}

/* ─── 会话行 ─── */

function SessionDragOverlay({
  session,
  groups,
  members,
}: {
  session: ChatSession;
  groups: SessionGroup[];
  members: Record<string, string>;
}) {
  const gid = members[session.id];
  const group = gid ? groups.find((g) => g.id === gid) : undefined;
  return (
    <DragOverlay dropAnimation={null}>
      <div className="flex items-center gap-2 px-2.5 py-2 rounded-lg bg-raised border border-edge-strong shadow-lg shadow-black/40 opacity-95 min-w-[160px]">
        {group && <span className="w-1.5 h-1.5 rounded-full shrink-0" style={{ background: group.color }} />}
        {/* 与行内一致的前置状态点：浮层不虚报运行态 */}
        <RunDot session={session} />
        <span className="text-[12px] text-dim truncate max-w-40">{session.title}</span>
        <span className="flex-1" />
        <DotsSixVertical size={11} className="text-faint/60 shrink-0" />
      </div>
    </DragOverlay>
  );
}

function SessionItem({
  session,
  ticketNo,
  isActive,
  locked = false,
  onOpenMenu,
  onOpenDialog,
}: {
  session: ChatSession;
  ticketNo: string;
  isActive: boolean;
  locked?: boolean;
  onOpenMenu: (sessionId: string, x: number, y: number) => void;
  onOpenDialog: (d: SessionDialogState) => void;
}) {
  const t = useT();
  const [showActions, setShowActions] = useState(false);
  // 会话绑定的协作 Agent（创建时固化）：claude 紫 / opencode 蓝色点，与 Composer 选择器一致。
  const agent = useApp((s) => s.agents.find((a) => a.id === session.agentConfigId));
  const pinned = useApp((s) => (s.sessionPinned[session.ticketNo] ?? []).includes(session.id));
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: session.id,
    disabled: locked,
  });

  return (
    <div
      ref={setNodeRef}
      style={{
        transform: CSS.Transform.toString(transform),
        transition,
        opacity: isDragging ? 0.35 : undefined,
      }}
      className="relative"
    >
      {/* motion 层与 dnd 层分离：layout 动画（重排/增删弹动）不与拖拽 transform 冲突 */}
      <motion.div
        layout="position"
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0, transition: { duration: 0.14 } }}
        transition={{ duration: 0.18, ease: [0.16, 1, 0.3, 1] }}
        className={`group flex items-center gap-2 px-2.5 py-2 rounded-lg transition-colors ${
          locked
            ? "cursor-not-allowed border border-transparent opacity-60"
            : `cursor-pointer ${
                isActive ? "bg-raised border border-edge" : "hover:bg-panel border border-transparent"
              }`
        }`}
        onClick={() => {
          if (locked) return;
          actions.switchSession(ticketNo, session.id);
        }}
        onContextMenu={(e) => {
          e.preventDefault();
          e.stopPropagation();
          if (!locked) onOpenMenu(session.id, e.clientX, e.clientY);
        }}
        onMouseEnter={() => setShowActions(true)}
        onMouseLeave={() => setShowActions(false)}
      >
        {/* 拖拽把手：只在此处启用 listeners，避免与行点击/行内按钮冲突 */}
        {!locked && (
          <span
            {...attributes}
            {...listeners}
            className="cursor-grab active:cursor-grabbing text-faint/50 hover:text-dim shrink-0 touch-none select-none"
            onClick={(e) => e.stopPropagation()}
            title={t("sess.dragHandleTip")}
          >
            <DotsSixVertical size={12} weight="bold" />
          </span>
        )}
        {/* 前置状态小圆点（T-105 第 6 轮）：蓝呼吸=运行中、红=中断、黄呼吸=待问答/待授权 */}
        <RunDot session={session} />
        <div className="flex-1 min-w-0">
          <div className="text-[12px] text-dim truncate flex items-center gap-1">
            {pinned && (
              <span title={t("sess.pinned")} className="shrink-0">
                <PushPin size={9} weight="fill" className="text-faint rotate-45" />
              </span>
            )}
            <span className="truncate">{session.title}</span>
          </div>
          <div className="font-mono text-[10px] text-faint mt-0.5 flex items-center gap-1.5 min-w-0">
            {/* agent 名在前（无色点），日期在后；id 缺失（旧数据）只显示日期，id 悬空（Agent 被删）兜底「已删除」 */}
            {session.agentConfigId && (
              <>
                <span
                  className={`font-sans truncate ${agent ? "" : "text-faint/70"}`}
                  title={agent ? `${agent.name} · ${agent.model}` : t("sess.agentDeletedTip")}
                >
                  {agent ? agent.name : t("sess.agentDeleted")}
                </span>
                <span className="text-edge-strong shrink-0">·</span>
              </>
            )}
            <span className="shrink-0">
              {new Date(session.createdAt).toLocaleDateString(getLocale(), { month: "short", day: "numeric" })}
            </span>
          </div>
        </div>
        {showActions && !locked && (
          <div className="flex items-center gap-0.5 shrink-0">
            <span className="[&>.icon-btn]:!w-5 [&>.icon-btn]:!h-5" onClick={(e) => e.stopPropagation()}>
              <RowCopyButton text={session.id} label={t("sess.copyId")} />
            </span>
            {session.status === "active" ? (
              <button
                className="icon-btn !w-5 !h-5"
                onClick={(e) => {
                  e.stopPropagation();
                  actions.archiveSession(ticketNo, session.id);
                }}
                title={t("sess.archive")}
                aria-label={t("sess.archive")}
              >
                <Archive size={11} />
              </button>
            ) : (
              <button
                className="icon-btn !w-5 !h-5"
                onClick={(e) => {
                  e.stopPropagation();
                  actions.restoreSession(ticketNo, session.id);
                }}
                title={t("sess.restore")}
                aria-label={t("sess.restore")}
              >
                <ArrowUUpLeft size={11} />
              </button>
            )}
            <button
              className="icon-btn !w-5 !h-5 text-danger/70 hover:text-danger"
              onClick={(e) => {
                e.stopPropagation();
                onOpenDialog({ kind: "delete", sessionId: session.id });
              }}
              title={t("common.delete")}
              aria-label={t("common.delete")}
            >
              <Trash size={11} />
            </button>
          </div>
        )}
      </motion.div>
    </div>
  );
}

/** 行内复制按钮：走全局 copyText（Clipboard API + execCommand 回退），成功闪 ✓。 */
function RowCopyButton({ text, label }: { text: string; label?: string }) {
  const t = useT();
  const [done, setDone] = useState(false);
  return (
    <button
      type="button"
      className="icon-btn"
      title={label ?? t("common.copy")}
      aria-label={label ?? t("common.copy")}
      onClick={() => {
        void copyText(text).then((ok) => {
          if (!ok) return;
          setDone(true);
          setTimeout(() => setDone(false), 1400);
        });
      }}
    >
      {done ? (
        <span className="text-accent text-[12px] leading-none">✓</span>
      ) : (
        <Copy size={12} weight="regular" />
      )}
    </button>
  );
}

/* ─── 右键菜单 ─── */

function MenuItem({
  icon,
  label,
  danger = false,
  onClick,
}: {
  icon: ReactNode;
  label: string;
  danger?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      className={`w-full flex items-center gap-2 px-3 py-1.5 rounded-md text-[12px] cursor-pointer text-left transition-colors ${
        danger ? "text-danger hover:bg-danger/10" : "text-dim hover:bg-raised hover:text-ink"
      }`}
      onClick={onClick}
    >
      {icon}
      {label}
    </button>
  );
}

function ContextMenu({
  session,
  pinned,
  archived,
  position,
  onClose,
  onOpenDialog,
}: {
  session: ChatSession;
  pinned: boolean;
  archived: boolean;
  position: { x: number; y: number };
  onClose: () => void;
  onOpenDialog: (d: SessionDialogState) => void;
}) {
  const t = useT();
  // 边缘防溢出：右钳 200（菜单宽约 188），下钳 300（六项菜单估高）
  const pos = {
    left: Math.min(position.x, window.innerWidth - 200),
    top: Math.min(position.y, window.innerHeight - 300),
  };
  const runThen = (fn: () => void) => {
    onClose();
    fn();
  };
  return (
    <>
      <div
        className="fixed inset-0 z-40"
        onClick={onClose}
        onContextMenu={(e) => {
          e.preventDefault();
          onClose();
        }}
      />
      <motion.div
        className="fixed z-50 card !p-1 shadow-lg shadow-black/40"
        style={pos}
        initial={{ opacity: 0, scale: 0.96, y: -4 }}
        animate={{ opacity: 1, scale: 1, y: 0 }}
        exit={{ opacity: 0, scale: 0.96, y: -4 }}
        transition={{ duration: 0.14, ease: [0.16, 1, 0.3, 1] }}
        onClick={(e) => e.stopPropagation()}
      >
        <MenuItem
          icon={<NotePencil size={12} className="text-faint" />}
          label={t("common.rename")}
          onClick={() => runThen(() => onOpenDialog({ kind: "rename", sessionId: session.id }))}
        />
        {!archived && (
          <MenuItem
            icon={
              pinned ? <PushPinSlash size={12} className="text-faint" /> : <PushPin size={12} className="text-faint" />
            }
            label={pinned ? t("sess.unpin") : t("sess.pin")}
            onClick={() => runThen(() => void actions.setSessionPinned(session.ticketNo, session.id, !pinned))}
          />
        )}
        <MenuItem
          icon={<Copy size={12} className="text-faint" />}
          label={t("sess.copyId")}
          onClick={() =>
            runThen(() => {
              void copyText(session.id).then((ok) => showToast(ok ? t("sess.copiedId") : t("common.copyFailed")));
            })
          }
        />
        <MenuItem
          icon={<FolderOpen size={12} className="text-faint" />}
          label={t("sess.moveToGroup")}
          onClick={() => runThen(() => onOpenDialog({ kind: "move-group", sessionId: session.id }))}
        />
        <MenuItem
          icon={archived ? <ArrowUUpLeft size={12} className="text-faint" /> : <Archive size={12} className="text-faint" />}
          label={archived ? t("sess.restore") : t("sess.archive")}
          onClick={() =>
            runThen(() => {
              if (archived) actions.restoreSession(session.ticketNo, session.id);
              else actions.archiveSession(session.ticketNo, session.id);
            })
          }
        />
        <div className="h-px bg-edge my-1" />
        <MenuItem
          icon={<Trash size={12} />}
          danger
          label={t("common.delete")}
          onClick={() => runThen(() => onOpenDialog({ kind: "delete", sessionId: session.id }))}
        />
      </motion.div>
    </>
  );
}
