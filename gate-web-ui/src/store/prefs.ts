/**
 * 本地偏好持久化（store）：localStorage 键的集中读写。
 * 键名与读取默认值都在这里登记，域内 setter 只调用对应 save/load。
 */
import type { Stage, QuoteChip, SessionGroup } from "@/shared/types";
import { ALL_STAGES, KANBAN_DEFAULT_STAGES, KANBAN_LANE_COUNT, KANBAN_STAGE_ORDER } from "@/shared/format";
import type { GateSections } from "./state";

const THEME_KEY = "gate-theme";
const AGENT_ID_KEY = "gate-agent-id";
const VISIBLE_STAGES_KEY = "gate-visible-stages";
const KANBAN_STAGES_KEY = "gate-kanban-stages";
const GATE_PANEL_KEY = "gate-panel-collapsed";
const GATE_SECTIONS_KEY = "gate-sections";
const COMPOSER_DRAFTS_KEY = "gate-composer-drafts";
const PENDING_QUOTES_KEY = "gate-pending-quotes";
const TERMINAL_CLOSE_ALL_KEY = "gate-terminal-close-all-confirm";
const SESSION_GROUPS_KEY = "gate-session-groups";
const SESSION_PINNED_KEY = "gate-session-pinned";
const FOLLOW_UP_BEHAVIOR_KEY = "gate-follow-up-behavior";
const QUEUED_MESSAGES_KEY = "gate-queued-messages";

/** 会话分组的落盘形态（T-105）：分组表 + 会话归属表（sessionId → groupId）。 */
export interface PersistedSessionGroups {
  groups: Record<string, SessionGroup[]>;
  members: Record<string, string>;
}

export const DEFAULT_GATE_SECTIONS: GateSections = { info: true, pipeline: true, sessions: true };

export function loadTheme(): "dark" | "light" {
  try {
    const raw = typeof window !== "undefined" ? localStorage.getItem(THEME_KEY) : null;
    return raw === "light" ? "light" : "dark";
  } catch {
    return "dark";
  }
}

export function saveTheme(theme: "dark" | "light") {
  try {
    localStorage.setItem(THEME_KEY, theme);
  } catch {
    /* ignore */
  }
}

export function loadAgentId(): string | null {
  try {
    return typeof window !== "undefined" ? localStorage.getItem(AGENT_ID_KEY) : null;
  } catch {
    return null;
  }
}

export function saveAgentId(id: string) {
  try {
    localStorage.setItem(AGENT_ID_KEY, id);
  } catch {
    /* ignore */
  }
}

/** 读取本地持久化的状态筛选；非法值回退为全部可见。 */
export function loadVisibleStages(): Stage[] {
  try {
    const raw = typeof window !== "undefined" ? localStorage.getItem(VISIBLE_STAGES_KEY) : null;
    if (!raw) return [...ALL_STAGES];
    const parsed = JSON.parse(raw) as Stage[];
    const valid = parsed.filter((x) => ALL_STAGES.includes(x));
    return valid.length > 0 ? valid : [...ALL_STAGES];
  } catch {
    return [...ALL_STAGES];
  }
}

export function saveVisibleStages(stages: Stage[]) {
  try {
    localStorage.setItem(VISIBLE_STAGES_KEY, JSON.stringify(stages));
  } catch {
    /* ignore */
  }
}

/** 读取本地持久化的看板甬道；数量不足固定 6 条或含非法值时回退默认六甬道，并按甬道顺序去重。 */
export function loadKanbanStages(): Stage[] {
  try {
    const raw = typeof window !== "undefined" ? localStorage.getItem(KANBAN_STAGES_KEY) : null;
    if (!raw) return [...KANBAN_DEFAULT_STAGES];
    const parsed = JSON.parse(raw) as Stage[];
    const valid = KANBAN_STAGE_ORDER.filter((x) => parsed.includes(x));
    return valid.length === KANBAN_LANE_COUNT ? valid : [...KANBAN_DEFAULT_STAGES];
  } catch {
    return [...KANBAN_DEFAULT_STAGES];
  }
}

export function saveKanbanStages(stages: Stage[]) {
  try {
    localStorage.setItem(KANBAN_STAGES_KEY, JSON.stringify(stages));
  } catch {
    /* ignore */
  }
}

