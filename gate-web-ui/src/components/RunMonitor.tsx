import { useEffect, useMemo, useRef, useState } from "react";
import { motion } from "motion/react";
import { CircleNotch, Pulse, Sparkle } from "@phosphor-icons/react";
import { jumpToTicketSession, useApp } from "../lib/store";
import type { AgentConfig, ChatSession, Ticket } from "../lib/types";

/** ms → "mm:ss"（超过 1 小时为 "h:mm:ss"）。 */
function elapsedLabel(from: number, now: number): string {
  const sec = Math.max(0, Math.floor((now - from) / 1000));
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = sec % 60;
  const p = (x: number) => String(x).padStart(2, "0");
  return h > 0 ? `${h}:${p(m)}:${p(s)}` : `${p(m)}:${p(s)}`;
}

interface RunRow {
  ticketNo: string;
  ticket: Ticket;
  session?: ChatSession;
  agent?: AgentConfig;
  since: number;
}

function RunRowItem({ row, now, active, onJump }: {
  row: RunRow;
  now: number;
  active: boolean;
  onJump: (no: string) => void;
}) {
  const title = row.ticket.title || "(无标题工单)";
  const sessionTitle = row.session?.title;
  const agentLabel = row.agent ? `${row.agent.name} · ${row.agent.model}` : "智能体运行中";
  return (
    <button
      onClick={() => onJump(row.ticketNo)}
      title={`跳转到 ${row.ticketNo} 的当前会话`}
      className={`w-full flex items-center gap-2.5 px-2.5 py-2 rounded-lg text-left cursor-pointer transition-colors border ${
        active ? "bg-raised border-edge" : "border-transparent hover:bg-raised hover:border-edge"
      }`}
    >
      <span className="w-6 h-6 rounded-md bg-accent-dim grid place-items-center text-accent shrink-0">
        <Sparkle size={13} weight="fill" />
      </span>
      <span className="flex-1 min-w-0">
        <span className="flex items-center gap-1.5 min-w-0">
          <span className="font-mono text-[11px] text-accent shrink-0">{row.ticketNo}</span>
          <span className="text-[12.5px] text-ink truncate">{title}</span>
        </span>
        <span className="mt-0.5 flex items-center gap-1.5 text-[10.5px] text-faint min-w-0">
          <span className="truncate">{agentLabel}</span>
          {sessionTitle && (
            <>
              <span className="text-edge-strong shrink-0">·</span>
              <span className="truncate">{sessionTitle}</span>
            </>
          )}
        </span>
      </span>
      <span className="shrink-0 flex items-center gap-1.5 text-faint">
        <span className="font-mono text-[11px] tabular-nums" title="本次运行时长">
          {elapsedLabel(row.since, now)}
        </span>
        <CircleNotch
          size={13}
          className="text-accent animate-[spin_0.9s_linear_infinite]"
          aria-label="运行中"
        />
      </span>
    </button>
  );
}

/**
 * 头部「运行监控 · 聚焦」面板（T-119）：
 * · 触发点常驻顶栏，展示当前运行中的智能体会话数；
 * · 聚焦列表为真正的面板——鼠标从触发点移入面板不会消失（同一 hover 容器 + 离开缓冲）；
 * · 点击触发点可钉住面板（移开鼠标也不关），再点或点击空白处/ESC 取消；
 * · 点击面板条目快速跳转到对应工单的当前会话。
 */
