/**
 * 闸门错误码 — 对齐后端 GateErrorCode (架构落地执行文档 §8.3).
 *
 * 后端是契约方: 新增码 / 改语义须同步本文件.
 * HTTP 状态码映射见 src/utils/errorCodeMap.ts.
 *
 * 值为字符串与后端 enum name 一致 (而非数字), 避免后端重排导致的漂移.
 */
export type GateErrorCode =
  | 'OK'
  | 'REJECT_FINDINGS'
  | 'REJECT_TOCTOU'
  | 'REJECT_PRECONDITION'
  | 'REJECT_NEEDS_HUMAN'
  | 'GATE_ERROR_ENGINE'
  | 'GATE_ERROR_IO'
  | 'GATE_ERROR_CONFIG'
  | 'USAGE'
  | 'INTERNAL';

/** 后端错误响应体 (架构落地执行文档 §8.3 + 后端文档 §4.4). */
export interface GateErrorResponse {
  error_code: number;
  error: GateErrorCode;
  message: string;
  detail: string[];
}
