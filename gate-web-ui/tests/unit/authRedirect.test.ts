/**
 * 401 → /login 守卫放行回归测试 (S0 验收标准 2).
 *
 * 缺陷背景: client.ts 401 处理只清 localStorage, authStore 内存 ref (token) 仍是旧值,
 * isAuthenticated 保持 true → 守卫 (src/router/index.ts:48) 把 /login 弹回 / → 401 跳转落空.
 * 修复: App.vue onAuthError 先 auth.logout() (清内存 + localStorage) 再 push /login.
 *
 * 用真实 router 单例 + 真实守卫验证两件事:
 *   1. authStore 残留旧 token → /login 被守卫弹回 (缺陷场景复现).
 *   2. auth.logout() 后 → /login 守卫放行 (修复达成, App.vue onAuthError 的执行路径).
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '@/stores/authStore';
import { router } from '@/router';
import { resetLocalStorage, setupPinia } from '../utils';

describe('401 → /login 守卫放行 (S0 验收 2)', () => {
  beforeEach(() => {
    resetLocalStorage();
    setupPinia();
  });

  afterEach(() => {
    resetLocalStorage();
  });

  it('authStore 残留旧 token 时, /login 被守卫弹回 (缺陷场景)', async () => {
    const auth = useAuthStore();
    auth.setToken('stale-token');
    await router.push({ name: 'login' });
    expect(router.currentRoute.value.name).not.toBe('login');
  });

  it('auth.logout() 后, /login 守卫放行 (修复达成)', async () => {
    const auth = useAuthStore();
    auth.setToken('stale-token');
    // App.vue onAuthError 在 401/403 时执行的路径: 先清 authStore 再跳 /login.
    auth.logout();
    await router.push({ name: 'login' });
    expect(router.currentRoute.value.name).toBe('login');
  });
});
