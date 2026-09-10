import {
  ArrowRight,
  ArrowUUpLeft,
  Check,
  CircleNotch,
  GitBranch,
  SealCheck,
  ShieldCheck,
  Warning,
  X,
} from "@phosphor-icons/react";
import { actions } from "@/app/actions";
import { formatBytes, hhmmss, shortHash } from "@/shared/format";
import type { Snapshot } from "@/shared/types";
import { CopyButton, HashReveal } from "@/shared/components/ui";
import { useT } from "@/i18n";

/** 快照指纹卡（预提审后展示）。 */
export function TreeHashCard({ snap }: { snap: Snapshot }) {
  const t = useT();
  return (
    <div className="rounded-xl border border-accent/25 bg-accent/[0.04] p-3.5 animate-slide-in">
      <div className="flex items-center gap-2 mb-2">
        <ShieldCheck size={15} className="text-accent" weight="fill" />
        <span className="text-[12px] font-semibold text-accent">{t("gate.cards.snapshotTitle", { n: snap.round })}</span>
        <span className="flex-1" />
        <CopyButton text={snap.treeHash} label={t("gate.cards.copyFingerprint")} />
      </div>
      <HashReveal hash={snap.treeHash} className="block font-mono text-[13.5px] tracking-wide text-ink break-all leading-relaxed" />
      <div className="mt-2.5 grid grid-cols-3 gap-2 text-[11px]">
        <div>
          <div className="text-faint">{t("gate.cards.baseCommit")}</div>
          <div className="font-mono text-dim mt-0.5">{shortHash(snap.baseCommit, 6, 4)}</div>
        </div>
        <div>
          <div className="text-faint">{t("gate.cards.changedFiles")}</div>
          <div className="font-mono text-dim mt-0.5">{t("mcp.toolCount", { n: snap.changedCount ?? snap.changedPaths.length })}</div>
        </div>
        <div>
          <div className="text-faint">{t("gate.cards.snapshotSize")}</div>
          <div className="font-mono text-dim mt-0.5">{formatBytes(snap.diffBytes)}</div>
        </div>
      </div>
      <div className="mt-2.5 pt-2.5 border-t border-accent/15 flex items-center gap-1.5 text-[11.5px] text-accent/90">
        <SealCheck size={13} weight="fill" />
        {t("gate.cards.integrity")}
      </div>
    </div>
  );
}

