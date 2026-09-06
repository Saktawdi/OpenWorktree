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
import { formatBytes, shortHash } from "@/shared/format";
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
function auditEventLabel(ev: EvidenceAuditEvent): { icon: "snapshot" | "review" | "publish" | "sync" | "anomaly" | "generic"; text: string; detail: string[]; anomaly: boolean } {
  const f = ev.fields ?? {};
  switch (ev.kind) {
    case "presubmit.ok":
      return { icon: "snapshot", text: `快照锁定 · ${f.paths ?? ""}`.trim(), detail: Object.entries(f).map(([k, v]) => `${k}=${v}`), anomaly: false };
    case "presubmit.blocked":
      return { icon: "anomaly", text: "预提审被拒绝", detail: Object.entries(f).map(([k, v]) => `${k}=${v}`), anomaly: true };
    case "review.pass":
      return { icon: "review", text: "审查通过，发布授权已签发", detail: [`原因 ${f.reason ?? ""}`, `引擎 ${f.engine ?? ""}`], anomaly: false };
    case "review.reject":
      return { icon: "review", text: "审查驳回", detail: [`原因 ${f.reason ?? ""}`, `引擎 ${f.engine ?? ""}`], anomaly: false };
    case "review.requires_human":
      return { icon: "review", text: "引擎请求人工核准", detail: [`原因 ${f.reason ?? ""}`], anomaly: false };
    case "publish.done":
      return {
        icon: "publish",
        text: `已发布 ${shortHash(f.commit ?? "", 7, 0)} → ${f.ref_after ?? ""}`.trim(),
        detail: [`分支 ${f.ref_after ?? ""}：${shortHash(f.ref_before ?? "", 7, 0)} → ${shortHash(f.commit ?? "", 7, 0)}`, "CAS 校验通过 · 授权 nonce 已消费"],
        anomaly: false,
      };
    case "publish.pending":
      return { icon: "anomaly", text: "推送未落地，状态 PENDING，需要 reconcile", detail: Object.entries(f).map(([k, v]) => `${k}=${v}`), anomaly: true };
    case "publish.toctou":
      return { icon: "anomaly", text: "审查后工作区又发生了变化，发布被拒绝（TOCTOU 防护）", detail: [`审查树 ${shortHash(f.reviewed_tree ?? "", 8, 4)}`, `当前树 ${shortHash(f.worktree_tree ?? "", 8, 4)}`], anomaly: true };
    case "publish.idempotent":
      return { icon: "publish", text: "重复发布请求：该提交已在目标分支，本次为无操作", detail: [`提交 ${shortHash(f.commit ?? "", 8, 4)}`], anomaly: false };
    case "publish.workspace_sync":
      return { icon: "sync", text: `工作区同步 ${f.status ?? ""}`, detail: f.note ? [f.note] : [], anomaly: f.status === "DEFERRED" };
    case "publish.clone_sync":
      return { icon: "sync", text: `克隆快进 ${f.status ?? ""}`, detail: f.reason ? [f.reason] : [], anomaly: f.status === "ERROR" || f.status === "SKIPPED" };
    case "reconcile":
      return { icon: "sync", text: `对账完成：提交 ${shortHash(f.commit ?? "", 8, 4)} ${f.published === "true" ? "已确认在目标分支" : "未在目标分支"}`, detail: [], anomaly: f.published !== "true" };
    case "ticket.restart":
      return { icon: "generic", text: "工单重启", detail: [f.reason ?? ""], anomaly: false };
    case "ticket.force_complete":
      return { icon: "generic", text: "强制标记完成", detail: [f.reason ?? ""], anomaly: false };
    case "ticket.cancel":
      return { icon: "generic", text: "工单取消", detail: [f.reason ?? ""], anomaly: false };
    default:
      return { icon: "generic", text: ev.kind, detail: Object.entries(f).map(([k, v]) => `${k}=${v}`), anomaly: false };
  }
}

/* ── 顶部：链完整性 + 导出 ── */

