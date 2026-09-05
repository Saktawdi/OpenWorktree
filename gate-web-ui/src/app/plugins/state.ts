/**
 * 插件系统（app/plugins）：贡献点注册表（独立 zustand store）。
 *
 * 不并入 appStore：贡献物是函数（不可 JSON 序列化），也不该进工作台快照；
 * 插件启停/重载本身就是注册表的生命周期，重进页面时由 host 重建。
 */
import { create } from "zustand";
import type { ChatInputActionContribution, PanelWidgetContribution, PluginListItem } from "./types";

type Disposable = () => void;

export type PluginStatus = "active" | "error" | "off";

export interface PluginView extends PluginListItem {
  status: PluginStatus;
  /** status === "error" 时的原因（激活异常/入口缺失等）。 */
  error: string | null;
}

export interface RegisteredAction {
  pluginId: string;
  action: ChatInputActionContribution;
}

export interface RegisteredWidget {
  pluginId: string;
  widget: PanelWidgetContribution;
}

interface PluginsState {
  /** 已完成至少一次目录同步（后端不可达时保持 false，设置页据此显示空态）。 */
  loaded: boolean;
  plugins: PluginView[];
  actions: RegisteredAction[];
  widgets: RegisteredWidget[];
  /** 最近一次目录同步失败原因；null = 正常。 */
  listError: string | null;
}

export const pluginStore = create<PluginsState>(() => ({
  loaded: false,
  plugins: [],
  actions: [],
  widgets: [],
  listError: null,
}));

export function usePlugins<T>(selector: (s: PluginsState) => T): T {
  return pluginStore(selector);
}

const set = pluginStore.setState;

/* ─── 供 host 调用的注册表操作（全部幂等，可安全重复 dispose） ─── */

/** 整表替换目录视图（保留已加载插件激活失败的 error 状态）。 */
export function setCatalog(list: PluginListItem[], listError: string | null, loaded: boolean) {
  set((st) => {
    const prev = new Map(st.plugins.map((p) => [p.id, p]));
    return {
      loaded,
      listError,
      plugins: list.map((item) => {
        const existing = prev.get(item.id);
        // 禁用一律 off；启用时仅延续 error（激活失败）状态，off/active 由 host 加载后敲定。
        const status: PluginStatus = !item.enabled
          ? "off"
          : existing?.status === "error"
            ? "error"
            : "active";
        return { ...item, status, error: status === "error" ? (existing?.error ?? null) : null };
      }),
    };
  });
}

export function markPlugin(id: string, status: PluginStatus, error: string | null) {
  set((st) => ({
    plugins: st.plugins.map((p) => (p.id === id ? { ...p, status, error } : p)),
  }));
}

export function dropPlugin(id: string) {
  set((st) => ({
    plugins: st.plugins.map((p) => (p.id === id ? { ...p, status: "off", error: null } : p)),
    actions: st.actions.filter((a) => a.pluginId !== id),
    widgets: st.widgets.filter((w) => w.pluginId !== id),
  }));
}

export function clearAll() {
  set({ loaded: false, plugins: [], actions: [], widgets: [], listError: null });
}

export function registerAction(pluginId: string, action: ChatInputActionContribution): Disposable {
  set((st) => ({ actions: [...st.actions, { pluginId, action }] }));
  return () => {
    set((st) => ({
      actions: st.actions.filter((a) => !(a.pluginId === pluginId && a.action === action)),
    }));
  };
}

export function registerWidget(pluginId: string, widget: PanelWidgetContribution): Disposable {
  set((st) => ({ widgets: [...st.widgets, { pluginId, widget }] }));
  return () => {
    set((st) => ({
      widgets: st.widgets.filter((w) => !(w.pluginId === pluginId && w.widget === widget)),
    }));
  };
}
