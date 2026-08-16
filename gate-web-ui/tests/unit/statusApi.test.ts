import { beforeEach, describe, expect, it, vi } from 'vitest';
import { client } from '@/api/client';
import { getStatus } from '@/api/status';

describe('status API normalization', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('maps the backend status projection and preserves null snapshot fields', async () => {
    vi.spyOn(client, 'get').mockResolvedValue({
      data: {
        target_ref: 'refs/heads/main',
        auth_tip: 'abc123',
        auth_commit_count: '12',
        tickets: [{
          ticket_no: 'GATE-1',
          stage: 'READY_TO_PUBLISH',
          latest_round: 2,
          latest_tree_hash: '',
          latest_intent_status: null,
          latest_commit_sha: 'def456',
          published_in_auth: 'false',
        }],
      },
    } as never);

    const result = await getStatus();

    expect(result).toMatchObject({
      targetRef: 'refs/heads/main',
      authTip: 'abc123',
      authCommitCount: 12,
    });
    expect(result.tickets[0]).toEqual({
      ticketNo: 'GATE-1',
      stage: 'READY_TO_PUBLISH',
      latestRound: 2,
      latestTreeHash: null,
      latestIntentStatus: null,
      latestCommitSha: 'def456',
      publishedInAuth: false,
    });
  });

  it('uses PENDING for an invalid stage and ignores ticket rows without numbers', async () => {
    vi.spyOn(client, 'get').mockResolvedValue({
      data: { tickets: [{ ticket_no: 'GATE-2', stage: 'not-a-stage' }, { stage: 'DONE' }] },
    } as never);

    const result = await getStatus();

    expect(result.tickets).toEqual([expect.objectContaining({ ticketNo: 'GATE-2', stage: 'PENDING' })]);
  });
});
