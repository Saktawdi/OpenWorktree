/**
 * 门禁域 API（gate）：审查引擎配置、快照列表与审查结果回填。
 * 门禁四阶段流程（presubmit/sync-base/review/publish）见 flows.ts。
 */
import { t } from "@/i18n";
import { api } from "@/net";
import { appStore } from "@/store";
import type {
  EvidenceBundle,
  EvidenceFailure,
  EvidencePublishIntent,
  EvidenceReport,
  EvidenceRound,
  Finding,
  Severity,
  Snapshot,
  VerdictInfo,
} from "@/shared/types";
import { setEvidence, setFindings, setVerdict } from "./state";

/**
 * 拉取引擎配置（/api/config 的 engine_configured + engine 节）：
 * AI 审查入口的可用性与按钮文案都依赖它；失败不阻断连接，仅视为未加载。
 */
export async function loadEngineConfig() {
  try {
    const data = await api<{
      engine_configured?: boolean;
      engine?: { provider_id?: string | null; model?: string | null; timeout_seconds?: number | null };
    }>("/api/config");
    appStore.setState({
      engine: {
        configured: data.engine_configured === true,
        providerId: data.engine?.provider_id ?? null,
        model: data.engine?.model ?? null,
        timeoutSeconds: data.engine?.timeout_seconds ?? null,
      },
    });
  } catch {
    /* 配置读取失败不阻断：engine 保持 null，AI 入口按未加载处理 */
  }
}

export async function loadPresubmits(no: string) {
  try {
    const data = await api<{
      presubmits: Array<{
        review_round: number;
        tree_hash: string;
        base_commit: string;
        target_ref: string;
        diff_bytes: number;
        changed_count: number;
        created_at: string;
      }>;
    }>(`/api/tickets/${no}/presubmits`);
    const list: Snapshot[] = (data.presubmits ?? []).map((p) => ({
      round: p.review_round,
      treeHash: p.tree_hash,
      baseCommit: p.base_commit,
      targetRef: p.target_ref,
      diffBytes: p.diff_bytes,
      changedPaths: [],
      changedCount: p.changed_count,
      capturedAt: Date.parse(p.created_at),
    }));
    appStore.setState((st) => ({ snapshots: { ...st.snapshots, [no]: list } }));
  } catch {
    /* 尚无快照记录时静默 */
  }
}

interface RawReviewResult {
  verdict: string;
  engine_id: string;
  review_round: number;
  degraded?: boolean;
  findings: string;
  /** 判决理由（审计回读；旧后端/旧工单缺省）。 */
  reason?: string | null;
  /** 判决的结构化依据（offending findings / missing paths / byte/line 数）。 */
  detail?: string[] | null;
}

/**
 * 解析审查证据 blob（EvidenceCodec 的两种对象形态）：
 * - `{"kind":"report","findings":[…snake_case…]}` —— 正常引擎报告；
 * - `{"kind":"failure","failure_kind":…,"detail":…}` —— 引擎未产出判决（超时/崩溃），
 *   合成一条 BLOCKER 发现，让驳回有具体原因可看，而不是"共 0 项发现"。
 * 兼容旧的顶层数组形态（demo 数据）。
 */
function parseFindings(raw: string): Finding[] {
  try {
    const parsed: unknown = JSON.parse(raw);
    if (Array.isArray(parsed)) {
      return parsed.map(mapFinding).filter((f): f is Finding => f !== null);
    }
    if (parsed && typeof parsed === "object") {
      const obj = parsed as Record<string, unknown>;
      if (obj.kind === "failure") {
        const kind = String(obj.failure_kind ?? "CRASH");
        return [
          {
            severity: "BLOCKER",
            path: "",
            ruleId: `engine/${kind}`,
            message: String(obj.detail ?? t("findings.engineFailed")),
            suggestion:
              t("gateapi.degradedNote"),
          },
        ];
      }
      if (Array.isArray(obj.findings)) {
        return (obj.findings as unknown[])
          .map(mapFinding)
          .filter((f): f is Finding => f !== null);
      }
    }
  } catch {
    /* 非结构化时按空处理 */
  }
  return [];
}

/** 兼容 snake_case（EvidenceCodec 落盘）与 camelCase（demo 数据）两种字段名。 */
function mapFinding(f: unknown): Finding | null {
  if (!f || typeof f !== "object") return null;
  const o = f as Record<string, unknown>;
  const num = (v: unknown) => (typeof v === "number" ? v : undefined);
  const lineStart = num(o.lineStart) ?? num(o.line_start);
  const lineEnd = num(o.lineEnd) ?? num(o.line_end);
  const ruleId = o.ruleId ?? o.rule_id;
  return {
    severity: (o.severity as Severity) ?? "INFO",
    path: String(o.path ?? ""),
    lineStart,
    lineEnd,
    ruleId: ruleId ? String(ruleId) : undefined,
    message: String(o.message ?? ""),
    suggestion: o.suggestion ? String(o.suggestion) : undefined,
  };
}

function verdictReason(verdict: string): string {
  if (verdict === "PASS") return t("decision.passDefault");
  if (verdict === "REQUIRES_HUMAN") return t("gateapi.requiresHuman");
  return t("gateapi.rejectReason");
}

async function applyReviewResult(no: string) {
  const rr = await api<RawReviewResult>(`/api/tickets/${no}/review-result`);
  setFindings(no, parseFindings(rr.findings));
  const verdict: VerdictInfo = {
    verdict: rr.verdict as "PASS" | "REJECT" | "REQUIRES_HUMAN",
    reason: rr.reason || verdictReason(rr.verdict),
    engineId: rr.engine_id,
    round: rr.review_round,
    degraded: rr.degraded === true,
    detail: rr.detail ?? [],
  };
  setVerdict(no, verdict);
}

