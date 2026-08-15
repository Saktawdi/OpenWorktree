/**
 * 测试辅助 — Pinia 初始化 + localStorage stub.
 *
 * useSSE 在 jsdom 下需要 localStorage; vitest 默认 jsdom 提供了, 但保险起见每用例重置.
 */
import { createPinia, setActivePinia } from 'pinia';

export function setupPinia(): void {
  setActivePinia(createPinia());
}

export function resetLocalStorage(): void {
  try {
    localStorage.clear();
  } catch {
    // 静默
  }
}

/** 模拟 EventSource: 推入若干 message + done, 触发 onopen / onmessage / 命名事件 / onerror. */
export interface FakeEventSourceOptions {
  /** 待推事件列表 (kind=事件名, data=字符串; 'message' 为默认事件). */
  events: Array<{ kind: string; data: string }>;
  /** 推送间隔 (ms). */
  interval?: number;
  /** open 前是否先抛 error 触发重连测试. */
  errorBeforeOpen?: boolean;
}

/** 推入一个 fake EventSource 构造器到 globalThis.EventSource. */
export function installFakeEventSource(opts: FakeEventSourceOptions): {
  instances: Array<{
    url: string;
    close: () => void;
    triggerError: () => void;
  }>;
  cleanup: () => void;
} {
  const instances: Array<{ url: string; close: () => void; triggerError: () => void }> = [];
  const interval = opts.interval ?? 0;
  let cleared = false;

  class FakeEventSource {
    static readonly CONNECTING = 0;
    static readonly OPEN = 1;
    static readonly CLOSED = 2;
    readonly CONNECTING = 0;
    readonly OPEN = 1;
    readonly CLOSED = 2;
    readyState: number = FakeEventSource.CONNECTING;
    url: string;
    onopen: ((ev: Event) => void) | null = null;
    onmessage: ((ev: MessageEvent) => void) | null = null;
    onerror: ((ev: Event) => void) | null = null;
    private listeners: Record<string, Array<(ev: MessageEvent) => void>> = {};
    private timer: ReturnType<typeof setInterval> | null = null;

    constructor(url: string) {
      this.url = url;
      instances.push({
        url,
        close: () => this.doClose(),
        triggerError: () => this.fireError(),
      });

      if (opts.errorBeforeOpen) {
        // 模拟初始连接失败.
        setTimeout(() => {
          if (cleared) return;
          this.readyState = FakeEventSource.CLOSED;
          this.fireError();
        }, 0);
        return;
      }

      // open.
      setTimeout(() => {
        if (cleared) return;
        this.readyState = FakeEventSource.OPEN;
        this.onopen?.(new Event('open'));
        // 推事件.
        let i = 0;
        if (opts.events.length === 0) return;
        const tick = () => {
          if (cleared || i >= opts.events.length) return;
          const ev = opts.events[i++];
          if (ev === undefined) return;
          if (ev.kind === 'message') {
            const me = new MessageEvent('message', { data: ev.data });
            this.onmessage?.(me);
            // 同步触发 addEventListener('message', ...) 注册的 listeners (与浏览器行为一致).
            for (const fn of this.listeners['message'] ?? []) {
              fn(me);
            }
          } else {
            const arr = this.listeners[ev.kind] ?? [];
            for (const fn of arr) {
              fn(new MessageEvent(ev.kind, { data: ev.data }));
            }
          }
        };
        if (interval > 0) {
          this.timer = setInterval(tick, interval);
        } else {
          while (i < opts.events.length) tick();
        }
      }, 0);
    }

    addEventListener(name: string, fn: (ev: MessageEvent) => void): void {
      if (!this.listeners[name]) this.listeners[name] = [];
      this.listeners[name].push(fn);
    }
    removeEventListener(name: string, fn: (ev: MessageEvent) => void): void {
      const arr = this.listeners[name];
      if (!arr) return;
      this.listeners[name] = arr.filter((f) => f !== fn);
    }
    dispatchEvent(): boolean {
      return true;
    }
    close(): void {
      this.doClose();
    }
    private doClose(): void {
      this.readyState = FakeEventSource.CLOSED;
      if (this.timer !== null) clearInterval(this.timer);
      this.timer = null;
    }
    private fireError(): void {
      this.onerror?.(new Event('error'));
    }
  }

  // 保存原值, cleanup 时还原.
  const original = (globalThis as { EventSource?: typeof EventSource }).EventSource;
  (globalThis as { EventSource: typeof EventSource }).EventSource =
    FakeEventSource as unknown as typeof EventSource;

  return {
    instances,
    cleanup: () => {
      cleared = true;
      if (original) {
        (globalThis as { EventSource: typeof EventSource }).EventSource = original;
      } else {
        delete (globalThis as { EventSource?: typeof EventSource }).EventSource;
      }
    },
  };
}
