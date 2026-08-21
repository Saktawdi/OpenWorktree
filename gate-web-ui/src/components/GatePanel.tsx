import { useMemo, useState } from "react";
import { motion, AnimatePresence } from "motion/react";
import {
  ArrowRight,
  ArrowUUpLeft,
  Archive,
  Check,
  CircleNotch,
  FileText,
  GitBranch,
  Hash,
  ListChecks,
  LockKey,
  NotePencil,
  Plus,
  RocketLaunch,
  SealCheck,
  ShieldCheck,
  Trash,
  Warning,
  X,
} from "@phosphor-icons/react";
import { actions } from "../lib/actions";
import { formatBytes, hhmmss, shortHash } from "../lib/format";
import {
  NO_SESSIONS,
  archiveSession,
  createSession,
  deleteSession,
  pushSystemMessage,
  restoreSession,
  setStage,
  switchSession,
  useApp,
} from "../lib/store";
import type { ChatSession, Snapshot } from "../lib/types";
import { CopyButton, HashReveal, Spinner } from "./ui";

/* ─── Stepper (horizontal pipeline) ─── */

function Stepper({ stage, snap, commitSha }: { stage: string; snap?: Snapshot; commitSha?: string }) {
  const gated = ["PRESUBMITTED", "IN_REVIEW", "REJECTED", "READY_TO_PUBLISH", "NEEDS_HUMAN", "DONE"].includes(stage);
  const coded = gated || snap !== undefined;
  const reviewed = ["READY_TO_PUBLISH", "NEEDS_HUMAN", "DONE"].includes(stage);
  const rejected = stage === "REJECTED";

  const activeIdx =
    stage === "IN_PROGRESS" || stage === "PENDING"
      ? 0
      : stage === "PRESUBMITTED"
        ? 1
        : stage === "IN_REVIEW" || stage === "REJECTED"
          ? 2
          : stage === "READY_TO_PUBLISH" || stage === "NEEDS_HUMAN"
            ? 2
            : 3;

  const st = (i: number): "done" | "active" | "error" | "todo" => {
    if (rejected && i === 2) return "error";
    if (i < activeIdx) return "done";
    if (i === activeIdx) return stage === "DONE" ? "done" : "active";
    return "todo";
  };

  const nodes: Array<{ index: number; label: string; sub?: string }> = [
    { index: 0, label: "编码协作", sub: coded ? "已完成" : "工作中" },
    { index: 1, label: "预提审快照", sub: snap ? `R${snap.round}·${shortHash(snap.treeHash, 8, 4)}` : undefined },
    { index: 2, label: "门禁审查", sub: rejected ? "已驳回" : reviewed ? "已放行" : undefined },
    { index: 3, label: "发布主分支", sub: commitSha ? shortHash(commitSha, 8, 4) : undefined },
  ];

  const circleSize = "w-[24px] h-[24px]";
  const circleBase = `relative z-10 shrink-0 ${circleSize} rounded-full border grid place-items-center text-[10px] font-mono transition-colors`;

  return (
    <div className="relative px-1 pt-0.5">
      {/* connector lines — positioned behind circles */}
      <div className="absolute top-[12px] left-[calc(12.5%+6px)] right-[calc(12.5%+6px)] h-px bg-edge" />
      <div className="absolute top-[12px] left-[calc(12.5%+6px)] h-px bg-accent/40" style={{ width: `${Math.min(activeIdx / 3, 1) * 75}%` }} />

      {/* circles */}
      <div className="relative flex justify-between">
        {nodes.map((n) => {
          const s = st(n.index);
          return (
            <span
              key={n.index}
              className={`${circleBase} ${
                s === "done"
                  ? "bg-accent-dim border-accent/50 text-accent"
                  : s === "active"
                    ? "border-accent text-accent bg-canvas shadow-[0_0_0_3px_rgba(53,217,158,0.08)]"
                    : s === "error"
                      ? "bg-danger-dim border-danger/50 text-danger"
                      : "border-edge text-faint bg-sunken"
              }`}
            >
              {s === "done" ? (
                <Check size={12} weight="bold" />
              ) : s === "error" ? (
                <X size={12} weight="bold" />
              ) : s === "active" ? (
                <span className="w-1.5 h-1.5 rounded-full bg-accent animate-breathe" />
              ) : (
                n.index + 1
              )}
            </span>
          );
        })}
      </div>

      {/* labels */}
      <div className="relative flex justify-between mt-2">
        {nodes.map((n) => {
          const s = st(n.index);
          return (
            <div key={n.index} className="w-[24px] flex-none first:flex-none flex flex-col items-center last:items-center">
              <div
                className={`text-center leading-4 ${
                  s === "todo"
                    ? "text-faint"
                    : s === "error"
                      ? "text-danger"
                      : s === "done"
                        ? "text-accent"
                        : "text-ink"
                }`}
              >
                <div className="text-[11px] font-medium whitespace-nowrap">{n.label}</div>
                {n.sub && (
                  <div className="font-mono text-[10px] text-faint truncate leading-3">{n.sub}</div>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

/* ─── Info Cards ─── */

function TreeHashCard({ snap }: { snap: Snapshot }) {
  return (
    <div className="rounded-xl border border-accent/25 bg-accent/[0.04] p-3.5 animate-slide-in">
      <div className="flex items-center gap-2 mb-2">
        <ShieldCheck size={15} className="text-accent" weight="fill" />
        <span className="text-[12px] font-semibold text-accent">快照指纹 · 第 {snap.round} 轮</span>
        <span className="flex-1" />
        <CopyButton text={snap.treeHash} label="复制指纹" />
      </div>
      <HashReveal hash={snap.treeHash} className="block font-mono text-[13.5px] tracking-wide text-ink break-all leading-relaxed" />
      <div className="mt-2.5 grid grid-cols-3 gap-2 text-[11px]">
        <div>
          <div className="text-faint">基线提交</div>
          <div className="font-mono text-dim mt-0.5">{shortHash(snap.baseCommit, 6, 4)}</div>
        </div>
        <div>
          <div className="text-faint">变更文件</div>
          <div className="font-mono text-dim mt-0.5">{snap.changedPaths.length} 个</div>
        </div>
        <div>
          <div className="text-faint">快照体量</div>
          <div className="font-mono text-dim mt-0.5">{formatBytes(snap.diffBytes)}</div>
        </div>
      </div>
      <div className="mt-2.5 pt-2.5 border-t border-accent/15 flex items-center gap-1.5 text-[11.5px] text-accent/90">
        <SealCheck size={13} weight="fill" />
        完整性校验通过 · 所见即所审，所审即所发
      </div>
    </div>
  );
}

function TaskCard({ task }: { task: { kind: string; percent: number; label: string } }) {
  const title =
    task.kind === "presubmit" ? "正在锁定快照" : task.kind === "review" ? "门禁审查执行中" : "正在发布";
  return (
    <div className="card p-3.5 animate-rise">
      <div className="flex items-center gap-2 mb-2.5">
        <CircleNotch size={14} className="text-accent animate-[spin_0.9s_linear_infinite]" />
        <span className="text-[12.5px] font-medium">{title}</span>
        <span className="flex-1" />
        <span className="font-mono text-[11px] text-faint tabular-nums">{task.percent}%</span>
      </div>
      <div className="h-1 rounded-full bg-edge overflow-hidden">
        <div
          className="h-full rounded-full bg-accent transition-all duration-500"
          style={{ width: `${task.percent}%` }}
        />
      </div>
      <div className="mt-2 text-[11.5px] text-faint">{task.label}</div>
    </div>
  );
}

function VerdictBanner({
  ticketNo,
  verdict,
  findingsCount,
}: {
  ticketNo: string;
  verdict: { verdict: string; reason: string; engineId: string; authorizationId?: string };
  findingsCount: number;
}) {
  if (verdict.verdict === "PASS") {
    return (
      <div className="rounded-xl border border-accent/30 bg-accent/[0.06] p-3.5 animate-slide-in">
        <div className="flex items-center gap-2">
          <SealCheck size={16} className="text-accent" weight="fill" />
          <span className="text-[13px] font-semibold text-accent">门禁放行</span>
        </div>
        <div className="mt-1 text-[12.5px] text-dim">{verdict.reason}</div>
        <div className="mt-2 flex items-center gap-2 font-mono text-[11px] text-faint">
          <span>授权 {verdict.authorizationId}</span>
          <span>·</span>
          <span>{verdict.engineId}</span>
        </div>
      </div>
    );
  }
  if (verdict.verdict === "REQUIRES_HUMAN") {
    return (
      <div className="rounded-xl border border-warn/30 bg-warn/[0.06] p-3.5 animate-slide-in">
        <div className="flex items-center gap-2">
          <Warning size={16} className="text-warn" weight="fill" />
          <span className="text-[13px] font-semibold text-warn">需人工核准</span>
        </div>
        <div className="mt-1 text-[12.5px] text-dim">{verdict.reason}</div>
      </div>
    );
  }
  return (
    <div className="rounded-xl border border-danger/30 bg-danger/[0.05] p-3.5 animate-slide-in">
      <div className="flex items-center gap-2">
        <X size={16} className="text-danger" weight="fill" />
        <span className="text-[13px] font-semibold text-danger">门禁驳回</span>
        <span className="chip border border-danger/30 text-danger bg-danger/10">{findingsCount} 项发现</span>
      </div>
      <div className="mt-1 text-[12.5px] text-dim">{verdict.reason}</div>
      <button
        className="btn btn-sm mt-2.5 border-danger/30 text-danger hover:bg-danger/10 hover:border-danger/50"
        onClick={() => actions.returnWithFindings(ticketNo)}
      >
        <ArrowUUpLeft size={13} />
        带意见返回会话
      </button>
    </div>
  );
}

function OutcomeCard({
  outcome,
}: {
  outcome: { commitSha: string; refBefore: string; refAfter: string; targetRef: string; publishedAt: number };
}) {
  return (
    <div className="rounded-xl border border-accent/25 bg-accent/[0.04] p-4 animate-slide-in">
      <div className="flex items-center justify-center w-10 h-10 mx-auto rounded-full bg-accent-dim border border-accent/40">
        <Check size={20} className="text-accent" weight="bold" />
      </div>
      <div className="mt-2.5 text-center text-[14px] font-semibold">已发布至主分支</div>
      <div className="mt-0.5 text-center font-mono text-[11px] text-faint">
        {outcome.targetRef.replace("refs/heads/", "")} · {hhmmss(outcome.publishedAt)}
      </div>
      <div className="mt-3 rounded-lg bg-sunken border border-edge px-3 py-2 flex items-center justify-center gap-2">
        <span className="text-[11px] text-faint">提交</span>
        <HashReveal hash={outcome.commitSha.slice(0, 16)} className="font-mono text-[12.5px] text-accent" />
        <CopyButton text={outcome.commitSha} label="复制提交号" />
      </div>
      <div className="mt-2.5 flex items-center justify-center gap-2 font-mono text-[11px] text-faint">
        <GitBranch size={12} />
        <span>{shortHash(outcome.refBefore, 6, 4)}</span>
        <ArrowRight size={11} className="text-accent" />
        <span className="text-dim">{shortHash(outcome.refAfter, 6, 4)}</span>
      </div>
      <div className="mt-2.5 pt-2.5 border-t border-edge text-center text-[11.5px] text-faint">
        审计日志已追加 · 快照指纹核验一致
      </div>
    </div>
  );
}

/* ─── Ticket Info Section ─── */

function TicketInfo({ ticketNo }: { ticketNo: string }) {
  const ticket = useApp((s) => s.tickets.find((t) => t.ticketNo === ticketNo));
  const [expanded, setExpanded] = useState(true);

  if (!ticket) return null;

  return (
    <div className="border-b border-edge">
      <button
        className="w-full flex items-center gap-2 px-4 py-2.5 text-left hover:bg-raised/50 transition-colors cursor-pointer"
        onClick={() => setExpanded(!expanded)}
      >
        <FileText size={14} className="text-faint shrink-0" />
        <span className="text-[12px] font-medium text-dim">工单信息</span>
        <span className="flex-1" />
        <span
          className={`text-[11px] text-faint transition-transform duration-150 ${expanded ? "rotate-0" : "-rotate-90"}`}
        >
          ▾
        </span>
      </button>
      <AnimatePresence>
        {expanded && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: "auto", opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.2, ease: [0.16, 1, 0.3, 1] }}
            className="overflow-hidden"
          >
            <div className="px-4 pb-3 space-y-2.5">
              {ticket.description && (
                <div>
                  <div className="text-[10.5px] uppercase tracking-wider text-faint mb-1">需求描述</div>
                  <div className="text-[12.5px] text-dim leading-relaxed whitespace-pre-wrap">{ticket.description}</div>
                </div>
              )}
              {ticket.note && (
                <div>
                  <div className="text-[10.5px] uppercase tracking-wider text-faint mb-1">备注</div>
                  <div className="text-[12.5px] text-dim leading-relaxed whitespace-pre-wrap">{ticket.note}</div>
                </div>
              )}
              {!ticket.description && !ticket.note && (
                <div className="text-[12px] text-faint text-center py-2">暂无描述信息</div>
              )}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

/* ─── Session List Section ─── */

function SessionList({ ticketNo }: { ticketNo: string }) {
  const sessions = useApp((s) => s.sessions[ticketNo] ?? NO_SESSIONS);
  const activeSessionId = useApp((s) => s.activeSessionId[ticketNo]);
  const [tab, setTab] = useState<"active" | "archived">("active");

  const activeSessions = useMemo(() => sessions.filter((s) => s.status === "active"), [sessions]);
  const archivedSessions = useMemo(() => sessions.filter((s) => s.status === "archived"), [sessions]);
  const displayed = tab === "active" ? activeSessions : archivedSessions;

  const handleCreate = () => {
    createSession(ticketNo);
  };

  return (
    <div className="flex flex-col min-h-0">
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
        <button
          className="icon-btn !w-6 !h-6"
          onClick={handleCreate}
          title="新建会话"
          aria-label="新建会话"
        >
          <Plus size={13} weight="bold" />
        </button>
      </div>

      {/* Session list */}
      <div className="flex-1 min-h-0 overflow-y-auto px-3 pb-2">
        {displayed.length === 0 ? (
          <div className="py-6 text-center">
            <div className="text-[12px] text-faint">
              {tab === "active" ? "暂无活跃会话" : "暂无归档会话"}
            </div>
            {tab === "active" && (
              <button
                className="btn btn-sm mt-2 text-[11px]"
                onClick={handleCreate}
              >
                <Plus size={12} />
                新建会话
              </button>
            )}
          </div>
        ) : (
          <div className="space-y-1">
            {displayed.map((sess) => (
              <SessionItem
                key={sess.id}
                session={sess}
                isActive={sess.id === activeSessionId}
                ticketNo={ticketNo}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

function SessionItem({
  session,
  isActive,
  ticketNo,
}: {
  session: ChatSession;
  isActive: boolean;
  ticketNo: string;
}) {
  const [showActions, setShowActions] = useState(false);

  return (
    <div
      className={`group relative flex items-center gap-2 px-2.5 py-2 rounded-lg transition-colors cursor-pointer ${
        isActive ? "bg-raised border border-edge" : "hover:bg-panel border border-transparent"
      }`}
      onClick={() => switchSession(ticketNo, session.id)}
      onMouseEnter={() => setShowActions(true)}
      onMouseLeave={() => setShowActions(false)}
    >
      <span
        className={`w-1.5 h-1.5 rounded-full shrink-0 ${
          isActive ? "bg-accent" : session.status === "archived" ? "bg-faint" : "bg-dim"
        }`}
      />
      <div className="flex-1 min-w-0">
        <div className="text-[12px] text-dim truncate">{session.title}</div>
        <div className="font-mono text-[10px] text-faint mt-0.5">
          {new Date(session.createdAt).toLocaleDateString("zh-CN", { month: "short", day: "numeric" })}
        </div>
      </div>
      {showActions && (
        <div className="flex items-center gap-0.5 shrink-0">
          {session.status === "active" ? (
            <button
              className="icon-btn !w-5 !h-5"
              onClick={(e) => {
                e.stopPropagation();
                archiveSession(ticketNo, session.id);
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
                restoreSession(ticketNo, session.id);
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
              deleteSession(ticketNo, session.id);
            }}
            title="删除"
            aria-label="删除"
          >
            <Trash size={11} />
          </button>
        </div>
      )}
    </div>
  );
}

/* ─── Main Panel ─── */

export function GatePanel({ ticketNo }: { ticketNo: string }) {
  const stage = useApp((s) => s.tickets.find((t) => t.ticketNo === ticketNo)?.stage);
  const snaps = useApp((s) => s.snapshots[ticketNo]);
  const task = useApp((s) => s.tasks[ticketNo]);
  const verdict = useApp((s) => s.verdicts[ticketNo]);
  const findingsCount = useApp((s) => s.findings[ticketNo]?.length ?? 0);
  const gateBusy = useApp((s) => s.gateBusy[ticketNo] ?? false);
  const diffCount = useApp((s) => s.diffs[ticketNo]?.length ?? 0);
  const outcome = useApp((s) => s.outcomes[ticketNo]);
  const sessions = useApp((s) => s.sessions[ticketNo] ?? NO_SESSIONS);
  const activeSessionId = useApp((s) => s.activeSessionId[ticketNo]);

  const snap = snaps?.[snaps.length - 1];
  const round = snaps?.length ?? 0;

  // Ensure there's always at least one active session
  const hasActiveSession = sessions.some((s) => s.status === "active");

  let action: { label: string; icon: React.ReactNode; onClick?: () => void; disabled?: boolean; hint?: string; primary?: boolean } | null = null;

  if (stage === "IN_PROGRESS" || stage === "REJECTED" || stage === "PENDING") {
    action = {
      label: "预提审 · 锁定快照",
      icon: <LockKey size={15} weight="fill" />,
      onClick: () => actions.presubmit(ticketNo),
      disabled: gateBusy || diffCount === 0,
      hint:
        diffCount === 0
          ? "沙箱内暂无变更，先让 Agent 完成编码"
          : "生成不可变快照指纹，作为审查与发布的唯一凭据",
      primary: true,
    };
  } else if (stage === "PRESUBMITTED") {
    action = {
      label: "触发门禁审查",
      icon: <ShieldCheck size={15} weight="fill" />,
      onClick: () => actions.review(ticketNo),
      disabled: gateBusy,
      hint: "审查引擎将基于快照 R" + round + " 判决，与工作区后续改动无关",
      primary: true,
    };
  } else if (stage === "IN_REVIEW") {
    action = {
      label: "审查执行中…",
      icon: <Spinner />,
      disabled: true,
      hint: "判决落盘前不会触碰主分支",
    };
  } else if (stage === "READY_TO_PUBLISH") {
    action = {
      label: "一键安全发布",
      icon: <RocketLaunch size={15} weight="fill" />,
      onClick: () => actions.publish(ticketNo),
      disabled: gateBusy,
      hint: "原子推送至主分支；发布内容与快照指纹强一致",
      primary: true,
    };
  } else if (stage === "NEEDS_HUMAN") {
    action = null;
  }

  return (
    <aside className="w-[400px] shrink-0 border-l border-edge flex flex-col bg-canvas">
      {/* ─── Header ─── */}
      <div className="shrink-0 px-4 pt-3 pb-3 border-b border-edge bg-sunken/50">
        <div className="flex items-center justify-between mb-3">
          <span className="kicker">门禁流水线</span>
          {round > 0 && (
            <span className="chip border border-edge-strong bg-raised text-dim font-mono">第 {round} 轮</span>
          )}
        </div>
        <Stepper stage={stage ?? "PENDING"} snap={snap} commitSha={outcome?.commitSha} />
      </div>

      {/* ─── Scrollable Content ─── */}
      <div className="flex-1 min-h-0 flex flex-col">
        {/* Ticket Info */}
        <TicketInfo ticketNo={ticketNo} />

        {/* Task / Verdict / Outcome cards */}
        {(task || snap || verdict || outcome) && (
          <div className="px-4 py-3 space-y-3 border-b border-edge">
            {task && <TaskCard task={task} />}
            {snap && !task && <TreeHashCard snap={snap} />}
            {verdict && !task && <VerdictBanner ticketNo={ticketNo} verdict={verdict} findingsCount={findingsCount} />}
            {outcome && <OutcomeCard outcome={outcome} />}
            {stage === "NEEDS_HUMAN" && (
              <div className="grid grid-cols-2 gap-2">
                <button className="btn btn-primary h-9" onClick={() => actions.overridePass(ticketNo)}>
                  人工核准放行
                </button>
                <button
                  className="btn btn-danger-ghost h-9"
                  onClick={() => {
                    setStage(ticketNo, "REJECTED");
                    pushSystemMessage(ticketNo, "人工驳回 · 请根据审查意见修复后重新提审", "warn");
                  }}
                >
                  驳回重修
                </button>
              </div>
            )}
          </div>
        )}

        {/* Session List */}
        <div className="flex-1 min-h-0 flex flex-col">
          <SessionList ticketNo={ticketNo} />
        </div>
      </div>

      {/* ─── Action Button (sticky bottom) ─── */}
      {action && (
        <div className="shrink-0 border-t border-edge bg-surface p-3.5">
          <button
            className={`btn btn-lg w-full ${action.primary ? "btn-primary" : ""}`}
            disabled={action.disabled}
            onClick={action.onClick}
            title={action.hint}
          >
            {action.icon}
            {action.label}
          </button>
          {action.hint && <div className="mt-2 text-center text-[11.5px] text-faint">{action.hint}</div>}
        </div>
      )}
      {stage === "DONE" && (
        <div className="shrink-0 border-t border-edge bg-surface p-3.5">
          <button className="btn btn-lg w-full" disabled>
            <Check size={15} weight="bold" />
            工单已完成归档
          </button>
        </div>
      )}
    </aside>
  );
}
