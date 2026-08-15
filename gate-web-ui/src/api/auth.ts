/**
 * 认证 API — 对齐后端 POST /api/auth/verify (后端文档 §4.1).
 *
 * 后端: 校验 token sha256 (HUMAN 域), 200 = 通过, 401 = 失败.
 * 前端: 登录页输入 token → 调本接口 → 200 则 setToken 进 authStore.
 */
import { client } from './client';

export interface AuthVerifyResponse {
  /** 后端 200 响应体 (待后端定案; 暂给最小字段). */
  ok: boolean;
  /** token 摘要 (可选, 用于顶部展示). */
  tokenDigest?: string;
}

/**
 * POST /api/auth/verify — 校验 token (登录).
 *
 * 登录页调用时 authStore 还未 setToken, 这里手动注入 header.
 * 401 由 client.ts 默认 onAuthError 处理, 会跳 /login — 但登录页本身就在 /login,
 * router 守卫会再次跳转 (no-op); 调用方按 catch 区分 401 给本地提示即可.
 */
export async function verifyToken(token: string): Promise<AuthVerifyResponse> {
  const resp = await client.post<AuthVerifyResponse>(
    '/auth/verify',
    {},
    {
      headers: { Authorization: `Bearer ${token}` },
    },
  );
  return resp.data;
}

/** GET /api/health — 存活探针 (免 token, 后端文档 §3.4). */
export async function health(): Promise<{ status: string }> {
  const resp = await client.get<{ status: string }>('/health');
  return resp.data;
}
