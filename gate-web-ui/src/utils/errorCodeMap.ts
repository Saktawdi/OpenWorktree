/**
 * GateErrorCode → 用户可读中文消息 + HTTP 状态码映射.
 * 对齐后端文档 §4.4 错误模型表.
 *
 * 后端响应体: { error_code, error, message, detail }.
 * 这里按 error (enum name) 字符串映射, 避免后端 numeric 重排导致漂移.
 */
import type { GateErrorCode } from '@/types/errors';

export interface ErrorMapEntry {
  /** 后端文档 §4.4 的 HTTP 状态码. */
  httpStatus: number;
  /** 用户可读中文消息 (后端 message 字段可直接用, 这里是 fallback). */
  message: string;
}

export const ERROR_CODE_MAP: Readonly<Record<GateErrorCode, ErrorMapEntry>> = {
  OK: { httpStatus: 200, message: '成功' },
  REJECT_FINDINGS: {
    httpStatus: 422,
    message: '审核未通过: 存在 findings (业务结果, 非错误)',
  },
  REJECT_TOCTOU: {
    httpStatus: 409,
    message: 'tree 不一致: 与 base_commit 冲突, 请重新 presubmit',
  },
  REJECT_PRECONDITION: {
    httpStatus: 422,
    message: '前置条件不满足 (base 移动 / 非 FF / 空 diff / ref 非白名单)',
  },
  REJECT_NEEDS_HUMAN: { httpStatus: 422, message: '审核需人工裁决' },
  GATE_ERROR_ENGINE: { httpStatus: 502, message: '审核引擎失败' },
  GATE_ERROR_IO: { httpStatus: 503, message: 'IO / 锁忙 / DB 错误' },
  GATE_ERROR_CONFIG: { httpStatus: 500, message: '配置错误' },
  USAGE: { httpStatus: 400, message: '参数错误' },
  INTERNAL: { httpStatus: 500, message: '服务器内部错误' },
};

/** 给定一个 GateErrorCode, 返回中文消息 (含 detail 拼接). */
export function formatGateError(
  code: GateErrorCode,
  fallbackMessage?: string,
  detail?: string[],
): string {
  const entry = ERROR_CODE_MAP[code];
  const head = fallbackMessage && fallbackMessage.trim().length > 0
    ? fallbackMessage
    : entry?.message ?? '未知错误';
  if (detail && detail.length > 0) {
    return `${head}\n${detail.map((d) => `  • ${d}`).join('\n')}`;
  }
  return head;
}