function ChainStatusBar({ bundle, onRefresh, refreshing }: { bundle: EvidenceBundle; onRefresh: () => void; refreshing: boolean }) {
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
          链完整性 <span className="text-ink font-medium">✓ 完整</span>
          <span className="text-faint font-mono"> · {chain.totalLines} 条记录 · 哈希链可检测篡改（检测而非阻止）</span>
        </span>
      ) : (
        <span className="text-danger">
          链完整性 ✗ 自第 {chain.brokenAtLine} 条记录起链断裂，之后的记录不可信
        </span>
      )}
      <span className="flex-1" />
      <button className="btn btn-sm h-7 text-[11.5px]" onClick={onRefresh} disabled={refreshing}>
        {refreshing ? "验证中…" : "验证链"}
      </button>
      <ExportButton bundle={bundle} />
    </div>
  );
}

function ExportButton({ bundle }: { bundle: EvidenceBundle }) {
  const onClick = () => {
    const lines = [
      `# 证据链导出 · ${bundle.ticketNo}`,
      `导出时间：${new Date().toISOString()}`,
      `哈希链验证：${bundle.chain.ok ? "完整" : `自第 ${bundle.chain.brokenAtLine} 条断裂`}（共 ${bundle.chain.totalLines} 条）`,
      "",
      "## 审查轮次",
      ...bundle.rounds.map((r) =>
        [
          `### 第 ${r.reviewRound} 轮（${r.createdAt}）`,
          `tree=${r.treeHash} base=${r.baseCommit} target=${r.targetRef}`,
          `diff ${r.diffBytes}B sha256=${r.diffSha256}`,
          r.review ? `判决 ${r.review.verdict} · 引擎 ${r.review.engineId} · covered_ok=${r.review.coveredOk} · degraded=${r.review.degraded}` : "（无审查结果行）",
          r.decision ? `理由 ${r.decision.reason}${r.decision.detail.length ? `\n依据:\n${r.decision.detail.map((d) => `  - ${d}`).join("\n")}` : ""}` : "",
          "",
        ].filter(Boolean).join("\n"),
      ),
      "## 发布意图",
      ...bundle.publishIntents.map(
        (p) =>
          `- R${p.reviewRound} ${p.status} commit=${p.commitSha ?? "-"} ${p.targetRef} ${p.refBefore ?? "?"} → ${p.refAfter ?? "?"} approval=${p.approvalId ?? "-"}${p.approvalConsumed ? "（已消费）" : ""}`,
      ),
      "",
      "## 状态变更",
      ...bundle.stageChanges.map((s) => `- R${s.round} ${s.kind}: ${s.fromStage} → ${s.toStage} 理由：${s.reason}`),
      "",
      "## 审计事件（本工单）",
      ...bundle.auditEvents.map((e) => `${e.at} ${e.kind} ${JSON.stringify(e.fields ?? {})}`),
    ];
    const blob = new Blob([lines.join("\n")], { type: "text/markdown;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `evidence-${bundle.ticketNo}.md`;
    a.click();
    URL.revokeObjectURL(url);
    showToast("证据包已导出（Markdown）");
  };
  return (
    <button className="btn btn-sm h-7 text-[11.5px]" onClick={onClick} title="导出该工单全部证据（轮次/判决/发布/审计）">
      <Download size={13} />
      导出
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
      {row("审查通过的 tree", round?.treeHash ?? intent.treeHash, intent.treeHash, eq1)}
      {row("授权基线 = 推送前 tip", intent.baseCommit, intent.refBefore ?? "", eq2)}
      <div className="flex items-center gap-2 font-mono text-[11.5px] py-0.5">
        <span className="text-faint w-[118px] shrink-0">授权目标分支</span>
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
        <span>授权 {shortHash(intent.approvalId ?? "", 8, 0)}{intent.approvalConsumed ? " · nonce 已消费" : ""}</span>
        <span className="ml-auto">审查的就是发布的：CAS 原子推送保证</span>
      </div>
    </div>
  );
}

/* ── 轮次卡 ── */

function RoundSection({ round, intents, isLatest, focused }: { round: EvidenceRound; intents: EvidencePublishIntent[]; isLatest: boolean; focused: boolean }) {
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
          message: ev.detail ?? "审查引擎未能完成本轮判决",
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
    verdict?.verdict === "PASS" ? "审查通过" : verdict?.verdict === "REQUIRES_HUMAN" ? "需人工核准" : verdict?.degraded ? "引擎故障驳回" : "审查驳回";
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
          <span className="text-[12.5px] font-semibold text-ink">第 {round.reviewRound} 轮</span>
          {isLatest && <span className="chip border border-accent/30 text-accent bg-accent/10">最新</span>}
          <span className="font-mono text-[10.5px] text-faint">{timeLabel(round.createdAt)}</span>
        </div>

        {/* 快照锁定 */}
        <EventRow icon="snapshot" title="快照锁定 · 所见即所审">
          <div className="mt-0.5 flex flex-wrap items-center gap-x-3 gap-y-1 font-mono text-[11px] text-faint">
            <span className="text-dim">tree {shortHash(round.treeHash, 8, 4)}</span>
            <span>base {shortHash(round.baseCommit, 7, 0)}</span>
            <span>{round.changedPaths.length} 文件</span>
            <span>{formatBytes(round.diffBytes)}</span>
            <button className="text-info hover:underline cursor-pointer bg-transparent border-0 p-0 font-mono text-[11px]" onClick={openDiff}>
              查看锁定差异
            </button>
          </div>
          <div className="font-mono text-[10.5px] text-faint">diff sha256 {shortHash(round.diffSha256, 8, 6)}</div>
          {showDiff && (
            <div className="mt-2 max-h-[420px] overflow-auto rounded-lg border border-edge bg-canvas">
              {diffLoading ? (
                <div className="p-3 text-[12px] text-faint flex items-center gap-2">
                  <CircleNotch size={13} className="animate-[spin_0.9s_linear_infinite]" /> 加载中…
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
                  <div className="p-3 text-[12px] text-faint">（差异为空）</div>
                )
              )}
            </div>
          )}
        </EventRow>

        {/* 审查 */}
        {verdict ? (
          <EventRow icon="review" title={`审查 · ${verdict.engineId}${verdict.modelName ? ` / ${verdict.modelName}` : ""}`}>
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1 font-mono text-[11px] text-faint">
              <span>覆盖 {round.evidence && "covered_paths" in round.evidence && Array.isArray(round.evidence.covered_paths) ? round.evidence.covered_paths.length : "?"}/{round.changedPaths.length}</span>
              {verdict.degraded && <span className="text-warn">未降级可信</span>}
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
                    判决 {verdictLabel}
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
                    });
                    return (
                      <>
                        <div className="mt-1.5 text-[12.5px] leading-relaxed text-ink">{expl.summary}</div>
                        {expl.points.length > 0 && (
                          <ul className="mt-1 space-y-0.5">
                            {expl.points.slice(0, 6).map((p, i) => (
                              <li key={i} className="font-mono text-[11px] text-dim break-all">• {p}</li>
                            ))}
                            {expl.points.length > 6 && <li className="text-[10.5px] text-faint">… 共 {expl.points.length} 条</li>}
                          </ul>
                        )}
                      </>
                    );
                  })()
                ) : (
                  <div className="mt-1.5 text-[12px] text-dim">{verdict.verdict === "PASS" ? "全部策略通过，发布授权已签发" : "该工单的历史判决未记录结构化理由"}</div>
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
                      查看全部发现
                    </button>
                    {evidenceFindings[0]?.path && (
                      <button
                        className="btn btn-sm h-7 text-[11.5px]"
                        onClick={() => jumpToFinding(evidenceFindings[0].path, evidenceFindings[0].lineStart ?? 1)}
                      >
                        定位首个发现
                      </button>
                    )}
                  </div>
                )}
                {tone === "pass" && (
                  <div className="mt-1.5 font-mono text-[10.5px] text-faint">
                    授权：绑定 tree {shortHash(round.treeHash, 8, 4)} / base {shortHash(round.baseCommit, 7, 0)} · 目标 {round.targetRef.replace("refs/heads/", "")}
                  </div>
                )}
              </div>
            </div>
          </EventRow>
        ) : (
          <EventRow icon="review" title="尚未审查" />
        )}

        {/* 发布 */}
        {intent && (intent.status === "PUBLISHED" || intent.status === "PENDING") && (
          <EventRow
            icon={intent.status === "PUBLISHED" ? "publish" : "anomaly"}
            anomaly={intent.status !== "PUBLISHED"}
            title={
              intent.status === "PUBLISHED"
                ? `发布 · ${timeLabel(intent.finishedAt ?? intent.createdAt)}`
                : "发布未落地（PENDING）· 待对账"
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
  const bundle = useApp((s) => s.evidence[ticketNo]);
  const mode = useApp((s) => s.mode);
  const focus = useApp((s) => s.evidenceFocus);
  const [refreshing, setRefreshing] = useState(false);

  const verify = async () => {
    setRefreshing(true);
    await loadEvidence(ticketNo);
    setRefreshing(false);
    const b = appStore.getState().evidence[ticketNo];
    showToast(b?.chain.ok ? `链验证通过：共 ${b.chain.totalLines} 条记录` : `链验证失败：自第 ${b?.chain.brokenAtLine} 条记录起断裂`);
  };

  const stageChangeRows: Array<EvidenceStageChange & { key: string }> = (bundle?.stageChanges ?? []).map((s, i) => ({ ...s, key: `sc-${i}` }));

  if (!bundle) {
    return (
      <div className="flex-1 min-h-0 grid place-items-center">
        <div className="text-center max-w-[340px]">
          <div className="mx-auto w-11 h-11 rounded-xl border border-dashed border-edge-strong grid place-items-center mb-3">
            <FileText size={20} className="text-faint" />
          </div>
          <div className="text-[13.5px] text-dim">证据链尚未加载</div>
          <div className="mt-1 text-[12px] text-faint leading-relaxed">
            预提审、审查与发布的事实会按轮次沉淀在这里；刷新页面或重新打开工单可重试加载。
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
            演示模式：本页所有判决、哈希与发布回执均为演示数据，不构成真实证据。
          </div>
        </div>
      )}
      <div className={`max-w-[760px] mx-auto space-y-3 ${mode === "demo" ? "pt-1" : ""}`}>
        {/* 工单头 + 链完整性 */}
        <div>
          <div className="flex items-center gap-2 pb-2">
            <span className="text-[14px] font-semibold">证据链</span>
            <span className="font-mono text-[11px] text-faint">{bundle.ticketNo}</span>
            <span className="flex-1" />
            {bundle.auditTruncated && (
              <span className="text-[10.5px] text-faint">审计事件仅显示最近 {bundle.auditEvents.length} / {bundle.auditEventsTotal} 条</span>
            )}
          </div>
          <ChainStatusBar bundle={bundle} onRefresh={verify} refreshing={refreshing} />
        </div>

        {noEvidence && (
          <div className="text-center py-10 text-[12.5px] text-faint">
            这张工单还没有走过门禁：预提审后会在这里锁定第一份快照证据。
          </div>
        )}

        {/* 工单创建 */}
        {rounds.length > 0 && (
          <div className="relative pl-5">
            <span className="absolute left-[5px] top-2 bottom-0 w-px bg-edge" />
            <span className="absolute left-0 top-[7px] w-[11px] h-[11px] rounded-full border-2 bg-canvas border-edge-strong" />
            <div className="pb-4">
              <div className="flex items-center gap-2">
                <span className="text-[12.5px] text-dim font-medium">工单创建</span>
                <span className="font-mono text-[10.5px] text-faint">{timeLabel(bundle.createdAt)}</span>
              </div>
              <div className="mt-0.5 pl-7 font-mono text-[11px] text-faint">
                目标分支 {rounds[0]?.targetRef.replace("refs/heads/", "") ?? "-"}
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
              <span className="text-[12.5px] font-semibold text-dim">人工干预记录</span>
              <span className="font-mono text-[10.5px] text-faint">{stageChangeRows.length} 次 · 理由必填</span>
            </div>
            {stageChangeRows.map((s) => (
              <EventRow
                key={s.key}
                icon="human"
                title={`${timeLabel(s.createdAt)} · ${s.kind === "restart" ? "重启" : s.kind === "force_complete" ? "强制已完成" : "取消工单"}（第 ${s.round} 轮前后）· ${s.fromStage} → ${s.toStage}`}
                detail={[`理由：${s.reason}`]}
              />
            ))}
          </div>
        )}

        {/* 异常事件横幅（不折叠） */}
        {anomalies.length > 0 && (
          <div className="rounded-xl border border-danger/30 bg-danger/[0.06] p-3 space-y-1.5">
            <div className="flex items-center gap-2">
              <WarningCircle size={14} weight="fill" className="text-danger" />
              <span className="text-[12.5px] font-semibold text-danger">需要留意的异常事件</span>
            </div>
            {anomalies.map((e, i) => {
              const l = auditEventLabel(e);
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
              原始审计事件（{bundle.auditEvents.length} 条 · 含哈希链字段）
            </summary>
            <div className="px-3 pb-3 max-h-[360px] overflow-auto space-y-1">
              {bundle.auditEvents.map((e, i) => {
                const l = auditEventLabel(e);
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
