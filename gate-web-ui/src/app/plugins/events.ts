/**
 * 插件系统（app/plugins）：事件总线（fan-out + handler 异常隔离）。
 *
 * 语义（与 SDK PluginEventMap 注释一致）：
 *   - 事件在语义写入点显式 emit（工单/会话域的状态写入处），不做 store 快照 diff 还原；
 *   - no replay：订阅晚于 emit 即错过，不重放、不去重；
 *   - 单个 handler 抛异常只进日志，不影响其它订阅者，更不崩主应用。
 * emit 不判断插件是否加载：无订阅者时是空操作，事件语义与插件生命周期解耦。
 */
import type { Disposable, PluginEventEnvelope, PluginEventMap } from "./types";

type AnyHandler = (payload: unknown) => void;

/** 键为事件名或 "*"（通配）；Set 保证同 handler 重复注册只生效一次。 */
const handlers = new Map<string, Set<AnyHandler>>();

export function onPluginEvent(event: string, handler: AnyHandler): Disposable {
  let set = handlers.get(event);
  if (!set) {
    set = new Set();
    handlers.set(event, set);
  }
  set.add(handler);
  return () => {
    const cur = handlers.get(event);
    if (!cur) return;
    cur.delete(handler);
    if (cur.size === 0) handlers.delete(event);
  };
}

export function emitPluginEvent<K extends keyof PluginEventMap & string>(
  type: K,
  payload: PluginEventMap[K],
): void {
  // type/payload 由同一 K 关联，TS 无法静态证明配对属于 union，此处断言安全。
  const envelope = { type, payload } as PluginEventEnvelope;
  deliver(type, payload);
  deliver("*", envelope);
}

function deliver(event: string, payload: unknown): void {
  const set = handlers.get(event);
  if (!set) return;
  for (const handler of set) {
    try {
      handler(payload);
    } catch (e) {
      console.warn(`[plugin-events] ${event} 订阅者异常`, e);
    }
  }
}
