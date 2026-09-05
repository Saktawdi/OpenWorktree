/**
 * 插件系统（app/plugins）：贡献点注册表（独立 zustand store）。
 *
 * 不并入 appStore：贡献物是函数（不可 JSON 序列化），也不该进工作台快照；
 * 插件启停/重载本身就是注册表的生命周期，重进页面时由 host 重建。
 *
 * 注册表是插槽总线：所有贡献物统一落在 contributions[]（按区域名 + 注册顺序），
 * 渲染方按 SlotName 过滤消费——新增区域/贡献不需要改注册表本身。
 */
import { create } from "zustand";
import type { PluginListItem } from "./types";

type Disposable = () => void;

export type PluginStatus = "active" | "error" | "off";

export interface PluginView extends PluginListItem {
  status: PluginStatus;
  /** status === "error" 时的原因（激活异常/入口缺失等）。 */
  error: string | null;
}

/** 一条插件贡献物：归属插件 + 目标区域 + 贡献物本体（契约由 slots.ts 的 SlotContributionMap 锚定）。 */
export interface RegisteredContribution {
  pluginId: string;
  slot: string;
  contribution: unknown;
}

interface PluginsState {
  /** 已完成至少一次目录同步（后端不可达时保持 false，设置页据此显示空态）。 */
  loaded: boolean;
  plugins: PluginView[];
  contributions: RegisteredContribution[];
  /** 最近一次目录同步失败原因；null = 正常。 */
  listError: string | null;
}

export const pluginStore = create<PluginsState>(() => ({
  loaded: false,
  plugins: [],
  contributions: [],
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
    contributions: st.contributions.filter((c) => c.pluginId !== id),
  }));
}

export function clearAll() {
  set({ loaded: false, plugins: [], contributions: [], listError: null });
}

export function registerContribution(
  pluginId: string,
  slot: string,
  contribution: unknown,
): Disposable {
  set((st) => ({ contributions: [...st.contributions, { pluginId, slot, contribution }] }));
  return () => {
    set((st) => ({
      contributions: st.contributions.filter(
        (c) => !(c.pluginId === pluginId && c.slot === slot && c.contribution === contribution),
      ),
    }));
  };
}
