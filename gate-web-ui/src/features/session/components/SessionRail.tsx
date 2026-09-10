import { useEffect, useRef, useState } from "react";
import { Check, ListChecks, CircleNotch } from "@phosphor-icons/react";
import { NO_CHAT, useApp } from "@/store";
import type { ChatItem, ClaudeTaskItem, TodoItem } from "@/shared/types";
import { computeContextBreakdown,
  computeContextPercent,
  formatTokens,
  toneColor,
  usageTone, } from "@/shared/contextUsage";
import { todoProgress } from "@/shared/todoUtils";
import { useT } from "@/i18n";

/**
 * 会话横条（CenterTabs）右侧的图标按钮组（openchamber 式）：
 * · 任务清单环 —— opencode todowrite 进度 / claude TaskCreate·TaskUpdate journal
 *   （按 session.cli 三元挂载，两链互不相扰），点击弹出对应面板；
 * · 上下文环 —— 会话窗口占用（绿→黄→红 60/85 分档），点击弹出角色占比面板。
 * 无数据的图标自动隐藏；弹层从横条下方向下弹出，点击外部 / Esc 关闭。
 */

const RING_SIZE = 26;
const RING_STROKE = 2.25;

function ProgressRing({
  percent,
  color,
  children,
  title,
}: {
  percent: number;
  color: string;
  children: React.ReactNode;
  title: string;
}) {
  const radius = (RING_SIZE - RING_STROKE) / 2;
  const circumference = 2 * Math.PI * radius;
  const clamped = Math.max(0, Math.min(100, percent));
  return (
    // 显式尺寸：absolute 定位的 SVG 依赖它同心铺满，图标由 grid 居中
    <span
      className="relative grid place-items-center shrink-0"
      style={{ width: RING_SIZE, height: RING_SIZE }}
      title={title}
    >
      <svg
        width={RING_SIZE}
        height={RING_SIZE}
        viewBox={`0 0 ${RING_SIZE} ${RING_SIZE}`}
        className="absolute inset-0 -rotate-90"
        role="progressbar"
        aria-valuenow={Math.round(clamped)}
        aria-valuemin={0}
        aria-valuemax={100}
      >
        <circle
          cx={RING_SIZE / 2}
          cy={RING_SIZE / 2}
          r={radius}
          fill="none"
          stroke="var(--color-edge-strong)"
          strokeOpacity={0.5}
          strokeWidth={RING_STROKE}
        />
        <circle
          cx={RING_SIZE / 2}
          cy={RING_SIZE / 2}
          r={radius}
          fill="none"
          stroke={color}
          strokeWidth={RING_STROKE}
          strokeLinecap="round"
          strokeDasharray={circumference}
          strokeDashoffset={circumference * (1 - clamped / 100)}
          className="transition-[stroke-dashoffset,stroke] duration-300"
        />
      </svg>
      <span className="relative grid place-items-center leading-none">{children}</span>
    </span>
  );
}

function RailButton({
  open,
  onClick,
  label,
  children,
}: {
  open: boolean;
  onClick: () => void;
  label: string;
  children: React.ReactNode;
}) {
  return (
    <button
      className={`w-8 h-8 rounded-full grid place-items-center border backdrop-blur-sm transition-all duration-150 cursor-pointer ${
        open
          ? "border-accent/45 bg-raised shadow-sm"
          : "border-edge bg-panel/90 shadow-xs hover:border-edge-strong hover:bg-raised hover:shadow-sm"
      }`}
      aria-label={label}
      title={label}
      onClick={onClick}
    >
      {children}
    </button>
  );
}

function useDismiss(open: boolean, close: () => void) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) close();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") close();
    };
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open, close]);
  return ref;
}

const PRIORITY_DOT: Record<NonNullable<TodoItem["priority"]>, string> = {
  high: "bg-danger",
  medium: "bg-info",
  low: "bg-faint",
};

