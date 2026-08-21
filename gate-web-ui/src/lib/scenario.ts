import type {
  AgentConfigOption,
  DiffFile,
  Finding,
  Project,
  Ticket,
} from "./types";

export const DEMO_PROJECT: Project = {
  id: "acme-checkout",
  name: "Acme Checkout",
  workspacePath: "D:/work/acme-checkout",
  targetRef: "refs/heads/main",
};

export const DEMO_AGENTS: AgentConfigOption[] = [
  { id: "claude-sonnet", name: "Claude", model: "claude-sonnet-4-5", cli: "claude" },
  { id: "oc-codex", name: "OpenCode", model: "gpt-5-codex", cli: "opencode" },
];

export const DEMO_TICKETS: Ticket[] = [
  {
    ticketNo: "T-104",
    title: "为 /api/checkout 添加速率限制",
    stage: "IN_PROGRESS",
    priority: "P0",
    projectId: DEMO_PROJECT.id,
    labels: ["backend", "security"],
    description: "防止重复提交与恶意刷单，超限返回 429。",
    agentName: "Claude",
    createdAt: new Date(Date.now() - 3600_000 * 2).toISOString(),
    updatedAt: new Date(Date.now() - 600_000).toISOString(),
  },
  {
    ticketNo: "T-102",
    title: "购物车合并策略：多端登录行去重",
    stage: "IN_PROGRESS",
    priority: "P2",
    projectId: DEMO_PROJECT.id,
    labels: ["backend"],
    agentName: "OpenCode",
    createdAt: new Date(Date.now() - 3600_000 * 26).toISOString(),
    updatedAt: new Date(Date.now() - 3600_000 * 3).toISOString(),
  },
  {
    ticketNo: "T-101",
    title: "修复结算金额精度丢失（分转元浮点误差）",
    stage: "DONE",
    priority: "P1",
    projectId: DEMO_PROJECT.id,
    labels: ["backend", "bug"],
    agentName: "Claude",
    createdAt: new Date(Date.now() - 3600_000 * 52).toISOString(),
    updatedAt: new Date(Date.now() - 3600_000 * 40).toISOString(),
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
