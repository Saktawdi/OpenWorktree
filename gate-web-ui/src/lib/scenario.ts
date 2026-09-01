import type {
  AgentConfig,
  AgentRuntime,
  DiffFile,
  Finding,
  GitRepoView,
  GitTreeEntry,
  Project,
  Ticket,
} from "./types";

export const DEMO_PROJECTS: Project[] = [
  {
    id: "acme-checkout",
    name: "Acme Checkout",
    workspacePath: "D:/work/acme-checkout",
    targetRef: "refs/heads/main",
    authRepo: "D:/repo/auth/acme-checkout.git",
    priority: "P0",
    size: "medium",
    tags: ["电商", "交易链路"],
    starred: true,
    sortOrder: 1,
    ticketCount: 3,
    activeTicketCount: 2,
    createdAt: new Date(Date.now() - 86400_000 * 30).toISOString(),
    updatedAt: new Date(Date.now() - 3600_000 * 2).toISOString(),
  },
  {
    id: "nexus-docs",
    name: "Nexus Docs",
    workspacePath: "D:/work/nexus-docs",
    targetRef: "refs/heads/main",
    authRepo: "D:/repo/auth/nexus-docs.git",
    priority: "P2",
    size: "small",
    tags: ["文档站"],
    starred: false,
    sortOrder: 2,
    ticketCount: 2,
    activeTicketCount: 1,
    createdAt: new Date(Date.now() - 86400_000 * 12).toISOString(),
    updatedAt: new Date(Date.now() - 3600_000 * 20).toISOString(),
  },
];

export const DEMO_AGENTS: AgentConfig[] = [
  {
    id: "claude-sonnet",
    name: "Claude 主力",
    cli: "claude",
    providerId: null,
    model: "claude-sonnet-4-5",
    systemPrompt: "你是结算系统的资深工程师，改动保持最小化，遵循现有代码风格。",
    extraFlags: [],
    description: "日常编码与修复的主力配置",
    injectContext: true,
  },
  {
    id: "oc-codex",
    name: "OpenCode 备援",
    cli: "opencode",
    providerId: null,
    model: "gpt-5-codex",
    systemPrompt: null,
    extraFlags: ["-c", "model_context_limit=200000"],
    description: "大上下文重构与批量迁移",
    injectContext: true,
  },
];

export const DEMO_RUNTIMES: AgentRuntime[] = [
  {
    name: "opencode",
    available: true,
    version: "0.6.12",
    models: ["gpt-5-codex", "claude-sonnet-4-5", "qwen3-coder-plus"],
    modelSource: "cli",
  },
  {
    name: "claude",
    available: true,
    version: "2.1.34",
    models: ["default", "sonnet", "opus", "haiku"],
    modelSource: "cli-hints",
  },
];

export const DEMO_OC_PROVIDERS: import("./types").OpenCodeProvider[] = [
  {
    key: "github-copilot",
    name: "GitHub Copilot",
    npm: "@ai-sdk/openai-compatible",
    baseURL: "https://api.githubcopilot.com/",
    apiKey: null,
    models: ["gpt-4.1", "claude-sonnet-4.5"],
    modelCount: 2,
  },
  {
    key: "deepseek",
    name: "DeepSeek",
    npm: "@ai-sdk/openai-compatible",
    baseURL: "https://api.deepseek.com/v1",
    apiKey: null,
    models: ["deepseek-chat", "deepseek-reasoner"],
    modelCount: 2,
  },
];

const T = (h: number) => new Date(Date.now() - h * 3600_000).toISOString();

