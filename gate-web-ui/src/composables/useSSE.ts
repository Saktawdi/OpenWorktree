/**
 * SSE 统一封装 (ADR-F3).
 *
 * EventSource 不支持自定义 header, 故 token 走 ?token= query param.
 * 后端文档 §3.4.1: SSE 端点的 Filter 额外接受 ?token= (优先 header, 缺失回落 query).
 *
 * 职责:
 *   - token 注入: 从 authStore / localStorage 读, 拼到 URL query.
 *   - 重连: 指数退避 (1s, 2s, 4s, ..., 上限 30s), 上限后停止重连.
 *   - 事件分发: 默认 message 事件 + 命名事件 (event: <name>).
 *   - 销毁自动 close(): 防 EventSource 泄漏.
 *
 * 复用点 (前端文档 §5.2):
 *   - 审核进度 SSE: GET /api/tasks/{id}/events (S2)
 *   - 发布进度 SSE: GET /api/tasks/{id}/events (S2)
 *   - 会话流式 SSE: GET /api/sessions/{sid}/events (S3b)
 */
import { onBeforeUnmount, ref, type Ref } from 'vue';

export interface UseSSEOptions {
  /** SSE 完整路径 (相对 origin, e.g. /api/tasks/abc/events). */
  path: string;
  /** 是否激活; false 时不连接. 默认 true. */
  immediate?: boolean;
  /** 最大重连次数 (默认 6 次, 第 6 次退避到 ~30s). */
  maxRetries?: number;
  /**
   * 事件处理器映射. key 为事件名 (默认事件用 'message').
   *   - 后端 SSE 发 `event: done\n` 时, handler key = 'done'.
   *   - 后端发无名事件 `data: {...}\n\n` 时, handler key = 'message'.
   */
  handlers?: Record<string, (data: string, rawEvent: MessageEvent) => void>;
  /**
   * 连接状态变化回调 (调试用).
   */
  onStateChange?: (state: SSEConnectionState) => void;
}

export type SSEConnectionState = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'closed' | 'error';

export interface UseSSEReturn {
  readonly state: Ref<SSEConnectionState>;
  readonly retryCount: Ref<number>;
  readonly lastError: Ref<string | null>;
  /** 关闭连接 (手动). 之后不会再重连. */
  close: () => void;
  /** 重新打开 (重置 retryCount). */
  reopen: () => void;
}

const STORAGE_KEY = 'gate_token';

function readToken(): string {
  try {
    return localStorage.getItem(STORAGE_KEY) ?? '';
  } catch {
    return '';
  }
}

/** 拼接 ?token= query (若 url 已含 query 则用 &). */
function appendToken(path: string, token: string): string {
  if (token.length === 0) return path;
  const sep = path.includes('?') ? '&' : '?';
  return `${path}${sep}token=${encodeURIComponent(token)}`;
}

export function useSSE(opts: UseSSEOptions): UseSSEReturn {
  const immediate = opts.immediate ?? true;
  const maxRetries = opts.maxRetries ?? 6;
  const handlers = opts.handlers ?? {};

  const state = ref<SSEConnectionState>('idle');
  const retryCount = ref(0);
  const lastError = ref<string | null>(null);

  let es: EventSource | null = null;
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  /** 用户主动 close 后不再重连. */
  let closed = false;

  function setState(next: SSEConnectionState): void {
    state.value = next;
    opts.onStateChange?.(next);
  }

  function scheduleReconnect(): void {
    if (closed) return;
    if (retryCount.value >= maxRetries) {
      setState('error');
      lastError.value = `重连上限 (${maxRetries}) 已达, 停止重连`;
      return;
    }
    // 指数退避: 1s, 2s, 4s, 8s, 16s, 30s (cap).
    const delay = Math.min(1000 * 2 ** retryCount.value, 30_000);
    retryCount.value += 1;
    setState('reconnecting');
    reconnectTimer = setTimeout(() => {
      if (closed) return;
      connect();
    }, delay);
  }

  function connect(): void {
    if (closed) return;
    const token = readToken();
    const url = appendToken(opts.path, token);
    setState('connecting');

    try {
      es = new EventSource(url);
    } catch (e) {
      lastError.value = e instanceof Error ? e.message : String(e);
      setState('error');
      scheduleReconnect();
      return;
    }

    es.onopen = () => {
      retryCount.value = 0;
      setState('open');
    };

    es.onerror = (ev) => {
      // EventSource error 没有可读 message; 浏览器会自动重连, 但我们接管重连以应用退避策略.
      lastError.value = 'SSE 连接错误';
      // 先读 readyState 再 close: close 会先把 readyState 置为 CLOSED,
      // 若先 close 再判断则恒为 CLOSED, 重连分支 (scheduleReconnect) 永远不可达.
      const closedByServer = es?.readyState === EventSource.CLOSED;
      // 关闭当前实例避免浏览器默认重连与我们退避策略双触发.
      try {
        es?.close();
      } catch {
        // 静默
      }
      // readyState 在 close 前已是 CLOSED → 服务端显式关流 (如 task done), 不再重连;
      // 否则为网络错误 → 指数退避重连.
      if (closedByServer) {
        setState('closed');
      } else {
        scheduleReconnect();
      }
      // 防止 lint 警告未使用变量
      void ev;
    };

    // 默认 message 事件.
    const messageHandler = handlers['message'];
    if (messageHandler) {
      es.addEventListener('message', (ev: MessageEvent) => {
        messageHandler(ev.data, ev);
      });
    }

    // 命名事件.
    for (const name of Object.keys(handlers)) {
      if (name === 'message') continue;
      const handler = handlers[name];
      if (!handler) continue;
      es.addEventListener(name, (ev: MessageEvent) => {
        handler(ev.data, ev);
      });
    }
  }

  function close(): void {
    closed = true;
    if (reconnectTimer !== null) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    if (es !== null) {
      try {
        es.close();
      } catch {
        // 静默
      }
      es = null;
    }
    setState('closed');
  }

  function reopen(): void {
    closed = false;
    retryCount.value = 0;
    lastError.value = null;
    close();
    closed = false;
    connect();
  }

  if (immediate) {
    connect();
  }

  // 组件卸载时自动 close, 防 EventSource 泄漏 (前端文档 §5.2).
  onBeforeUnmount(() => {
    close();
  });

  return { state, retryCount, lastError, close, reopen };
}
