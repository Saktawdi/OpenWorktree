/**
 * stage.ts 测试 — 看板列映射 (P2 状态机驱动).
 *
 * 验证: KANBAN_COLUMNS 一列不多一列不少;
 *       stageToKanbanColumnStage 把 REJECTED 并入 IN_PROGRESS.
 */
import { describe, expect, it } from 'vitest';
import {
  KANBAN_COLUMNS,
  TICKET_STAGE_LABELS,
  stageToKanbanColumnStage,
  type TicketStage,
} from '@/types/stage';

const ALL_STAGES: TicketStage[] = [
  'PENDING',
  'IN_PROGRESS',
  'PRESUBMITTED',
  'IN_REVIEW',
  'REJECTED',
  'READY_TO_PUBLISH',
  'NEEDS_HUMAN',
  'DONE',
  'CANCELLED',
];

describe('stage.ts', () => {
  it('TICKET_STAGE_LABELS 覆盖全部 9 个 stage', () => {
    for (const s of ALL_STAGES) {
      expect(TICKET_STAGE_LABELS[s]).toBeTruthy();
    }
    expect(ALL_STAGES.length).toBe(9);
  });

  it('KANBAN_COLUMNS 不含 REJECTED 列 (REJECTED 并入 IN_PROGRESS)', () => {
    const colStages = KANBAN_COLUMNS.map((c) => c.stage);
    expect(colStages).not.toContain('REJECTED');
  });

  it('KANBAN_COLUMNS 列数 = 8 (9 stage - REJECTED = 8)', () => {
    expect(KANBAN_COLUMNS.length).toBe(8);
  });

  it('KANBAN_COLUMNS 列序正确', () => {
    const stages = KANBAN_COLUMNS.map((c) => c.stage);
    expect(stages).toEqual([
      'PENDING',
      'IN_PROGRESS',
      'PRESUBMITTED',
      'IN_REVIEW',
      'READY_TO_PUBLISH',
      'NEEDS_HUMAN',
      'DONE',
      'CANCELLED',
    ]);
  });

  it('stageToKanbanColumnStage: REJECTED → IN_PROGRESS', () => {
    expect(stageToKanbanColumnStage('REJECTED')).toBe('IN_PROGRESS');
  });

  it('stageToKanbanColumnStage: 其余 stage 一一对应', () => {
    for (const s of ALL_STAGES) {
      if (s === 'REJECTED') continue;
      expect(stageToKanbanColumnStage(s)).toBe(s);
    }
  });

  it('DONE / CANCELLED 标记为 collapsed (折叠窄列)', () => {
    const done = KANBAN_COLUMNS.find((c) => c.stage === 'DONE');
    const cancelled = KANBAN_COLUMNS.find((c) => c.stage === 'CANCELLED');
    expect(done?.collapsed).toBe(true);
    expect(cancelled?.collapsed).toBe(true);
  });
});