export const DEMO_TICKETS: Ticket[] = [
  {
    ticketNo: "T-104",
    title: "为 /api/checkout 添加速率限制",
    stage: "IN_PROGRESS",
    priority: "P0",
    projectId: "acme-checkout",
    labels: ["backend", "security"],
    description: "防止重复提交与恶意刷单：单客户端 60 秒窗口内最多 30 次，超限返回 429 并携带 Retry-After。",
    note: "压测基线：当前 /api/checkout 在 200 QPS 下 P99 为 340ms。",
    targetRef: "refs/heads/main",
    clonePath: "D:/project/ai-generate/local-git-ticket-system/local-run/clones/T-104",
    agentConfigId: "claude-sonnet",
    execTokenTotal: 5046,
    createdAt: T(26),
    updatedAt: T(1),
  },
  {
    ticketNo: "T-102",
    title: "购物车合并策略：多端登录行去重",
    stage: "IN_PROGRESS",
    priority: "P2",
    projectId: "acme-checkout",
    labels: ["backend"],
    description: "多端同时登录时购物车按 SKU 合并，数量取较大一方。",
    targetRef: "refs/heads/main",
    clonePath: "D:/project/ai-generate/local-git-ticket-system/local-run/clones/T-102",
    agentConfigId: "oc-codex",
    execTokenTotal: 1450,
    createdAt: T(28),
    updatedAt: T(4),
  },
  {
    ticketNo: "T-101",
    title: "修复结算金额精度丢失（分转元浮点误差）",
    stage: "DONE",
    priority: "P1",
    projectId: "acme-checkout",
    labels: ["backend", "bug"],
    description: "金额统一以「分」为最小单位存储与计算，展示层再转换。",
    note: "已发布提交 f3c9a71d…，审计日志 #a91 已归档。",
    targetRef: "refs/heads/main",
    clonePath: "D:/project/ai-generate/local-git-ticket-system/local-run/clones/T-101",
    agentConfigId: "claude-sonnet",
    execTokenTotal: 8873,
    createdAt: T(54),
    updatedAt: T(40),
  },
  {
    ticketNo: "T-201",
    title: "文档站搜索接入中文分词",
    stage: "IN_PROGRESS",
    priority: "P2",
    projectId: "nexus-docs",
    labels: ["search"],
    description: "替换默认 tokenizer，支持中文标题与正文命中。",
    targetRef: "refs/heads/main",
    clonePath: "D:/project/ai-generate/local-git-ticket-system/local-run/clones/T-201",
    agentConfigId: "oc-codex",
    execTokenTotal: 0,
    createdAt: T(20),
    updatedAt: T(19),
  },
  {
    ticketNo: "T-202",
    title: "修复暗色主题下代码块对比度不足",
    stage: "DONE",
    priority: "P3",
    projectId: "nexus-docs",
    labels: ["ui"],
    targetRef: "refs/heads/main",
    clonePath: "D:/project/ai-generate/local-git-ticket-system/local-run/clones/T-202",
    agentConfigId: "claude-sonnet",
    execTokenTotal: 2210,
    createdAt: T(70),
    updatedAt: T(60),
  },
];

const RL_V1: string[] = [
  'import type { NextFunction, Request, Response } from "express";',
  "",
  "interface Bucket {",
  "  tokens: number;",
  "  lastRefill: number;",
  "}",
  "",
  "const buckets = new Map<string, Bucket>();",
  "",
  "export const RATE_LIMIT = 30;",
  "const REFILL_MS = 60_000;",
  "",
  "function refill(bucket: Bucket, now: number) {",
  "  const elapsed = now - bucket.lastRefill;",
  "  if (elapsed < REFILL_MS) return;",
  "  bucket.tokens = RATE_LIMIT;",
  "  bucket.lastRefill = now;",
  "}",
  "",
  "export function rateLimit(req: Request, res: Response, next: NextFunction) {",
  '  const key = req.ip ?? "unknown";',
  "  const now = Date.now();",
  "  let bucket = buckets.get(key);",
  "  if (!bucket) {",
  "    bucket = { tokens: 60, lastRefill: now };",
  "    buckets.set(key, bucket);",
  "  }",
  "  refill(bucket, now);",
  "  if (bucket.tokens <= 0) {",
  '    res.status(429).json({ error: "too_many_requests" });',
  "    return;",
  "  }",
  "  bucket.tokens -= 1;",
  "  next();",
  "}",
];

function addedFile(path: string, lines: string[]): DiffFile {
  return {
    path,
    status: "added",
    additions: lines.length,
    deletions: 0,
    hunks: [
      {
        header: `@@ -0,0 +1,${lines.length} @@`,
        lines: lines.map((content, i) => ({ type: "add" as const, newNo: i + 1, content })),
      },
    ],
  };
}