/** 读取本地持久化的面板整栏折叠偏好；缺省为展开。 */
export function loadGatePanelCollapsed(): boolean {
  try {
    return typeof window !== "undefined" && localStorage.getItem(GATE_PANEL_KEY) === "1";
  } catch {
    return false;
  }
}

export function saveGatePanelCollapsed(collapsed: boolean) {
  try {
    localStorage.setItem(GATE_PANEL_KEY, collapsed ? "1" : "0");
  } catch {
    /* ignore */
  }
}

export function loadGateSections(): GateSections {
  try {
    const raw = typeof window !== "undefined" ? localStorage.getItem(GATE_SECTIONS_KEY) : null;
    if (!raw) return { ...DEFAULT_GATE_SECTIONS };
    const parsed = JSON.parse(raw) as Partial<GateSections>;
    return {
      info: parsed.info ?? true,
      pipeline: parsed.pipeline ?? true,
      sessions: parsed.sessions ?? true,
    };
  } catch {
    return { ...DEFAULT_GATE_SECTIONS };
  }
}

export function saveGateSections(sections: GateSections) {
  try {
    localStorage.setItem(GATE_SECTIONS_KEY, JSON.stringify(sections));
  } catch {
    /* ignore */
  }
}

/** 读取本地持久化的输入框草稿（按工单号键）；损坏/不可用时返回空表，空串条目不还原。 */
export function loadComposerDrafts(): Record<string, string> {
  try {
    const raw = typeof window !== "undefined" ? localStorage.getItem(COMPOSER_DRAFTS_KEY) : null;
    if (!raw) return {};
    const parsed = JSON.parse(raw) as Record<string, unknown>;
    const out: Record<string, string> = {};
    for (const [no, v] of Object.entries(parsed)) {
      if (typeof v === "string" && v.trim() !== "") out[no] = v;
    }
    return out;
  } catch {
    return {};
  }
}

export function saveComposerDrafts(drafts: Record<string, string>) {
  try {
    localStorage.setItem(COMPOSER_DRAFTS_KEY, JSON.stringify(drafts));
  } catch {
    /* 配额满或隐私模式等存储不可用场景：草稿降级为仅本窗口内保留 */
  }
}

/** 读取本地持久化的引用片段胶囊（按工单号键）；损坏/非法条目直接丢弃。 */
export function loadPendingQuotes(): Record<string, QuoteChip[]> {
  try {
    const raw = typeof window !== "undefined" ? localStorage.getItem(PENDING_QUOTES_KEY) : null;
    if (!raw) return {};
    const parsed = JSON.parse(raw) as Record<string, unknown>;
    const out: Record<string, QuoteChip[]> = {};
    for (const [no, v] of Object.entries(parsed)) {
      if (!Array.isArray(v)) continue;
      const chips = v.filter(
        (c): c is QuoteChip =>
          !!c &&
          typeof c === "object" &&
          typeof (c as QuoteChip).id === "string" &&
          typeof (c as QuoteChip).text === "string" &&
          (c as QuoteChip).text.trim() !== "",
      );
      if (chips.length > 0) out[no] = chips;
    }
    return out;
  } catch {
    return {};
  }
}

export function savePendingQuotes(quotes: Record<string, QuoteChip[]>) {
  try {
    localStorage.setItem(PENDING_QUOTES_KEY, JSON.stringify(quotes));
  } catch {
    /* 存储不可用时降级为仅本窗口内保留 */
  }
}

/** 「关闭全部终端」确认弹窗是否已选"不再提醒"；缺省为仍需提醒。 */
export function loadTerminalCloseAllConfirmed(): boolean {
  try {
    return typeof window !== "undefined" && localStorage.getItem(TERMINAL_CLOSE_ALL_KEY) === "1";
  } catch {
    return false;
  }
}

export function saveTerminalCloseAllConfirmed(neverAsk: boolean) {
  try {
    localStorage.setItem(TERMINAL_CLOSE_ALL_KEY, neverAsk ? "1" : "0");
  } catch {
    /* ignore */
  }
}

/* ─── 会话分组（T-105）：端侧软数据（live 后端无分组 API），损坏条目直接丢弃 ─── */

