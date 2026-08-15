/**
 * authStore — Bearer token 持久化 (localStorage) + 登录态.
 *
 * 单用户 (P5 防蠕变); 复用后端 HUMAN 域 token (ADR-10).
 * localStorage key 固定为 gate_token; 401/403 时由 client.ts 调用 logout() 清空.
 */
import { defineStore } from 'pinia';
import { computed, ref } from 'vue';

const STORAGE_KEY = 'gate_token';

function readToken(): string {
  try {
    return localStorage.getItem(STORAGE_KEY) ?? '';
  } catch {
    // localStorage 不可用 (e.g. 隐私模式 / 测试环境) 时返回空, 不抛.
    return '';
  }
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(readToken());

  const isAuthenticated = computed(() => token.value.length > 0);

  /** token 摘要 (前 6 + 后 4), 用于顶部登录态展示. */
  const tokenDigest = computed(() => {
    if (token.value.length === 0) return '';
    if (token.value.length <= 10) return token.value;
    return `${token.value.slice(0, 6)}…${token.value.slice(-4)}`;
  });

  function setToken(t: string): void {
    const trimmed = t.trim();
    token.value = trimmed;
    try {
      if (trimmed.length > 0) {
        localStorage.setItem(STORAGE_KEY, trimmed);
      } else {
        localStorage.removeItem(STORAGE_KEY);
      }
    } catch {
      // 静默: 内存态已更新, 持久化失败不阻塞当前会话.
    }
  }

  function logout(): void {
    setToken('');
  }

  return { token, isAuthenticated, tokenDigest, setToken, logout };
});
