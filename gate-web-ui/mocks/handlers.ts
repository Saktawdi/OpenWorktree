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

const mockProjects = [
  {
    id: 'proj-alpha',
    name: '核心网关',
    workspace_path: 'D:/project/ai-generate/local-git-ticket-system/gate-web',
    target_ref: 'refs/heads/main',
    auth_repo: 'gate',
    priority: 'P1',
    size: 'medium',
    tags: ['核心服务', '智能体协作'],
    ticket_count: 2,
    active_ticket_count: 1,
    created_at: '2026-08-01T09:00:00Z',
    updated_at: '2026-08-04T09:00:00Z',
  },
  {
    id: 'proj-beta',
    name: '智能工作台',
    workspace_path: 'D:/project/ai-generate/local-git-ticket-system/gate-web-ui',
    target_ref: 'refs/heads/main',
    auth_repo: 'gate-web-ui',
    priority: 'P2',
    size: 'small',
    tags: ['控制台'],
    ticket_count: 1,
    active_ticket_count: 1,
    created_at: '2026-08-05T09:00:00Z',
    updated_at: '2026-08-06T09:00:00Z',
  },
  {
    id: 'proj-docs',
    name: '文档站',
    workspace_path: 'D:/project/ai-generate/local-git-ticket-system/doc',
    target_ref: 'refs/heads/main',
    auth_repo: 'gate-docs',
    priority: null,
    size: null,
    tags: ['文档'],
    ticket_count: 0,
    active_ticket_count: 0,
    created_at: '2026-08-07T09:00:00Z',
    updated_at: '2026-08-07T09:00:00Z',
  },
];

type MockTicket = {
  no: string;
  title: string;
  description?: string | null;
  note?: string | null;
  labels?: string[];
  stage: string;
  targetRef: string;
  reviewRound: number | null;
  treeHash: string | null;
  baseCommit: string | null;
  execTokenTotal: number | null;
  execTokenSource: string;
  agentConfigId: string | null;
  priority: string | null;
  projectId: string;
  project: string;
  createdAt: string;
  updatedAt: string;
};

const mockTickets: MockTicket[] = [
  { no: 'PROJ-1', title: '接入审核引擎', stage: 'DONE', targetRef: 'refs/heads/main', reviewRound: 1, treeHash: '1111111', baseCommit: '0000000', execTokenTotal: null, execTokenSource: 'unavailable', agentConfigId: null, priority: 'P2', projectId: 'proj-alpha', project: '核心网关', createdAt: '2026-08-01T09:00:00Z', updatedAt: '2026-08-01T10:00:00Z' },
  { no: 'PROJ-8', title: '修复资源泄漏（驳回回喂中）', stage: 'IN_PROGRESS', targetRef: 'refs/heads/main', reviewRound: 1, treeHash: '8888888', baseCommit: 'a1b2c3d4e5f6', execTokenTotal: null, execTokenSource: 'unavailable', agentConfigId: null, priority: 'P0', projectId: 'proj-alpha', project: 'Alpha 网关', createdAt: '2026-08-03T09:00:00Z', updatedAt: '2026-08-04T09:00:00Z' },
  { no: 'PROJ-10', title: '会话编排网页端接入', stage: 'IN_REVIEW', targetRef: 'refs/heads/main', reviewRound: 2, treeHash: 'aaaaaaa', baseCommit: 'a1b2c3d4e5f6', execTokenTotal: null, execTokenSource: 'unavailable', agentConfigId: null, priority: 'P1', projectId: 'proj-beta', project: '智能工作台', createdAt: '2026-08-05T09:00:00Z', updatedAt: '2026-08-06T09:00:00Z' },
];

type MockAgentConfig = {
  id: string;
  name: string;
  cli: string;
  provider_id: string | null;
  model: string | null;
  system_prompt: string | null;
  extra_flags: string[];
  description: string | null;
};

const mockAgentConfigs: MockAgentConfig[] = [
  {
    id: 'opencode-default',
    name: 'OpenCode 默认',
    cli: 'OPENCODE',
    provider_id: null,
    model: null,
    system_prompt: '在工单 clone 中完成任务，并通过 Gate MCP 提交预提审。',
    extra_flags: [],
    description: '使用 OpenCode 自己的供应商和默认模型。',
  },
];

