/**
 * 审核决策 — 对齐后端 Decision / Finding / Severity / Verdict (架构落地执行文档 §5.3 + 前端文档 §6.3).
 *
 * 后端是契约方: severity / verdict 的取值集合与后端 enum name 一致.
 */
export type Severity = 'blocker' | 'warning' | 'nit';

export type Verdict = 'pass' | 'reject' | 'requires_human';

/** 单条审核发现. */
export interface Finding {
  severity: Severity;
  file: string;
  lineStart: number;
  lineEnd: number;
  message: string;
  suggestion: string;
}

/** 审核决策 (review 结果). */
export interface Decision {
  verdict: Verdict;
  reason: string;
  detail: string[];
  findings: Finding[];
  /** 人工裁决的授权 (单次消费), 后端可选返回. */
  authorization?: string;
}
