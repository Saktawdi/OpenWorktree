import { useMemo, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import {
  Archive,
  ArrowUUpLeft,
  Chats,
  CircleNotch,
  DotsSixVertical,
  Folder,
  LockKey,
  NotePencil,
  Plus,
  Trash,
} from "@phosphor-icons/react";
import { actions } from "@/app/actions";
import { appStore, NO_SESSIONS, useApp } from "@/store";
import { setGateSection } from "@/features/gate/state";
import type { ChatSession } from "@/shared/types";
import { CopyButton } from "@/shared/components/ui";
import {
  DndContext,
  DragOverlay,
  PointerSensor,
  closestCenter,
  useSensor,
  useSensors,
  type DragEndEvent,
  type DragStartEvent,
} from "@dnd-kit/core";
import { SortableContext, arrayMove, useSortable, verticalListSortingStrategy } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";

/**
 * 会话列表段（collapsible）：活跃/归档两 tab + 新建会话草稿。
 * 渲染在右侧门禁面板底部，但职权属于会话域（数据与动作都来自 session）。
 */
export function SessionSection({ ticketNo, locked = false }: { ticketNo: string; locked?: boolean }) {
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
        <span className="text-[12px] font-medium text-dim">会话列表</span>
        {activeCount > 0 && (
          <span className="chip border border-edge-strong bg-raised text-dim font-mono">{activeCount} 活跃</span>
        )}
        <span className="flex-1" />
        <span
          className={`text-[11px] text-faint transition-transform duration-150 ${expanded ? "rotate-0" : "-rotate-90"}`}
        >
          ▾
        </span>
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
  const sessions = useApp((s) => s.sessions[ticketNo] ?? NO_SESSIONS);
  const activeSessionId = useApp((s) => s.activeSessionId[ticketNo]);
  const creating = useApp((s) => s.creatingSession[ticketNo] ?? false);
  const [tab, setTab] = useState<"active" | "archived">("active");
  /** 拖拽跟随浮层内容：onDragStart 记录被拖会话，结束/取消时清空。 */
  const [dragOverlay, setDragOverlay] = useState<React.ReactElement | null>(null);

  const activeSessions = useMemo(() => sessions.filter((s) => s.status === "active"), [sessions]);
  const archivedSessions = useMemo(() => sessions.filter((s) => s.status === "archived"), [sessions]);
  const displayed = tab === "active" ? activeSessions : archivedSessions;

  // distance 约束让「按下并点击」与「按住拖动」区分开，点击切换会话不受影响
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
  );

  const handleDragStart = (event: DragStartEvent) => {
    const sess = displayed.find((x) => x.id === event.active.id);
    if (!sess) return;
    setDragOverlay(
      <div className="flex items-center gap-2 px-2.5 py-2 rounded-lg bg-raised border border-edge-strong shadow-lg shadow-black/30 opacity-95">
        <span className="w-1.5 h-1.5 rounded-full shrink-0 bg-accent" />
        <span className="text-[12px] text-dim truncate max-w-40">{sess.title}</span>
      </div>,
    );
  };

  const handleDragCancel = () => setDragOverlay(null);

  const handleDragEnd = (event: DragEndEvent) => {
    setDragOverlay(null);
    const { active, over } = event;
    if (!over || active.id === over.id) return;
    const from = displayed.findIndex((x) => x.id === active.id);
    const to = displayed.findIndex((x) => x.id === over.id);
    if (from < 0 || to < 0) return;
    // 只重排当前 tab 内的相对顺序，另一组保持原序，重放回完整列表（活跃在前、归档在后）
    const reordered = arrayMove(displayed, from, to);
    const activeNext = tab === "active" ? reordered : activeSessions;
    const archivedNext = tab === "archived" ? reordered : archivedSessions;
    appStore.setState((st) => ({
      sessions: { ...st.sessions, [ticketNo]: [...activeNext, ...archivedNext] },
    }));
  };

  const handleCreate = () => {
    if (locked) return;
    // 新建会话 = 进入空白草稿态（清空聊天区、解锁 Agent 选择），首条消息时才真正建会话。
    // 已在草稿态（无激活会话）时按钮置灰，重复点击无意义。
    actions.startSessionDraft(ticketNo);
  };
  const drafting = (activeSessionId ?? "") === "";

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
          活跃
          {activeSessions.length > 0 && (
            <span className="ml-1 font-mono text-[10px] text-faint">{activeSessions.length}</span>
          )}
        </button>
        <button
          className={`px-2.5 py-1 rounded-md text-[11.5px] font-medium transition-colors cursor-pointer ${
            tab === "archived"
              ? "bg-raised text-ink border border-edge"
              : "text-dim hover:text-ink border border-transparent"
          }`}
          onClick={() => setTab("archived")}
        >
          归档
          {archivedSessions.length > 0 && (
            <span className="ml-1 font-mono text-[10px] text-faint">{archivedSessions.length}</span>
          )}
        </button>
        <span className="flex-1" />
        {!locked && (
          <button
            className="icon-btn !w-6 !h-6 disabled:opacity-50 disabled:pointer-events-none"
            onClick={handleCreate}
            disabled={drafting}
            title={drafting ? "已在新会话草稿中" : "新建会话"}
            aria-label="新建会话"
          >
            <Plus size={13} weight="bold" />
          </button>
        )}
        {locked && (
          <span className="inline-flex items-center gap-1 text-[10.5px] text-faint" title="工单已取消 · 会话操作已锁定">
            <LockKey size={11} weight="fill" />
            已锁定
          </span>
        )}
      </div>

      {/* Session list */}
      <div className="flex-1 min-h-0 overflow-y-auto px-3 pb-2">
        {displayed.length === 0 ? (
          <div className="py-6 text-center">
            <div className="text-[12px] text-faint">
              {tab === "active"
                ? drafting
                  ? "新会话草稿已就绪 · 选择 Agent 后发送首条消息"
                  : "暂无活跃会话"
                : "暂无归档会话"}
            </div>
            {tab === "active" && !locked && !drafting && (
              <button
                className="btn btn-sm mt-2 text-[11px]"
                onClick={handleCreate}
              >
                <Plus size={12} />
                新建会话
              </button>
            )}
            {tab === "active" && locked && (
              <div className="mt-2 inline-flex items-center gap-1 text-[11px] text-faint">
                <LockKey size={11} weight="fill" />
                工单已取消，无法新建会话
              </div>
            )}
          </div>
        ) : (
          <DndContext
            sensors={sensors}
            collisionDetection={closestCenter}
            onDragStart={handleDragStart}
            onDragCancel={handleDragCancel}
            onDragEnd={handleDragEnd}
          >
            <SortableContext
              items={displayed.map((s) => s.id)}
              strategy={verticalListSortingStrategy}
            >
              <div className="space-y-1">
                {displayed.map((sess) => (
                  <SessionItem
                    key={sess.id}
                    session={sess}
                    isActive={sess.id === activeSessionId}
                    ticketNo={ticketNo}
                    locked={locked}
                  />
                ))}
              </div>
            </SortableContext>
            {dragOverlay && <DragOverlay>{dragOverlay}</DragOverlay>}
          </DndContext>
        )}
      </div>

      {/* Creating overlay — 草稿首条消息触发的「建会话→写覆盖→发消息」三步期间阻断重复操作 */}
      {creating && (
        <div className="absolute inset-0 z-20 grid place-items-center bg-canvas/70 backdrop-blur-[1.5px] rounded-lg">
          <div className="flex items-center gap-2 rounded-lg border border-edge bg-raised px-3.5 py-2 shadow-lg shadow-black/30 animate-rise">
            <CircleNotch size={14} className="text-accent animate-[spin_0.9s_linear_infinite]" />
            <span className="text-[12px] text-dim">正在创建会话…</span>
          </div>
        </div>
      )}
    </div>
  );
}

