import {
  ArrowRight,
  ArrowUUpLeft,
  Check,
  CircleNotch,
  GitBranch,
  LockKey,
  RocketLaunch,
  SealCheck,
  ShieldCheck,
  Warning,
  X,
} from "@phosphor-icons/react";
import { actions } from "../lib/actions";
import { formatBytes, hhmmss, shortHash } from "../lib/format";
import {
  pushSystemMessage,
  setStage,
  useApp,
} from "../lib/store";
import type { Snapshot } from "../lib/types";
import { CopyButton, HashReveal, Spinner } from "./ui";

function StepperNode({
  state,
  index,
  label,
  sub,
  last,
}: {
  state: "done" | "active" | "error" | "todo";
  index: number;
  label: string;
  sub?: string;
  last?: boolean;
}) {
  return (
    <div className="relative flex gap-3">
      {!last && (
        <span
          className={`absolute left-[13px] top-7 bottom-0 w-px ${
            state === "done" ? "bg-accent/40" : "bg-edge"
          }`}
        />
      )}
      <span
        className={`relative z-10 shrink-0 w-[27px] h-[27px] rounded-full border grid place-items-center text-[11px] font-mono ${
          state === "done"
            ? "bg-accent-dim border-accent/50 text-accent"
            : state === "active"
              ? "border-accent text-accent bg-canvas shadow-[0_0_0_4px_rgba(53,217,158,0.08)]"
              : state === "error"
                ? "bg-danger-dim border-danger/50 text-danger"
                : "border-edge text-faint bg-canvas"
        }`}
      >
        {state === "done" ? (
          <Check size={13} weight="bold" />
        ) : state === "error" ? (
          <X size={13} weight="bold" />
        ) : state === "active" ? (
          <span className="w-2 h-2 rounded-full bg-accent animate-breathe" />
        ) : (
          index + 1
        )}
      </span>
      <div className="pb-5 min-w-0">
        <div className={`text-[13px] leading-6 ${state === "todo" ? "text-faint" : "text-ink"}`}>{label}</div>
        {sub && <div className="font-mono text-[11px] text-faint truncate">{sub}</div>}
      </div>
    </div>
  );
}

function Stepper({ stage, snap, commitSha }: { stage: string; snap?: Snapshot; commitSha?: string }) {
  const gated = ["PRESUBMITTED", "IN_REVIEW", "REJECTED", "READY_TO_PUBLISH", "NEEDS_HUMAN", "DONE"].includes(stage);
  const coded = gated || (snap !== undefined);
  const reviewed = ["READY_TO_PUBLISH", "NEEDS_HUMAN", "DONE"].includes(stage);
  const published = stage === "DONE";
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

  return (
    <div>
      <StepperNode index={0} state={st(0)} label="编码协作" sub={coded ? "沙箱内改动已完成" : "Agent 正在沙箱内工作"} />
      <StepperNode
        index={1}
        state={st(1)}
        label="预提审快照"
        sub={snap ? `R${snap.round} · ${shortHash(snap.treeHash, 8, 4)}` : undefined}
      />
      <StepperNode
        index={2}
        state={st(2)}
        label="门禁审查"
        sub={rejected ? "已驳回 · 等待修复后重新提审" : reviewed ? "已放行 · 授权生效" : undefined}
      />
      <StepperNode
        index={3}
        state={st(3)}
        label="发布主分支"
        last
        sub={commitSha ? shortHash(commitSha, 8, 4) : undefined}
      />
    </div>
  );
}

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
          className="h-full rounded-full bg-gradient-to-r from-accent-dim to-accent transition-all duration-500"
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
        className="btn mt-2.5 h-7 text-[12px] border-danger/30 text-danger hover:bg-danger/10 hover:border-danger/50"
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
    <div className="rounded-xl border border-accent/25 bg-gradient-to-b from-accent/[0.07] to-transparent p-4 animate-slide-in">
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

export function GatePanel({ ticketNo }: { ticketNo: string }) {
  const stage = useApp((s) => s.tickets.find((t) => t.ticketNo === ticketNo)?.stage);
  const snaps = useApp((s) => s.snapshots[ticketNo]);
  const task = useApp((s) => s.tasks[ticketNo]);
  const verdict = useApp((s) => s.verdicts[ticketNo]);
  const findingsCount = useApp((s) => s.findings[ticketNo]?.length ?? 0);
  const gateBusy = useApp((s) => s.gateBusy[ticketNo] ?? false);
  const diffCount = useApp((s) => s.diffs[ticketNo]?.length ?? 0);
  const outcome = useApp((s) => s.outcomes[ticketNo]);

  const snap = snaps?.[snaps.length - 1];
  const round = snaps?.length ?? 0;

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
      <div className="h-12 shrink-0 flex items-center px-4 border-b border-edge">
        <span className="kicker">门禁流水线</span>
        <span className="flex-1" />
        {round > 0 && (
          <span className="chip border border-edge-strong bg-raised text-dim font-mono">第 {round} 轮</span>
        )}
      </div>

      <div className="flex-1 min-h-0 overflow-y-auto p-4 space-y-3.5">
        <div className="card p-4">
          <Stepper stage={stage ?? "PENDING"} snap={snap} commitSha={outcome?.commitSha} />
        </div>

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

      {action && (
        <div className="shrink-0 border-t border-edge bg-panel/60 p-3.5">
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
        <div className="shrink-0 border-t border-edge bg-panel/60 p-3.5">
          <button className="btn btn-lg w-full" disabled>
            <Check size={15} weight="bold" />
            工单已完成归档
          </button>
        </div>
      )}
    </aside>
  );
}
