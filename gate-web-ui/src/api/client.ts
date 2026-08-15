/**
 * axios 客户端 — Bearer token 注入 + 错误码映射 + 401/403 跳登录.
 *
 * baseURL = /api (dev 由 vite proxy 转发到后端 127.0.0.1:4097).
 * 响应拦截器:
 *   - 401/403 → 清 authStore + 跳 /login (登录页本身豁免, 避免循环)
 *   - 其他错误 → 按 GateErrorCode → 中文消息, 经回调通知 (默认 console, 由 view 层挂 message)
 *
 * 注意: router 是循环依赖高发点. 这里用 lazy import + 模块级 routerGetter 注入,
 *       client.ts 不直接 import router/index.ts, 由 main.ts 注入 setRouterGetter.
 */
import axios, {
  type AxiosError,
  type AxiosInstance,
  type InternalAxiosRequestConfig,
} from 'axios';
import type { GateErrorResponse } from '@/types/errors';
import { formatGateError } from '@/utils/errorCodeMap';

export interface ClientHandlers {
  /** 401/403 触发时调用 (默认: 清 token + 跳登录). */
  onAuthError?: () => void;
  /** 业务错误 (含 GateErrorCode) 触发, 用于 view 层挂 Naive UI message. */
  onBusinessError?: (code: GateErrorResponse['error'], message: string) => void;
}

let routerGetter: (() => { push: (path: string) => void }) | null = null;

/** main.ts 在创建 router 后调用, 注入跳转能力. 避免循环依赖. */
export function setRouterGetter(getter: () => { push: (path: string) => void }): void {
  routerGetter = getter;
}

let handlers: ClientHandlers = {
  onAuthError: () => {
    routerGetter?.().push('/login');
  },
  onBusinessError: (_code, message) => {
    // 默认仅 console; view 层 mount 时用 useClientError 替换为 message.error.
    // eslint-disable-next-line no-console
    console.error('[gate]', message);
  },
};

export function setClientHandlers(next: ClientHandlers): void {
  handlers = { ...handlers, ...next };
}

function buildClient(): AxiosInstance {
  const instance = axios.create({
    baseURL: '/api',
    timeout: 30_000,
  });

  // 请求拦截: 注入 Authorization: Bearer <token>.
  instance.interceptors.request.use((config: InternalAxiosRequestConfig) => {
    // 延迟读取 localStorage, 避免与 authStore 初始化顺序耦合.
    let token = '';
    try {
      token = localStorage.getItem('gate_token') ?? '';
    } catch {
      token = '';
    }
    if (token.length > 0 && config.headers) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  });

  // 响应拦截: 401/403 跳登录; 业务错误按 GateErrorCode 映射.
  instance.interceptors.response.use(
    (resp) => resp,
    (error: AxiosError<GateErrorResponse>) => {
      const status = error.response?.status ?? 0;
      const body = error.response?.data;

      if (status === 401 || status === 403) {
        // 先清 token 再跳, 避免登录页守卫又把用户弹回 /login (会循环).
        try {
          localStorage.removeItem('gate_token');
        } catch {
          // 静默
        }
        handlers.onAuthError?.();
        return Promise.reject(error);
      }

      if (body && typeof body.error === 'string') {
        const message = formatGateError(body.error, body.message, body.detail);
        handlers.onBusinessError?.(body.error, message);
      } else if (error.message) {
        handlers.onBusinessError?.('INTERNAL', error.message);
      }

      return Promise.reject(error);
    },
  );

  return instance;
}

export const client: AxiosInstance = buildClient();
