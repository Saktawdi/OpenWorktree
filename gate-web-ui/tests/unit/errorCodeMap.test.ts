/**
 * errorCodeMap 测试 — GateErrorCode → 中文消息 + HTTP 状态码映射.
 */
import { describe, expect, it } from 'vitest';
import { ERROR_CODE_MAP, formatGateError } from '@/utils/errorCodeMap';

describe('errorCodeMap', () => {
  it('OK → 200', () => {
    expect(ERROR_CODE_MAP.OK.httpStatus).toBe(200);
  });

  it('REJECT_FINDINGS → 422', () => {
    expect(ERROR_CODE_MAP.REJECT_FINDINGS.httpStatus).toBe(422);
  });

  it('REJECT_TOCTOU → 409', () => {
    expect(ERROR_CODE_MAP.REJECT_TOCTOU.httpStatus).toBe(409);
  });

  it('GATE_ERROR_ENGINE → 502', () => {
    expect(ERROR_CODE_MAP.GATE_ERROR_ENGINE.httpStatus).toBe(502);
  });

  it('formatGateError 优先用 fallbackMessage', () => {
    const msg = formatGateError('REJECT_FINDINGS', '审核未通过: 2 条 blocker', []);
    expect(msg).toBe('审核未通过: 2 条 blocker');
  });

  it('formatGateError fallback 到默认消息', () => {
    const msg = formatGateError('REJECT_FINDINGS');
    expect(msg).toContain('findings');
  });

  it('formatGateError 拼接 detail', () => {
    const msg = formatGateError('REJECT_PRECONDITION', undefined, ['base 已移动', '非 FF']);
    expect(msg).toContain('base 已移动');
    expect(msg).toContain('非 FF');
  });
});
