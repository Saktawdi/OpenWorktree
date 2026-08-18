import type { TicketStage } from '@/types/stage';

/** 工单阶段 → 中文标签（控制台展示用，真实数据来自后端 stage 字段）。 */
export const stageLabels: Record<TicketStage, string> = {
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