function checkoutMountDiff(): DiffFile {
  const lines: DiffFile["hunks"][number]["lines"] = [
    { type: "ctx", oldNo: 1, newNo: 1, content: 'import { Router } from "express";' },
    { type: "ctx", oldNo: 2, newNo: 2, content: 'import { confirmOrder } from "../services/order";' },
    { type: "add", newNo: 3, content: 'import { rateLimit } from "../middleware/rate-limit";' },
    { type: "ctx", oldNo: 3, newNo: 4, content: "" },
    { type: "ctx", oldNo: 4, newNo: 5, content: "export const checkoutRouter = Router();" },
    { type: "ctx", oldNo: 5, newNo: 6, content: "" },
    { type: "del", oldNo: 6, content: 'checkoutRouter.post("/api/checkout", async (req, res) => {' },
    {
      type: "add",
      newNo: 7,
      content: 'checkoutRouter.post("/api/checkout", rateLimit, async (req, res) => {',
    },
    { type: "ctx", oldNo: 7, newNo: 8, content: "  const order = await confirmOrder(req.body);" },
    { type: "ctx", oldNo: 8, newNo: 9, content: "  res.json(order);" },
    { type: "ctx", oldNo: 9, newNo: 10, content: "});" },
  ];
  return {
    path: "src/routes/checkout.ts",
    status: "modified",
    additions: 2,
    deletions: 1,
    hunks: [{ header: "@@ -1,9 +1,10 @@", lines }],
  };
}

export const DIFF_T104_R1: DiffFile[] = [
  addedFile("src/middleware/rate-limit.ts", RL_V1),
  checkoutMountDiff(),
];

const RL_V2: string[] = [
  'import type { NextFunction, Request, Response } from "express";',
  "",
  "interface Bucket {",
  "  tokens: number;",
  "  lastRefill: number;",
  "  pending: number;",
  "}",
  "",
  "const buckets = new Map<string, Bucket>();",
  "const MAX_BUCKETS = 10_000;",
  "",
  "export const RATE_LIMIT = 30;",
  "export const WINDOW_SECONDS = 60;",
  "export const MAX_PENDING = 64;",
  "const REFILL_MS = WINDOW_SECONDS * 1_000;",
  "",
  "function refill(bucket: Bucket, now: number) {",
  "  const elapsed = now - bucket.lastRefill;",
  "  if (elapsed < REFILL_MS) return;",
  "  bucket.tokens = RATE_LIMIT;",
  "  bucket.lastRefill = now;",
  "}",
  "",
  "export function rateLimit(req: Request, res: Response, next: NextFunction) {",
  '  const key = req.ip ?? "unknown";',
  "  const now = Date.now();",
  "  let bucket = buckets.get(key);",
  "  if (!bucket) {",
  "    if (buckets.size >= MAX_BUCKETS) {",
  '      res.status(503).json({ error: "capacity_exceeded" });',
  "      return;",
  "    }",
  "    bucket = { tokens: RATE_LIMIT, lastRefill: now, pending: 0 };",
  "    buckets.set(key, bucket);",
  "  }",
  "  refill(bucket, now);",
  "  if (bucket.tokens <= 0) {",
  '    res.set("Retry-After", String(WINDOW_SECONDS));',
  '    res.status(429).json({ error: "too_many_requests" });',
  "    return;",
  "  }",
  "  bucket.tokens -= 1;",
  "  next();",
  "}",
];

const RL_TEST: string[] = [
  'import { describe, expect, it } from "vitest";',
  'import { rateLimit, WINDOW_SECONDS } from "../src/middleware/rate-limit";',
  "",
  "function mockRes() {",
  "  return {",
  "    statusCode: 0,",
  "    headers: {} as Record<string, string>,",
  "    set(k: string, v: string) { this.headers[k] = v; },",
  "    status(code: number) { this.statusCode = code; return this; },",
  "    json() {},",
  "  };",
  "}",
  "",
  'describe("rateLimit", () => {',
  '  it("emits Retry-After on 429", () => {',
  "    const res = mockRes();",
  "    for (let i = 0; i < 30; i++) rateLimit({ ip: \"1.2.3.4\" } as any, res as any, () => {});",
  "    rateLimit({ ip: \"1.2.3.4\" } as any, res as any, () => {});",
  "    expect(res.statusCode).toBe(429);",
  '    expect(res.headers["Retry-After"]).toBe(String(WINDOW_SECONDS));',
  "  });",
  "});",
];

export const DIFF_T104_R2: DiffFile[] = [
  addedFile("src/middleware/rate-limit.ts", RL_V2),
  checkoutMountDiff(),
  addedFile("tests/rate-limit.test.ts", RL_TEST),
];

