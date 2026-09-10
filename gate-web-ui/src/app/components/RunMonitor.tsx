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
import { jumpToTicketSession, selectTicketLive } from "@/features/ticket";
import { clearSessionInterrupted, dismissSessionAsks } from "@/features/session";
import { clearReviewEnded } from "@/features/gate";
import { useApp } from "@/store";
import { useT } from "@/i18n";
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
  /** 同一会话可能同时挂着提问与授权（多待决）；行按会话聚合，kinds 记录实际构成。 */
  kinds: Array<"question" | "permission">;
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

interface ReviewedRow {
  ticketNo: string;
  ticket: Ticket;
  session?: ChatSession;
  verdict: "PASS" | "REJECT" | "REQUIRES_HUMAN";
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
  const t = useT();
  const title = row.ticket.title || t("runmonitor.untitledTicket");
  const sessionTitle = row.session.title || t("runmonitor.untitled");
  const agentLabel = row.agent ? `${row.agent.name} · ${row.agent.model}` : t("runmonitor.agentRunning");

  return (
    <button
      onClick={() => onJump(row.ticketNo, row.session.id)}
      title={t("runmonitor.jumpToSession", { no: row.ticketNo, title: sessionTitle })}
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
        <span className="font-mono text-[10.5px] tabular-nums" title={t("runmonitor.runDurationTip")}>
          {elapsedLabel(row.since, now)}
        </span>
        <CircleNotch
          size={12}
          className="text-info animate-[spin_0.9s_linear_infinite]"
          aria-label={t("common.running")}
        />
      </span>
    </button>
  );
}

