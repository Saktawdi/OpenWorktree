import { beforeEach, describe, expect, it, vi } from 'vitest';
import { client } from '@/api/client';
import { createTicket, listTickets, normalizeTicket } from '@/api/tickets';

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
});