function SessionItem({
  session,
  isActive,
  ticketNo,
  locked = false,
}: {
  session: ChatSession;
  isActive: boolean;
  ticketNo: string;
  locked?: boolean;
}) {
  const [showActions, setShowActions] = useState(false);
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number } | null>(null);
  // 会话绑定的协作 Agent（创建时固化）：claude 紫 / opencode 蓝色点，与 Composer 选择器一致。
  const agent = useApp((s) => s.agents.find((a) => a.id === session.agentConfigId));
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: session.id,
    disabled: locked,
  });

  const handleContextMenu = (e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setContextMenu({ x: e.clientX, y: e.clientY });
  };

  const handleCloseContextMenu = () => {
    setContextMenu(null);
  };

  const handleRename = () => {
    const next = window.prompt("重命名会话", session.title)?.trim();
    if (!next || next === session.title) return;
    actions.renameSession(ticketNo, session.id, next);
  };

  return (
    <div
      ref={setNodeRef}
      style={{
        transform: CSS.Transform.toString(transform),
        transition,
        opacity: isDragging ? 0.4 : undefined,
      }}
      className={`group relative flex items-center gap-2 px-2.5 py-2 rounded-lg transition-colors ${
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
      onContextMenu={handleContextMenu}
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
          title="拖拽排序"
        >
          <DotsSixVertical size={12} weight="bold" />
        </span>
      )}
      {/* 呼吸效果的活动指示器 */}
      <span
        className={`w-1.5 h-1.5 rounded-full shrink-0 ${
          isActive
            ? "bg-accent animate-breathe"
            : session.status === "archived"
            ? "bg-faint"
            : "bg-dim"
        }`}
      />
      <div className="flex-1 min-w-0">
        <div className="text-[12px] text-dim truncate">{session.title}</div>
        <div className="font-mono text-[10px] text-faint mt-0.5 flex items-center gap-1.5 min-w-0">
          {/* agent 名在前（无色点），日期在后；id 缺失（旧数据）只显示日期，id 悬空（Agent 被删）兜底「已删除」 */}
          {session.agentConfigId && (
            <>
              <span
                className={`font-sans truncate ${agent ? "" : "text-faint/70"}`}
                title={agent ? `${agent.name} · ${agent.model}` : "该会话绑定的智能体已被删除"}
              >
                {agent ? agent.name : "已删除"}
              </span>
              <span className="text-edge-strong shrink-0">·</span>
            </>
          )}
          <span className="shrink-0">
            {new Date(session.createdAt).toLocaleDateString("zh-CN", { month: "short", day: "numeric" })}
          </span>
        </div>
      </div>
      {showActions && !locked && (
        <div className="flex items-center gap-0.5 shrink-0">
          <span
            className="[&>.icon-btn]:!w-5 [&>.icon-btn]:!h-5"
            onClick={(e) => e.stopPropagation()}
          >
            <CopyButton text={session.id} label="复制会话 ID" />
          </span>
          {session.status === "active" ? (
            <button
              className="icon-btn !w-5 !h-5"
              onClick={(e) => {
                e.stopPropagation();
                actions.archiveSession(ticketNo, session.id);
              }}
              title="归档"
              aria-label="归档"
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
              title="恢复"
              aria-label="恢复"
            >
              <ArrowUUpLeft size={11} />
            </button>
          )}
          <button
            className="icon-btn !w-5 !h-5 text-danger/70 hover:text-danger"
            onClick={(e) => {
              e.stopPropagation();
              actions.deleteSession(ticketNo, session.id);
            }}
            title="删除"
            aria-label="删除"
          >
            <Trash size={11} />
          </button>
        </div>
      )}
      {/* 右键菜单：fixed 定位 + 全屏透明背板，点外部/再右键即关闭 */}
      {contextMenu && !locked && (
        <>
          <div
            className="fixed inset-0 z-40"
            onClick={handleCloseContextMenu}
            onContextMenu={(e) => {
              e.preventDefault();
              handleCloseContextMenu();
            }}
          />
          <div
            className="fixed z-50 card !p-1 shadow-lg shadow-black/40 animate-rise"
            style={{
              left: Math.min(contextMenu.x, window.innerWidth - 168),
              top: Math.min(contextMenu.y, window.innerHeight - 190),
            }}
            onClick={(e) => e.stopPropagation()}
          >
            <button
              className="w-full flex items-center gap-2 px-3 py-1.5 rounded-md text-[12px] text-dim hover:bg-raised hover:text-ink cursor-pointer text-left"
              onClick={(e) => {
                e.stopPropagation();
                handleCloseContextMenu();
                handleRename();
              }}
            >
              <NotePencil size={12} className="text-faint" />
              重命名
            </button>
            <button
              className="w-full flex items-center gap-2 px-3 py-1.5 rounded-md text-[12px] text-dim hover:bg-raised hover:text-ink cursor-pointer text-left"
              onClick={(e) => {
                e.stopPropagation();
                handleCloseContextMenu();
                actions.moveSessionToTop(ticketNo, session.id);
              }}
            >
              <ArrowUUpLeft size={12} className="text-faint" />
              置顶
            </button>
            <button
              className="w-full flex items-center gap-2 px-3 py-1.5 rounded-md text-[12px] text-dim hover:bg-raised hover:text-ink cursor-pointer text-left"
              onClick={(e) => {
                e.stopPropagation();
                handleCloseContextMenu();
                void navigator.clipboard.writeText(session.id);
              }}
            >
              <CopyButton text={session.id} label="复制会话 ID" />
            </button>
            <button
              className="w-full flex items-center gap-2 px-3 py-1.5 rounded-md text-[12px] text-dim hover:bg-raised hover:text-ink cursor-pointer text-left"
              onClick={(e) => {
                e.stopPropagation();
                handleCloseContextMenu();
                actions.moveSessionToGroupDialog(ticketNo, session.id);
              }}
            >
              <Folder size={12} className="text-faint" />
              移入分组
            </button>
            <div className="h-px bg-edge my-1" />
            <button
              className="w-full flex items-center gap-2 px-3 py-1.5 rounded-md text-[12px] text-danger hover:bg-danger/10 cursor-pointer text-left"
              onClick={(e) => {
                e.stopPropagation();
                handleCloseContextMenu();
                actions.deleteSession(ticketNo, session.id);
              }}
            >
              <Trash size={12} />
              删除
            </button>
          </div>
        </>
      )}
    </div>
  );
}
