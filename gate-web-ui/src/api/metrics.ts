/**
 * 成本度量 API — 对齐后端 /api/metrics 与 /api/metrics/h1.
 *
 * 后端返回 snake_case records; 这里映射为前端 camelCase MetricsRow / H1Verdict.
 */
import { client } from './client';
import type { H1Classification, H1Verdict, MetricsRow } from '@/types/metrics';

type RawRecord = Record<string, unknown>;

function recordOf(value: unknown): RawRecord {
  return value && typeof value === 'object' ? value as RawRecord : {};
}

function stringOf(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : value == null ? fallback : String(value);
}

function nullableNumber(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && value.trim() && Number.isFinite(Number(value))) return Number(value);
  return null;
}

function numberOr(value: unknown, fallback: number): number {
  const n = nullableNumber(value);
  return n ?? fallback;
}

function rows(value: unknown, key: string): unknown[] {
  if (Array.isArray(value)) return value;
  const raw = recordOf(value);
  return Array.isArray(raw[key]) ? raw[key] : [];
}

export function normalizeMetricsRow(value: unknown): MetricsRow {
  const raw = recordOf(value);
  return {
    ticketNo: stringOf(raw.ticket_no ?? raw.ticketNo),
    title: stringOf(raw.title),
    stage: stringOf(raw.stage),
    execTokenTotal: nullableNumber(raw.exec_token_total ?? raw.execTokenTotal),
    execTokenSource: raw.exec_token_source === 'agent_cli' || raw.exec_token_source === 'manual' || raw.exec_token_source === 'unavailable'
      ? raw.exec_token_source as MetricsRow['execTokenSource']
      : null,
    reviewTokenTotal: nullableNumber(raw.review_token_total ?? raw.reviewTokenTotal),
  };
}

export function normalizeH1Verdict(value: unknown): H1Verdict {
  const raw = recordOf(value);
  const classification = stringOf(raw.classification).toUpperCase() as H1Classification;
  return {
    firstPassRate: numberOr(raw.first_pass_rate ?? raw.firstPassRate, 0),
    costRatioMedian: numberOr(raw.cost_ratio_median ?? raw.costRatioMedian, 0),
    classification: ['PASS', 'PARTIAL', 'FAIL'].includes(classification) ? classification : 'FAIL',
    degraded: Boolean(raw.degraded),
    degradedReasons: (() => {
      const reasons = raw.degraded_reasons ?? raw.degradedReasons;
      return Array.isArray(reasons) ? reasons.map(String) : [];
    })(),
  };
}

export async function getMetrics(): Promise<MetricsRow[]> {
  const resp = await client.get<unknown>('/metrics');
  return rows(resp.data, 'records').map(normalizeMetricsRow).filter((row) => row.ticketNo);
}

export async function getMetricsH1(): Promise<H1Verdict> {
  const resp = await client.get<unknown>('/metrics/h1');
  return normalizeH1Verdict(resp.data);
}

export async function reconcile(ticketNo?: string): Promise<unknown[]> {
  const resp = await client.post<unknown>('/reconcile', ticketNo ? { ticket_no: ticketNo } : {});
  const raw = recordOf(resp.data);
  return Array.isArray(raw.outcomes) ? raw.outcomes : [];
}