/** 门禁任务进度卡（presubmit/review/publish 轮询期间）。 */
export function TaskCard({ task }: { task: { kind: string; percent: number; label: string } }) {
  const t = useT();
  const title =
    task.kind === "presubmit" ? t("kanban.toast.lockingSnapshot") : task.kind === "review" ? t("gate.cards.reviewing") : t("gate.cards.publishing");
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

/** 判决横幅（放行 / 需人工 / 引擎故障 / 驳回）。 */
export function VerdictBanner({
  ticketNo,
  verdict,
  findingsCount,
}: {
  ticketNo: string;
  verdict: { verdict: string; reason: string; engineId: string; authorizationId?: string; degraded?: boolean };
  findingsCount: number;
}) {
  const t = useT();
  if (verdict.verdict === "PASS") {
    return (
      <div className="rounded-xl border border-accent/30 bg-accent/[0.06] p-3.5 animate-slide-in">
        <div className="flex items-center gap-2">
          <SealCheck size={16} className="text-accent" weight="fill" />
          <span className="text-[13px] font-semibold text-accent">{t("gate.cards.pass")}</span>
        </div>
        <div className="mt-1 text-[12.5px] text-dim">{verdict.reason}</div>
        <div className="mt-2 flex items-center gap-2 font-mono text-[11px] text-faint">
          {verdict.authorizationId && (
            <>
              <span>{t("gate.cards.authorization")} {verdict.authorizationId}</span>
              <span>·</span>
            </>
          )}
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
          <span className="text-[13px] font-semibold text-warn">{t("gate.cards.needsHuman")}</span>
        </div>
        <div className="mt-1 text-[12.5px] text-dim">{verdict.reason}</div>
      </div>
    );
  }
  // 引擎故障型驳回（degraded）：不是对代码的判决，不展示"带意见返回会话"——
  // 原地重试入口在下方（stage 停留在 PRESUBMITTED，轮次不消耗）。
  if (verdict.degraded) {
    return (
      <div className="rounded-xl border border-warn/30 bg-warn/[0.06] p-3.5 animate-slide-in">
        <div className="flex items-center gap-2">
          <Warning size={16} className="text-warn" weight="fill" />
          <span className="text-[13px] font-semibold text-warn">{t("gate.cards.degraded")}</span>
          <span className="chip border border-warn/30 text-warn bg-warn/10">{t("gate.cards.retriable")}</span>
        </div>
        <div className="mt-1 text-[12.5px] text-dim">
          {t("gate.cards.degradedNote")}
        </div>
      </div>
    );
  }
  return (
    <div className="rounded-xl border border-danger/30 bg-danger/[0.05] p-3.5 animate-slide-in">
      <div className="flex items-center gap-2">
        <X size={16} className="text-danger" weight="fill" />
        <span className="text-[13px] font-semibold text-danger">{t("gate.cards.rejected")}</span>
        <span className="chip border border-danger/30 text-danger bg-danger/10">{t("gate.cards.findingsCount", { n: findingsCount })}</span>
      </div>
      <div className="mt-1 text-[12.5px] text-dim">{verdict.reason}</div>
      <button
        className="btn btn-sm mt-2.5 border-danger/30 text-danger hover:bg-danger/10 hover:border-danger/50"
        onClick={() => actions.returnWithFindings(ticketNo)}
      >
        <ArrowUUpLeft size={13} />
        {t("gate.cards.returnWithFindings")}
      </button>
    </div>
  );
}

/** 发布结果卡（READY_TO_PUBLISH 发布成功后的收据）。 */
export function OutcomeCard({
  outcome,
}: {
  outcome: {
    commitSha: string;
    refBefore: string;
    refAfter: string;
    targetRef: string;
    publishedAt: number;
    workspaceSyncStatus?: string | null;
    workspaceSyncNote?: string | null;
  };
}) {
  const t = useT();
  const branch = outcome.targetRef.replace("refs/heads/", "");
  const noteSha = outcome.workspaceSyncNote?.includes("->")
    ? outcome.workspaceSyncNote.split("->").pop()?.trim()
    : outcome.workspaceSyncNote?.trim();
  const syncSha = noteSha || shortHash(outcome.refAfter, 8, 0) || outcome.refAfter.slice(0, 8);

  return (
    <div className="rounded-xl border border-accent/25 bg-accent/[0.04] p-4 animate-slide-in">
      <div className="flex items-center justify-center w-10 h-10 mx-auto rounded-full bg-accent-dim border border-accent/40">
        <Check size={20} className="text-accent" weight="bold" />
      </div>
      <div className="mt-2.5 text-center text-[14px] font-semibold">{t("gate.cards.published")}</div>
      <div className="mt-0.5 text-center font-mono text-[11px] text-faint">
        {branch} · {hhmmss(outcome.publishedAt)}
      </div>
      <div className="mt-3 rounded-lg bg-sunken border border-edge px-3 py-2 flex items-center justify-center gap-2">
        <span className="text-[11px] text-faint">{t("gate.cards.commit")}</span>
        <HashReveal hash={outcome.commitSha.slice(0, 16)} className="font-mono text-[12.5px] text-accent" />
        <CopyButton text={outcome.commitSha} label={t("gate.cards.copyCommit")} />
      </div>
      <div className="mt-2.5 flex items-center justify-center gap-2 font-mono text-[11px] text-faint">
        <GitBranch size={12} />
        <span>{shortHash(outcome.refBefore, 6, 4)}</span>
        <ArrowRight size={11} className="text-accent" />
        <span className="text-dim">{shortHash(outcome.refAfter, 6, 4)}</span>
      </div>
      <div
        className={`mt-2.5 text-center text-[11.5px] ${
          outcome.workspaceSyncStatus === "SYNCED"
            ? "text-accent"
            : outcome.workspaceSyncStatus === "DEFERRED"
              ? "text-warn"
              : outcome.workspaceSyncStatus === "ALREADY"
                ? "text-dim"
                : "text-faint"
        }`}
      >
        {outcome.workspaceSyncStatus === "SYNCED"
          ? t("gate.cards.sync.synced", { branch, sha: syncSha })
          : outcome.workspaceSyncStatus === "ALREADY"
            ? t("gate.cards.sync.already")
            : outcome.workspaceSyncStatus === "DEFERRED"
              ? t("gate.cards.sync.deferred", { note: outcome.workspaceSyncNote ?? "" })
              : t("gate.cards.sync.unsynced")}
      </div>
      <div className="mt-2.5 pt-2.5 border-t border-edge text-center text-[11.5px] text-faint">
        {t("gate.cards.auditNote")}
      </div>
    </div>
  );
}