export async function loadReviewState(no: string) {
  try {
    await applyReviewResult(no);
  } catch {
    /* 尚无审查结果时静默返回 */
  }
}


/* ── 证据链（GET /api/tickets/{no}/evidence 聚合投影） ── */

/** snake_case 聚合响应的原样形状（字段映射集中在 mapEvidenceBundle）。 */
interface RawEvidenceBundle {
  ticket_no: string;
  created_at?: string | null;
  chain: { ok: boolean; total_lines: number; broken_at_line: number };
  rounds: Array<{
    review_round: number;
    tree_hash: string;
    base_commit: string;
    target_ref: string;
    diff_bytes: number;
    diff_sha256: string;
    created_at: string;
    evidence: EvidenceReport | EvidenceFailure | { kind: "unreadable" } | null;
    changed_paths: string[];
    review?: {
      verdict: string;
      engine_id: string;
      engine_version?: string;
      model_name?: string;
      covered_ok: boolean;
      degraded: boolean;
      created_at: string;
      cost?: Record<string, number | null> | null;
    };
    decision?: { verdict: string; reason: string; detail?: string[] } | null;
  }>;
  stage_changes: Array<{
    round: number;
    from_stage: string;
    to_stage: string;
    kind: string;
    reason: string;
    created_at: string | null;
  }>;
  publish_intents: Array<{
    review_round: number;
    tree_hash: string;
    base_commit: string;
    target_ref: string;
    commit_sha: string | null;
    status: string;
    ref_before: string | null;
    ref_after: string | null;
    created_at: string;
    finished_at: string | null;
    approval_id: string | null;
    approval_consumed?: boolean;
  }>;
  audit_events: Array<Record<string, unknown>>;
  audit_events_total: number;
  audit_truncated: boolean;
}

function mapEvidenceBundle(raw: RawEvidenceBundle): EvidenceBundle {
  const rounds: EvidenceRound[] = (raw.rounds ?? []).map((r) => ({
    reviewRound: r.review_round,
    treeHash: r.tree_hash,
    baseCommit: r.base_commit,
    targetRef: r.target_ref,
    diffBytes: r.diff_bytes,
    diffSha256: r.diff_sha256,
    createdAt: r.created_at,
    changedPaths: r.changed_paths ?? [],
    evidence: r.evidence,
    review: r.review
      ? {
          verdict: r.review.verdict as "PASS" | "REJECT" | "REQUIRES_HUMAN",
          engineId: r.review.engine_id,
          engineVersion: r.review.engine_version,
          modelName: r.review.model_name,
          coveredOk: r.review.covered_ok,
          degraded: r.review.degraded,
          createdAt: r.review.created_at,
          cost: r.review.cost
            ? {
                promptTokens: r.review.cost.prompt_tokens ?? null,
                completionTokens: r.review.cost.completion_tokens ?? null,
                totalTokens: r.review.cost.total_tokens ?? null,
                reviewWallMs: r.review.cost.review_wall_ms ?? null,
              }
            : null,
        }
      : undefined,
    decision: r.decision
      ? { verdict: r.decision.verdict, reason: r.decision.reason ?? "", detail: r.decision.detail ?? [] }
      : null,
  }));
  return {
    ticketNo: raw.ticket_no,
    createdAt: raw.created_at ?? null,
    chain: {
      ok: raw.chain.ok,
      totalLines: raw.chain.total_lines,
      brokenAtLine: raw.chain.broken_at_line,
    },
    rounds,
    stageChanges: (raw.stage_changes ?? []).map((s) => ({
      round: s.round,
      fromStage: s.from_stage,
      toStage: s.to_stage,
      kind: s.kind as "restart" | "force_complete" | "cancel",
      reason: s.reason,
      createdAt: s.created_at,
    })),
    publishIntents: (raw.publish_intents ?? []).map((p) => ({
      reviewRound: p.review_round,
      treeHash: p.tree_hash,
      baseCommit: p.base_commit,
      targetRef: p.target_ref,
      commitSha: p.commit_sha,
      status: p.status as EvidencePublishIntent["status"],
      refBefore: p.ref_before,
      refAfter: p.ref_after,
      createdAt: p.created_at,
      finishedAt: p.finished_at,
      approvalId: p.approval_id,
      approvalConsumed: p.approval_consumed,
    })),
    auditEvents: (raw.audit_events ?? []) as unknown as EvidenceBundle["auditEvents"],
    auditEventsTotal: raw.audit_events_total ?? 0,
    auditTruncated: raw.audit_truncated === true,
  };
}

/** 拉取并写入证据链聚合数据；失败置 null（UI 显示加载失败态，不弹 toast 打扰）。
 *  demo 模式无后端：由 demoEvidence 从 store 事实投影同一形状的数据（带 demo 水印标记）。 */
export async function loadEvidence(no: string) {
  if (appStore.getState().mode === "demo") {
    const { buildDemoEvidence } = await import("./demoEvidence");
    setEvidence(no, buildDemoEvidence(no));
    return;
  }
  try {
    const raw = await api<RawEvidenceBundle>(`/api/tickets/${no}/evidence`);
    setEvidence(no, mapEvidenceBundle(raw));
  } catch {
    setEvidence(no, null);
  }
}
