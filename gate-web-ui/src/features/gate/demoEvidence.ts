/**
 * demo 模式的证据链投影：live 数据走后端聚合端点，demo 没有后端，
 * 这里从 demo 流程写入的 store 事实（snapshots/verdicts/outcomes/stageChanges）
 * 投影出同一形状的 EvidenceBundle。哈希与事件是编的——EvidenceView 会以
 * 显眼水印声明这一点（需求文档 §七.3：别把假证据做得和真的一样）。
 */
import { appStore } from "@/store";
import { fakeSha } from "@/shared/format";
import type {
  EvidenceAuditEvent,
  EvidenceBundle,
  EvidencePublishIntent,
  EvidenceReport,
  EvidenceRound,
  EvidenceStageChange,
  Finding,
} from "@/shared/types";

export function buildDemoEvidence(no: string): EvidenceBundle {
  const st = appStore.getState();
  const snaps = st.snapshots[no] ?? [];
  const verdict = st.verdicts[no];
  const outcome = st.outcomes[no];
  const changes = st.stageChanges[no] ?? [];

  const rounds: EvidenceRound[] = snaps.map((snap) => {
    const isVerdictRound = verdict?.round === snap.round;
    const passedLater = verdict != null && verdict.round > snap.round;
    const demoFindings: Finding[] = passedLater ? FINDINGS_DEMO_R1 : (isVerdictRound && verdict.verdict !== "PASS" ? FINDINGS_DEMO_R1 : []);
    const evidence: EvidenceReport = {
      kind: "report",
      engine_id: verdict?.engineId ?? "gate-policy/prism",
      model_name: "demo-model",
      degraded: false,
      total_tokens: isVerdictRound ? 12400 : null,
      covered_paths: snap.changedPaths,
      findings: demoFindings.map((f) => ({
        severity: f.severity,
        path: f.path,
        line_start: f.lineStart,
        line_end: f.lineEnd,
        rule_id: f.ruleId,
        message: f.message,
        suggestion: f.suggestion,
      })),
    };
    return {
      reviewRound: snap.round,
      treeHash: snap.treeHash,
      baseCommit: snap.baseCommit,
      targetRef: snap.targetRef,
      diffBytes: snap.diffBytes,
      diffSha256: fakeSha(`diffsha:${no}:${snap.round}`),
      createdAt: new Date(snap.capturedAt).toISOString(),
      changedPaths: snap.changedPaths,
      evidence,
      review:
        isVerdictRound && verdict
          ? {
              verdict: verdict.verdict,
              engineId: verdict.engineId,
              modelName: "demo-model",
              coveredOk: true,
              degraded: verdict.degraded === true,
              createdAt: new Date(snap.capturedAt + 90_000).toISOString(),
              cost: { promptTokens: 9800, completionTokens: 2600, totalTokens: 12400, reviewWallMs: 38000 },
            }
          : undefined,
      decision: isVerdictRound
        ? {
            verdict: verdict?.verdict ?? "REJECT",
            reason: verdict?.reason ?? "",
            detail: (verdict?.detail ?? []).concat(demoFindings.map((f) => `${f.severity} ${f.path}:${f.lineStart ?? 1} ${f.message}`)),
          }
        : passedLater
          ? {
              verdict: "REJECT",
              reason: "findings at or above configured strictness",
              detail: demoFindings.map((f) => `${f.severity} ${f.path}:${f.lineStart ?? 1} ${f.message}`),
            }
          : null,
    };
  });

  const publishIntents: EvidencePublishIntent[] =
    outcome && verdict
      ? [
          {
            reviewRound: verdict.round,
            treeHash: snaps[snaps.length - 1]?.treeHash ?? fakeSha(`tree:${no}`),
            baseCommit: snaps[snaps.length - 1]?.baseCommit ?? fakeSha(`base:${no}`),
            targetRef: outcome.targetRef,
            commitSha: outcome.commitSha,
            status: "PUBLISHED",
            refBefore: outcome.refBefore,
            refAfter: outcome.refAfter,
            createdAt: new Date(outcome.publishedAt - 30_000).toISOString(),
            finishedAt: new Date(outcome.publishedAt).toISOString(),
            approvalId: verdict.authorizationId ?? fakeSha(`auth:${no}`),
            approvalConsumed: true,
          },
        ]
      : [];

  const stageChanges: EvidenceStageChange[] = changes.map((c) => ({
    round: c.round,
    fromStage: c.fromStage,
    toStage: c.toStage,
    kind: c.kind,
    reason: c.reason,
    createdAt: c.createdAt,
  }));

  // 合成审计事件（顺序即哈希链）
  const auditEvents: EvidenceAuditEvent[] = [];
  let prev = "0".repeat(64);
  const push = (kind: string, reviewRound: number | null, fields: Record<string, string>) => {
    const hash = fakeSha(`${no}:${kind}:${auditEvents.length}`);
    auditEvents.push({
      at: new Date(Date.now() - (auditEvents.length + 1) * 60_000).toISOString(),
      kind,
      ticket_no: no,
      review_round: reviewRound,
      fields,
      prev_hash: prev,
      hash,
    });
    prev = hash;
  };
  for (const r of rounds) {
    push("presubmit.ok", r.reviewRound, { tree: r.treeHash, base: r.baseCommit, target: r.targetRef });
    if (r.review) {
      push(`review.${r.review.verdict.toLowerCase()}`, r.reviewRound, {
        tree: r.treeHash,
        engine: r.review.engineId,
        reason: r.decision?.reason ?? "",
      });
    }
    const intent = publishIntents.find((p) => p.reviewRound === r.reviewRound);
    if (intent) {
      push("publish.done", r.reviewRound, {
        tree: intent.treeHash,
        commit: intent.commitSha ?? "",
        ref_before: intent.refBefore ?? "",
        ref_after: intent.refAfter ?? "",
        push_accepted: "true",
      });
      push("publish.workspace_sync", r.reviewRound, { status: "SYNCED", note: "" });
      push("publish.clone_sync", r.reviewRound, { status: "OK", behind: "0", from: "", to: intent.commitSha ?? "" });
    }
  }

  return {
    ticketNo: no,
    createdAt: st.tickets.find((t) => t.ticketNo === no)?.createdAt ?? null,
    chain: { ok: true, totalLines: auditEvents.length, brokenAtLine: -1 },
    rounds,
    stageChanges,
    publishIntents,
    auditEvents,
    auditEventsTotal: auditEvents.length,
    auditTruncated: false,
    demo: true,
  };
}

/** demo 剧本的第一轮驳回发现（与 demo/engine 的 T-104 两轮剧情一致）。 */
const FINDINGS_DEMO_R1: Finding[] = [
  {
    severity: "BLOCKER",
    path: "src/pay/Refund.java",
    lineStart: 88,
    ruleId: "security/idempotency",
    message: "退款接口缺少幂等键校验，重复请求可能双倍退款",
    suggestion: "以退款单号为幂等键，命中已处理单号时直接返回既有结果",
  },
  {
    severity: "WARNING",
    path: "src/pay/Refund.java",
    lineStart: 102,
    ruleId: "resource/timeout",
    message: "外部网关调用未设置超时，存在线程挂起风险",
  },
];
