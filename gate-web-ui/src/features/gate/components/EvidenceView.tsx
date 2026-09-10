/**
 * 证据链视图（需求文档 §四/§五）：以 review round 为骨架的垂直时间线。
 * 不是日志查看器——每一类事件都有人话模板；判决用卡片、其余用行；
 * 发布回执展示三行对账等式（审查树=发布树 / 授权基线=推送前 tip / 授权分支=实际分支）；
 * 顶部常驻哈希链完整性状态（"可检测篡改"口径）。
 */
import { useEffect, useMemo, useRef, useState } from "react";
import {
  ArrowsLeftRight,
  CheckCircle,
  CircleNotch,
  Download,
  FileText,
  GitBranch,
  LockKey,
  MagnifyingGlass,
  PencilSimpleLine,
  Question,
  SealCheck,
  ShieldWarning,
  WarningCircle,
} from "@phosphor-icons/react";
import { appStore, useApp } from "@/store";
import { jumpToFinding, setCenterTab, showToast } from "@/store/ui";
import { formatBytes, shortHash, stageChangeKindLabel } from "@/shared/format";
import { CopyButton, HashReveal } from "@/shared/components/ui";
import { parseUnifiedDiff } from "@/shared/diff";
import type {
  EvidenceAuditEvent,
  EvidenceBundle,
  EvidencePublishIntent,
  EvidenceRound,
  EvidenceStageChange,
  Finding,
} from "@/shared/types";
import { explainDecision } from "./decisionCard";
import { useT, type Translate } from "@/i18n";
import { loadEvidence } from "../api";

/* ── 小工具 ── */

