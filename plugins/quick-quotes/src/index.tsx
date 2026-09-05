/**
 * 快捷语录插件入口。
 * 职责：KV 装载/种子/防抖保存，语录 → composer chips 的注册与重注册，管理面板挂件。
 */
import "./styles.css";
import type { PluginContext } from "./host-types";
import { BUILTIN_QUOTES, sanitizeQuotes, toAction, type QuoteItem } from "./quotes";
import { quoteStore } from "./quote-store";
import { QuotesManager } from "./manager";

const KV_KEY = "quotes";
const SAVE_DEBOUNCE_MS = 500;

export function activate(ctx: PluginContext) {
  const kv = ctx.kv;
  let chipDisposers: Array<() => void> = [];
  let saveTimer: ReturnType<typeof setTimeout> | null = null;
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

  const unsubscribe = quoteStore.subscribe(() => {
    syncChips();
    persist();
  });

  /* 初始装载：KV 为空（首次安装）→ 种子内置语录；有值 → 清洗后恢复 */
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
      quoteStore.set(sanitizeQuotes(stored));
    } catch (e) {
      ctx.log("语录装载失败，回退内置", e);
      quoteStore.set([...BUILTIN_QUOTES]);
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
      />
    ),
  });

  return () => {
    disposed = true;
    unsubscribe();
    chipDisposers.forEach((d) => d());
    if (saveTimer) clearTimeout(saveTimer);
    disposeWidget();
  };
}
