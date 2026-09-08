/**
 * 插件内部设置存储：划选动作的开关与自定义文案（极简 observable）。
 * 变更 → 重注册划选动作 + 防抖 KV 落盘（见 index.tsx）。
 */
export interface SelectionSettings {
  /** 划选菜单是否显示「引用并追问」动作。 */
  enabled: boolean;
  /** 动作在划选菜单里显示的文案（空则用默认）。 */
  label: string;
  /** 点击动作后追加到对话框的追问文案（空则用默认）。 */
  prompt: string;
}

export const SELECTION_DEFAULTS: SelectionSettings = {
  enabled: true,
  label: "引用并追问",
  prompt: "请结合上面的引用展开说明：",
};

type Listener = () => void;

let snapshot: SelectionSettings = { ...SELECTION_DEFAULTS };

const listeners = new Set<Listener>();

function sanitize(raw: unknown): SelectionSettings {
  if (raw == null || typeof raw !== "object") return { ...SELECTION_DEFAULTS };
  const r = raw as Record<string, unknown>;
  return {
    enabled: typeof r.enabled === "boolean" ? r.enabled : SELECTION_DEFAULTS.enabled,
    label: typeof r.label === "string" ? r.label : SELECTION_DEFAULTS.label,
    prompt: typeof r.prompt === "string" ? r.prompt : SELECTION_DEFAULTS.prompt,
  };
}

export const selectionSettingsStore = {
  get(): SelectionSettings {
    return snapshot;
  },
  set(next: SelectionSettings) {
    snapshot = sanitize(next);
    listeners.forEach((l) => l());
  },
  /** 从 KV 恢复（装载时调用；缺键保持默认，并通知所有外部订阅者更新状态）。 */
  loadFrom(raw: unknown) {
    snapshot = sanitize(raw);
    listeners.forEach((l) => l());
  },
  subscribe(l: Listener): () => void {
    listeners.add(l);
    return () => {
      listeners.delete(l);
    };
  },
};
