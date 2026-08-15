/**
 * authStore 测试 — token 存取 + 登录态 + logout 清空.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '@/stores/authStore';
import { resetLocalStorage, setupPinia } from '../utils';

describe('authStore', () => {
  beforeEach(() => {
    resetLocalStorage();
    setupPinia();
  });

  it('初始无 token 时 isAuthenticated=false', () => {
    const auth = useAuthStore();
    expect(auth.isAuthenticated).toBe(false);
    expect(auth.token).toBe('');
    expect(auth.tokenDigest).toBe('');
  });

  it('setToken 后持久化到 localStorage', () => {
    const auth = useAuthStore();
    auth.setToken('abc123def456');
    expect(auth.isAuthenticated).toBe(true);
    expect(auth.token).toBe('abc123def456');
    expect(localStorage.getItem('gate_token')).toBe('abc123def456');
    expect(auth.tokenDigest).toBe('abc123…f456');
  });

  it('setToken 自动 trim 空白', () => {
    const auth = useAuthStore();
    auth.setToken('  tok  ');
    expect(auth.token).toBe('tok');
    expect(localStorage.getItem('gate_token')).toBe('tok');
  });

  it('setToken 空串等价于 logout', () => {
    const auth = useAuthStore();
    auth.setToken('abc');
    auth.setToken('');
    expect(auth.isAuthenticated).toBe(false);
    expect(auth.token).toBe('');
    expect(localStorage.getItem('gate_token')).toBeNull();
  });

  it('logout 清空 token 与 localStorage', () => {
    const auth = useAuthStore();
    auth.setToken('abc');
    auth.logout();
    expect(auth.isAuthenticated).toBe(false);
    expect(auth.token).toBe('');
    expect(localStorage.getItem('gate_token')).toBeNull();
  });

  it('短 token 的 tokenDigest 不截断', () => {
    const auth = useAuthStore();
    auth.setToken('short');
    // length=5 <= 10, 不截断.
    expect(auth.tokenDigest).toBe('short');
  });

  it('tokenDigest 头6尾4', () => {
    const auth = useAuthStore();
    auth.setToken('1234567890abcde');
    expect(auth.tokenDigest).toBe('123456…bcde');
  });
});
