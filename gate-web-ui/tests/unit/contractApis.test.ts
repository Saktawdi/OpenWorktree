/**
 * 第一阶段接口契约联调测试：新增/改造的 API 层必须把后端 snake_case 映射为前端 camelCase，
 * 并且请求体使用后端契约字段。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { client } from '@/api/client';
import { getHistory, listTicketSessions, sendMessage, startSession } from '@/api/sessions';
import { startPublish, startReview } from '@/api/review';
import { getTask, normalizeTask } from '@/api/tasks';
import { listAgentConfigs } from '@/api/agentConfig';
import { getMetrics } from '@/api/metrics';
import { getPresubmitDiff, getReviewResult, presubmitTicket } from '@/api/tickets';

describe('contract API mappers', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('sessions list/history map snake_case envelopes to camelCase', async () => {
    vi.spyOn(client, 'get')
      .mockResolvedValueOnce({
        data: {
          sessions: [{
            id: 's1',
            ticket_no: 'T-1',
            agent_config_id: 'cfg-1',
            cli: 'OPENCODE',
            status: 'ACTIVE',
            cli_session_id: null,
            clone_path: '/tmp/T-1',
            allocated_port: 51000,
            started_at: '2026-08-16T00:00:00Z',
            finished_at: null,
            cumulative_usage: { prompt_tokens: 1, completion_tokens: 2, total_tokens: 3 },
          }],
        },
      } as never)
      .mockResolvedValueOnce({
        data: {
          messages: [{
            id: 'm1',
            session_id: 's1',
            role: 'ASSISTANT',
            content: 'hi',
            tool_calls: [{ name: 'read', arguments_json: '{}', result_json: null }],
            usage: { prompt_tokens: 1, completion_tokens: 2, total_tokens: 3 },
            degraded: false,
            timestamp: '2026-08-16T00:00:01Z',
          }],
        },
      } as never);

    const sessions = await listTicketSessions('T-1');
    expect(sessions[0]).toMatchObject({
      id: 's1',
      ticketNo: 'T-1',
      agentConfigId: 'cfg-1',
      cli: 'OPENCODE',
      allocatedPort: 51000,
      cumulativeUsage: { promptTokens: 1, completionTokens: 2, totalTokens: 3 },
    });

    const history = await getHistory('s1');
    expect(history[0]).toMatchObject({
      id: 'm1',
      sessionId: 's1',
      role: 'ASSISTANT',
      toolCalls: [{ name: 'read', argumentsJson: '{}', resultJson: null }],
      usage: { promptTokens: 1, completionTokens: 2, totalTokens: 3 },
    });
  });

  it('sessions create/send use backend snake_case payload and task_id mapping', async () => {
    const post = vi.spyOn(client, 'post')
      .mockResolvedValueOnce({ data: { id: 's2', ticket_no: 'T-1', agent_config_id: 'cfg-1', cli: 'CLAUDE', status: 'ACTIVE', cli_session_id: null, clone_path: '/tmp/T-1', allocated_port: -1, started_at: '2026-08-16T00:00:00Z', finished_at: null, cumulative_usage: null } } as never)
      .mockResolvedValueOnce({ data: { task_id: 'task-1' } } as never);

    await startSession('T-1', { agentConfigId: 'cfg-1', initialPrompt: 'go' });
    expect(post).toHaveBeenNthCalledWith(1, '/tickets/T-1/sessions', {
      agent_config_id: 'cfg-1',
      initial_prompt: 'go',
    });

    const accepted = await sendMessage('s2', 'hello');
    expect(post).toHaveBeenNthCalledWith(2, '/sessions/s2/messages', { message: 'hello' });
    expect(accepted).toEqual({ taskId: 'task-1' });
  });

  it('review/publish map task_id and send backend fields', async () => {
    const post = vi.spyOn(client, 'post')
      .mockResolvedValueOnce({ data: { task_id: 'review-task' } } as never)
      .mockResolvedValueOnce({ data: { task_id: 'publish-task' } } as never);

    await startReview('T-1', { round: 2, humanPass: true, note: 'ok' });
    expect(post).toHaveBeenNthCalledWith(1, '/tickets/T-1/review', {
      round: 2,
      human_pass: true,
      note: 'ok',
    });

    await startPublish('T-1', { round: 2 });
    expect(post).toHaveBeenNthCalledWith(2, '/tickets/T-1/publish', { round: 2 });
  });

  it('tasks map snake_case GateTask and keep status string', async () => {
    vi.spyOn(client, 'get').mockResolvedValue({
      data: {
        id: 't1',
        type: 'review',
        ticket_no: 'T-1',
        session_id: null,
        status: 'SUCCEEDED',
        started_at: '2026-08-16T00:00:00Z',
        finished_at: '2026-08-16T00:00:01Z',
        result_json: '{"verdict":"PASS"}',
        error_json: null,
      },
    } as never);

    const task = await getTask('t1');
    expect(task).toMatchObject({
      id: 't1',
      ticketNo: 'T-1',
      status: 'succeeded',
      resultJson: '{"verdict":"PASS"}',
    });
    expect(normalizeTask({ id: 't2', type: 'publish', status: 'RUNNING' }).type).toBe('publish');
  });

  it('agent-configs list maps snake_case fields', async () => {
    vi.spyOn(client, 'get').mockResolvedValue({
      data: {
        agent_configs: [{
          id: 'cfg-1',
          name: 'Claude',
          cli: 'CLAUDE',
          provider_id: 'p1',
          model: 'm1',
          system_prompt: 'prompt',
          extra_flags: ['--x'],
          description: 'desc',
        }],
      },
    } as never);

    const configs = await listAgentConfigs();
    expect(configs[0]).toMatchObject({
      id: 'cfg-1',
      providerId: 'p1',
      systemPrompt: 'prompt',
      extraFlags: ['--x'],
    });
  });

  it('metrics and presubmit/review-result endpoints map snake_case', async () => {
    const get = vi.spyOn(client, 'get')
      .mockResolvedValueOnce({
        data: {
          records: [{
            ticket_no: 'T-1',
            title: 't',
            stage: 'DONE',
            exec_token_total: 10,
            exec_token_source: 'agent_cli',
            review_token_total: 2,
          }],
        },
      } as never)
      .mockResolvedValueOnce({
        data: {
          ticket_no: 'T-1',
          review_round: 1,
          tree_hash: 'abc',
          base_commit: 'def',
          diff: '--- a\n+++ b\n',
        },
      } as never)
      .mockResolvedValueOnce({
        data: {
          ticket_no: 'T-1',
          review_round: 1,
          verdict: 'REJECT',
          engine_id: 'manual',
          covered_ok: false,
          degraded: true,
          findings: 'BLOCKER: nope',
        },
      } as never);

    const metrics = await getMetrics();
    expect(metrics[0]).toMatchObject({
      ticketNo: 'T-1',
      execTokenTotal: 10,
      execTokenSource: 'agent_cli',
      reviewTokenTotal: 2,
    });

    const diff = await getPresubmitDiff('T-1', 1);
    expect(diff).toMatchObject({ reviewRound: 1, treeHash: 'abc', diff: '--- a\n+++ b\n' });

    const review = await getReviewResult('T-1');
    expect(review).toMatchObject({ verdict: 'REJECT', degraded: true, findings: 'BLOCKER: nope' });

    expect(get).toHaveBeenCalledWith('/metrics');
    expect(get).toHaveBeenCalledWith('/tickets/T-1/presubmit/1/diff');
    expect(get).toHaveBeenCalledWith('/tickets/T-1/review-result');
  });

  it('presubmitTicket posts and maps snake_case result', async () => {
    vi.spyOn(client, 'post').mockResolvedValue({
      data: {
        ticket_no: 'T-1',
        review_round: 1,
        tree_hash: 'abc',
        base_commit: 'def',
        target_ref: 'refs/heads/main',
        diff_bytes: 12,
        changed_paths: ['a.txt'],
        integrity_warnings: ['rule:detail'],
      },
    } as never);

    const result = await presubmitTicket('T-1');
    expect(client.post).toHaveBeenCalledWith('/tickets/T-1/presubmit', {});
    expect(result).toMatchObject({
      reviewRound: 1,
      treeHash: 'abc',
      changedPaths: ['a.txt'],
      integrityWarnings: ['rule:detail'],
    });
  });
});
