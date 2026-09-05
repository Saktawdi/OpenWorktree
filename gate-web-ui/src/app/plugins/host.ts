/**
 * 插件系统（app/plugins）：宿主运行时。
 *
 * 生命周期定义（前端"热插拔"语义）：
 *   加载 = 注入共享 React → 动态 import 入口（?v=cacheTag 缓存击穿）→ activate(ctx) 注册贡献点；
 *   禁用 = 逆序执行 Disposable 链 → 移除贡献点与样式 link；
 *   重载 = 禁用 → 以新 cacheTag 重新 import（产物更新后指纹必然变化）。
 * 任何插件异常只进错误面板，不崩主应用。
 */
import "./shared";
import { appStore } from "@/store";
import {
  hostFetch,
  kvDel,
  kvGet,
  kvSet,
  listPlugins,
  requestPluginReload,
  setPluginEnabled,
} from "./api";
import {
  clearAll,
  dropPlugin,
  markPlugin,
  registerAction,
  registerWidget,
  setCatalog,
} from "./state";
import type {
  Disposable,
  HostFetchRequest,
  PanelWidgetContribution,
  ChatInputActionContribution,
  PluginContext,
  PluginEntryModule,
  PluginKv,
  PluginListItem,
} from "./types";

interface ActivePlugin {
  info: PluginListItem;
  disposers: Disposable[];
  cssLink: HTMLLinkElement | null;
}

const active = new Map<string, ActivePlugin>();
let syncPromise: Promise<void> | null = null;

/** live + 后端连通才允许初始化；由 boot 与模式切换处调用。幂等（并发/StrictMode 双调用安全）。 */
export function initPlugins(): Promise<void> {
  const st = appStore.getState();
  if (st.mode !== "live" || st.conn !== "ok") {
    return Promise.resolve();
  }
  syncPromise ??= syncPlugins().catch((e) => {
    console.warn("[plugins] 初始化失败", e);
  });
  return syncPromise;
}

/** 断连/退出席位：卸载全部插件并复位（下一次 initPlugins 重新同步）。 */
export function shutdownPlugins() {
  for (const id of [...active.keys()]) {
    unloadPlugin(id);
  }
  clearAll();
  syncPromise = null;
}

/** 重新拉目录并做差量加载/卸载（设置页「刷新」与启停/重载后调用；总是强制重新同步）。 */
export async function refreshPlugins(): Promise<void> {
  if (appStore.getState().mode !== "live") return;
  syncPromise = null;
  syncPromise = syncPlugins().catch((e) => {
    console.warn("[plugins] 同步失败", e);
  });
  return syncPromise;
}

async function syncPlugins(): Promise<void> {
  let list: PluginListItem[];
  try {
    list = await listPlugins();
  } catch (e) {
    setCatalog([], `插件目录读取失败：${(e as Error).message}`, false);
    return;
  }
  // 目录视图先行（error 状态在 markPlugin 里写入，必须先有目录行）。
  setCatalog(list, null, true);
  // 差量：已禁用/消失的卸载；新启用且未加载的加载；指纹变化的整装重载。
  for (const item of list) {
    const current = active.get(item.id);
    if (!item.enabled) {
      if (current) unloadPlugin(item.id);
      continue;
    }
    if (current && current.info.cacheTag !== item.cacheTag) {
      unloadPlugin(item.id);
    }
    if (!active.has(item.id)) {
      await loadPlugin(item);
    }
  }
  for (const id of [...active.keys()]) {
    if (!list.some((i) => i.id === id && i.enabled)) {
      unloadPlugin(id);
    }
  }
}

export async function togglePlugin(id: string, enabled: boolean): Promise<void> {
  await setPluginEnabled(id, enabled);
  if (!enabled) {
    unloadPlugin(id);
    await syncCatalogOnly();
  } else {
    await refreshAfterChange();
  }
}

export async function reloadPluginById(id: string): Promise<void> {
  await requestPluginReload(id);
  if (active.has(id)) {
    unloadPlugin(id);
  }
  await refreshAfterChange();
}

async function refreshAfterChange(): Promise<void> {
  syncPromise = null;
  await refreshPlugins();
}