const mockProviders = [
  {
    id: 'newapi',
    name: 'New API',
    base_url: 'http://127.0.0.1:3000/v1',
    type: 'openai-compatible',
    credential_configured: true,
    model_count: 2,
    models: ['gpt-5.2-codex', 'claude-sonnet-4-5'],
  },
];

function workspaceView(path = 'D:/project/ai-generate/local-git-ticket-system') {
  const normalized = path.replaceAll('\\', '/').replace(/\/$/, '');
  const slash = normalized.lastIndexOf('/');
  return {
    path: normalized,
    parent: slash > 2 ? normalized.slice(0, slash) : null,
    exists: true,
    roots: [
      { name: 'C:/', path: 'C:/' },
      { name: 'D:/', path: 'D:/' },
    ],
    directories: [
      { name: 'gate-web', path: `${normalized}/gate-web`, is_git_repo: true, is_registered_project: false },
      { name: 'gate-web-ui', path: `${normalized}/gate-web-ui`, is_git_repo: true, is_registered_project: true },
      { name: 'doc', path: `${normalized}/doc`, is_git_repo: false, is_registered_project: true },
    ],
  };
}

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

  http.get('/api/config', ({ request }) => {
    const unauth = checkAuth(request);
    if (unauth) return unauth;
    return HttpResponse.json({
      project: 'GATE',
      auth_repo: 'gate',
      clones_root: 'D:/project/ai-generate/local-git-ticket-system/clones',
      target_ref_whitelist: ['refs/heads/main'],
      gate_home: 'D:/project/ai-generate/local-git-ticket-system',
      engine_configured: true,
      web: { bind: '127.0.0.1', port: 4097, allowed_origins: ['http://127.0.0.1:5173'] },
    });
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
        { ticketNo: 'PROJ-8', stage: mockTickets.find((ticket) => ticket.no === 'PROJ-8')?.stage ?? 'IN_PROGRESS', latestRound: 1, latestTreeHash: '8888888', latestIntentStatus: 'rejected', latestCommitSha: null, publishedInAuth: false },
        { ticketNo: 'PROJ-9', stage: 'PRESUBMITTED', latestRound: 2, latestTreeHash: '9999999', latestIntentStatus: 'pending', latestCommitSha: null, publishedInAuth: false },
        { ticketNo: 'PROJ-10', stage: mockTickets.find((ticket) => ticket.no === 'PROJ-10')?.stage ?? 'IN_REVIEW', latestRound: 2, latestTreeHash: 'aaaaaaa', latestIntentStatus: 'pending', latestCommitSha: null, publishedInAuth: false },
        { ticketNo: 'PROJ-11', stage: 'READY_TO_PUBLISH', latestRound: 1, latestTreeHash: 'bbbbbbb', latestIntentStatus: 'approved', latestCommitSha: null, publishedInAuth: false },
        { ticketNo: 'PROJ-12', stage: 'NEEDS_HUMAN', latestRound: 1, latestTreeHash: 'ccccccc', latestIntentStatus: 'requires_human', latestCommitSha: null, publishedInAuth: false },
      ],
    });
  }),

  // --- GET /api/tickets (需 token) — 对齐后端 ticketJson 投影 (priority/project 见 V5) ---
  http.get('/api/tickets', ({ request }) => {
    const unauth = checkAuth(request);
    if (unauth) return unauth;
    return HttpResponse.json(mockTickets);
  }),

  // --- V5 项目与工作区 mock，保证本地原型可以走完整的创建/编辑/进入看板路径 ---
  http.get('/api/workspaces', ({ request }) => {
    const unauth = checkAuth(request);
    if (unauth) return unauth;
    return HttpResponse.json(workspaceView());
  }),

  http.post('/api/workspaces', async ({ request }) => {
    const unauth = checkAuth(request);
    if (unauth) return unauth;
    const body = await request.json() as { path?: unknown };
    return HttpResponse.json(workspaceView(typeof body.path === 'string' ? body.path : undefined));
  }),

  http.get('/api/projects', ({ request }) => {
    const unauth = checkAuth(request);
    if (unauth) return unauth;
    return HttpResponse.json({ projects: mockProjects.map((project) => ({
      ...project,
      ticket_count: mockTickets.filter((ticket) => ticket.projectId === project.id).length,
      active_ticket_count: mockTickets.filter((ticket) => ticket.projectId === project.id && !['DONE', 'CANCELLED'].includes(ticket.stage)).length,
    })) });
  }),

  http.post('/api/projects', async ({ request }) => {
    const unauth = checkAuth(request);
    if (unauth) return unauth;
    const body = await request.json() as Record<string, unknown>;
    const name = String(body.name ?? '新项目').trim() || '新项目';
    const baseId = name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '') || 'project';
    let id = baseId;
    let suffix = 2;
    while (mockProjects.some((project) => project.id === id)) id = `${baseId}-${suffix++}`;
    const now = new Date().toISOString();
    const project = {
      id,
      name,
      workspace_path: String(body.workspace_path ?? 'D:/workspace/new-project'),
      target_ref: String(body.target_ref ?? 'refs/heads/main'),
      auth_repo: 'gate',
      priority: null,
      size: null,
      tags: [],
      ticket_count: 0,
      active_ticket_count: 0,
      created_at: now,
      updated_at: now,
    };
    mockProjects.push(project);
    return HttpResponse.json(project, { status: 201 });
  }),

  http.put('/api/projects/:projectId', async ({ request, params }) => {
    const unauth = checkAuth(request);
    if (unauth) return unauth;
    const project = mockProjects.find((item) => item.id === String(params.projectId));
    if (!project) return HttpResponse.json({ error: 'project not found' }, { status: 404 });
    const body = await request.json() as Record<string, unknown>;
    if (typeof body.name === 'string' && body.name.trim()) project.name = body.name.trim();
    if ('priority' in body) project.priority = body.priority == null ? null : String(body.priority);
    if ('size' in body) project.size = body.size == null ? null : String(body.size);
    if (Array.isArray(body.tags)) project.tags = body.tags.map(String).filter(Boolean);
    project.updated_at = new Date().toISOString();
    return HttpResponse.json(project);
  }),

  http.get('/api/projects/:projectId/tickets', ({ request, params }) => {
    const unauth = checkAuth(request);
    if (unauth) return unauth;
    return HttpResponse.json(mockTickets.filter((ticket) => ticket.projectId === String(params.projectId)));
  }),

  http.get('/api/projects/:projectId/tickets/:no', ({ request, params }) => {
    const unauth = checkAuth(request);
    if (unauth) return unauth;
    const ticket = mockTickets.find((item) => item.projectId === String(params.projectId) && item.no === String(params.no));
    return ticket
      ? HttpResponse.json(ticket)
      : HttpResponse.json({ error: 'ticket not found' }, { status: 404 });
  }),

  http.patch('/api/projects/:projectId/tickets/:no', async ({ request, params }) => {
    const unauth = checkAuth(request);
    if (unauth) return unauth;
    const ticket = mockTickets.find((item) => item.projectId === String(params.projectId) && item.no === String(params.no));
    if (!ticket) return HttpResponse.json({ error: 'ticket not found' }, { status: 404 });
    const body = await request.json() as Record<string, unknown>;
    if (typeof body.title === 'string' && body.title.trim()) ticket.title = body.title.trim();
    if ('description' in body) ticket.description = body.description == null ? null : String(body.description);
    if ('note' in body) ticket.note = body.note == null ? null : String(body.note);
    if ('labels' in body && Array.isArray(body.labels)) ticket.labels = body.labels.map(String).filter(Boolean);
    if (typeof body.stage === 'string') ticket.stage = body.stage;
    if (body.priority === null || ['P0', 'P1', 'P2', 'P3'].includes(String(body.priority))) {
      ticket.priority = body.priority === null ? null : String(body.priority);
    }
    ticket.updatedAt = new Date().toISOString();
    return HttpResponse.json(ticket);
  }),

  http.post('/api/projects/:projectId/tickets', async ({ request, params }) => {
    const unauth = checkAuth(request);
    if (unauth) return unauth;
    const body = await request.json() as Record<string, unknown>;
    const projectId = String(params.projectId);
    const project = mockProjects.find((item) => item.id === projectId);
    const now = new Date().toISOString();
    const ticket = {
      no: String(body.ticket_no ?? `PROJ-${mockTickets.length + 1}`),
      title: String(body.title ?? '未命名工单'),
      description: body.description == null ? null : String(body.description),
      note: body.note == null ? null : String(body.note),
      labels: Array.isArray(body.labels) ? body.labels.map(String).filter(Boolean) : [],
      stage: 'PENDING',
      targetRef: project?.target_ref ?? 'refs/heads/main',
      reviewRound: null,
      treeHash: null,
      baseCommit: null,
      execTokenTotal: null,
      execTokenSource: 'unavailable',
      agentConfigId: null,
      priority: body.priority == null ? 'P2' : String(body.priority),
      projectId,
      project: project?.name ?? projectId,
      createdAt: now,
      updatedAt: now,
    };
    mockTickets.push(ticket);
    return HttpResponse.json(ticket, { status: 201 });
  }),

  // Agent runtime/settings mock used by the local-first CLI settings surface.
  http.get('/api/agent-runtimes', ({ request }) => {
    const unauth = checkAuth(request);
    if (unauth) return unauth;
    return HttpResponse.json({
      agent_runtimes: [
        { name: 'claude', available: true, version: '2.1.232', note: null, models: ['default', 'sonnet', 'opus', 'haiku'], model_source: 'cli-hints' },
        { name: 'opencode', available: true, version: '1.18.18', note: null, models: ['default', 'opencode/big-pickle', 'opencode-go/glm-5.2'], model_source: 'cli' },
      ],
    });
  }),

  http.get('/api/agent-configs', ({ request }) => {
    const unauth = checkAuth(request);
    if (unauth) return unauth;
    return HttpResponse.json({ agent_configs: mockAgentConfigs });
  }),

  http.post('/api/agent-configs', async ({ request }) => {
    const unauth = checkAuth(request);
    if (unauth) return unauth;
    const body = await request.json() as Record<string, unknown>;
    const config = {
      id: String(body.id ?? `agent-${mockAgentConfigs.length + 1}`),
      name: String(body.name ?? '本机 CLI 配置'),
      cli: String(body.cli ?? 'CLAUDE'),
      provider_id: body.provider_id == null ? null : String(body.provider_id),
      model: body.model == null ? null : String(body.model),
      system_prompt: body.system_prompt == null ? null : String(body.system_prompt),
      extra_flags: Array.isArray(body.extra_flags) ? body.extra_flags.map(String) : [],
      description: body.description == null ? null : String(body.description),
    };
    mockAgentConfigs.push(config);
    return HttpResponse.json(config, { status: 201 });
  }),

  http.put('/api/agent-configs/:id', async ({ request, params }) => {
    const unauth = checkAuth(request);
    if (unauth) return unauth;
    const config = mockAgentConfigs.find((item) => item.id === String(params.id));
    if (!config) return HttpResponse.json({ error: 'agent config not found' }, { status: 404 });
    const body = await request.json() as Record<string, unknown>;
    if (typeof body.name === 'string') config.name = body.name;
    if (typeof body.cli === 'string') config.cli = body.cli;
    config.provider_id = body.provider_id == null ? null : String(body.provider_id);
    config.model = body.model == null ? null : String(body.model);
    config.system_prompt = body.system_prompt == null ? null : String(body.system_prompt);
    config.extra_flags = Array.isArray(body.extra_flags) ? body.extra_flags.map(String) : [];
    config.description = body.description == null ? null : String(body.description);
    return HttpResponse.json(config);
  }),

  http.delete('/api/agent-configs/:id', ({ request, params }) => {
    const unauth = checkAuth(request);
    if (unauth) return unauth;
    const index = mockAgentConfigs.findIndex((item) => item.id === String(params.id));
    if (index < 0) return HttpResponse.json({ error: 'agent config not found' }, { status: 404 });
    mockAgentConfigs.splice(index, 1);
    return HttpResponse.json({ ok: true });
  }),

  http.get('/api/providers', ({ request }) => {
    const unauth = checkAuth(request);
    if (unauth) return unauth;
    return HttpResponse.json({ providers: mockProviders });
  }),

];

export { MOCK_TOKEN };