function TodoRow({ todo }: { todo: TodoItem }) {
  const done = todo.status === "completed";
  const cancelled = todo.status === "cancelled";
  return (
    <div className="flex items-start gap-2 min-w-0">
      {todo.priority && !cancelled && (
        <span className={`w-1.5 h-1.5 rounded-full mt-[7px] shrink-0 ${PRIORITY_DOT[todo.priority]}`} />
      )}
      {todo.status === "in_progress" ? (
        <CircleNotch size={13} className="text-info shrink-0 mt-[3px] animate-[spin_0.9s_linear_infinite]" />
      ) : done ? (
        <Check size={13} className="text-accent shrink-0 mt-[3px]" weight="bold" />
      ) : (
        <span
          className={`w-[13px] h-[13px] rounded-full border shrink-0 mt-[3px] ${
            cancelled ? "border-faint/50" : "border-edge-strong"
          }`}
        />
      )}
      <span
        className={`text-[12px] leading-relaxed flex-1 min-w-0 break-words ${
          cancelled ? "text-faint line-through" : done ? "text-dim" : "text-ink"
        }`}
      >
        {todo.content}
      </span>
    </div>
  );
}

const TODO_GROUPS: Array<{ key: TodoItem["status"]; labelKey: "todo.group.inProgress" | "todo.group.pending" | "todo.group.completed" | "todo.group.cancelled" }> = [
  { key: "in_progress", labelKey: "todo.group.inProgress" },
  { key: "pending", labelKey: "todo.group.pending" },
  { key: "completed", labelKey: "todo.group.completed" },
  { key: "cancelled", labelKey: "todo.group.cancelled" },
];

/** claude 任务条目行（V24）：subject 为主文案，activeForm 是执行中动作描述，description 折叠为副文案。 */
function ClaudeTaskRow({ task }: { task: ClaudeTaskItem }) {
  const done = task.status === "completed";
  const cancelled = task.status === "cancelled";
  const working = task.status === "in_progress";
  return (
    <div className="flex items-start gap-2 min-w-0">
      {working ? (
        <CircleNotch size={13} className="text-info shrink-0 mt-[3px] animate-[spin_0.9s_linear_infinite]" />
      ) : done ? (
        <Check size={13} className="text-accent shrink-0 mt-[3px]" weight="bold" />
      ) : (
        <span
          className={`w-[13px] h-[13px] rounded-full border shrink-0 mt-[3px] ${
            cancelled ? "border-faint/50" : "border-edge-strong"
          }`}
        />
      )}
      <div className="flex-1 min-w-0">
        <span
          className={`text-[12px] leading-relaxed block break-words ${
            cancelled ? "text-faint line-through" : done ? "text-dim" : "text-ink"
          }`}
        >
          {task.subject}
        </span>
        {working && task.activeForm && (
          <span className="block text-[11px] leading-relaxed text-info/90 mt-0.5 break-words">
            {task.activeForm}
          </span>
        )}
        {task.description && (
          <span className="block text-[11px] leading-relaxed text-faint mt-0.5 break-words line-clamp-3">
            {task.description}
          </span>
        )}
      </div>
      <span className="font-mono text-[10px] tabular-nums text-faint/70 shrink-0 mt-[4px]">#{task.id}</span>
    </div>
  );
}