export function RunMonitor() {
  const t = useT();
  const busy = useApp((s) => s.busy);
  const busySince = useApp((s) => s.busySince);
  const sessionBusy = useApp((s) => s.sessionBusy);
  const sessionBusySince = useApp((s) => s.sessionBusySince);
  const sessionInterrupted = useApp((s) => s.sessionInterrupted);
  const pendingPermissions = useApp((s) => s.pendingPermissions);
  const pendingQuestions = useApp((s) => s.pendingQuestions);
  const sessionEnded = useApp((s) => s.sessionEnded);
  const reviewEnded = useApp((s) => s.reviewEnded);
  const tickets = useApp((s) => s.tickets);
  const sessions = useApp((s) => s.sessions);
  const activeSessionId = useApp((s) => s.activeSessionId);
  const agents = useApp((s) => s.agents);
  const agentId = useApp((s) => s.agentId);
  const selectedNo = useApp((s) => s.selectedNo);
  const mode = useApp((s) => s.mode);
  const conn = useApp((s) => s.conn);

  const [open, setOpen] = useState(false);
  const [pinned, setPinned] = useState(false);
  const [now, setNow] = useState(() => Date.now());
  const wrapRef = useRef<HTMLDivElement>(null);
  const closeTimer = useRef<number | null>(null);

  // 1. 待回答 / 待授权 会话与工单（按会话聚合：一个会话的多个待决合并为一行）
  const askList = useMemo<AskRow[]>(() => {
    const rows = new Map<string, AskRow>();
    const upsert = (
      ticketNo: string,
      sessionId: string,
      kind: "question" | "permission",
    ) => {
      const ticket = tickets.find((t) => t.ticketNo === ticketNo);
      if (!ticket) return;
      const key = `${ticketNo}-${sessionId}`;
      const existing = rows.get(key);
      if (existing) {
        if (!existing.kinds.includes(kind)) existing.kinds.push(kind);
        return;
      }
      const session = (sessions[ticketNo] ?? []).find((x) => x.id === sessionId);
      rows.set(key, {
        ticketNo,
        ticket,
        sessionId,
        session,
        kind,
        kinds: [kind],
      });
    };
    for (const [, q] of Object.entries(pendingQuestions)) upsert(q.ticketNo, q.sessionId, "question");
    for (const [, p] of Object.entries(pendingPermissions)) upsert(p.ticketNo, p.sessionId, "permission");
    return [...rows.values()];
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
          title: t("runmonitor.currentSession"),
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

  // 5. 审查结果提醒列表（T-110：与已结束同级低优呈现；打开工单即清除）
  const reviewedList = useMemo<ReviewedRow[]>(() => {
    const rows: ReviewedRow[] = [];
    for (const [no, info] of Object.entries(reviewEnded)) {
      if (busy[no]) continue;
      const ticket = tickets.find((t) => t.ticketNo === no);
      if (ticket) {
        const sid = activeSessionId[no];
        const session = sid ? (sessions[no] ?? []).find((x) => x.id === sid) : undefined;
        rows.push({ ticketNo: no, ticket, session, verdict: info.verdict, at: info.at });
      }
    }
    return rows.sort((a, b) => b.at - a.at);
  }, [reviewEnded, busy, tickets, activeSessionId, sessions]);

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
  const reviewedCount = reviewedList.length;
  const finishedCount = endedCount + reviewedCount;

  // 氛围优先级判定：黄（待回答/待授权） > 红（中断） > 蓝（运行中） > 绿（已结束/已审查） > 空闲
  type AmbientTone = "yellow" | "red" | "blue" | "green" | "idle";
  const tone: AmbientTone = useMemo(() => {
    if (askCount > 0) return "yellow";
    if (interruptedCount > 0) return "red";
    if (runCount > 0) return "blue";
    if (finishedCount > 0) return "green";
    return "idle";
  }, [askCount, interruptedCount, runCount, finishedCount]);

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

  /* 打开某工单并聚焦会话（红/黄条目点击 = "打开处理"的完整语义）：
     jumpToTicketSession 只做本地选中、不拉数据——会话列表/历史/待决卡片从未加载时
     （刷新后提醒重亮即点、慢节拍补拉来的提醒），落地是空列表空对话，条目无从处理。
     live 走 selectTicketLive 完整打开（会话/历史/待决卡片/门禁状态一次拉齐，幂等）；
     失败或 demo 回退本地选中。 */
  const jumpOpen = async (no: string, sid?: string) => {
    if (mode === "live" && conn === "ok") {
      try {
        await selectTicketLive(no, sid);
        closePanel();
        return;
      } catch {
        /* 拉取失败不阻断跳转：本地选中兜底 */
      }
    }
    jumpToTicketSession(no, sid);
    closePanel();
  };

  /* 待回答/待授权条目：视为"已关注该会话"——清掉其名下待决登记并记入忽略表，同会话
     若还挂着中断标记一并清除（红/黄两组不因另一半残留而常驻），黄组/徽标/会话黄点
     随之消退；完整打开工单后会话内即有可作答的卡片，作答才是服务端真正的了结。 */
  const jumpToAsk = (item: AskRow) => {
    dismissSessionAsks(item.sessionId);
    clearSessionInterrupted(item.sessionId);
    void jumpOpen(item.ticketNo, item.sessionId);
  };

  /* 中断条目：同样按"已关注该会话"处理——清除中断标记，名下若有未决登记一并视为已见
     （打开后仍可作答/重跑），红组与红点随之消退。 */
  const jumpToInterrupted = (item: InterruptedRow) => {
    clearSessionInterrupted(item.session.id);
    dismissSessionAsks(item.session.id);
    void jumpOpen(item.ticketNo, item.session.id);
  };

  /* 审查结果条目：打开工单即视为已读——清除该工单的 reviewEnded 登记，绿色提醒组
     （及 chip 上的已审查计数）随之消退，与待回答/中断条目的"点击即移出"语义一致。 */
  const jumpToReviewed = (item: ReviewedRow) => {
    clearReviewEnded(item.ticketNo);
    void jumpOpen(item.ticketNo, item.session?.id);
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
    if (tone === "yellow") return t("runmonitor.chip.pending", { n: askCount });
    if (tone === "red") return t("runmonitor.chip.interrupted", { n: interruptedCount });
    if (tone === "blue") return t("runmonitor.chip.running", { n: runCount });
    if (tone === "green") {
      if (endedCount > 0 && reviewedCount === 0) return t("runmonitor.chip.ended", { n: endedCount });
      if (reviewedCount > 0 && endedCount === 0) return t("runmonitor.chip.reviewed", { n: reviewedCount });
      return t("runmonitor.chip.finished", { n: finishedCount });
    }
    return t("runmonitor.chip.idle");
  }, [tone, askCount, interruptedCount, runCount, endedCount, reviewedCount, finishedCount, t]);

  const dotClass = {
    yellow: "bg-warn animate-breathe",
    red: "bg-danger",
    blue: "bg-info animate-breathe",
    green: "bg-accent",
    idle: "",
  }[tone];

  const hasAnyContent = askCount > 0 || interruptedCount > 0 || runCount > 0 || endedCount > 0 || reviewedCount > 0;

  return (
    <div
      ref={wrapRef}
      className="relative hidden md:inline-flex items-center rounded-full p-0.5 bg-sunken border border-edge min-w-0"
      onMouseEnter={openPanel}
      onMouseLeave={scheduleClose}
    >
      <button
        className={`inline-flex items-center gap-1.5 h-7 rounded-full px-2.5 text-[12.5px] font-medium cursor-pointer transition-colors duration-150 border min-w-0 ${chipStyles}`}
        title={t("runmonitor.chipTip")}
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
            <span className="kicker">{t("runmonitor.focusTitle")}</span>
            <span className="flex-1" />
            <span className="chip border border-edge-strong bg-raised text-dim font-mono text-[11px]">
              {runCount > 0 ? t("runmonitor.sessionsRunning", { n: runCount }) : t("runmonitor.idle")}
            </span>
          </div>

          {!hasAnyContent ? (
            <div className="px-3 py-6 text-center">
              <div className="text-[12.5px] text-dim">{t("runmonitor.emptyTitle")}</div>
              <div className="mt-1 text-[11.5px] text-faint">
                {t("runmonitor.emptyHint")}
              </div>
            </div>
          ) : (
            <div className="max-h-[380px] overflow-y-auto p-0.5 space-y-3">
              {/* 1. 待回答 / 待授权 分组（黄色氛围） */}
              {askCount > 0 && (
                <div className="rounded-lg border border-warn/30 bg-warn/5 p-2 space-y-1.5">
                  <div className="flex items-center gap-1.5 px-1 text-warn text-[11px] font-semibold">
                    <Question size={13} weight="bold" />
                    <span>{t("runmonitor.group.asks", { n: askCount })}</span>
                  </div>
                  <div className="space-y-1">
                    {askList.map((item) => {
                      const mixed = item.kinds.length > 1;
                      return (
                        <button
                          key={`${item.ticketNo}-${item.sessionId}`}
                          onClick={() => jumpToAsk(item)}
                          title={t("runmonitor.askTip", { no: item.ticketNo, title: item.session?.title ?? "" })}
                          className="w-full flex items-center gap-2 px-2 py-1.5 rounded bg-raised/80 hover:bg-raised border border-warn/20 hover:border-warn/50 text-left transition-colors cursor-pointer"
                        >
                          <span className="w-5 h-5 rounded bg-warn/15 text-warn grid place-items-center shrink-0">
                            {item.kind === "question" && !mixed ? (
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
                              {mixed
                                ? t("runmonitor.askMixed", { n: item.kinds.length })
                                : item.kind === "question"
                                  ? t("runmonitor.askQuestion")
                                  : t("runmonitor.askPermission")}
                              {item.session?.title ? ` · ${item.session.title}` : ""}
                            </span>
                          </span>
                          <span className="text-[10.5px] font-medium text-warn shrink-0">{t("runmonitor.goHandle")}</span>
                        </button>
                      );
                    })}
                  </div>
                </div>
              )}

              {/* 2. 中断事件分组（红色氛围） */}
              {interruptedCount > 0 && (
                <div className="rounded-lg border border-danger/30 bg-danger/5 p-2 space-y-1.5">
                  <div className="flex items-center gap-1.5 px-1 text-danger text-[11px] font-semibold">
                    <WarningCircle size={13} weight="bold" />
                    <span>{t("runmonitor.group.interrupted", { n: interruptedCount })}</span>
                  </div>
                  <div className="space-y-1">
                    {interruptedList.map((item) => (
                      <button
                        key={item.session.id}
                        onClick={() => jumpToInterrupted(item)}
                        title={t("runmonitor.interruptedTip", { title: item.session.title || item.ticket.title })}
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
                            {t("runmonitor.interruptedDesc")}
                          </span>
                        </span>
                        <span className="text-[10.5px] font-medium text-danger shrink-0">{t("runmonitor.view")}</span>
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
                    <span>{t("runmonitor.group.running", { n: runCount })}</span>
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
                              {t("runmonitor.sessionsRunning", { n: group.rows.length })}
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

              {/* 4. 已结束 / 审查结果提醒分组（绿色氛围，仅在没有更高优事件时作为低优提醒） */}
              {(endedCount > 0 || reviewedCount > 0) && runCount === 0 && interruptedCount === 0 && askCount === 0 && (
                <>
                  {endedCount > 0 && (
                  <div className="rounded-lg border border-accent/30 bg-accent/5 p-2 space-y-1.5">
                    <div className="flex items-center gap-1.5 px-1 text-accent text-[11px] font-semibold">
                      <CheckCircle size={13} weight="bold" />
                      <span>{t("runmonitor.group.ended", { n: endedCount })}</span>
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
                              {t("runmonitor.endedDesc")}
                            </span>
                          </span>
                          <span className="text-[10.5px] font-medium text-accent shrink-0">{t("runmonitor.view")}</span>
                        </button>
                      ))}
                    </div>
                  </div>
                  )}

                  {/* 审查结果（T-110）：与已结束同级低优——打开工单即视为已读并从监控移除；驳回行红色区分 */}
                  {reviewedCount > 0 && (
                  <div className="rounded-lg border border-accent/30 bg-accent/5 p-2 space-y-1.5">
                    <div className="flex items-center gap-1.5 px-1 text-accent text-[11px] font-semibold">
                      <CheckCircle size={13} weight="bold" />
                      <span>{t("runmonitor.group.reviewed", { n: reviewedCount })}</span>
                    </div>
                    <div className="space-y-1">
                      {reviewedList.map((item) => {
                        const rejected = item.verdict === "REJECT";
                        return (
                          <button
                            key={item.ticketNo}
                            onClick={() => jumpToReviewed(item)}
                            title={t("runmonitor.reviewedTip", { no: item.ticketNo })}
                            className={`w-full flex items-center gap-2 px-2 py-1.5 rounded bg-raised/80 hover:bg-raised border text-left transition-colors cursor-pointer ${
                              rejected ? "border-danger/20 hover:border-danger/50" : "border-accent/20 hover:border-accent/50"
                            }`}
                          >
                            <span className={`w-5 h-5 rounded grid place-items-center shrink-0 ${
                              rejected ? "bg-danger/15 text-danger" : "bg-accent/15 text-accent"
                            }`}>
                              {rejected ? <WarningCircle size={12} weight="bold" /> : <CheckCircle size={12} weight="bold" />}
                            </span>
                            <span className="flex-1 min-w-0">
                              <span className="flex items-center gap-1.5 min-w-0">
                                <span className={`font-mono text-[11px] shrink-0 ${
                                  rejected ? "text-danger" : "text-accent"
                                }`}>
                                  {item.ticketNo}
                                </span>
                                <span className="text-[12px] text-ink truncate">{item.ticket.title}</span>
                              </span>
                              <span className={`text-[10.5px] truncate block ${
                                rejected ? "text-danger/80" : "text-dim"
                              }`}>
                                {item.verdict === "PASS"
                                  ? t("runmonitor.verdict.pass")
                                  : item.verdict === "REQUIRES_HUMAN"
                                    ? t("runmonitor.verdict.human")
                                    : t("runmonitor.verdict.reject")}
                              </span>
                            </span>
                            <span className={`text-[10.5px] font-medium shrink-0 ${
                              rejected ? "text-danger" : "text-accent"
                            }`}>
                              {rejected ? t("runmonitor.viewReject") : t("runmonitor.view")}
                            </span>
                          </button>
                        );
                      })}
                    </div>
                  </div>
                  )}
                </>
              )}
            </div>
          )}

          <div className="divider my-1.5" />
          <div className="px-2 pb-0.5 pt-0.5 text-[10.5px] text-faint flex items-center gap-1.5">
            <Sparkle size={10} className="text-faint shrink-0" />
            {pinned ? t("runmonitor.pinnedHint") : t("runmonitor.hoverHint")}
          </div>
        </motion.div>
      )}
    </div>
  );
}

