import { useEffect, useRef, useState } from "react";
import { Check, ListChecks, CircleNotch } from "@phosphor-icons/react";
import { NO_CHAT, useApp } from "../lib/store";
import type { ChatItem, TodoItem } from "../lib/types";
import {
  computeContextBreakdown,
  computeContextPercent,
  formatTokens,
  toneColor,
  usageTone,
} from "../lib/contextUsage";
import { todoProgress } from "../lib/todoUtils";

/**
 * 会话右侧竖排图标栏（openchamber 式）：
 * · 任务清单环 —— todowrite 进度（已完成/总数），点击弹出 todolist 面板；
 * · 上下文环 —— 会话窗口占用（绿→黄→红 60/85 分档），点击弹出角色占比面板。
 * 无数据的图标自动隐藏；弹层点击外部 / Esc 关闭。
 */

const RING_SIZE = 34;
const RING_STROKE = 3;

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
    <span className="relative grid place-items-center" title={title}>
      <svg
        viewBox={`0 0 ${RING_SIZE} ${RING_SIZE}`}
        width={RING_SIZE}
        height={RING_SIZE}
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
      {children}
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
      className={`w-[34px] h-[34px] rounded-full grid place-items-center border transition-colors cursor-pointer ${
        open
          ? "border-edge-strong bg-raised"
          : "border-edge bg-panel/90 hover:bg-raised hover:border-edge-strong"
      } shadow-xs`}
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

const TODO_GROUPS: Array<{ key: TodoItem["status"]; label: string; dot?: string }> = [
  { key: "in_progress", label: "进行中" },
  { key: "pending", label: "待处理" },
  { key: "completed", label: "已完成" },
  { key: "cancelled", label: "已取消" },
];

function TodoPanel({ todos }: { todos: TodoItem[] }) {
  const p = todoProgress(todos);
  return (
    <div className="w-[300px] max-h-[420px] overflow-y-auto p-3 space-y-3">
      <div className="flex items-center gap-2">
        <ListChecks size={14} className="text-accent" />
        <span className="text-[12.5px] font-semibold text-ink">任务清单</span>
        <span className="flex-1" />
        <span className="font-mono text-[11px] text-dim">
          {p.completed}/{p.total}
        </span>
      </div>
      <div className="h-1 rounded-full bg-edge overflow-hidden">
        <div
          className="h-full rounded-full bg-accent transition-[width] duration-300"
          style={{ width: `${Math.min(100, p.percent)}%` }}
        />
      </div>
      <div className="flex gap-3 text-[11px] text-faint">
        <span>共 {p.total}</span>
        {p.inProgress > 0 && <span className="text-info">进行中 {p.inProgress}</span>}
        {p.pending > 0 && <span>待处理 {p.pending}</span>}
        {p.completed > 0 && <span className="text-accent">已完成 {p.completed}</span>}
        {p.cancelled > 0 && <span>已取消 {p.cancelled}</span>}
      </div>
      {TODO_GROUPS.map(({ key, label }) => {
        const group = todos.filter((t) => t.status === key);
        if (group.length === 0) return null;
        return (
          <div key={key} className="space-y-1.5">
            <div className="text-[10.5px] font-semibold uppercase tracking-wide text-faint">
              {label}
            </div>
            <div className="space-y-1.5 pl-1">
              {group.map((t, i) => (
                <TodoRow key={t.id ?? `${key}-${i}`} todo={t} />
              ))}
            </div>
          </div>
        );
      })}
    </div>
  );
}

function BreakdownRow({ label, tokens, percent, color }: {
  label: string;
  tokens: number;
  percent: number;
  color: string;
}) {
  return (
    <div className="space-y-1">
      <div className="flex items-center gap-2 text-[12px]">
        <span className="text-dim w-14 shrink-0">{label}</span>
        <span className="flex-1 h-1.5 rounded-full bg-edge overflow-hidden">
          <span
            className="block h-full rounded-full transition-[width] duration-300"
            style={{ width: `${percent}%`, backgroundColor: color }}
          />
        </span>
        <span className="font-mono text-[11px] text-ink w-9 text-right">{percent}%</span>
      </div>
      <div className="pl-16 font-mono text-[10px] text-faint">≈ {formatTokens(tokens)} tokens</div>
    </div>
  );
}