/** claude 任务面板（V24 平行链）：与 TodoPanel 同款式（分组/进度条/计数）。 */
function ClaudeTaskPanel({ tasks }: { tasks: ClaudeTaskItem[] }) {
  const t = useT();
  const p = todoProgress(tasks);
  return (
    <div className="w-[300px]">
      <div className="sticky top-0 bg-overlay/95 backdrop-blur-sm px-3.5 pt-3 pb-2.5 border-b border-edge space-y-2.5">
        <div className="flex items-center gap-2">
          <ListChecks size={14} className="text-accent" weight="bold" />
          <span className="text-[12.5px] font-semibold text-ink">{t("todo.title")}</span>
          <span className="flex-1" />
          <span className="font-mono text-[11px] tabular-nums text-dim">
            {p.completed}
            <span className="text-faint">/{p.total}</span>
          </span>
        </div>
        <div className="h-1.5 rounded-full bg-edge overflow-hidden">
          <div
            className="h-full rounded-full bg-gradient-to-r from-accent/80 to-accent transition-[width] duration-300"
            style={{ width: `${Math.min(100, p.percent)}%` }}
          />
        </div>
        <div className="flex gap-2.5 flex-wrap text-[10.5px]">
          <span className="text-faint">{t("todo.total", { n: p.total })}</span>
          {p.inProgress > 0 && (
            <span className="inline-flex items-center gap-1 text-info">
              <span className="w-1 h-1 rounded-full bg-info animate-breathe" />
              {t("todo.inProgress", { n: p.inProgress })}
            </span>
          )}
          {p.pending > 0 && <span className="text-faint">{t("todo.pending", { n: p.pending })}</span>}
          {p.completed > 0 && <span className="text-accent">{t("todo.completed", { n: p.completed })}</span>}
          {p.cancelled > 0 && <span className="text-faint/70 line-through">{t("todo.cancelled", { n: p.cancelled })}</span>}
        </div>
      </div>
      <div className="max-h-[340px] overflow-y-auto p-3.5 space-y-3.5">
        {TODO_GROUPS.map(({ key, labelKey }) => {
          const group = tasks.filter((tk) => tk.status === key);
          if (group.length === 0) return null;
          return (
            <div key={key} className="space-y-1.5">
              <div className="flex items-center gap-1.5 text-[10.5px] font-semibold uppercase tracking-wider text-faint">
                {key === "in_progress" && <span className="w-1.5 h-1.5 rounded-full bg-info animate-breathe" />}
                {key === "completed" && <Check size={10} weight="bold" className="text-accent" />}
                {key === "pending" && <span className="w-1.5 h-1.5 rounded-full border border-edge-strong" />}
                {key === "cancelled" && <span className="text-faint/70 leading-none">×</span>}
                {t(labelKey)}
                <span className="flex-1 border-t border-edge/60" />
              </div>
              <div className="space-y-1.5 pl-1">
                {group.map((tk) => (
                  <ClaudeTaskRow key={`task-${tk.id}`} task={tk} />
                ))}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function TodoPanel({ todos }: { todos: TodoItem[] }) {
  const t = useT();
  const p = todoProgress(todos);
  return (
    <div className="w-[300px]">
      <div className="sticky top-0 bg-overlay/95 backdrop-blur-sm px-3.5 pt-3 pb-2.5 border-b border-edge space-y-2.5">
        <div className="flex items-center gap-2">
          <ListChecks size={14} className="text-accent" weight="bold" />
          <span className="text-[12.5px] font-semibold text-ink">{t("todo.title")}</span>
          <span className="flex-1" />
          <span className="font-mono text-[11px] tabular-nums text-dim">
            {p.completed}
            <span className="text-faint">/{p.total}</span>
          </span>
        </div>
        <div className="h-1.5 rounded-full bg-edge overflow-hidden">
          <div
            className="h-full rounded-full bg-gradient-to-r from-accent/80 to-accent transition-[width] duration-300"
            style={{ width: `${Math.min(100, p.percent)}%` }}
          />
        </div>
        <div className="flex gap-2.5 flex-wrap text-[10.5px]">
          <span className="text-faint">{t("todo.total", { n: p.total })}</span>
          {p.inProgress > 0 && (
            <span className="inline-flex items-center gap-1 text-info">
              <span className="w-1 h-1 rounded-full bg-info animate-breathe" />
              {t("todo.inProgress", { n: p.inProgress })}
            </span>
          )}
          {p.pending > 0 && <span className="text-faint">{t("todo.pending", { n: p.pending })}</span>}
          {p.completed > 0 && <span className="text-accent">{t("todo.completed", { n: p.completed })}</span>}
          {p.cancelled > 0 && <span className="text-faint/70 line-through">{t("todo.cancelled", { n: p.cancelled })}</span>}
        </div>
      </div>
      <div className="max-h-[340px] overflow-y-auto p-3.5 space-y-3.5">
        {TODO_GROUPS.map(({ key, labelKey }) => {
          const group = todos.filter((td) => td.status === key);
          if (group.length === 0) return null;
          return (
            <div key={key} className="space-y-1.5">
              <div className="flex items-center gap-1.5 text-[10.5px] font-semibold uppercase tracking-wider text-faint">
                {key === "in_progress" && <span className="w-1.5 h-1.5 rounded-full bg-info animate-breathe" />}
                {key === "completed" && <Check size={10} weight="bold" className="text-accent" />}
                {key === "pending" && <span className="w-1.5 h-1.5 rounded-full border border-edge-strong" />}
                {key === "cancelled" && <span className="text-faint/70 leading-none">×</span>}
                {t(labelKey)}
                <span className="flex-1 border-t border-edge/60" />
              </div>
              <div className="space-y-1.5 pl-1">
                {group.map((td, i) => (
                  <TodoRow key={td.id ?? `${key}-${i}`} todo={td} />
                ))}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function BreakdownRow({ label, percent, color }: {
  label: string;
  percent: number;
  color: string;
}) {
  return (
    <div className="flex items-center gap-2.5 text-[12px]">
      <span className="w-1.5 h-1.5 rounded-full shrink-0" style={{ backgroundColor: color }} />
      <span className="text-dim w-14 shrink-0">{label}</span>
      <span className="flex-1 h-1.5 rounded-full bg-edge overflow-hidden">
        <span
          className="block h-full rounded-full transition-[width] duration-300"
          style={{ width: `${percent}%`, backgroundColor: color }}
        />
      </span>
      <span className="font-mono text-[11px] tabular-nums text-ink w-9 text-right">{percent}%</span>
    </div>
  );
}

function ContextPanel({ items, ctx }: {
  items: ChatItem[];
  ctx: { tokens: number; limit: number | null } | undefined;
}) {
  const t = useT();
  const usage = computeContextPercent(ctx, items);
  const breakdown = computeContextBreakdown(items);
  const total = breakdown.user + breakdown.assistant + breakdown.tool + breakdown.other;
  const pct = (v: number) => (total > 0 ? Math.round((v / total) * 100) : 0);
  const tone = usage ? usageTone(usage.percent) : "ok";
  return (
    <div className="w-[300px] p-3.5 space-y-3">
      <div className="flex items-baseline gap-2">
        <span className="text-[12.5px] font-semibold text-ink">{t("ctx.title")}</span>
        <span className="flex-1" />
        {usage && (
          <span className="font-mono text-[15px] font-bold tabular-nums leading-none" style={{ color: toneColor(tone) }}>
            {usage.percent.toFixed(0)}
            <span className="text-[10px] font-semibold text-faint">%</span>
          </span>
        )}
      </div>
      {usage ? (
        <div className="space-y-2 pt-0.5">
          <BreakdownRow label={t("ctx.user")} percent={pct(breakdown.user)} color="var(--color-info)" />
          <BreakdownRow label={t("ctx.assistant")} percent={pct(breakdown.assistant)} color="var(--color-accent)" />
          <BreakdownRow label={t("ctx.tool")} percent={pct(breakdown.tool)} color="var(--color-warn)" />
          <BreakdownRow label={t("ctx.other")} percent={pct(breakdown.other)} color="var(--color-faint)" />
        </div>
      ) : (
        <div className="text-[12px] text-faint py-2">{t("ctx.empty")}</div>
      )}
      {usage && (
        <div className="pt-2.5 border-t border-edge flex items-center gap-1.5 font-mono text-[10.5px] text-faint">
          <span>
            {formatTokens(usage.tokens)} / {formatTokens(usage.limit)} tokens
          </span>
          {usage.estimated && <span className="text-warn">{t("ctx.estimated")}</span>}
          {!ctx?.limit && <span>{t("ctx.limitUnknown")}</span>}
        </div>
      )}
    </div>
  );
}

export function SessionRail({ ticketNo }: { ticketNo: string }) {
  const t = useT();
  // 任务清单按会话 id 键控（V21/V24）：读当前活跃会话的投影，草稿态（无会话）天然无清单。
  const activeSessionId = useApp((s) => s.activeSessionId[ticketNo]);
  const todos = useApp((s) => (activeSessionId ? s.todos[activeSessionId] : undefined));
  const tasks = useApp((s) => (activeSessionId ? s.claudeTasks[activeSessionId] : undefined));
  // 双链三元挂载（V24）：claude 会话的清单环走 TaskCreate/TaskUpdate journal，
  // opencode（含草稿态/未知）仍走 todowrite 快照——两链互不相扰。
  const activeSession = useApp((s) =>
    activeSessionId ? (s.sessions[ticketNo] ?? []).find((x) => x.id === activeSessionId) : undefined,
  );
  const agents = useApp((s) => s.agents);
  const isClaudeSession = agents.find((a) => a.id === activeSession?.agentConfigId)?.cli === "claude";
  const ctx = useApp((s) => s.context[ticketNo]);
  const chat = useApp((s) => (s.selectedNo === ticketNo ? s.chats[ticketNo] : undefined) ?? NO_CHAT);
  const [openPanel, setOpenPanel] = useState<"todo" | "task" | "context" | null>(null);
  const railRef = useDismiss(openPanel !== null, () => setOpenPanel(null));

  const progress = isClaudeSession
    ? tasks && tasks.length > 0
      ? todoProgress(tasks)
      : null
    : todos && todos.length > 0
      ? todoProgress(todos)
      : null;
  const usage = computeContextPercent(ctx, chat);
  // 仅有系统提示词的空会话（无实测 tokens、无真实对话）不显示上下文环。
  const hasConversation =
    ctx?.tokens > 0 || chat.some((i) => i.kind === "user" || i.kind === "assistant");
  const showContext = usage !== null && hasConversation;
  const ctxPct = usage ? Math.round(usage.percent) : 0;

  if (!progress && !showContext) return null;

  return (
    <div ref={railRef} className="self-center flex items-center gap-1.5">
      {progress && (
        <div className="relative">
          <RailButton
            open={openPanel === "todo" || openPanel === "task"}
            onClick={() =>
              setOpenPanel(
                (isClaudeSession ? openPanel === "task" : openPanel === "todo")
                  ? null
                  : isClaudeSession
                    ? "task"
                    : "todo",
              )
            }
            label={t("todo.railLabel", { done: progress.completed, total: progress.total })}
          >
            <ProgressRing
              percent={progress.percent}
              color="var(--color-accent)"
              title={t("todo.railLabel", { done: progress.completed, total: progress.total })}
            >
              <ListChecks size={12} className="text-accent" weight="bold" />
            </ProgressRing>
          </RailButton>
          {(openPanel === "todo" || openPanel === "task") && (
            <div className="absolute right-0 top-[calc(100%+10px)] z-30 rounded-2xl border border-edge bg-overlay backdrop-blur-sm shadow-xl animate-scale-in overflow-hidden">
              {openPanel === "task" ? (
                <ClaudeTaskPanel tasks={tasks ?? []} />
              ) : (
                <TodoPanel todos={todos ?? []} />
              )}
            </div>
          )}
        </div>
      )}
      {showContext && usage && (
        <div className="relative">
          <RailButton
            open={openPanel === "context"}
            onClick={() => setOpenPanel(openPanel === "context" ? null : "context")}
            label={t("ctx.railLabel", { n: ctxPct })}
          >
            <ProgressRing
              percent={usage.percent}
              color={toneColor(usageTone(usage.percent))}
              title={t("ctx.railLabel", { n: ctxPct })}
            >
              <span
                className="font-mono font-bold leading-none tabular-nums"
                style={{
                  color: toneColor(usageTone(usage.percent)),
                  fontSize: ctxPct >= 100 ? 7 : ctxPct >= 10 ? 8.5 : 10,
                }}
              >
                {ctxPct}
                <span style={{ fontSize: ctxPct >= 100 ? 5.5 : 6.5, fontWeight: 600 }}>%</span>
              </span>
            </ProgressRing>
          </RailButton>
          {openPanel === "context" && (
            <div className="absolute right-0 top-[calc(100%+10px)] z-30 rounded-2xl border border-edge bg-overlay backdrop-blur-sm shadow-xl animate-scale-in overflow-hidden">
              <ContextPanel items={chat} ctx={ctx} />
            </div>
          )}
        </div>
      )}
    </div>
  );
}
