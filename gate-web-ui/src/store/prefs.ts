/**
 * 本地偏好持久化（store）：localStorage 键的集中读写。
 * 键名与读取默认值都在这里登记，域内 setter 只调用对应 save/load。
 */
import type { Stage } from "@/shared/types";
import { ALL_STAGES, KANBAN_DEFAULT_STAGES, KANBAN_LANE_COUNT, KANBAN_STAGE_ORDER } from "@/shared/format";
import type { GateSections } from "./state";

const THEME_KEY = "gate-theme";
const AGENT_ID_KEY = "gate-agent-id";
const VISIBLE_STAGES_KEY = "gate-visible-stages";
const KANBAN_STAGES_KEY = "gate-kanban-stages";
const GATE_PANEL_KEY = "gate-panel-collapsed";
const GATE_SECTIONS_KEY = "gate-sections";
const COMPOSER_DRAFTS_KEY = "gate-composer-drafts";

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
