/**
 * client.ts 测试 — 请求拦截器注入 Bearer + 响应拦截器 401 跳登录.
 *
 * 用 msw/node 起真 mock server, client 发真请求, 验证拦截器行为.
 *
 * 注意: 401 测试不能让 client.ts 默认 onAuthError 跳 router (router 还未初始化),
 *       用 setClientHandlers 覆盖为 fn 验证调用即可.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { client, setClientHandlers } from '@/api/client';
import { resetLocalStorage } from '../utils';

const MOCK_TOKEN = 'mock-human-token-1234567890abcdef';

const server = setupServer(
  http.get('/api/status', ({ request }) => {
    const auth = request.headers.get('authorization');
    if (auth !== `Bearer ${MOCK_TOKEN}`) {
      return HttpResponse.json(
        { error_code: 64, error: 'USAGE', message: '未授权', detail: [] },
        { status: 401 },
      );
    }
    return HttpResponse.json({
      project: 'demo',
      authRepoPath: '/tmp/demo/auth.git',
    });
  }),
  http.get('/api/health', () => HttpResponse.json({ status: 'ok' })),
);

beforeEach(() => {
  resetLocalStorage();
  server.listen({ onUnhandledRequest: 'error' });
});

afterEach(() => {
  server.resetHandlers();
  server.close();
});

describe('client.ts', () => {
  it('请求拦截器: localStorage 有 token 时注入 Authorization 头', async () => {
    localStorage.setItem('gate_token', MOCK_TOKEN);
    const resp = await client.get('/status');
    expect(resp.data.project).toBe('demo');
  });

  it('请求拦截器: 无 token 时 401 (后端校验失败)', async () => {
    await expect(client.get('/status')).rejects.toMatchObject({
      response: { status: 401 },
    });
  });

  it('响应拦截器: 401 触发 onAuthError + 清 localStorage', async () => {
    localStorage.setItem('gate_token', 'wrong-token');
    const onAuthError = vi.fn();
    setClientHandlers({ onAuthError });

    await expect(client.get('/status')).rejects.toMatchObject({
      response: { status: 401 },
    });

    expect(onAuthError).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem('gate_token')).toBeNull();
  });
});
