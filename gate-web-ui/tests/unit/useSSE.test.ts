/**
 * useSSE 测试 — EventSource 封装 + ?token= query + 命名事件分发.
 *
 * 用 FakeEventSource (tests/utils.ts) 替换 globalThis.EventSource, 验证:
 *   - URL 拼接 ?token= (有 token 时)
 *   - 默认 message 事件分发
 *   - 命名事件 (e.g. 'done') 分发
 *   - state 变化 (idle → connecting → open)
 *
 * FakeEventSource 用 setTimeout(0) 调度 open + 事件; 用 fake timers + flushPromises 双管齐下.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises } from '@vue/test-utils';
import { useSSE } from '@/composables/useSSE';
import {
  installFakeEventSource,
  resetLocalStorage,
  setupPinia,
} from '../utils';

beforeEach(() => {
  resetLocalStorage();
  setupPinia();
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  resetLocalStorage();
});

/** 走完 FakeEventSource 的 setTimeout(0) open + 后续推事件. */
async function flushSSE(events: number, interval: number): Promise<void> {
  await flushPromises();
  if (interval > 0 && events > 0) {
    for (let i = 0; i < events; i++) {
      vi.advanceTimersByTime(interval);
      await flushPromises();
    }
  } else {
    vi.advanceTimersByTime(0);
    await flushPromises();
  }
}

describe('useSSE', () => {
  it('URL 拼接 ?token= (localStorage 有 token)', async () => {
    localStorage.setItem('gate_token', 'tok123');
    const fake = installFakeEventSource({ events: [], interval: 0 });
    try {
      useSSE({ path: '/api/tasks/abc/events', immediate: true });
      await flushSSE(0, 0);
      expect(fake.instances.length).toBe(1);
      expect(fake.instances[0]?.url).toBe('/api/tasks/abc/events?token=tok123');
    } finally {
      fake.cleanup();
    }
  });

  it('无 token 时不带 query', async () => {
    const fake = installFakeEventSource({ events: [], interval: 0 });
    try {
      useSSE({ path: '/api/tasks/abc/events', immediate: true });
      await flushSSE(0, 0);
      expect(fake.instances[0]?.url).toBe('/api/tasks/abc/events');
    } finally {
      fake.cleanup();
    }
  });

  it('URL 已含 query 时用 & 拼接', async () => {
    localStorage.setItem('gate_token', 'tok');
    const fake = installFakeEventSource({ events: [], interval: 0 });
    try {
      useSSE({ path: '/api/tasks/abc/events?foo=bar', immediate: true });
      await flushSSE(0, 0);
      expect(fake.instances[0]?.url).toBe('/api/tasks/abc/events?foo=bar&token=tok');
    } finally {
      fake.cleanup();
    }
  });

  it('默认 message 事件分发', async () => {
    const fake = installFakeEventSource({
      events: [
        { kind: 'message', data: '{"percent":10}' },
        { kind: 'message', data: '{"percent":50}' },
      ],
      interval: 0,
    });
    try {
      const onMessage = vi.fn();
      const { state } = useSSE({
        path: '/api/tasks/abc/events',
        immediate: true,
        handlers: { message: onMessage },
      });
      await flushSSE(2, 0);
      expect(onMessage).toHaveBeenCalledTimes(2);
      expect(onMessage).toHaveBeenNthCalledWith(1, '{"percent":10}', expect.any(MessageEvent));
      expect(state.value).toBe('open');
    } finally {
      fake.cleanup();
    }
  });

  it('命名事件 (done) 分发', async () => {
    const fake = installFakeEventSource({
      events: [{ kind: 'done', data: '{"status":"SUCCEEDED"}' }],
      interval: 0,
    });
    try {
      const onDone = vi.fn();
      const onMessage = vi.fn();
      useSSE({
        path: '/api/tasks/abc/events',
        immediate: true,
        handlers: { done: onDone, message: onMessage },
      });
      await flushSSE(1, 0);
      expect(onDone).toHaveBeenCalledTimes(1);
      expect(onDone).toHaveBeenCalledWith('{"status":"SUCCEEDED"}', expect.any(MessageEvent));
      expect(onMessage).not.toHaveBeenCalled();
    } finally {
      fake.cleanup();
    }
  });

  it('close() 后 state=closed, 不再分发', async () => {
    const fake = installFakeEventSource({ events: [], interval: 100 });
    try {
      const { state, close } = useSSE({
        path: '/api/tasks/abc/events',
        immediate: true,
      });
      await flushSSE(0, 0);
      close();
      expect(state.value).toBe('closed');
    } finally {
      fake.cleanup();
    }
  });

  it('state 变化: idle → connecting → open', async () => {
    const fake = installFakeEventSource({
      events: [{ kind: 'message', data: 'hi' }],
      interval: 0,
    });
    try {
      const { state } = useSSE({
        path: '/api/tasks/abc/events',
        immediate: true,
      });
      expect(state.value).toBe('connecting');
      await flushSSE(1, 0);
      expect(state.value).toBe('open');
    } finally {
      fake.cleanup();
    }
  });

  it('网络错误 (readyState≠CLOSED) → 指数退避重连, 新建连接', async () => {
    const fake = installFakeEventSource({ events: [], interval: 0 });
    try {
      const { state, retryCount } = useSSE({
        path: '/api/tasks/abc/events',
        immediate: true,
      });
      await flushSSE(0, 0); // open 已触发, readyState=OPEN.
      fake.instances[0]?.triggerError(); // 非 CLOSED → 应走重连而非 closed.
      expect(state.value).toBe('reconnecting');
      expect(retryCount.value).toBe(1);
      // 退避 1s 后 connect() 新建 EventSource.
      await vi.advanceTimersByTimeAsync(1000);
      await flushPromises();
      expect(fake.instances.length).toBe(2);
    } finally {
      fake.cleanup();
    }
  });

  it('服务端显式关流 (readyState=CLOSED) → closed, 不重连', async () => {
    const fake = installFakeEventSource({ events: [], interval: 0 });
    try {
      const { state, retryCount } = useSSE({
        path: '/api/tasks/abc/events',
        immediate: true,
      });
      await flushSSE(0, 0);
      // 模拟服务端关流: FakeEventSource.close() 把 readyState 置为 CLOSED.
      fake.instances[0]?.close();
      fake.instances[0]?.triggerError();
      expect(state.value).toBe('closed');
      expect(retryCount.value).toBe(0);
      // 不重连: 推进时间也不会新建连接.
      await vi.advanceTimersByTimeAsync(5000);
      expect(fake.instances.length).toBe(1);
    } finally {
      fake.cleanup();
    }
  });
});
