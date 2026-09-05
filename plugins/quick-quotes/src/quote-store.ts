/**
 * 插件内部语录存储：极简 observable（useSyncExternalStore 直接可订阅）。
 * 语录变更 → 同步重注册 chips（经宿主 pluginStore）+ 防抖 KV 落盘（见 index.tsx）。
 */
import type { QuoteItem } from "./quotes";

type Listener = () => void;

let snapshot: QuoteItem[] = [];

const listeners = new Set<Listener>();

export const quoteStore = {
  get(): QuoteItem[] {
    return snapshot;
  },
  set(next: QuoteItem[]) {
    snapshot = next;
    listeners.forEach((l) => l());
  },
  subscribe(l: Listener): () => void {
    listeners.add(l);
    return () => {
      listeners.delete(l);
    };
  },
};
