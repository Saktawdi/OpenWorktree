import { beforeEach, describe, expect, it, vi } from 'vitest';
import { client } from '@/api/client';
import { createTicket, listTickets, normalizeTicket, updateTicket } from '@/api/tickets';

describe('tickets API normalization', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('maps the backend ticket envelope and snake_case fields', async () => {
    vi.spyOn(client, 'get').mockResolvedValue({
      data: {
        tickets: [{
          ticket_no: 'GATE-7',
          title: '真实工单',
          target_ref: 'refs/heads/main',
          clone_path: 'C:/clones/GATE-7',
          stage: 'IN_REVIEW',
          review_round: '2',
          exec_token_total: 128,
          exec_token_source: 'agent_cli',
          created_at: '2026-08-15T09:00:00Z',
          updated_at: '2026-08-15T10:00:00Z',
          priority: 'P1',
        }],
      },
    } as never);

    const rows = await listTickets();

    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({
      no: 'GATE-7',
      targetRef: 'refs/heads/main',
      clonePath: 'C:/clones/GATE-7',
      reviewRound: 2,
      execTokenTotal: 128,
      execTokenSource: 'agent_cli',
      priority: 'P1',
    });
  });

  it('normalizes unknown stages to PENDING and drops blank ticket numbers', () => {
    expect(normalizeTicket({ ticket_no: '', stage: 'UNKNOWN' }).no).toBe('');
    expect(normalizeTicket({ ticket_no: 'GATE-8', stage: 'UNKNOWN' }).stage).toBe('PENDING');
  });

  it('sends the backend create payload instead of the frontend camelCase shape', async () => {
    const post = vi.spyOn(client, 'post').mockResolvedValue({
      data: { ticket_no: 'GATE-9', title: '新工单', target_ref: 'refs/heads/main', stage: 'IN_PROGRESS' },
    } as never);

    await createTicket({ ticketNo: 'GATE-9', title: '新工单', agentConfigId: 'manual' });

    expect(post).toHaveBeenCalledWith('/tickets', {
      ticket_no: 'GATE-9',
      title: '新工单',
      agent_config_id: 'manual',
    });
  });

  it('uses the project-scoped endpoint for a project board', async () => {
    const get = vi.spyOn(client, 'get').mockResolvedValue({
      data: { tickets: [{ ticket_no: 'ALPHA-1', title: '项目工单', stage: 'IN_PROGRESS' }] },
    } as never);

    await listTickets('alpha');

    expect(get).toHaveBeenCalledWith('/projects/alpha/tickets');
  });

  it('binds creation to the current project board', async () => {
    const post = vi.spyOn(client, 'post').mockResolvedValue({
      data: { ticket_no: 'ALPHA-2', title: '项目工单', stage: 'IN_PROGRESS', project_id: 'alpha' },
    } as never);

    await createTicket({ ticketNo: 'ALPHA-2', title: '项目工单', projectId: 'alpha' });

    expect(post).toHaveBeenCalledWith('/projects/alpha/tickets', {
      ticket_no: 'ALPHA-2',
      title: '项目工单',
      project_id: 'alpha',
    });
  });

  it('uses the project scope when moving a ticket from the board', async () => {
    const patch = vi.spyOn(client, 'patch').mockResolvedValue({
      data: { ticket_no: 'ALPHA-1', title: '项目工单', stage: 'CANCELLED', project_id: 'alpha' },
    } as never);

    await updateTicket('ALPHA-1', { stage: 'CANCELLED' }, 'alpha');

    expect(patch).toHaveBeenCalledWith('/projects/alpha/tickets/ALPHA-1', { stage: 'CANCELLED' });
  });

  it('round-trips editable ticket content through the project PATCH endpoint', async () => {
    const patch = vi.spyOn(client, 'patch').mockResolvedValue({
      data: {
        ticket_no: 'ALPHA-1',
        title: '更新后的标题',
        description: '验收标准',
        note: '和后端保持一致',
        labels: ['前端', '体验'],
        stage: 'IN_PROGRESS',
        project_id: 'alpha',
      },
    } as never);

    const updated = await updateTicket('ALPHA-1', {
      title: '更新后的标题',
      description: '验收标准',
      note: '和后端保持一致',
      labels: ['前端', '体验'],
    }, 'alpha');

    expect(patch).toHaveBeenCalledWith('/projects/alpha/tickets/ALPHA-1', {
      title: '更新后的标题',
      description: '验收标准',
      note: '和后端保持一致',
      labels: ['前端', '体验'],
    });
    expect(updated.description).toBe('验收标准');
    expect(updated.note).toBe('和后端保持一致');
    expect(updated.labels).toEqual(['前端', '体验']);
  });
});
