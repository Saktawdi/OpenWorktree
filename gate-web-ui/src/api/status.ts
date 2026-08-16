/**
 * 项目状态 API — 对齐后端 GET /api/status (后端文档 §4.1 + gate-application/StatusResult.java).
 *
 * 后端是契约方: StatusResult record 投影 (targetRef/authTip/authCommitCount/tickets).
 * 字段 camelCase 映射 Java record 字段, 后端一变须同步本文件.
 */
import { client } from './client';
import type { TicketStage } from '@/types/stage';

/** 对齐后端 StatusResult.TicketStatus (嵌套 record). */
export interface TicketStatus {
  /** 工单号 (ticket_no, 项目内唯一, 如 PROJ-12). */
  ticketNo: string;
  /** TicketStage (见 src/types/stage.ts). */
  stage: TicketStage;
  /** 最新 review_round (无预提审记录时为 null). */
  latestRound: number | null;
  /** 最新 tree_hash (预提审固化锚点). */
  latestTreeHash: string | null;
  /** 最新意图状态 (审批记录派生). */
  latestIntentStatus: string | null;
  /** 最新 commit sha (已发布时为非空). */
  latestCommitSha: string | null;
  /** 是否已发布进 auth 库. */
  publishedInAuth: boolean;
}

/** 对齐后端 StatusResult (GateService.status 只读投影). */
export interface StatusResult {
  /** 目标分支 ref (如 refs/heads/main). */
  targetRef: string;
  /** auth 库 HEAD (tip). */
  authTip: string;
  /** auth 库提交数. */
  authCommitCount: number;
  /** 工单状态列表. */
  tickets: TicketStatus[];
}

export async function getStatus(): Promise<StatusResult> {
  const resp = await client.get<unknown>('/status');
  const raw = resp.data && typeof resp.data === 'object' ? resp.data as Record<string, unknown> : {};
  const rows = Array.isArray(raw.tickets) ? raw.tickets : [];
  const stageOf = (value: unknown): TicketStage => {
    const stage = String(value ?? '').toUpperCase() as TicketStage;
    return ['PENDING', 'IN_PROGRESS', 'PRESUBMITTED', 'IN_REVIEW', 'REJECTED', 'READY_TO_PUBLISH', 'NEEDS_HUMAN', 'DONE', 'CANCELLED'].includes(stage)
      ? stage
      : 'PENDING';
  };
  const numberOf = (value: unknown): number | null => {
    if (typeof value === 'number' && Number.isFinite(value)) return value;
    if (typeof value === 'string' && value.trim() && Number.isFinite(Number(value))) return Number(value);
    return null;
  };
  const nullableStringOf = (value: unknown): string | null => {
    if (value == null) return null;
    const result = String(value).trim();
    return result || null;
  };
  const booleanOf = (value: unknown): boolean => {
    if (typeof value === 'boolean') return value;
    if (typeof value === 'string') return value.trim().toLowerCase() === 'true';
    if (typeof value === 'number') return value !== 0;
    return false;
  };
  return {
    targetRef: String(raw.target_ref ?? raw.targetRef ?? ''),
    authTip: String(raw.auth_tip ?? raw.authTip ?? ''),
    authCommitCount: numberOf(raw.auth_commit_count ?? raw.authCommitCount) ?? 0,
    tickets: rows.map((value) => {
      const ticket = value && typeof value === 'object' ? value as Record<string, unknown> : {};
      return {
        ticketNo: String(ticket.ticket_no ?? ticket.ticketNo ?? ''),
        stage: stageOf(ticket.stage),
        latestRound: numberOf(ticket.latest_round ?? ticket.latestRound),
        latestTreeHash: nullableStringOf(ticket.latest_tree_hash ?? ticket.latestTreeHash),
        latestIntentStatus: nullableStringOf(ticket.latest_intent_status ?? ticket.latestIntentStatus),
        latestCommitSha: nullableStringOf(ticket.latest_commit_sha ?? ticket.latestCommitSha),
        publishedInAuth: booleanOf(ticket.published_in_auth ?? ticket.publishedInAuth),
      };
    }).filter((ticket) => ticket.ticketNo),
  };
}
