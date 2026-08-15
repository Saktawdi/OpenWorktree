/**
 * MSW handlers — 假后端 mock.
 *
 * 覆盖 S0 端点 (后端文档 §4.1):
 *   - POST /api/auth/verify
 *   - GET  /api/health
 *   - GET  /api/status
 *
 * Bearer 校验: 任何非 /api/auth/verify / /api/health 的 /api/* 请求,
 *              Authorization 头缺失或不等于 MOCK_TOKEN → 401 (模拟后端 Filter).
 *
 * 注意: MSW 不能 mock EventSource (浏览器原生 API, 不走 fetch).
 *       SSE 走单独的 Node server (mocks/sse-server.mjs on 127.0.0.1:4098),
 *       vite.config.ts 的 SSE proxy target 指向它.
 */
import { http, HttpResponse, delay, type JsonBodyType } from 'msw';

const MOCK_TOKEN = 'mock-human-token-1234567890abcdef';

/** 校验 Bearer token; 返回 401 响应或 null (通过). */
function checkAuth(request: Request): HttpResponse<JsonBodyType> | null {
  const auth = request.headers.get('authorization');
  if (auth !== `Bearer ${MOCK_TOKEN}`) {
    return HttpResponse.json<JsonBodyType>(
      {
        error_code: 64,
        error: 'USAGE',
        message: '未授权: Bearer token 缺失或不匹配',
        detail: [],
      },
      { status: 401 },
    );
  }
  return null;
}

export const handlers = [
  // --- POST /api/auth/verify ---
  http.post('/api/auth/verify', async ({ request }) => {
    const auth = request.headers.get('authorization');
    if (auth === `Bearer ${MOCK_TOKEN}`) {
      return HttpResponse.json({ ok: true, tokenDigest: MOCK_TOKEN.slice(0, 6) + '…' });
    }
    return HttpResponse.json(
      {
        error_code: 64,
        error: 'USAGE',
        message: 'token 无效或已撤销',
        detail: [],
      },
      { status: 401 },
    );
  }),

  // --- GET /api/health (免 token, 后端文档 §3.4) ---
  http.get('/api/health', async () => {
    await delay(50);
    return HttpResponse.json({ status: 'ok' });
  }),

  // --- GET /api/status (需 token) — 对齐后端 StatusResult (gate-application/StatusResult.java) ---
  http.get('/api/status', ({ request }) => {
    const unauth = checkAuth(request);
    if (unauth) return unauth;
    return HttpResponse.json({
      targetRef: 'refs/heads/main',
      authTip: 'a1b2c3d4e5f6',
      authCommitCount: 42,
      tickets: [
        { ticketNo: 'PROJ-1', stage: 'DONE', latestRound: 1, latestTreeHash: '1111111', latestIntentStatus: 'approved', latestCommitSha: 'a1b2c3d4e5f6', publishedInAuth: true },
        { ticketNo: 'PROJ-2', stage: 'DONE', latestRound: 1, latestTreeHash: '2222222', latestIntentStatus: 'approved', latestCommitSha: 'b2c3d4e5f6a7', publishedInAuth: true },
        { ticketNo: 'PROJ-3', stage: 'DONE', latestRound: 2, latestTreeHash: '3333333', latestIntentStatus: 'approved', latestCommitSha: 'c3d4e5f6a7b8', publishedInAuth: true },
        { ticketNo: 'PROJ-4', stage: 'DONE', latestRound: 1, latestTreeHash: '4444444', latestIntentStatus: 'approved', latestCommitSha: 'd4e5f6a7b8c9', publishedInAuth: true },
        { ticketNo: 'PROJ-5', stage: 'DONE', latestRound: 1, latestTreeHash: '5555555', latestIntentStatus: 'approved', latestCommitSha: 'e5f6a7b8c9d0', publishedInAuth: true },
        { ticketNo: 'PROJ-6', stage: 'PENDING', latestRound: null, latestTreeHash: null, latestIntentStatus: null, latestCommitSha: null, publishedInAuth: false },
        { ticketNo: 'PROJ-7', stage: 'PENDING', latestRound: null, latestTreeHash: null, latestIntentStatus: null, latestCommitSha: null, publishedInAuth: false },
        { ticketNo: 'PROJ-8', stage: 'IN_PROGRESS', latestRound: 1, latestTreeHash: '8888888', latestIntentStatus: 'rejected', latestCommitSha: null, publishedInAuth: false },
        { ticketNo: 'PROJ-9', stage: 'PRESUBMITTED', latestRound: 2, latestTreeHash: '9999999', latestIntentStatus: 'pending', latestCommitSha: null, publishedInAuth: false },
        { ticketNo: 'PROJ-10', stage: 'IN_REVIEW', latestRound: 2, latestTreeHash: 'aaaaaaa', latestIntentStatus: 'pending', latestCommitSha: null, publishedInAuth: false },
        { ticketNo: 'PROJ-11', stage: 'READY_TO_PUBLISH', latestRound: 1, latestTreeHash: 'bbbbbbb', latestIntentStatus: 'approved', latestCommitSha: null, publishedInAuth: false },
        { ticketNo: 'PROJ-12', stage: 'NEEDS_HUMAN', latestRound: 1, latestTreeHash: 'ccccccc', latestIntentStatus: 'requires_human', latestCommitSha: null, publishedInAuth: false },
      ],
    });
  }),

  // --- GET /api/tickets (需 token) — 对齐后端 TicketRepository.findAll (S1 前瞻) ---
  http.get('/api/tickets', ({ request }) => {
    const unauth = checkAuth(request);
    if (unauth) return unauth;
    return HttpResponse.json([
      { no: 'PROJ-1', title: '接入 prism 审核引擎', stage: 'DONE', targetRef: 'refs/heads/main', reviewRound: 1, treeHash: '1111111', baseCommit: '0000000', execTokenTotal: null, execTokenSource: 'unavailable', agentConfigId: null, createdAt: '2026-08-01T09:00:00Z', updatedAt: '2026-08-01T10:00:00Z' },
      { no: 'PROJ-8', title: '修复资源泄漏 (驳回回喂中)', stage: 'IN_PROGRESS', targetRef: 'refs/heads/main', reviewRound: 1, treeHash: '8888888', baseCommit: 'a1b2c3d4e5f6', execTokenTotal: null, execTokenSource: 'unavailable', agentConfigId: null, createdAt: '2026-08-03T09:00:00Z', updatedAt: '2026-08-04T09:00:00Z' },
      { no: 'PROJ-10', title: '会话编排 Web 接入', stage: 'IN_REVIEW', targetRef: 'refs/heads/main', reviewRound: 2, treeHash: 'aaaaaaa', baseCommit: 'a1b2c3d4e5f6', execTokenTotal: null, execTokenSource: 'unavailable', agentConfigId: null, createdAt: '2026-08-05T09:00:00Z', updatedAt: '2026-08-06T09:00:00Z' },
    ]);
  }),
];

export { MOCK_TOKEN };
