/**
 * 门禁域 API（gate）：审查引擎配置、快照列表与审查结果回填。
 * 门禁四阶段流程（presubmit/sync-base/review/publish）见 flows.ts。
 */
import { api } from "@/net";
import { appStore } from "@/store";
import type { Finding, Severity, Snapshot, VerdictInfo } from "@/shared/types";
import { setFindings, setVerdict } from "./state";

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
            message: String(obj.detail ?? "审查引擎未能完成本轮判决"),
            suggestion:
              "引擎未产出有效审查（超时/崩溃/上游或凭据问题）。可重试 AI 审查、检查引擎上游可用性，或改用人工审查。",
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
  if (verdict === "PASS") return "全部策略通过，发布授权已签发";
  if (verdict === "REQUIRES_HUMAN") return "需人工核准后放行";
  return "存在阻断项或引擎未能完成本轮判决，详见下方发现";
}

async function applyReviewResult(no: string) {
  const rr = await api<RawReviewResult>(`/api/tickets/${no}/review-result`);
  setFindings(no, parseFindings(rr.findings));
  const verdict: VerdictInfo = {
    verdict: rr.verdict as "PASS" | "REJECT" | "REQUIRES_HUMAN",
    reason: verdictReason(rr.verdict),
    engineId: rr.engine_id,
    round: rr.review_round,
    degraded: rr.degraded === true,
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