export const FINDINGS_R1: Finding[] = [
  {
    severity: "BLOCKER",
    path: "src/middleware/rate-limit.ts",
    lineStart: 22,
    ruleId: "gate/resource-exhaustion",
    message: "buckets Map 以客户端 IP 为键无上限增长，且桶内未设并发排队上限；公网环境下可被伪造 IP 撑爆内存。",
    suggestion: "为 buckets 设置容量上限（超限返回 503），并移除无界 pending 语义。",
  },
  {
    severity: "WARNING",
    path: "tests/rate-limit.test.ts",
    ruleId: "gate/test-coverage",
    message: "缺少对 429 响应头 Retry-After 的断言，错误路径契约不可观测。",
    suggestion: "补充用例：触发限流后断言 statusCode === 429 且 Retry-After === 窗口秒数。",
  },
  {
    severity: "NIT",
    path: "src/middleware/rate-limit.ts",
    lineStart: 23,
    ruleId: "gate/style",
    message: "初始 tokens 使用魔法数字 60，与 RATE_LIMIT 语义不一致，易在调参时漏改。",
    suggestion: "统一引用 RATE_LIMIT 或提取命名常量 INITIAL_TOKENS。",
  },
];

export function t102Diff(): DiffFile[] {
  const lines: DiffFile["hunks"][number]["lines"] = [
    { type: "ctx", oldNo: 1, newNo: 1, content: "export function mergeCarts(a: CartLine[], b: CartLine[]) {" },
    { type: "del", oldNo: 2, content: "  const seen = new Set<string>();" },
    { type: "add", newNo: 2, content: "  const keyed = new Map<string, CartLine>();" },
    { type: "del", oldNo: 3, content: "  return [...a, ...b].filter((l) => {" },
    { type: "del", oldNo: 4, content: "    const k = l.sku;" },
    { type: "del", oldNo: 5, content: "    if (seen.has(k)) return false;" },
    { type: "del", oldNo: 6, content: "    seen.add(k);" },
    { type: "add", newNo: 3, content: "  for (const l of [...a, ...b]) {" },
    { type: "add", newNo: 4, content: "    const prev = keyed.get(l.sku);" },
    { type: "add", newNo: 5, content: "    keyed.set(l.sku, prev ? { ...l, qty: Math.max(prev.qty, l.qty) } : l);" },
    { type: "del", oldNo: 7, content: "    return true;" },
    { type: "del", oldNo: 8, content: "  });" },
    { type: "add", newNo: 6, content: "  }" },
    { type: "add", newNo: 7, content: "  return [...keyed.values()];" },
    { type: "ctx", oldNo: 9, newNo: 8, content: "}" },
  ];
  return [
    {
      path: "src/cart/merge.ts",
      status: "modified",
      additions: 5,
      deletions: 6,
      hunks: [{ header: "@@ -1,9 +1,8 @@", lines }],
    },
  ];
}

const H = (s: string) => fakeShaSync(s);

function fakeShaSync(seed: string): string {
  let h1 = 0x811c9dc5;
  let out = "";
  for (let i = 0; i < seed.length; i++) {
    h1 ^= seed.charCodeAt(i);
    h1 = Math.imul(h1, 0x01000193) >>> 0;
  }
  let state = h1 || 1;
  for (let i = 0; i < 40; i++) {
    state ^= state << 13;
    state >>>= 0;
    state ^= state >> 17;
    state ^= state << 5;
    state >>>= 0;
    out += "0123456789abcdef"[state % 16];
  }
  return out;
}

const GATE_AUTHOR = "gate";

export const GIT_ACME: GitRepoView = {
  branches: [
    { name: "refs/heads/main", tip: H("acme:c6"), lane: 0 },
    { name: "refs/heads/gate/t-104", tip: H("acme:b2"), lane: 1 },
    { name: "refs/heads/gate/t-102", tip: H("acme:d1"), lane: 2 },
  ],
  commits: [
    {
      sha: H("acme:b2"),
      parents: [H("acme:b1")],
      message: "feat(rate-limit): 补充 Retry-After 断言用例",
      author: GATE_AUTHOR,
      time: T(1),
      refs: ["refs/heads/gate/t-104"],
      lane: 1,
    },
    {
      sha: H("acme:b1"),
      parents: [H("acme:c5")],
      message: "feat(rate-limit): 新增令牌桶中间件并挂载结算路由",
      author: GATE_AUTHOR,
      time: T(2),
      refs: [],
      lane: 1,
    },
    {
      sha: H("acme:d1"),
      parents: [H("acme:c5")],
      message: "feat(cart): 多端购物车按 SKU 合并取较大数量",
      author: GATE_AUTHOR,
      time: T(4),
      refs: ["refs/heads/gate/t-102"],
      lane: 2,
    },
    {
      sha: H("acme:c5"),
      parents: [H("acme:c4")],
      message: "Merge gate/t-101: 修复结算金额精度丢失",
      author: GATE_AUTHOR,
      time: T(40),
      refs: [],
      lane: 0,
    },
    {
      sha: H("acme:c4"),
      parents: [H("acme:c3")],
      message: "fix(order): 金额计算统一以分为单位",
      author: GATE_AUTHOR,
      time: T(41),
      refs: [],
      lane: 0,
    },
    {
      sha: H("acme:c3"),
      parents: [H("acme:c2")],
      message: "chore: 升级依赖并收紧 tsconfig",
      author: "李澈",
      time: T(90),
      refs: ["tag: v1.4.0"],
      lane: 0,
    },
    {
      sha: H("acme:c2"),
      parents: [H("acme:c1")],
      message: "feat(checkout): 结算路由骨架",
      author: "李澈",
      time: T(120),
      refs: [],
      lane: 0,
    },
    {
      sha: H("acme:c1"),
      parents: [],
      message: "init: 仓库初始化",
      author: "李澈",
      time: T(200),
      refs: [],
      lane: 0,
    },
  ],
};

