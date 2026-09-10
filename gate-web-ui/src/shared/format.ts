import type { Stage, Priority } from "@/shared/types";
import { t as i18nT, type Translate } from "@/i18n";

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
  if (!Number.isFinite(n) || n <= 0) return "0 B";
  if (n < 1024) return `${n} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let v = n;
  let u = -1;
  do {
    v /= 1024;
    u++;
  } while (v >= 1024 && u < units.length - 1);
  return `${v.toFixed(1)} ${units[u]}`;
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

export function relativeTime(iso: string, tr: Translate = i18nT): string {
  const then = new Date(iso).getTime();
  const diff = Date.now() - then;
  if (Number.isNaN(then)) return "";
  const min = Math.floor(diff / 60000);
  if (min < 1) return tr("time.justNow");
  if (min < 60) return tr("time.minutesAgo", { n: min });
  const hr = Math.floor(min / 60);
  if (hr < 24) return tr("time.hoursAgo", { n: hr });
  return tr("time.daysAgo", { n: Math.floor(hr / 24) });
}

/** 时长格式化："52 分 46 秒" / "46 秒"；0 或缺省返回 null（调用方改用步数表述）。 */
export function formatDuration(ms: number, tr: Translate = i18nT): string | null {
  if (!(ms > 0)) return null;
  const total = Math.round(ms / 1000);
  if (total < 60) return tr("time.seconds", { n: total });
  return tr("time.minSec", { m: Math.floor(total / 60), s: total % 60 });
}

/** 工单状态标签（当前语言）。 */
const STAGE_KEYS: Record<Stage, `stage.${Stage}`> = {
  PENDING: "stage.PENDING",
  IN_PROGRESS: "stage.IN_PROGRESS",
  PRESUBMITTED: "stage.PRESUBMITTED",
  IN_REVIEW: "stage.IN_REVIEW",
  REJECTED: "stage.REJECTED",
  READY_TO_PUBLISH: "stage.READY_TO_PUBLISH",
  NEEDS_HUMAN: "stage.NEEDS_HUMAN",
  DONE: "stage.DONE",
  CANCELLED: "stage.CANCELLED",
};

export function stageLabel(stage: Stage, tr: Translate = i18nT): string {
  return tr(STAGE_KEYS[stage]);
}

/** 状态变更记录动作标签（重启/强制已完成/取消）。 */
const STAGE_CHANGE_KEYS: Record<"restart" | "force_complete" | "cancel", `stageChange.${"restart" | "force_complete" | "cancel"}`> = {
  restart: "stageChange.restart",
  force_complete: "stageChange.force_complete",
  cancel: "stageChange.cancel",
};

export function stageChangeKindLabel(
  kind: "restart" | "force_complete" | "cancel",
  tr: Translate = i18nT,
): string {
  return tr(STAGE_CHANGE_KEYS[kind]);
}

/** 审查严重度标签（阻断/警告/细微/提示）；未知严重度原样返回。 */
export function severityLabel(sev: string, tr: Translate = i18nT): string {
  const key = `severity.${sev}`;
  return tr(`severity.${sev}` as never) !== key ? tr(`severity.${sev}` as never) : sev;
}

/** 推理力度档位标签（高/中/低/最高/极简/关闭；未知值原样返回）。 */
export function variantLabel(v: string, tr: Translate = i18nT): string {
  const key = `variant.${v.toLowerCase()}`;
  return tr(key as never) !== key ? tr(key as never) : v;
}

export const PRIORITY_COLOR: Record<Priority, string> = {
  P0: "text-danger border-danger/40 bg-danger/10",
  P1: "text-warn border-warn/40 bg-warn/10",
  P2: "text-info border-info/40 bg-info/10",
  P3: "text-violet border-violet/40 bg-violet/10",
};

/**
 * 工单列表排序权重（升序 = 自上而下）：越接近发布的活跃工单越靠上，
 * 待处理随后，终态沉底（已完成次之，已取消恒在最末）。已驳回视同进行中。
 * 同一状态内再按优先级 P0→P3，最后保持手动拖拽顺序。
 */
export const STAGE_SORT_RANK: Record<Stage, number> = {
  READY_TO_PUBLISH: 0,
  NEEDS_HUMAN: 1,
  IN_REVIEW: 2,
  PRESUBMITTED: 3,
  IN_PROGRESS: 4,
  REJECTED: 4,
  PENDING: 5,
  DONE: 6,
  CANCELLED: 7,
};

export const PRIORITY_RANK: Record<Priority, number> = { P0: 0, P1: 1, P2: 2, P3: 3 };

/** 状态筛选面板的全集（与 Stage 类型一一对应）。 */
export const ALL_STAGES: Stage[] = [
  "PENDING",
  "IN_PROGRESS",
  "REJECTED",
  "PRESUBMITTED",
  "IN_REVIEW",
  "READY_TO_PUBLISH",
  "NEEDS_HUMAN",
  "DONE",
  "CANCELLED",
];

/** 看板甬道渲染顺序：流水线六状态在前，其他状态（已驳回/需人工/已取消）在后。 */
export const KANBAN_STAGE_ORDER: Stage[] = [
  "PENDING",
  "IN_PROGRESS",
  "PRESUBMITTED",
  "IN_REVIEW",
  "READY_TO_PUBLISH",
  "DONE",
  "REJECTED",
  "NEEDS_HUMAN",
  "CANCELLED",
];

/** 看板默认勾选的甬道：流水线六状态，恰好铺满一行，视觉最均衡。 */
export const KANBAN_DEFAULT_STAGES: Stage[] = KANBAN_STAGE_ORDER.slice(0, 6);

/**
 * 看板固定同时上板的甬道数：最多最少都是 6 条，恰好铺满一行，视觉最均衡。
 * 勾选数量因此恒等于 6，更换甬道只能「一上一下」互换，而非自由增减。
 */
export const KANBAN_LANE_COUNT = 6;

export function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

let idCounter = 0;
export function uid(prefix = "id"): string {
  idCounter += 1;
  return `${prefix}-${Date.now().toString(36)}-${idCounter}`;
}
