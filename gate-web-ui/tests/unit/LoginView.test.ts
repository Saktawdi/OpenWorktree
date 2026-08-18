/**
 * LoginView 交互回归: 语义化 form 提交 / 校验错误 / 后端拒绝 / 成功跳转.
 * 覆盖本轮 UI 打磨引入的结构 (form@submit, role=alert 错误, loading 态).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, type VueWrapper } from '@vue/test-utils';
import { createPinia } from 'pinia';
import LoginView from '@/views/LoginView.vue';
import * as authApi from '@/api/auth';
import { resetLocalStorage } from '../utils';

const verifyToken = vi.spyOn(authApi, 'verifyToken');

const push = vi.fn();
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ push }),
}));

let wrapper: VueWrapper | null = null;

function mountLogin() {
  wrapper = mount(LoginView, { global: { plugins: [createPinia()] } });
  return wrapper;
}

beforeEach(() => {
  resetLocalStorage();
  verifyToken.mockReset();
  push.mockReset();
});

afterEach(() => {
  wrapper?.unmount();
  wrapper = null;
  resetLocalStorage();
});

describe('LoginView', () => {
  it('提交空令牌时给出内联校验错误, 不发起请求', async () => {
    const w = mountLogin();
    await w.get('form').trigger('submit');

    expect(w.get('[role="alert"]').text()).toBe('请输入登录令牌');
    expect(verifyToken).not.toHaveBeenCalled();
  });

  it('过短令牌直接判无效, 不发起请求', async () => {
    const w = mountLogin();
    await w.get('#login-token').setValue('abc');
    await w.get('form').trigger('submit');

    expect(w.get('[role="alert"]').text()).toContain('无效或已撤销');
    expect(verifyToken).not.toHaveBeenCalled();
  });

  it('后端拒绝时展示错误并保留在登录页', async () => {
    verifyToken.mockResolvedValueOnce({ ok: false } as never);
    const w = mountLogin();
    await w.get('#login-token').setValue('mock-human-token-1234567890abcdef');
    await w.get('form').trigger('submit');
    await vi.waitFor(() => expect(w.get('[role="alert"]').text()).toContain('未通过后端校验'));

    expect(push).not.toHaveBeenCalled();
    expect(w.get('#login-token').attributes('aria-invalid')).toBe('true');
  });

  it('校验通过时写入 authStore 并跳转 home', async () => {
    verifyToken.mockResolvedValueOnce({ ok: true } as never);
    const w = mountLogin();
    await w.get('#login-token').setValue('mock-human-token-1234567890abcdef');
    await w.get('form').trigger('submit');
    await vi.waitFor(() => expect(push).toHaveBeenCalledWith({ name: 'home' }));

    expect(w.find('[role="alert"]').exists()).toBe(false);
    expect(localStorage.getItem('gate_token')).toBe('mock-human-token-1234567890abcdef');
  });
});