/** 读取落盘的分组表 + 会话归属表；非法/损坏条目跳过，保证恢复后的形状总是完整。 */
export function loadSessionGroups(): PersistedSessionGroups {
  try {
    const raw = typeof window !== "undefined" ? localStorage.getItem(SESSION_GROUPS_KEY) : null;
    if (!raw) return { groups: {}, members: {} };
    const parsed = JSON.parse(raw) as Partial<PersistedSessionGroups>;
    const groups: Record<string, SessionGroup[]> = {};
    for (const [no, list] of Object.entries(parsed.groups ?? {})) {
      if (!Array.isArray(list)) continue;
      const valid = list.filter(
        (g): g is SessionGroup =>
          !!g && typeof g === "object" && typeof g.id === "string" && g.id !== "" &&
          typeof g.name === "string" && g.name.trim() !== "" && typeof g.color === "string" && g.color !== "",
      );
      if (valid.length > 0) groups[no] = valid;
    }
    const members: Record<string, string> = {};
    for (const [sid, gid] of Object.entries(parsed.members ?? {})) {
      if (typeof sid === "string" && sid !== "" && typeof gid === "string" && gid !== "") {
        members[sid] = gid;
      }
    }
    return { groups, members };
  } catch {
    return { groups: {}, members: {} };
  }
}

export function saveSessionGroups(data: PersistedSessionGroups) {
  try {
    localStorage.setItem(SESSION_GROUPS_KEY, JSON.stringify(data));
  } catch {
    /* 存储不可用时降级为仅本窗口内保留 */
  }
}

/** 读取落盘的置顶会话（key = 工单号；有序 sessionId 数组）。 */
export function loadSessionPinned(): Record<string, string[]> {
  try {
    const raw = typeof window !== "undefined" ? localStorage.getItem(SESSION_PINNED_KEY) : null;
    if (!raw) return {};
    const parsed = JSON.parse(raw) as Record<string, unknown>;
    const out: Record<string, string[]> = {};
    for (const [no, v] of Object.entries(parsed)) {
      if (!Array.isArray(v)) continue;
      const ids = v.filter((x): x is string => typeof x === "string" && x !== "");
      if (ids.length > 0) out[no] = ids;
    }
    return out;
  } catch {
    return {};
  }
}

export function saveSessionPinned(pinned: Record<string, string[]>) {
  try {
    localStorage.setItem(SESSION_PINNED_KEY, JSON.stringify(pinned));
  } catch {
    /* 存储不可用时降级为仅本窗口内保留 */
  }
}

/* ─── 消息排队与插队偏好（T-107） ─── */

export function loadFollowUpBehavior(): "queue" | "steer" {
  try {
    const raw = typeof window !== "undefined" ? localStorage.getItem(FOLLOW_UP_BEHAVIOR_KEY) : null;
    return raw === "steer" ? "steer" : "queue";
  } catch {
    return "queue";
  }
}

export function saveFollowUpBehavior(behavior: "queue" | "steer") {
  try {
    localStorage.setItem(FOLLOW_UP_BEHAVIOR_KEY, behavior);
  } catch {
    /* ignore */
  }
}

export function loadQueuedMessages(): Record<string, import("@/shared/types").QueuedMessage[]> {
  try {
    const raw = typeof window !== "undefined" ? localStorage.getItem(QUEUED_MESSAGES_KEY) : null;
    if (!raw) return {};
    const parsed = JSON.parse(raw) as Record<string, unknown>;
    const out: Record<string, import("@/shared/types").QueuedMessage[]> = {};
    for (const [sid, list] of Object.entries(parsed)) {
      if (!Array.isArray(list)) continue;
      const valid = list.filter((item): item is import("@/shared/types").QueuedMessage =>
        item && typeof item === "object" && typeof item.id === "string" && typeof item.content === "string",
      );
      if (valid.length > 0) out[sid] = valid;
    }
    return out;
  } catch {
    return {};
  }
}

export function saveQueuedMessages(data: Record<string, import("@/shared/types").QueuedMessage[]>) {
  try {
    // 过滤掉空数组以保持存储整洁
    const clean: Record<string, import("@/shared/types").QueuedMessage[]> = {};
    for (const [sid, list] of Object.entries(data)) {
      if (list && list.length > 0) clean[sid] = list;
    }
    localStorage.setItem(QUEUED_MESSAGES_KEY, JSON.stringify(clean));
  } catch {
    /* ignore */
  }
}