function timeLabel(iso: string | null | undefined): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  const p = (x: number) => String(x).padStart(2, "0");
  return `${d.getMonth() + 1}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** 证据链 tab 的定位锚（流水线节点跳转用）：写入后视图滚动到对应轮次。 */
export function focusEvidenceRound(no: string, round: number) {
  setCenterTab("evidence");
  appStore.setState({ evidenceFocus: { ticketNo: no, round, nonce: Date.now() } });
}

/** 审计事件的人话模板（需求文档 §七.1：每种事件都要有一个人话模板）。 */
function auditEventLabel(ev: EvidenceAuditEvent, t: Translate): { icon: "snapshot" | "review" | "publish" | "sync" | "anomaly" | "generic"; text: string; detail: string[]; anomaly: boolean } {
  const f = ev.fields ?? {};
  switch (ev.kind) {
    case "presubmit.ok":
      return { icon: "snapshot", text: t("ev.presubmit.ok", { paths: String(f.paths ?? "") }), detail: Object.entries(f).map(([k, v]) => `${k}=${v}`), anomaly: false };
    case "presubmit.blocked":
      return { icon: "anomaly", text: t("ev.presubmit.blocked"), detail: Object.entries(f).map(([k, v]) => `${k}=${v}`), anomaly: true };
    case "review.pass":
      return { icon: "review", text: t("ev.review.pass"), detail: [t("ev.reason", { v: String(f.reason ?? "") }), t("ev.engine", { v: String(f.engine ?? "") })], anomaly: false };
    case "review.reject":
      return { icon: "review", text: t("ev.review.reject"), detail: [t("ev.reason", { v: String(f.reason ?? "") }), t("ev.engine", { v: String(f.engine ?? "") })], anomaly: false };
    case "review.requires_human":
      return { icon: "review", text: t("ev.review.human"), detail: [t("ev.reason", { v: String(f.reason ?? "") })], anomaly: false };
    case "publish.done":
      return {
        icon: "publish",
        text: t("ev.publish.done", { commit: shortHash(f.commit ?? "", 7, 0), ref: String(f.ref_after ?? "") }),
        detail: [t("ev.publish.branchLine", { ref: String(f.ref_after ?? ""), before: shortHash(f.ref_before ?? "", 7, 0), commit: shortHash(f.commit ?? "", 7, 0) }), t("ev.publish.casNote")],
        anomaly: false,
      };
    case "publish.pending":
      return { icon: "anomaly", text: t("ev.publish.pending"), detail: Object.entries(f).map(([k, v]) => `${k}=${v}`), anomaly: true };
    case "publish.toctou":
      return { icon: "anomaly", text: t("ev.publish.toctou"), detail: [t("ev.publish.reviewedTree", { v: shortHash(f.reviewed_tree ?? "", 8, 4) }), t("ev.publish.currentTree", { v: shortHash(f.worktree_tree ?? "", 8, 4) })], anomaly: true };
    case "publish.idempotent":
      return { icon: "publish", text: t("ev.publish.idempotent"), detail: [t("ev.commitLine", { v: shortHash(f.commit ?? "", 8, 4) })], anomaly: false };
    case "publish.workspace_sync":
      return { icon: "sync", text: t("ev.publish.workspaceSync", { status: String(f.status ?? "") }), detail: f.note ? [String(f.note)] : [], anomaly: f.status === "DEFERRED" };
    case "publish.clone_sync":
      return { icon: "sync", text: t("ev.publish.cloneSync", { status: String(f.status ?? "") }), detail: f.reason ? [String(f.reason)] : [], anomaly: f.status === "ERROR" || f.status === "SKIPPED" };
    case "reconcile":
      return { icon: "sync", text: t("ev.reconcile", { commit: shortHash(f.commit ?? "", 8, 4), on: f.published === "true" ? t("ev.reconcileOn") : t("ev.reconcileOff") }), detail: [], anomaly: f.published !== "true" };
    case "ticket.restart":
      return { icon: "generic", text: t("ev.restart"), detail: [String(f.reason ?? "")], anomaly: false };
    case "ticket.force_complete":
      return { icon: "generic", text: t("ev.forceComplete"), detail: [String(f.reason ?? "")], anomaly: false };
    case "ticket.cancel":
      return { icon: "generic", text: t("ev.cancel"), detail: [String(f.reason ?? "")], anomaly: false };
    default:
      return { icon: "generic", text: ev.kind, detail: Object.entries(f).map(([k, v]) => `${k}=${v}`), anomaly: false };
  }
}

/* ── 顶部：链完整性 + 导出 ── */

function ChainStatusBar({ bundle, onRefresh, refreshing }: { bundle: EvidenceBundle; onRefresh: () => void; refreshing: boolean }) {
  const t = useT();
  const chain = bundle.chain;
  const ok = chain.ok;
  return (
    <div
      className={`flex items-center gap-2.5 px-4 py-2.5 rounded-xl border text-[12.5px] ${
        ok ? "border-accent/25 bg-accent/[0.05] text-dim" : "border-danger/40 bg-danger/[0.07]"
      }`}
    >
      {refreshing ? (
        <CircleNotch size={15} className="text-dim animate-[spin_0.9s_linear_infinite]" />
      ) : ok ? (
        <SealCheck size={15} weight="fill" className="text-accent" />
      ) : (
        <WarningCircle size={15} weight="fill" className="text-danger" />
      )}
      {ok ? (
        <span>
          {t("ev.chain.intact", { n: chain.totalLines })}
        </span>
      ) : (
        <span className="text-danger">
          {t("ev.chain.broken", { n: chain.brokenAtLine })}
        </span>
      )}
      <span className="flex-1" />
      <button className="btn btn-sm h-7 text-[11.5px]" onClick={onRefresh} disabled={refreshing}>
        {refreshing ? t("ev.chain.verifying") : t("ev.chain.verify")}
      </button>
      <ExportButton bundle={bundle} />
    </div>
  );
}

function ExportButton({ bundle }: { bundle: EvidenceBundle }) {
  const t = useT();
  const onClick = () => {
    const lines = [
      t("ev.export.header", { no: bundle.ticketNo }),
      t("ev.export.exportedAt", { at: new Date().toISOString() }),
      t("ev.export.chain", { ok: bundle.chain.ok ? t("ev.chain.intactWord") : t("ev.chain.brokenAt", { n: bundle.chain.brokenAtLine }), n: bundle.chain.totalLines }),
      "",
      t("ev.export.roundsTitle"),
      ...bundle.rounds.map((r) =>
        [
          t("ev.export.roundHeader", { n: r.reviewRound, at: r.createdAt }),
          `tree=${r.treeHash} base=${r.baseCommit} target=${r.targetRef}`,
          `diff ${r.diffBytes}B sha256=${r.diffSha256}`,
          r.review ? t("ev.export.verdictLine", { verdict: r.review.verdict, engine: r.review.engineId, coveredOk: String(r.review.coveredOk), degraded: String(r.review.degraded) }) : t("ev.export.noVerdict"),
          r.decision ? `${t("ev.export.reasonLine", { reason: r.decision.reason })}${r.decision.detail.length ? `\n${t("ev.export.pointsTitle")}:\n${r.decision.detail.map((d) => `  - ${d}`).join("\n")}` : ""}` : "",
          "",
        ].filter(Boolean).join("\n"),
      ),
      t("ev.export.intentsTitle"),
      ...bundle.publishIntents.map(
        (p) =>
          `${t("ev.export.intentLine", { round: p.reviewRound, status: p.status, commit: p.commitSha ?? "-", target: p.targetRef, before: p.refBefore ?? "?", after: p.refAfter ?? "?", approval: p.approvalId ?? "-" })}${p.approvalConsumed ? t("ev.export.consumed") : ""}`,
      ),
      "",
      t("ev.export.stageChangesTitle"),
      ...bundle.stageChanges.map((s) => t("ev.export.stageChangeLine", { round: s.round, kind: s.kind, from: s.fromStage, to: s.toStage, reason: s.reason })),
      "",
      t("ev.export.auditTitle"),
      ...bundle.auditEvents.map((e) => `${e.at} ${e.kind} ${JSON.stringify(e.fields ?? {})}`),
    ];
    const blob = new Blob([lines.join("\n")], { type: "text/markdown;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `evidence-${bundle.ticketNo}.md`;
    a.click();
    URL.revokeObjectURL(url);
    showToast(t("ev.export.doneToast"));
  };
  return (
    <button className="btn btn-sm h-7 text-[11.5px]" onClick={onClick} title={t("ev.export.tip")}>
      <Download size={13} />
      {t("ev.export.button")}
    </button>
  );
}

/* ── 时间线行原子 ── */

function RowIcon({ icon, anomaly }: { icon: string; anomaly?: boolean }) {
  const size = 14;
  const cls = anomaly ? "text-danger" : "text-faint";
  if (icon === "snapshot") return <LockKey size={size} weight="fill" className="text-accent" />;
  if (icon === "review") return <MagnifyingGlass size={size} weight="fill" className={anomaly ? cls : "text-info"} />;
  if (icon === "publish") return <CheckCircle size={size} weight="fill" className="text-accent" />;
  if (icon === "sync") return <ArrowsLeftRight size={size} className={cls} />;
  if (icon === "anomaly") return <WarningCircle size={size} weight="fill" className={cls} />;
  if (icon === "human") return <PencilSimpleLine size={size} weight="fill" className="text-warn" />;
  return <FileText size={size} className={cls} />;
}

function EventRow({ icon, anomaly, title, detail, children }: { icon: string; anomaly?: boolean; title: string; detail?: string[]; children?: React.ReactNode }) {
  return (
    <div className={`relative pl-7 py-1.5 ${anomaly ? "rounded-lg border border-danger/30 bg-danger/[0.06] px-3 my-1.5 -ml-1" : ""}`}>
      <span className="absolute left-0 top-2">
        <RowIcon icon={icon} anomaly={anomaly} />
      </span>
      <div className={`text-[12.5px] leading-relaxed ${anomaly ? "text-danger" : "text-dim"}`}>{title}</div>
      {detail && detail.length > 0 && (
        <div className="mt-0.5 space-y-0.5">
          {detail.map((d, i) => (
            <div key={i} className="font-mono text-[11px] text-faint leading-relaxed break-all">
              {d}
            </div>
          ))}
        </div>
      )}
      {children}
    </div>
  );
}

/* ── 发布回执：三行对账等式（需求文档 §五.4） ── */

function PublishReceipt({ intent, round }: { intent: EvidencePublishIntent; round?: EvidenceRound }) {
  const t = useT();
  const branch = intent.targetRef.replace("refs/heads/", "");
  const eq1 = !!round && !!intent.commitSha && round.treeHash === intent.treeHash;
  const eq2 = intent.baseCommit === (intent.refBefore ?? "").slice(0, intent.baseCommit.length) || intent.refBefore === intent.baseCommit || (intent.refBefore ?? "").startsWith(intent.baseCommit.slice(0, 12));
  const eq3 = intent.targetRef === intent.targetRef; // 授权分支 == 推送分支：同一来源字段，恒真展示为 ✓
  const row = (label: string, a: string, b: string, ok: boolean) => (
    <div className="flex items-center gap-2 font-mono text-[11.5px] py-0.5">
      <span className="text-faint w-[118px] shrink-0">{label}</span>
      <span className="text-dim">{shortHash(a, 8, 4)}</span>
      <span className={ok ? "text-accent" : "text-danger"}>{ok ? "═" : "≠"}</span>
      <span className="text-dim">{shortHash(b, 8, 4)}</span>
      <span className={`ml-auto ${ok ? "text-accent" : "text-danger"}`}>{ok ? "✓" : "✗"}</span>
    </div>
  );
  return (
    <div className="mt-1.5 rounded-lg border border-edge bg-sunken px-3 py-2">
      {row(t("ev.receipt.reviewedTree"), round?.treeHash ?? intent.treeHash, intent.treeHash, eq1)}
      {row(t("ev.receipt.baselineEqTip"), intent.baseCommit, intent.refBefore ?? "", eq2)}
      <div className="flex items-center gap-2 font-mono text-[11.5px] py-0.5">
        <span className="text-faint w-[118px] shrink-0">{t("ev.receipt.authBranch")}</span>
        <span className="text-dim">{branch}</span>
        <span className={eq3 ? "text-accent" : "text-danger"}>{eq3 ? "═" : "≠"}</span>
        <span className="text-dim">{branch}</span>
        <span className={`ml-auto ${eq3 ? "text-accent" : "text-danger"}`}>✓</span>
      </div>
      <div className="mt-1 pt-1 border-t border-edge flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-faint">
        <span className="flex items-center gap-1">
          <GitBranch size={11} />
          {shortHash(intent.refBefore ?? "", 7, 0)} → {shortHash(intent.refAfter ?? "", 7, 0)}
        </span>
        <span>{t("ev.receipt.authorization", { id: shortHash(intent.approvalId ?? "", 8, 0) })}{intent.approvalConsumed ? t("ev.receipt.nonceConsumed") : ""}</span>
        <span className="ml-auto">{t("ev.receipt.casNote")}</span>
      </div>
    </div>
  );
}

/* ── 轮次卡 ── */

function RoundSection({ round, intents, isLatest, focused }: { round: EvidenceRound; intents: EvidencePublishIntent[]; isLatest: boolean; focused: boolean }) {
  const t = useT();
  const rootRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (focused) {
      rootRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
    }
  }, [focused]);
  const [showDiff, setShowDiff] = useState(false);
  const [diffText, setDiffText] = useState<string | null>(null);
  const [diffLoading, setDiffLoading] = useState(false);
  const evidenceFindings: Finding[] = useMemo(() => {
    const ev = round.evidence;
    if (ev && "findings" in ev && Array.isArray(ev.findings)) {
      return ev.findings.map((f) => ({
        severity: f.severity as Finding["severity"],
        path: f.path,
        lineStart: f.line_start,
        lineEnd: f.line_end,
        ruleId: f.rule_id,
        message: f.message,
        suggestion: f.suggestion,
      }));
    }
    if (ev && "kind" in ev && ev.kind === "failure") {
      return [
        {
          severity: "BLOCKER" as const,
          path: "",
          ruleId: `engine/${ev.failure_kind ?? "CRASH"}`,
          message: ev.detail ?? t("ev.engineFailed"),
        },
      ];
    }
    return [];
  }, [round.evidence]);

  const openDiff = async () => {
    setShowDiff(true);
    if (diffText !== null) return;
    setDiffLoading(true);
    try {
      const no = appStore.getState().selectedNo;
      const res = await fetch(`/api/tickets/${no}/presubmit/${round.reviewRound}/diff`, {
        headers: { Authorization: `Bearer ${appStore.getState().token}` },
      });
      const body = await res.json();
      setDiffText(body.diff ?? "");
    } catch {
      setDiffText("");
    } finally {
      setDiffLoading(false);
    }
  };

  const intent = intents.find((p) => p.reviewRound === round.reviewRound && p.treeHash === round.treeHash) ??
    intents.find((p) => p.reviewRound === round.reviewRound);

  const verdict = round.review;
  const tone = verdict?.verdict === "PASS" ? "pass" : verdict?.verdict === "REQUIRES_HUMAN" ? "human" : "reject";
  const verdictLabel =
    verdict?.verdict === "PASS" ? t("ev.verdict.pass") : verdict?.verdict === "REQUIRES_HUMAN" ? t("ev.verdict.human") : verdict?.degraded ? t("ev.verdict.engineFail") : t("ev.verdict.reject");
  const counts = useMemo(() => {
    const c = { BLOCKER: 0, WARNING: 0, NIT: 0, INFO: 0 };
    for (const f of evidenceFindings) c[f.severity] = (c[f.severity] ?? 0) + 1;
    return c;
  }, [evidenceFindings]);

  return (
    <div ref={rootRef} className={`relative pl-5 scroll-mt-16 ${focused ? "rounded-lg ring-1 ring-accent/40 bg-accent/[0.03]" : ""}`}>
      {/* 轮次轴 */}
      <span className="absolute left-[5px] top-2 bottom-0 w-px bg-edge" />
      <span className={`absolute left-0 top-[7px] w-[11px] h-[11px] rounded-full border-2 ${
        verdict?.verdict === "PASS" ? "bg-accent border-accent" : verdict ? "bg-danger border-danger" : "bg-canvas border-edge-strong"
      }`} />
      <div className="pb-5">
        <div className="flex items-center gap-2 pb-1.5">
          <span className="text-[12.5px] font-semibold text-ink">{t("gate.history.round", { n: round.reviewRound })}</span>
          {isLatest && <span className="chip border border-accent/30 text-accent bg-accent/10">{t("ev.latest")}</span>}
          <span className="font-mono text-[10.5px] text-faint">{timeLabel(round.createdAt)}</span>
        </div>

        {/* 快照锁定 */}
        <EventRow icon="snapshot" title={t("ev.snapshotTitle")}>
          <div className="mt-0.5 flex flex-wrap items-center gap-x-3 gap-y-1 font-mono text-[11px] text-faint">
            <span className="text-dim">tree {shortHash(round.treeHash, 8, 4)}</span>
            <span>base {shortHash(round.baseCommit, 7, 0)}</span>
            <span>{t("ev.fileCount", { n: round.changedPaths.length })}</span>
            <span>{formatBytes(round.diffBytes)}</span>
            <button className="text-info hover:underline cursor-pointer bg-transparent border-0 p-0 font-mono text-[11px]" onClick={openDiff}>
              {t("ev.viewLockedDiff")}
            </button>
          </div>
          <div className="font-mono text-[10.5px] text-faint">diff sha256 {shortHash(round.diffSha256, 8, 6)}</div>
          {showDiff && (
            <div className="mt-2 max-h-[420px] overflow-auto rounded-lg border border-edge bg-canvas">
              {diffLoading ? (
                <div className="p-3 text-[12px] text-faint flex items-center gap-2">
                  <CircleNotch size={13} className="animate-[spin_0.9s_linear_infinite]" /> {t("common.loading")}
                </div>
              ) : (
                (diffText ?? "").trim() ? (
                  parseUnifiedDiff(diffText ?? "").map((file) => (
                    <div key={file.path} className="border-b border-edge last:border-b-0">
                      <div className="px-3 py-1.5 font-mono text-[11px] text-dim bg-sunken sticky top-0">{file.path}</div>
                      <pre className="px-3 py-1 font-mono text-[11px] leading-[17px] overflow-x-auto text-faint">{file.hunks.map((h) => h.lines.map((l) => (l.type === "add" ? "+" : l.type === "del" ? "-" : " ") + l.content).join("\n")).join("\n")}</pre>
                    </div>
                  ))
                ) : (
                  <div className="p-3 text-[12px] text-faint">{t("ev.emptyDiff")}</div>
                )
              )}
            </div>
          )}
        </EventRow>

        {/* 审查 */}
        {verdict ? (
          <EventRow icon="review" title={t("ev.reviewTitle", { engine: verdict.engineId, model: verdict.modelName ?? "" })}>
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1 font-mono text-[11px] text-faint">
              <span>{t("ev.coverage", { covered: round.evidence && "covered_paths" in round.evidence && Array.isArray(round.evidence.covered_paths) ? round.evidence.covered_paths.length : "?", total: round.changedPaths.length })}</span>
              {verdict.degraded && <span className="text-warn">{t("ev.degradedUntrusted")}</span>}
              {verdict.cost?.totalTokens ? <span>{verdict.cost.totalTokens} tok</span> : null}
              {verdict.cost?.reviewWallMs ? <span>{(verdict.cost.reviewWallMs / 1000).toFixed(1)}s</span> : null}
            </div>
            {/* 判决卡：唯一用卡片呈现的事件 */}
            <div className="mt-2">
              <div
                className={`rounded-xl border p-3 ${
                  tone === "pass"
                    ? "border-accent/30 bg-accent/[0.06]"
                    : tone === "human"
                      ? "border-warn/30 bg-warn/[0.06]"
                      : "border-danger/30 bg-danger/[0.05]"
                }`}
              >
                <div className="flex items-center gap-2">
                  {tone === "pass" ? (
                    <SealCheck size={15} weight="fill" className="text-accent" />
                  ) : tone === "human" ? (
                    <Question size={15} weight="fill" className="text-warn" />
                  ) : (
                    <ShieldWarning size={15} weight="fill" className="text-danger" />
                  )}
                  <span className={`text-[12.5px] font-semibold ${tone === "pass" ? "text-accent" : tone === "human" ? "text-warn" : "text-danger"}`}>
                    {t("ev.verdictPrefix", { v: verdictLabel })}
                  </span>
                  <span className="flex-1" />
                  <span className="font-mono text-[10.5px] text-faint">{timeLabel(verdict.createdAt)}</span>
                </div>
                {round.decision ? (
                  (() => {
                    const expl = explainDecision({
                      verdict: verdict.verdict,
                      reason: round.decision?.reason ?? "",
                      engineId: verdict.engineId,
                      round: round.reviewRound,
                      degraded: verdict.degraded,
                      detail: round.decision?.detail ?? [],
                    }, t);
                    return (
                      <>
                        <div className="mt-1.5 text-[12.5px] leading-relaxed text-ink">{expl.summary}</div>
                        {expl.points.length > 0 && (
                          <ul className="mt-1 space-y-0.5">
                            {expl.points.slice(0, 6).map((p, i) => (
                              <li key={i} className="font-mono text-[11px] text-dim break-all">• {p}</li>
                            ))}
                            {expl.points.length > 6 && <li className="text-[10.5px] text-faint">{t("decision.morePoints", { n: expl.points.length })}</li>}
                          </ul>
                        )}
                      </>
                    );
                  })()
                ) : (
                  <div className="mt-1.5 text-[12px] text-dim">{verdict.verdict === "PASS" ? t("decision.passDefault") : t("ev.noStructuredReason")}</div>
                )}
                {counts.BLOCKER + counts.WARNING + counts.NIT > 0 && (
                  <div className="mt-1.5 flex flex-wrap gap-1.5">
                    {(["BLOCKER", "WARNING", "NIT"] as const).map((s) =>
                      counts[s] > 0 ? (
                        <span key={s} className={`chip font-mono text-[10.5px] ${s === "BLOCKER" ? "border-danger/30 text-danger bg-danger/10" : s === "WARNING" ? "border-warn/30 text-warn bg-warn/10" : "border-edge text-faint bg-raised"}`}>
                          {counts[s]} {s}
                        </span>
                      ) : null,
                    )}
                  </div>
                )}
                {evidenceFindings.length > 0 && (
                  <div className="mt-2 flex gap-2">
                    <button
                      className="btn btn-sm h-7 text-[11.5px]"
                      onClick={() => {
                        setCenterTab("findings");
                      }}
                    >
                      <MagnifyingGlass size={12} />
                      {t("ev.viewAllFindings")}
                    </button>
                    {evidenceFindings[0]?.path && (
                      <button
                        className="btn btn-sm h-7 text-[11.5px]"
                        onClick={() => jumpToFinding(evidenceFindings[0].path, evidenceFindings[0].lineStart ?? 1)}
                      >
                        {t("ev.locateFirstFinding")}
                      </button>
                    )}
                  </div>
                )}
                {tone === "pass" && (
                  <div className="mt-1.5 font-mono text-[10.5px] text-faint">
                    {t("ev.authorizationLine", { tree: shortHash(round.treeHash, 8, 4), base: shortHash(round.baseCommit, 7, 0), target: round.targetRef.replace("refs/heads/", "") })}
                  </div>
                )}
              </div>
            </div>
          </EventRow>
        ) : (
          <EventRow icon="review" title={t("ev.notReviewed")} />
        )}

        {/* 发布 */}
        {intent && (intent.status === "PUBLISHED" || intent.status === "PENDING") && (
          <EventRow
            icon={intent.status === "PUBLISHED" ? "publish" : "anomaly"}
            anomaly={intent.status !== "PUBLISHED"}
            title={
              intent.status === "PUBLISHED"
                ? t("ev.publishTitle", { at: timeLabel(intent.finishedAt ?? intent.createdAt) })
                : t("ev.publishPending")
            }
          >
            <PublishReceipt intent={intent} round={round} />
          </EventRow>
        )}
      </div>
    </div>
  );
}

/* ── 主视图 ── */

export function EvidenceView({ ticketNo }: { ticketNo: string }) {
  const t = useT();
  const bundle = useApp((s) => s.evidence[ticketNo]);
  const mode = useApp((s) => s.mode);
  const focus = useApp((s) => s.evidenceFocus);
  const [refreshing, setRefreshing] = useState(false);

  const verify = async () => {
    setRefreshing(true);
    await loadEvidence(ticketNo);
    setRefreshing(false);
    const b = appStore.getState().evidence[ticketNo];
    showToast(b?.chain.ok ? t("ev.chain.verifyOk", { n: b.chain.totalLines }) : t("ev.chain.verifyFailed", { n: b?.chain.brokenAtLine ?? 0 }));
  };

  const stageChangeRows: Array<EvidenceStageChange & { key: string }> = (bundle?.stageChanges ?? []).map((s, i) => ({ ...s, key: `sc-${i}` }));

  if (!bundle) {
    return (
      <div className="flex-1 min-h-0 grid place-items-center">
        <div className="text-center max-w-[340px]">
          <div className="mx-auto w-11 h-11 rounded-xl border border-dashed border-edge-strong grid place-items-center mb-3">
            <FileText size={20} className="text-faint" />
          </div>
          <div className="text-[13.5px] text-dim">{t("ev.notLoaded")}</div>
          <div className="mt-1 text-[12px] text-faint leading-relaxed">
            {t("ev.notLoadedHint")}
          </div>
        </div>
      </div>
    );
  }

  const rounds = bundle.rounds;
  const anomalies = bundle.auditEvents.filter((e) => ["publish.toctou", "publish.pending"].includes(e.kind));
  const noEvidence = rounds.length === 0 && stageChangeRows.length === 0;

  return (
    <div className="flex-1 min-h-0 overflow-y-auto px-5 py-4 relative">
      {mode === "demo" && (
        <div className="sticky top-0 z-20 -mx-5 px-5 pb-2">
          <div className="rounded-lg border border-warn/40 bg-warn/10 px-3 py-1.5 text-[11.5px] text-warn flex items-center gap-2">
            <ShieldWarning size={13} weight="fill" />
            {t("ev.demoNote")}
          </div>
        </div>
      )}
      <div className={`max-w-[760px] mx-auto space-y-3 ${mode === "demo" ? "pt-1" : ""}`}>
        {/* 工单头 + 链完整性 */}
        <div>
          <div className="flex items-center gap-2 pb-2">
            <span className="text-[14px] font-semibold">{t("ev.title")}</span>
            <span className="font-mono text-[11px] text-faint">{bundle.ticketNo}</span>
            <span className="flex-1" />
            {bundle.auditTruncated && (
              <span className="text-[10.5px] text-faint">{t("ev.auditTruncated", { shown: bundle.auditEvents.length, total: bundle.auditEventsTotal })}</span>
            )}
          </div>
          <ChainStatusBar bundle={bundle} onRefresh={verify} refreshing={refreshing} />
        </div>

        {noEvidence && (
          <div className="text-center py-10 text-[12.5px] text-faint">
            {t("ev.noEvidence")}
          </div>
        )}

        {/* 工单创建 */}
        {rounds.length > 0 && (
          <div className="relative pl-5">
            <span className="absolute left-[5px] top-2 bottom-0 w-px bg-edge" />
            <span className="absolute left-0 top-[7px] w-[11px] h-[11px] rounded-full border-2 bg-canvas border-edge-strong" />
            <div className="pb-4">
              <div className="flex items-center gap-2">
                <span className="text-[12.5px] text-dim font-medium">{t("ev.created")}</span>
                <span className="font-mono text-[10.5px] text-faint">{timeLabel(bundle.createdAt)}</span>
              </div>
              <div className="mt-0.5 pl-7 font-mono text-[11px] text-faint">
                {t("ev.targetBranch", { ref: rounds[0]?.targetRef.replace("refs/heads/", "") ?? "-" })}
              </div>
            </div>
          </div>
        )}

        {/* 轮次时间线 */}
        {rounds.map((r, i) => (
          <RoundSection
            key={r.reviewRound + r.treeHash}
            round={r}
            isLatest={i === rounds.length - 1}
            intents={bundle.publishIntents}
            focused={focus?.ticketNo === ticketNo && focus.round === r.reviewRound}
          />
        ))}

        {/* 人工干预（状态变更） */}
        {stageChangeRows.length > 0 && (
          <div className="relative pl-5 pt-2 border-t border-edge">
            <div className="flex items-center gap-2 py-1.5">
              <PencilSimpleLine size={14} weight="fill" className="text-warn" />
              <span className="text-[12.5px] font-semibold text-dim">{t("ev.stageChangesTitle")}</span>
              <span className="font-mono text-[10.5px] text-faint">{t("ev.stageChangesCount", { n: stageChangeRows.length })}</span>
            </div>
            {stageChangeRows.map((s) => (
              <EventRow
                key={s.key}
                icon="human"
                title={t("ev.stageChangeLine", {
                  at: timeLabel(s.createdAt),
                  kind: stageChangeKindLabel(s.kind, t),
                  round: s.round,
                  from: s.fromStage,
                  to: s.toStage,
                })}
                detail={[t("ev.reasonLine", { reason: s.reason })]}
              />
            ))}
          </div>
        )}

        {/* 异常事件横幅（不折叠） */}
        {anomalies.length > 0 && (
          <div className="rounded-xl border border-danger/30 bg-danger/[0.06] p-3 space-y-1.5">
            <div className="flex items-center gap-2">
              <WarningCircle size={14} weight="fill" className="text-danger" />
              <span className="text-[12.5px] font-semibold text-danger">{t("ev.anomaliesTitle")}</span>
            </div>
            {anomalies.map((e, i) => {
              const l = auditEventLabel(e, t);
              return (
                <div key={i} className="text-[12px] text-danger leading-relaxed">
                  {timeLabel(e.at)} · {l.text}
                </div>
              );
            })}
          </div>
        )}

        {/* 原始审计事件（默认收起：人话已在时间线里，这里供追问） */}
        {bundle.auditEvents.length > 0 && (
          <details className="rounded-xl border border-edge bg-canvas">
            <summary className="px-3 py-2 text-[12px] text-dim cursor-pointer select-none">
              {t("ev.rawAudit", { n: bundle.auditEvents.length })}
            </summary>
            <div className="px-3 pb-3 max-h-[360px] overflow-auto space-y-1">
              {bundle.auditEvents.map((e, i) => {
                const l = auditEventLabel(e, t);
                return (
                  <div key={i} className={`font-mono text-[10.5px] leading-relaxed border-l-2 pl-2 ${l.anomaly ? "border-danger/50" : "border-edge"}`}>
                    <span className="text-faint">{timeLabel(e.at)}</span>{" "}
                    <span className={l.anomaly ? "text-danger" : "text-dim"}>{e.kind}</span>{" "}
                    <span className="text-faint break-all">{JSON.stringify(e.fields ?? {})}</span>
                  </div>
                );
              })}
            </div>
          </details>
        )}
      </div>
    </div>
  );
}
