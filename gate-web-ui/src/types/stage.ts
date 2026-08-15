/**
 * 工单阶段 — 对齐后端 TicketStage.
 *
 * 看板列直接映射本枚举, 一列不多一列不少 (P2 状态机驱动).
 * 注意: REJECTED 不单独成列, 回流到 IN_PROGRESS 列并标红 (前端 §4.2).
 */
export type TicketStage =
  | 'PENDING'
  | 'IN_PROGRESS'
  | 'PRESUBMITTED'
  | 'IN_REVIEW'
  | 'REJECTED'
  | 'READY_TO_PUBLISH'
  | 'NEEDS_HUMAN'
  | 'DONE'
  | 'CANCELLED';

/** 看板列定义: 列序即渲染顺序. REJECTED 单独列出但渲染时合并进 IN_PROGRESS 列 (见 ticketKanbanStageColumns). */
export interface KanbanColumn {
  stage: TicketStage;
  label: string;
  /** 是否为终态列 (折叠为窄列). */
  collapsed?: boolean;
}

/** 全部 TicketStage 对应的中文看板列名 (一列不多一列不少, P2). */
export const TICKET_STAGE_LABELS: Readonly<Record<TicketStage, string>> = {
  PENDING: '待开始',
  IN_PROGRESS: '进行中',
  PRESUBMITTED: '已预提审',
  IN_REVIEW: '审核中',
  REJECTED: '已驳回',
  READY_TO_PUBLISH: '可发布',
  NEEDS_HUMAN: '待人工',
  DONE: '已完成',
  CANCELLED: '已取消',
};

/**
 * 看板列序 (P2 状态机驱动, 一列不多一列不少).
 * REJECTED 不单独成列: 渲染时, stage=REJECTED 的工单并入 IN_PROGRESS 列并标红.
 */
export const KANBAN_COLUMNS: ReadonlyArray<KanbanColumn> = [
  { stage: 'PENDING', label: '待开始' },
  { stage: 'IN_PROGRESS', label: '进行中' },
  { stage: 'PRESUBMITTED', label: '已预提审' },
  { stage: 'IN_REVIEW', label: '审核中' },
  { stage: 'READY_TO_PUBLISH', label: '可发布' },
  { stage: 'NEEDS_HUMAN', label: '待人工' },
  { stage: 'DONE', label: '已完成', collapsed: true },
  { stage: 'CANCELLED', label: '已取消', collapsed: true },
];

/**
 * 给定一个工单 stage, 返回它应该落到看板的哪一列的 stage.
 * (REJECTED → IN_PROGRESS, 其余一一对应)
 */
export function stageToKanbanColumnStage(stage: TicketStage): TicketStage {
  return stage === 'REJECTED' ? 'IN_PROGRESS' : stage;
}
