/**
 * 快捷语录插件入口。
 * 职责：KV 装载/种子/原生条目迁移/防抖保存，语录 → composer chips 的注册与重注册，
 * 划选菜单动作（selection.menu，开关与文案可在设置面板配置），管理面板挂件。
 */
import "./styles.css";
import type { PluginContext } from "@gate/plugin-sdk";
import {
  BUILTIN_QUOTES,
  NATIVE_BUILTIN_IDS,
  sanitizeQuotes,
  toAction,
  type QuoteItem,
} from "./quotes";
import { quoteStore } from "./quote-store";
import { SELECTION_DEFAULTS, selectionSettingsStore } from "./selection-settings";
import { QuotesManager } from "./manager";

const KV_KEY = "quotes";
const SELECTION_KV_KEY = "selection-settings";
const SAVE_DEBOUNCE_MS = 500;

export function activate(ctx: PluginContext) {
  const kv = ctx.kv;
  let chipDisposers: Array<() => void> = [];
  let saveTimer: ReturnType<typeof setTimeout> | null = null;
  let selectionSaveTimer: ReturnType<typeof setTimeout> | null = null;
  let disposed = false;

  /* 语录 → chips：每次语录变化先全量注销再重注册（条目级 Disposable 由宿主保证幂等） */
  const syncChips = () => {
    chipDisposers.forEach((d) => d());
    chipDisposers = quoteStore.get().map((q) => ctx.registerChatInputAction(toAction(q)));
  };

  /* 防抖持久化 */
  const persist = () => {
    if (!kv || disposed) return;
    if (saveTimer) clearTimeout(saveTimer);
    saveTimer = setTimeout(() => {
      kv.set(KV_KEY, quoteStore.get()).catch((e) => ctx.log("语录保存失败", e));
    }, SAVE_DEBOUNCE_MS);
  };

  /* 划选动作设置防抖持久化 */
  const persistSelection = () => {
    if (!kv || disposed) return;
    if (selectionSaveTimer) clearTimeout(selectionSaveTimer);
    selectionSaveTimer = setTimeout(() => {
      kv.set(SELECTION_KV_KEY, selectionSettingsStore.get()).catch((e) =>
        ctx.log("划选设置保存失败", e),
      );
    }, SAVE_DEBOUNCE_MS);
  };

  const unsubscribe = quoteStore.subscribe(() => {
    syncChips();
    persist();
  });

  /* 划选动作注册表：开关关闭时注销；文案变化时重注册（每次全量重建，语义与 chips 一致） */
  let selectionDisposer: (() => void) | null = null;
  const syncSelectionAction = () => {
    selectionDisposer?.();
    selectionDisposer = null;
    const settings = selectionSettingsStore.get();
    if (!settings.enabled) return;
    selectionDisposer = ctx.registerSelectionAction({
      id: "quote-and-ask",
      label: settings.label.trim() || SELECTION_DEFAULTS.label,
      icon: "ChatText",
      run(api, text) {
        api.addToComposer(text);
        api.insertText(`\n${settings.prompt.trim() || SELECTION_DEFAULTS.prompt}`);
        api.toast("已加入引用与追问");
      },
    });
  };
  const unsubscribeSelection = selectionSettingsStore.subscribe(() => {
    syncSelectionAction();
    persistSelection();
  });

  /* 初始装载：KV 为空（首次安装）→ 种子内置语录；有值 → 清洗后恢复。
   * round 2 迁移：原生五条种子已回迁宿主，存量 KV 里的对应条目过滤回写，
   * 否则会与宿主原生 chip 成对重复。 */
  const boot = async () => {
    if (!kv) {
      quoteStore.set([...BUILTIN_QUOTES]);
      return;
    }
    try {
      const stored = await kv.get<unknown>(KV_KEY);
      if (stored == null) {
        quoteStore.set([...BUILTIN_QUOTES]);
        void kv.set(KV_KEY, BUILTIN_QUOTES);
        return;
      }
      const nativeIds = new Set<string>(NATIVE_BUILTIN_IDS);
      const migrated = sanitizeQuotes(stored).filter((q) => !nativeIds.has(q.id));
      quoteStore.set(migrated);
      void kv.set(KV_KEY, migrated);
    } catch (e) {
      ctx.log("语录装载失败，回退内置", e);
      quoteStore.set([...BUILTIN_QUOTES]);
    }
    /* 划选动作设置装载（缺键保持默认，不打扰 KV） */
    try {
      const sel = await kv.get<unknown>(SELECTION_KV_KEY);
      if (sel != null) selectionSettingsStore.loadFrom(sel);
      syncSelectionAction();
    } catch (e) {
      ctx.log("划选设置装载失败", e);
      syncSelectionAction();
    }
  };
  void boot();

  /* 管理面板（设置 → 插件 → 快捷语录） */
  const disposeWidget = ctx.registerPanelWidget({
    id: "quotes-manager",
    title: "快捷语录管理",
    render: () => (
      <QuotesManager
        hasKv={!!kv}
        onChange={(next) => quoteStore.set(next)}
        loadMcpTools={() => ctx.hostFetch("/api/mcp/status")}
        selectionSettings={selectionSettingsStore}
        canConfigureSelection={!!kv}
      />
    ),
  });

  return () => {
    disposed = true;
    unsubscribe();
    unsubscribeSelection();
    chipDisposers.forEach((d) => d());
    selectionDisposer?.();
    if (saveTimer) clearTimeout(saveTimer);
    if (selectionSaveTimer) clearTimeout(selectionSaveTimer);
    disposeWidget();
  };
}
