import type { Stage, Priority } from "./types";

const HEX = "0123456789abcdef";

export function fakeSha(seed: string): string {
  let h1 = 0x811c9dc5;
  let out = "";
  for (let i = 0; i < seed.length; i++) {
    h1 ^= seed.charCodeAt(i);
    h1 = Math.imul(h1, 0x01000193) >>> 0;
  }
  let state = h1 || 1;
  for (let i = 0; i < 64; i++) {
    state ^= state << 13;
    state >>>= 0;
    state ^= state >> 17;
    state ^= state << 5;
    state >>>= 0;
    out += HEX[state % 16];
  }
  return out;
}

export function shortHash(hash: string, head = 10, tail = 6): string {
  // slice(-0) === slice(0): a zero tail would append the full hash after the ellipsis.
  if (tail <= 0) return hash.slice(0, head);
  if (hash.length <= head + tail + 1) return hash;
  return `${hash.slice(0, head)}…${hash.slice(-tail)}`;
}

export function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  return `${(n / 1024).toFixed(1)} KB`;
}

export function formatTokens(n: number): string {
  if (n >= 1000) return `${(n / 1000).toFixed(1)}k`;
  return String(n);
}

export function hhmmss(ts: number): string {
  const d = new Date(ts);
  const p = (x: number) => String(x).padStart(2, "0");
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

export function relativeTime(iso: string): string {
  const then = new Date(iso).getTime();
  const diff = Date.now() - then;
  if (Number.isNaN(then)) return "";
  const min = Math.floor(diff / 60000);
  if (min < 1) return "刚刚";
  if (min < 60) return `${min} 分钟前`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr} 小时前`;
  return `${Math.floor(hr / 24)} 天前`;
}

export const STAGE_LABEL: Record<Stage, string> = {
  PENDING: "待处理",
  IN_PROGRESS: "进行中",
  PRESUBMITTED: "已预提交",
  IN_REVIEW: "审查中",
  REJECTED: "已驳回",
  READY_TO_PUBLISH: "可发布",
  NEEDS_HUMAN: "需人工",
  DONE: "已完成",
  CANCELLED: "已取消",
};

export const SEVERITY_LABEL: Record<string, string> = {
  BLOCKER: "阻断",
  WARNING: "警告",
  NIT: "细微",
  INFO: "提示",
};

export const PRIORITY_COLOR: Record<Priority, string> = {
  P0: "text-danger border-danger/40 bg-danger/10",
  P1: "text-warn border-warn/40 bg-warn/10",
  P2: "text-info border-info/40 bg-info/10",
  P3: "text-violet border-violet/40 bg-violet/10",
};

/**
 * 工单列表排序用的泳道序：与看板 LANES 顺序一致（REJECTED 归入进行中、
 * NEEDS_HUMAN 归入可发布），CANCELLED 为终态恒排最后。
 */
export const STAGE_LANE_RANK: Record<Stage, number> = {
  PENDING: 0,
  IN_PROGRESS: 1,
  REJECTED: 1,
  PRESUBMITTED: 2,
  IN_REVIEW: 3,
  READY_TO_PUBLISH: 4,
  NEEDS_HUMAN: 4,
  DONE: 5,
  CANCELLED: 6,
};

export function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

let idCounter = 0;
export function uid(prefix = "id"): string {
  idCounter += 1;
  return `${prefix}-${Date.now().toString(36)}-${idCounter}`;
}
