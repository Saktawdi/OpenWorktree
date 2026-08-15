/**
 * 项目状态 API — 对齐后端 GET /api/status (后端文档 §4.1 + gate-application/StatusResult.java).
 *
 * 后端是契约方: StatusResult record 投影 (targetRef/authTip/authCommitCount/tickets).
 * 字段 camelCase 映射 Java record 字段, 后端一变须同步本文件.
 */
import { client } from './client';

/** 对齐后端 StatusResult.TicketStatus (嵌套 record). */
export interface TicketStatus {
  /** 工单号 (ticket_no, 项目内唯一, 如 PROJ-12). */
  ticketNo: string;
  /** TicketStage (见 src/types/stage.ts). */
  stage: string;
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
  const resp = await client.get<StatusResult>('/status');
  return resp.data;
}