function ContextPanel({ items, ctx }: {
  items: ChatItem[];
  ctx: { tokens: number; limit: number | null } | undefined;
}) {
  const usage = computeContextPercent(ctx, items);
  const breakdown = computeContextBreakdown(items);
  const total = breakdown.user + breakdown.assistant + breakdown.tool + breakdown.other;
  const pct = (v: number) => (total > 0 ? Math.round((v / total) * 100) : 0);
  const tone = usage ? usageTone(usage.percent) : "ok";
  return (
    <div className="w-[300px] p-3 space-y-3">
      <div className="flex items-center gap-2">
        <span className="text-[12.5px] font-semibold text-ink">会话上下文</span>
        <span className="flex-1" />
        {usage && (
          <span className="font-mono text-[11px] font-semibold" style={{ color: toneColor(tone) }}>
            {usage.percent.toFixed(0)}%
          </span>
        )}
      </div>
      {usage ? (
        <div className="space-y-1">
          <BreakdownRow label="用户" tokens={breakdown.user} percent={pct(breakdown.user)} color="var(--color-info)" />
          <BreakdownRow label="助手" tokens={breakdown.assistant} percent={pct(breakdown.assistant)} color="var(--color-accent)" />
          <BreakdownRow label="工具调用" tokens={breakdown.tool} percent={pct(breakdown.tool)} color="var(--color-warn)" />
          <BreakdownRow label="其他" tokens={breakdown.other} percent={pct(breakdown.other)} color="var(--color-faint)" />
        </div>
      ) : (
        <div className="text-[12px] text-faint py-2">会话还没有内容，暂无上下文占用</div>
      )}
      {usage && (
        <div className="pt-2 border-t border-edge flex items-center gap-1.5 font-mono text-[10.5px] text-faint">
          <span>
            窗口 {formatTokens(usage.tokens)} / {formatTokens(usage.limit)}
          </span>
          {usage.estimated && <span className="text-warn">· 估算值</span>}
          {!ctx?.limit && <span>· 上限未知，按 200K 计</span>}
        </div>
      )}
    </div>
  );
}

export function SessionRail({ ticketNo }: { ticketNo: string }) {
  const todos = useApp((s) => s.todos[ticketNo]);
  const ctx = useApp((s) => s.context[ticketNo]);
  const chat = useApp((s) => (s.selectedNo === ticketNo ? s.chats[ticketNo] : undefined) ?? NO_CHAT);
  const [openPanel, setOpenPanel] = useState<"todo" | "context" | null>(null);
  const railRef = useDismiss(openPanel !== null, () => setOpenPanel(null));

  const progress = todos ? todoProgress(todos) : null;
  const usage = computeContextPercent(ctx, chat);
  // 仅有系统提示词的空会话（无实测 tokens、无真实对话）不显示上下文环。
  const hasConversation =
    ctx?.tokens > 0 || chat.some((i) => i.kind === "user" || i.kind === "assistant");
  const showContext = usage !== null && hasConversation;

  if (!progress && !showContext) return null;

  return (
    <div ref={railRef} className="absolute right-3 top-3 z-20 flex flex-col gap-2 items-center">
      {progress && (
        <div className="relative">
          <RailButton
            open={openPanel === "todo"}
            onClick={() => setOpenPanel(openPanel === "todo" ? null : "todo")}
            label="任务清单"
          >
            <ProgressRing
              percent={progress.percent}
              color="var(--color-accent)"
              title={`任务清单 ${progress.completed}/${progress.total}`}
            >
              <ListChecks size={15} className="text-ink" />
            </ProgressRing>
          </RailButton>
          {openPanel === "todo" && (
            <div className="absolute right-[calc(100%+8px)] top-0 rounded-xl border border-edge bg-overlay shadow-lg animate-scale-in overflow-hidden">
              <TodoPanel todos={todos ?? []} />
            </div>
          )}
        </div>
      )}
      {showContext && usage && (
        <div className="relative">
          <RailButton
            open={openPanel === "context"}
            onClick={() => setOpenPanel(openPanel === "context" ? null : "context")}
            label={`会话上下文 ${usage.percent.toFixed(0)}%`}
          >
            <ProgressRing
              percent={usage.percent}
              color={toneColor(usageTone(usage.percent))}
              title={`会话上下文 ${usage.percent.toFixed(0)}%`}
            >
              <span
                className="font-mono text-[9px] font-semibold leading-none"
                style={{ color: toneColor(usageTone(usage.percent)) }}
              >
                {Math.round(usage.percent)}
              </span>
            </ProgressRing>
          </RailButton>
          {openPanel === "context" && (
            <div className="absolute right-[calc(100%+8px)] top-0 rounded-xl border border-edge bg-overlay shadow-lg animate-scale-in overflow-hidden">
              <ContextPanel items={chat} ctx={ctx} />
            </div>
          )}
        </div>
      )}
    </div>
  );
}