export const GIT_NEXUS: GitRepoView = {
  branches: [
    { name: "refs/heads/main", tip: H("nx:c4"), lane: 0 },
    { name: "refs/heads/gate/t-201", tip: H("nx:e1"), lane: 1 },
  ],
  commits: [
    {
      sha: H("nx:e1"),
      parents: [H("nx:c4")],
      message: "feat(search): 引入中文分词器（进行中）",
      author: GATE_AUTHOR,
      time: T(19),
      refs: ["refs/heads/gate/t-201"],
      lane: 1,
    },
    {
      sha: H("nx:c4"),
      parents: [H("nx:c3")],
      message: "Merge gate/t-202: 修复暗色主题代码块对比度",
      author: GATE_AUTHOR,
      time: T(60),
      refs: [],
      lane: 0,
    },
    {
      sha: H("nx:c3"),
      parents: [H("nx:c2")],
      message: "fix(theme): 代码块背景提升至 AA 对比度",
      author: GATE_AUTHOR,
      time: T(61),
      refs: [],
      lane: 0,
    },
    {
      sha: H("nx:c2"),
      parents: [H("nx:c1")],
      message: "docs: 重写快速开始章节",
      author: "苏晚",
      time: T(140),
      refs: ["tag: v0.9.0"],
      lane: 0,
    },
    {
      sha: H("nx:c1"),
      parents: [],
      message: "init: 文档站初始化",
      author: "苏晚",
      time: T(300),
      refs: [],
      lane: 0,
    },
  ],
};

export const TREE_ACME: GitTreeEntry[] = [
  { path: "src", type: "dir", lastCommitShort: H("acme:b2").slice(0, 7), lastMessage: "feat(rate-limit): 补充 Retry-After 断言用例" },
  { path: "src/middleware", type: "dir", lastCommitShort: H("acme:b2").slice(0, 7), lastMessage: "feat(rate-limit): 补充 Retry-After 断言用例" },
  { path: "src/routes", type: "dir", lastCommitShort: H("acme:b1").slice(0, 7), lastMessage: "feat(rate-limit): 新增令牌桶中间件并挂载结算路由" },
  { path: "src/services", type: "dir", lastCommitShort: H("acme:c4").slice(0, 7), lastMessage: "fix(order): 金额计算统一以分为单位" },
  { path: "tests", type: "dir", lastCommitShort: H("acme:b2").slice(0, 7), lastMessage: "feat(rate-limit): 补充 Retry-After 断言用例" },
  { path: "package.json", type: "file", size: 842, lastCommitShort: H("acme:c3").slice(0, 7), lastMessage: "chore: 升级依赖并收紧 tsconfig" },
  { path: "tsconfig.json", type: "file", size: 512, lastCommitShort: H("acme:c3").slice(0, 7), lastMessage: "chore: 升级依赖并收紧 tsconfig" },
];

export const TREE_NEXUS: GitTreeEntry[] = [
  { path: "content", type: "dir", lastCommitShort: H("nx:c2").slice(0, 7), lastMessage: "docs: 重写快速开始章节" },
  { path: "theme", type: "dir", lastCommitShort: H("nx:c3").slice(0, 7), lastMessage: "fix(theme): 代码块背景提升至 AA 对比度" },
  { path: "config.mts", type: "file", size: 1204, lastCommitShort: H("nx:c1").slice(0, 7), lastMessage: "init: 文档站初始化" },
  { path: "package.json", type: "file", size: 640, lastCommitShort: H("nx:c2").slice(0, 7), lastMessage: "docs: 重写快速开始章节" },
];
