import { useEffect, useMemo, useRef, useState } from "react";
import { motion } from "motion/react";
import {
  CheckCircle,
  CircleNotch,
  FolderSimple,
  Pulse,
  Question,
  ShieldWarning,
  Sparkle,
  WarningCircle,
} from "@phosphor-icons/react";
import { jumpToTicketSession } from "@/features/ticket";
import { useApp } from "@/store";
import type { AgentConfig, ChatSession, Ticket } from "@/shared/types";

/** ms → "mm:ss"（超过 1 小时为 "h:mm:ss"）。 */
function elapsedLabel(from: number, now: number): string {
  const sec = Math.max(0, Math.floor((now - from) / 1000));
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = sec % 60;
  const p = (x: number) => String(x).padStart(2, "0");
  return h > 0 ? `${h}:${p(m)}:${p(s)}` : `${p(m)}:${p(s)}`;
}

interface SessionRunRow {
  ticketNo: string;
  ticket: Ticket;
  session: ChatSession;
  agent?: AgentConfig;
  since: number;
}

interface AskRow {
  ticketNo: string;
  ticket: Ticket;
  sessionId: string;
  session?: ChatSession;
  kind: "question" | "permission";
  id: string;
}

interface InterruptedRow {
  ticketNo: string;
  ticket: Ticket;
  session: ChatSession;
  agent?: AgentConfig;
  at: number;
}

interface EndedRow {
  ticketNo: string;
  ticket: Ticket;
  session?: ChatSession;
  at: number;
}

function RunningSessionItem({
  row,
  now,
  active,
  onJump,
  nested = false,
}: {
  row: SessionRunRow;
  now: number;
  active: boolean;
  onJump: (ticketNo: string, sessionId?: string) => void;
  nested?: boolean;
}) {
  const title = row.ticket.title || "(无标题工单)";
  const sessionTitle = row.session.title || "未命名会话";
  const agentLabel = row.agent ? `${row.agent.name} · ${row.agent.model}` : "智能体运行中";

  return (
    <button
      onClick={() => onJump(row.ticketNo, row.session.id)}
      title={`跳转到 ${row.ticketNo} 会话：${sessionTitle}`}
      className={`w-full flex items-center gap-2 px-2 py-1.5 rounded-lg text-left cursor-pointer transition-colors border ${
        nested ? "bg-panel/60 hover:bg-panel" : "bg-raised"
      } ${
        active
          ? "border-accent/40 bg-raised text-accent"
          : "border-transparent hover:border-edge"
      }`}
    >
      <span className="w-5 h-5 rounded bg-info/10 grid place-items-center text-info shrink-0">
        <Sparkle size={12} weight="fill" />
      </span>
      <span className="flex-1 min-w-0">
        <span className="flex items-center gap-1.5 min-w-0">
          {!nested && <span className="font-mono text-[11px] text-accent shrink-0">{row.ticketNo}</span>}
          <span className="text-[12px] text-ink truncate">{nested ? sessionTitle : title}</span>
        </span>
        <span className="flex items-center gap-1 text-[10.5px] text-faint min-w-0">
          <span className="truncate">{agentLabel}</span>
          {!nested && sessionTitle && (
            <>
              <span className="text-edge-strong shrink-0">·</span>
              <span className="truncate">{sessionTitle}</span>
            </>
          )}
        </span>
      </span>
      <span className="shrink-0 flex items-center gap-1 text-faint">
        <span className="font-mono text-[10.5px] tabular-nums" title="本次运行时长">
          {elapsedLabel(row.since, now)}
        </span>
        <CircleNotch
          size={12}
          className="text-info animate-[spin_0.9s_linear_infinite]"
          aria-label="运行中"
        />
      </span>
    </button>
  );
}