async function syncCatalogOnly(): Promise<void> {
  try {
    setCatalog(await listPlugins(), null, true);
  } catch {
    /* 目录读不到时保留当前视图 */
  }
}

/* ─── 加载 / 卸载 ─── */

async function loadPlugin(info: PluginListItem): Promise<void> {
  if (active.has(info.id)) return;
  const cssLink = injectCss(info);
  const disposers: Disposable[] = [];
  try {
    const url = `/plugins/${info.id}/${info.entry}?v=${info.cacheTag}`;
    // @vite-ignore：URL 运行时才拼得出，交给浏览器原生 import（dev 走 /plugins 代理）。
    const mod = (await import(/* @vite-ignore */ url)) as Record<string, unknown>;
    const candidate =
      typeof mod.activate === "function" ? mod : (mod.default as Record<string, unknown> | undefined);
    const activate =
      typeof candidate === "function" ? candidate : candidate?.activate;
    if (typeof activate !== "function") {
      throw new Error("入口未导出 activate(ctx)");
    }
    const ctx = buildContext(info, disposers);
    const returned = (activate as PluginEntryModule["activate"])(ctx);
    if (typeof returned === "function") disposers.push(returned);
    active.set(info.id, { info, disposers, cssLink });
    markPlugin(info.id, "active", null);
  } catch (e) {
    // 激活失败：清掉半注册的贡献点与样式，记录错误供设置页展示。
    disposers.forEach((d) => safeDispose(info.id, d));
    cssLink?.remove();
    const msg = (e as Error).message ?? String(e);
    console.warn(`[plugins] ${info.id} 激活失败：${msg}`);
    markPlugin(info.id, "error", msg);
  }
}

function unloadPlugin(id: string): void {
  const plugin = active.get(id);
  if (!plugin) return;
  active.delete(id);
  // 逆序执行：后注册的资源先释放（与宿主内其它 Disposable 链约定一致）。
  for (let i = plugin.disposers.length - 1; i >= 0; i--) {
    safeDispose(id, plugin.disposers[i]);
  }
  plugin.cssLink?.remove();
  dropPlugin(id);
}

function safeDispose(id: string, d: Disposable) {
  try {
    d();
  } catch (e) {
    console.warn(`[plugins] ${id} 清理回调异常`, e);
  }
}

function buildContext(info: PluginListItem, disposers: Disposable[]): PluginContext {
  const manifest: PluginContext["manifest"] = {
    id: info.id,
    name: info.name,
    version: info.version,
    apiVersion: info.apiVersion,
    entry: info.entry,
    css: info.css,
    description: info.description,
    permissions: info.permissions,
  };
  const context: PluginContext = {
    pluginId: info.id,
    manifest,
    registerChatInputAction(action: ChatInputActionContribution) {
      const dispose = registerAction(info.id, action);
      disposers.push(dispose);
      return dispose;
    },
    registerPanelWidget(widget: PanelWidgetContribution) {
      const dispose = registerWidget(info.id, widget);
      disposers.push(dispose);
      return dispose;
    },
    kv: info.permissions.includes("kv") ? makeKv(info.id) : null,
    async hostFetch<T>(path: string, init?: HostFetchRequest) {
      if (!info.permissions.includes("net")) {
        throw new Error("manifest 未声明 net 权限，无法使用 hostFetch");
      }
      return hostFetch<T>(path, init);
    },
    onDeactivate(fn: Disposable) {
      disposers.push(fn);
    },
    log(...args: unknown[]) {
      console.log(`[plugin:${info.id}]`, ...args);
    },
  };
  return context;
}

function makeKv(pluginId: string): PluginKv {
  return {
    get: <T,>(key: string) => kvGet<T>(pluginId, key),
    set: (key: string, value: unknown) => kvSet(pluginId, key, value),
    del: (key: string) => kvDel(pluginId, key),
  };
}

function injectCss(info: PluginListItem): HTMLLinkElement | null {
  if (!info.css) return null;
  if (document.querySelector(`link[data-gate-plugin="${info.id}"]`)) return null;
  const link = document.createElement("link");
  link.rel = "stylesheet";
  link.dataset.gatePlugin = info.id;
  link.href = `/plugins/${info.id}/${info.css}?v=${info.cacheTag}`;
  document.head.appendChild(link);
  return link;
}
