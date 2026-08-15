/**
 * 成本度量 — 对齐后端 MetricsService (架构落地执行文档 §6 + 后端文档 §4).
 *
 * H1 复测入口 (后端文档 §5.7): SessionMessage.usage 累计补齐执行侧度量.
 */
export interface MetricsRow {
  ticketNo: string;
  title: string;
  stage: string;
  /** 执行侧 token (会话累计回写). */
  execTokenTotal: number | null;
  execTokenSource: 'agent_cli' | null;
  /** 审核侧 token. */
  reviewTokenTotal: number | null;
}

export type H1Classification = 'PASS' | 'PARTIAL' | 'FAIL';

/** H1 判定 (后端 MetricsService.verdict). */
export interface H1Verdict {
  /** 首过率: 一次审核 pass 占比. */
  firstPassRate: number;
  /** 成本比中位数 (exec / review token). NaN 时为 degraded basis. */
  costRatioMedian: number;
  classification: H1Classification;
  /** degraded 标记 (e.g. 执行侧 token 全 null). */
  degraded: boolean;
  /** degraded 原因列表. */
  degradedReasons: string[];
}
