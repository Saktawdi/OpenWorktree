/**
 * 工单 — 对齐后端 ticket 表 (V1__init.sql + V3__cost_metrics.sql + V4 agent_config_id).
 *
 * 字段命名 camelCase 映射列名, 以后端为准 (前端文档 §2.4 B 方案: 手写 types).
 * 后端字段一变须同步本文件.
 */
import type { TicketStage } from './stage';

/** exec_token_source 枚举 (V3): agent_cli | manual | unavailable. */
export type ExecTokenSource = 'agent_cli' | 'manual' | 'unavailable';

export interface Ticket {
  /** ticket_no: 项目内唯一, 如 PROJ-12. */
  no: string;
  title: string;
  /** stage: 见 src/types/stage.ts 状态机. */
  stage: TicketStage;
  /** target_ref: 目标分支 ref (如 refs/heads/main). */
  targetRef: string;
  /** 最新 review_round — 派生自 presubmit 记录, 无预提审时为 null. */
  reviewRound: number | null;
  /** 最新 tree_hash — 预提审固化锚点 (派生自 presubmit). */
  treeHash: string | null;
  /** 最新 base_commit — 预提审基线 (派生自 presubmit). */
  baseCommit: string | null;
  /** exec_token_total (V3): 执行侧累计 token, 通常 null (agent CLI 未上报). */
  execTokenTotal: number | null;
  /** exec_token_source (V3): agent_cli | manual | unavailable. */
  execTokenSource: ExecTokenSource | null;
  /** agent_config_id (V4): 工单创建时指定的 AgentConfig; null = 系统默认. */
  agentConfigId: string | null;
  createdAt: string;
  updatedAt: string;
  /** 以下为前端展示增强字段（原型阶段本地补充，后端未强制） */
  project?: string;
  dependencies?: string[];
  labels?: string[];
  priority?: 'P0' | 'P1' | 'P2' | 'P3';
  commentCount?: number;
  branch?: string;
}

/** 建工单请求 (后端文档 §4.2, POST /api/tickets body; description 为 agent 输入). */
export interface CreateTicketRequest {
  title: string;
  description: string;
  targetRef: string;
  agentConfigId?: string;
}