export function RunMonitor() {
  const busy = useApp((s) => s.busy);
  const busySince = useApp((s) => s.busySince);
  const tickets = useApp((s) => s.tickets);
  const sessions = useApp((s) => s.sessions);
  const activeSessionId = useApp((s) => s.activeSessionId);
  const agents = useApp((s) => s.agents);
  const agentId = useApp((s) => s.agentId);
  const selectedNo = useApp((s) => s.selectedNo);

  const [open, setOpen] = useState(false);
  const [pinned, setPinned] = useState(false);
  const [now, setNow] = useState(() => Date.now());
  const wrapRef = useRef<HTMLDivElement>(null);
  const closeTimer = useRef<number | null>(null);

  const running = useMemo<RunRow[]>(
    () =>
      Object.keys(busy)
        .filter((no) => busy[no] && tickets.some((t) => t.ticketNo === no))
        .sort((a, b) => (busySince[b] ?? 0) - (busySince[a] ?? 0))
        .map((no) => {
          const ticket = tickets.find((t) => t.ticketNo === no)!;
          const sid = activeSessionId[no];
          const session = sid ? (sessions[no] ?? []).find((x) => x.id === sid) : undefined;
          const agent =
            agents.find((a) => a.id === (session?.agentConfigId ?? ticket.agentConfigId)) ??
            agents.find((a) => a.id === agentId);
          return { ticketNo: no, ticket, session, agent, since: busySince[no] ?? Date.now() };
        }),
    [busy, busySince, tickets, sessions, activeSessionId, agents, agentId],
  );

  const runCount = running.length;

  /* 面板打开时每秒刷新运行时长；关闭即停表。 */
  useEffect(() => {
    if (!open) return;
    setNow(Date.now());
    const t = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(t);
  }, [open]);

  const cancelCloseTimer = () => {
    if (closeTimer.current !== null) {
      window.clearTimeout(closeTimer.current);
      closeTimer.current = null;
    }
  };
  const openPanel = () => {
    cancelCloseTimer();
    setOpen(true);
  };
  const scheduleClose = () => {
    if (pinned) return;
    cancelCloseTimer();
    closeTimer.current = window.setTimeout(() => setOpen(false), 180);
  };
  const closePanel = () => {
    cancelCloseTimer();
    setOpen(false);
    setPinned(false);
  };
  useEffect(() => cancelCloseTimer, []);

  /* 打开期间：点击面板/触发点之外 → 关闭；ESC → 关闭。 */
  useEffect(() => {
    if (!open) return;
    const onPointerDown = (e: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) closePanel();
    };
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") closePanel();
    };
    document.addEventListener("mousedown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("mousedown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [open]);

  const jump = (no: string) => {
    jumpToTicketSession(no);
    closePanel();
  };

  return (
    <div
      ref={wrapRef}
      className="relative hidden md:inline-flex"
      onMouseEnter={openPanel}
      onMouseLeave={scheduleClose}
    >
      <button
        className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-[12px] font-medium cursor-pointer transition-all duration-150 ${
          runCount > 0
            ? "border-accent/40 bg-accent/10 text-accent shadow-sm hover:bg-accent/15 hover:border-accent/60"
            : "border-edge-strong bg-raised text-dim hover:text-ink"
        }`}
        title="智能体运行监控：悬停查看聚焦面板，点击钉住；点击面板条目可跳转对应会话"
        aria-expanded={open}
        onClick={() => {
          if (open && pinned) closePanel();
          else {
            setPinned(true);
            openPanel();
          }
        }}
      >
        <Pulse
          size={13}
          weight="bold"
          className={`shrink-0 ${runCount > 0 ? "animate-pulse" : "text-faint"}`}
        />
        {runCount > 0 ? `${runCount} 个智能体运行中` : "智能体空闲"}
        {runCount > 0 && (
          <span className="w-1.5 h-1.5 rounded-full bg-accent animate-breathe shrink-0" />
        )}
      </button>

      {open && (
        <motion.div
          initial={{ opacity: 0, y: -4, scale: 0.97 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          transition={{ type: "spring", stiffness: 500, damping: 30 }}
          className="absolute left-0 top-9 z-40 w-[340px] card p-1.5 shadow-xl shadow-black/50"
        >
          <div className="flex items-center gap-2 px-2.5 pt-1.5 pb-1">
            <span className="kicker">运行监控 · 聚焦</span>
            <span className="flex-1" />
            <span className="chip border border-edge-strong bg-raised text-dim font-mono">
              {runCount > 0 ? `${runCount} 运行中` : "空闲"}
            </span>
          </div>

          {runCount === 0 ? (
            <div className="px-3 py-6 text-center">
              <div className="text-[12.5px] text-dim">暂无运行中的智能体</div>
              <div className="mt-1 text-[11.5px] text-faint">
                在工单会话中发送消息后，运行状态会在这里聚焦显示
              </div>
            </div>
          ) : (
            <div className="max-h-[320px] overflow-y-auto p-1 space-y-0.5">
              {running.map((row) => (
                <RunRowItem
                  key={row.ticketNo}
                  row={row}
                  now={now}
                  active={row.ticketNo === selectedNo}
                  onJump={jump}
                />
              ))}
            </div>
          )}

          <div className="divider my-1" />
          <div className="px-2.5 pb-1 pt-0.5 text-[10.5px] text-faint flex items-center gap-1.5">
            <Sparkle size={10} className="text-faint shrink-0" />
            {pinned ? "已钉住：移开鼠标面板不会关闭" : "移入面板不会关闭 · 点击条目跳转到对应会话"}
          </div>
        </motion.div>
      )}
    </div>
  );
}