export function RunMonitor() {
  const busy = useApp((s) => s.busy);
  const busySince = useApp((s) => s.busySince);
  const sessionBusy = useApp((s) => s.sessionBusy);
  const sessionBusySince = useApp((s) => s.sessionBusySince);
  const sessionInterrupted = useApp((s) => s.sessionInterrupted);
  const pendingPermissions = useApp((s) => s.pendingPermissions);
  const pendingQuestions = useApp((s) => s.pendingQuestions);
  const sessionEnded = useApp((s) => s.sessionEnded);
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

  // 1. 待回答 / 待授权 会话与工单
  const askList = useMemo<AskRow[]>(() => {
    const rows: AskRow[] = [];
    const seenSessions = new Set<string>();

    for (const [id, q] of Object.entries(pendingQuestions)) {
      const ticket = tickets.find((t) => t.ticketNo === q.ticketNo);
      if (!ticket) continue;
      const session = (sessions[q.ticketNo] ?? []).find((x) => x.id === q.sessionId);
      const key = `${q.ticketNo}-${q.sessionId}`;
      if (!seenSessions.has(key)) {
        seenSessions.add(key);
        rows.push({ ticketNo: q.ticketNo, ticket, sessionId: q.sessionId, session, kind: "question", id });
      }
    }
    for (const [id, p] of Object.entries(pendingPermissions)) {
      const ticket = tickets.find((t) => t.ticketNo === p.ticketNo);
      if (!ticket) continue;
      const session = (sessions[p.ticketNo] ?? []).find((x) => x.id === p.sessionId);
      const key = `${p.ticketNo}-${p.sessionId}`;
      if (!seenSessions.has(key)) {
        seenSessions.add(key);
        rows.push({ ticketNo: p.ticketNo, ticket, sessionId: p.sessionId, session, kind: "permission", id });
      }
    }
    return rows;
  }, [pendingQuestions, pendingPermissions, tickets, sessions]);

  // 2. 中断会话列表（未处于运行态且非归档的 interrupted 会话）
  const interruptedList = useMemo<InterruptedRow[]>(() => {
    const rows: InterruptedRow[] = [];
    for (const [sid, at] of Object.entries(sessionInterrupted)) {
      if (sessionBusy[sid]) continue;
      for (const [no, sList] of Object.entries(sessions)) {
        const sess = sList.find((s) => s.id === sid);
        if (sess && sess.status !== "archived") {
          const ticket = tickets.find((t) => t.ticketNo === no);
          if (ticket) {
            const agent =
              agents.find((a) => a.id === (sess.agentConfigId ?? agentId)) ??
              agents.find((a) => a.id === agentId);
            rows.push({ ticketNo: no, ticket, session: sess, agent, at });
          }
          break;
        }
      }
    }
    return rows.sort((a, b) => b.at - a.at);
  }, [sessionInterrupted, sessionBusy, sessions, tickets, agents, agentId]);

  // 3. 运行中会话列表（会话维度）
  const runningSessions = useMemo<SessionRunRow[]>(() => {
    const list: SessionRunRow[] = [];
    const busySids = new Set(Object.keys(sessionBusy).filter((sid) => sessionBusy[sid]));

    for (const [no, sList] of Object.entries(sessions)) {
      const ticket = tickets.find((t) => t.ticketNo === no);
      if (!ticket) continue;
      for (const sess of sList) {
        if (busySids.has(sess.id)) {
          const agent =
            agents.find((a) => a.id === (sess.agentConfigId ?? agentId)) ??
            agents.find((a) => a.id === agentId);
          list.push({
            ticketNo: no,
            ticket,
            session: sess,
            agent,
            since: sessionBusySince[sess.id] ?? busySince[no] ?? Date.now(),
          });
        }
      }
    }

    // 兜底兼容：如果工单忙但其 sessions 列表尚未同步或无匹配 sessionBusy，补充单行
    for (const no of Object.keys(busy)) {
      if (busy[no] && !list.some((r) => r.ticketNo === no)) {
        const ticket = tickets.find((t) => t.ticketNo === no);
        if (!ticket) continue;
        const sid = activeSessionId[no] ?? "default";
        const session: ChatSession = (sessions[no] ?? []).find((x) => x.id === sid) ?? {
          id: sid,
          ticketNo: no,
          title: "当前会话",
          status: "active",
          createdAt: Date.now(),
          updatedAt: Date.now(),
          permissionAutoAccept: false,
          agentConfigId: agentId,
        };
        const agent = agents.find((a) => a.id === agentId);
        list.push({
          ticketNo: no,
          ticket,
          session,
          agent,
          since: busySince[no] ?? Date.now(),
        });
      }
    }

    return list.sort((a, b) => b.since - a.since);
  }, [sessionBusy, sessions, tickets, sessionBusySince, busySince, busy, agents, agentId, activeSessionId]);

  // 4. 已结束提醒列表（仅当没有正在运行、也没有中断时作为低优提醒）
  const endedList = useMemo<EndedRow[]>(() => {
    const rows: EndedRow[] = [];
    for (const [no, info] of Object.entries(sessionEnded)) {
      if (info.kind === "done" && !busy[no]) {
        const ticket = tickets.find((t) => t.ticketNo === no);
        if (ticket) {
          const sid = activeSessionId[no];
          const session = sid ? (sessions[no] ?? []).find((x) => x.id === sid) : undefined;
          rows.push({ ticketNo: no, ticket, session, at: info.at });
        }
      }
    }
    return rows.sort((a, b) => b.at - a.at);
  }, [sessionEnded, busy, tickets, activeSessionId, sessions]);

  // 按工单聚合运行中会话，以便多会话时折叠/分组展示
  const runningGroups = useMemo(() => {
    const groups: Array<{ ticketNo: string; ticket: Ticket; rows: SessionRunRow[] }> = [];
    const groupMap = new Map<string, { ticket: Ticket; rows: SessionRunRow[] }>();

    for (const row of runningSessions) {
      const existing = groupMap.get(row.ticketNo);
      if (existing) {
        existing.rows.push(row);
      } else {
        const entry = { ticket: row.ticket, rows: [row] };
        groupMap.set(row.ticketNo, entry);
        groups.push({ ticketNo: row.ticketNo, ticket: row.ticket, rows: entry.rows });
      }
    }
    return groups;
  }, [runningSessions]);

  const runCount = runningSessions.length;
  const askCount = askList.length;
  const interruptedCount = interruptedList.length;
  const endedCount = endedList.length;

  // 氛围优先级判定：黄（待回答/待授权） > 红（中断） > 蓝（运行中） > 绿（已结束） > 空闲
  type AmbientTone = "yellow" | "red" | "blue" | "green" | "idle";
  const tone: AmbientTone = useMemo(() => {
    if (askCount > 0) return "yellow";
    if (interruptedCount > 0) return "red";
    if (runCount > 0) return "blue";
    if (endedCount > 0) return "green";
    return "idle";
  }, [askCount, interruptedCount, runCount, endedCount]);

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

  const jump = (no: string, sid?: string) => {
    jumpToTicketSession(no, sid);
    closePanel();
  };

  // Chip 样式与文字映射
  const chipStyles = {
    yellow: "bg-warn/15 border-warn/40 text-warn hover:bg-warn/25",
    red: "bg-danger/15 border-danger/40 text-danger hover:bg-danger/25",
    blue: "bg-info/15 border-info/40 text-info hover:bg-info/25",
    green: "bg-accent/15 border-accent/40 text-accent hover:bg-accent/25",
    idle: "bg-transparent border-transparent text-dim hover:text-ink",
  }[tone];

  const chipIcon = {
    yellow: <Question size={13} weight="bold" className="shrink-0 animate-pulse text-warn" />,
    red: <WarningCircle size={13} weight="bold" className="shrink-0 animate-pulse text-danger" />,
    blue: <Pulse size={13} weight="bold" className="shrink-0 animate-pulse text-info" />,
    green: <CheckCircle size={13} weight="bold" className="shrink-0 text-accent" />,
    idle: <Pulse size={13} weight="bold" className="shrink-0 text-faint" />,
  }[tone];

  const chipLabel = useMemo(() => {
    if (tone === "yellow") return `${askCount} 个待处理`;
    if (tone === "red") return `${interruptedCount} 个已中断`;
    if (tone === "blue") return `${runCount} 个运行中`;
    if (tone === "green") return `${endedCount} 个已结束`;
    return "智能体空闲";
  }, [tone, askCount, interruptedCount, runCount, endedCount]);

  const dotClass = {
    yellow: "bg-warn animate-breathe",
    red: "bg-danger",
    blue: "bg-info animate-breathe",
    green: "bg-accent",
    idle: "",
  }[tone];

  const hasAnyContent = askCount > 0 || interruptedCount > 0 || runCount > 0 || endedCount > 0;

  return (
    <div
      ref={wrapRef}
      className="relative hidden md:inline-flex items-center rounded-full p-0.5 bg-sunken border border-edge min-w-0"
      onMouseEnter={openPanel}
      onMouseLeave={scheduleClose}
    >
      <button
        className={`inline-flex items-center gap-1.5 h-7 rounded-full px-2.5 text-[12.5px] font-medium cursor-pointer transition-colors duration-150 border min-w-0 ${chipStyles}`}
        title="智能体运行监控：悬停查看聚焦面板，点击钉住；点击条目快速跳转对应会话"
        aria-expanded={open}
        onClick={() => {
          if (open && pinned) closePanel();
          else {
            setPinned(true);
            openPanel();
          }
        }}
      >
        {chipIcon}
        <span className="truncate">{chipLabel}</span>
        {dotClass && <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${dotClass}`} />}
      </button>

      {open && (
        <motion.div
          initial={{ opacity: 0, y: -4, scale: 0.97 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          transition={{ type: "spring", stiffness: 500, damping: 30 }}
          className="absolute left-0 top-9 z-40 w-[360px] card p-2 shadow-xl shadow-black/50"
        >
          <div className="flex items-center gap-2 px-2 pt-1 pb-1.5">
            <span className="kicker">运行监控 · 聚焦</span>
            <span className="flex-1" />
            <span className="chip border border-edge-strong bg-raised text-dim font-mono text-[11px]">
              {runCount > 0 ? `${runCount} 会话运行中` : "空闲"}
            </span>
          </div>

          {!hasAnyContent ? (
            <div className="px-3 py-6 text-center">
              <div className="text-[12.5px] text-dim">暂无运行中的智能体会话</div>
              <div className="mt-1 text-[11.5px] text-faint">
                在工单会话中发送消息后，运行及异常状态会在这里聚焦显示
              </div>
            </div>
          ) : (
            <div className="max-h-[380px] overflow-y-auto p-0.5 space-y-3">
              {/* 1. 待回答 / 待授权 分组（黄色氛围） */}
              {askCount > 0 && (
                <div className="rounded-lg border border-warn/30 bg-warn/5 p-2 space-y-1.5">
                  <div className="flex items-center gap-1.5 px-1 text-warn text-[11px] font-semibold">
                    <Question size={13} weight="bold" />
                    <span>待回答 / 待授权 ({askCount})</span>
                  </div>
                  <div className="space-y-1">
                    {askList.map((item) => (
                      <button
                        key={`${item.kind}-${item.id}`}
                        onClick={() => jump(item.ticketNo, item.sessionId)}
                        className="w-full flex items-center gap-2 px-2 py-1.5 rounded bg-raised/80 hover:bg-raised border border-warn/20 hover:border-warn/50 text-left transition-colors cursor-pointer"
                      >
                        <span className="w-5 h-5 rounded bg-warn/15 text-warn grid place-items-center shrink-0">
                          {item.kind === "question" ? (
                            <Question size={12} weight="bold" />
                          ) : (
                            <ShieldWarning size={12} weight="bold" />
                          )}
                        </span>
                        <span className="flex-1 min-w-0">
                          <span className="flex items-center gap-1.5 min-w-0">
                            <span className="font-mono text-[11px] text-warn shrink-0">{item.ticketNo}</span>
                            <span className="text-[12px] text-ink truncate">{item.ticket.title}</span>
                          </span>
                          <span className="text-[10.5px] text-dim truncate block">
                            {item.kind === "question" ? "智能体在等待回答提问" : "智能体在等待权限授权"}
                            {item.session?.title ? ` · ${item.session.title}` : ""}
                          </span>
                        </span>
                        <span className="text-[10.5px] font-medium text-warn shrink-0">前往处理</span>
                      </button>
                    ))}
                  </div>
                </div>
              )}

              {/* 2. 中断事件分组（红色氛围） */}
              {interruptedCount > 0 && (
                <div className="rounded-lg border border-danger/30 bg-danger/5 p-2 space-y-1.5">
                  <div className="flex items-center gap-1.5 px-1 text-danger text-[11px] font-semibold">
                    <WarningCircle size={13} weight="bold" />
                    <span>中断事件 ({interruptedCount})</span>
                  </div>
                  <div className="space-y-1">
                    {interruptedList.map((item) => (
                      <button
                        key={item.session.id}
                        onClick={() => jump(item.ticketNo, item.session.id)}
                        className="w-full flex items-center gap-2 px-2 py-1.5 rounded bg-raised/80 hover:bg-raised border border-danger/20 hover:border-danger/50 text-left transition-colors cursor-pointer"
                      >
                        <span className="w-5 h-5 rounded bg-danger/15 text-danger grid place-items-center shrink-0">
                          <WarningCircle size={12} weight="bold" />
                        </span>
                        <span className="flex-1 min-w-0">
                          <span className="flex items-center gap-1.5 min-w-0">
                            <span className="font-mono text-[11px] text-danger shrink-0">{item.ticketNo}</span>
                            <span className="text-[12px] text-ink truncate">{item.session.title || item.ticket.title}</span>
                          </span>
                          <span className="text-[10.5px] text-dim truncate block">
                            会话已异常中断或中止 · 点击查看
                          </span>
                        </span>
                        <span className="text-[10.5px] font-medium text-danger shrink-0">查看</span>
                      </button>
                    ))}
                  </div>
                </div>
              )}

              {/* 3. 运行中会话分组（按工单聚合收拢多会话，默认展开呈现） */}
              {runCount > 0 && (
                <div className="space-y-1.5">
                  <div className="flex items-center gap-1.5 px-1 text-dim text-[11px] font-medium">
                    <Pulse size={12} weight="bold" className="text-info" />
                    <span>正在执行的会话 ({runCount})</span>
                  </div>
                  <div className="space-y-1">
                    {runningGroups.map((group) => {
                      const isMulti = group.rows.length > 1;
                      if (!isMulti) {
                        return (
                          <RunningSessionItem
                            key={group.rows[0].session.id}
                            row={group.rows[0]}
                            now={now}
                            active={group.rows[0].ticketNo === selectedNo}
                            onJump={jump}
                          />
                        );
                      }
                      // 多会话同工单：收纳在一个卡片分组内，默认展开呈现
                      return (
                        <div
                          key={group.ticketNo}
                          className="rounded-lg border border-edge bg-raised/60 p-2 space-y-1.5"
                        >
                          <div className="flex items-center gap-1.5 px-0.5 min-w-0">
                            <FolderSimple size={13} weight="fill" className="text-accent shrink-0" />
                            <span className="font-mono text-[11px] text-accent font-semibold shrink-0">
                              {group.ticketNo}
                            </span>
                            <span className="text-[12px] font-medium text-ink truncate flex-1">
                              {group.ticket.title}
                            </span>
                            <span className="chip text-[10px] bg-info/10 text-info border border-info/20 shrink-0">
                              {group.rows.length} 个会话运行中
                            </span>
                          </div>
                          <div className="space-y-1 pl-2 border-l-2 border-edge">
                            {group.rows.map((row) => (
                              <RunningSessionItem
                                key={row.session.id}
                                row={row}
                                now={now}
                                active={row.ticketNo === selectedNo}
                                onJump={jump}
                                nested
                              />
                            ))}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}

              {/* 4. 已结束提醒分组（绿色氛围） */}
              {endedCount > 0 && runCount === 0 && interruptedCount === 0 && askCount === 0 && (
                <div className="rounded-lg border border-accent/30 bg-accent/5 p-2 space-y-1.5">
                  <div className="flex items-center gap-1.5 px-1 text-accent text-[11px] font-semibold">
                    <CheckCircle size={13} weight="bold" />
                    <span>最近已完成 ({endedCount})</span>
                  </div>
                  <div className="space-y-1">
                    {endedList.map((item) => (
                      <button
                        key={item.ticketNo}
                        onClick={() => jump(item.ticketNo, item.session?.id)}
                        className="w-full flex items-center gap-2 px-2 py-1.5 rounded bg-raised/80 hover:bg-raised border border-accent/20 hover:border-accent/50 text-left transition-colors cursor-pointer"
                      >
                        <span className="w-5 h-5 rounded bg-accent/15 text-accent grid place-items-center shrink-0">
                          <CheckCircle size={12} weight="bold" />
                        </span>
                        <span className="flex-1 min-w-0">
                          <span className="flex items-center gap-1.5 min-w-0">
                            <span className="font-mono text-[11px] text-accent shrink-0">{item.ticketNo}</span>
                            <span className="text-[12px] text-ink truncate">{item.ticket.title}</span>
                          </span>
                          <span className="text-[10.5px] text-dim truncate block">
                            回合已正常完成 · 点击查看
                          </span>
                        </span>
                        <span className="text-[10.5px] font-medium text-accent shrink-0">查看</span>
                      </button>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}

          <div className="divider my-1.5" />
          <div className="px-2 pb-0.5 pt-0.5 text-[10.5px] text-faint flex items-center gap-1.5">
            <Sparkle size={10} className="text-faint shrink-0" />
            {pinned ? "已钉住：移开鼠标面板不会关闭" : "移入面板保持开启 · 点击条目快速跳转会话"}
          </div>
        </motion.div>
      )}
    </div>
  );
}

