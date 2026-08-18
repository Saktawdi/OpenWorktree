import { afterEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { createMemoryHistory, createRouter } from 'vue-router';
import { mount, type VueWrapper } from '@vue/test-utils';
import AppLayout from '@/views/AppLayout.vue';

let wrapper: VueWrapper | null = null;

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/cost', name: 'cost', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  });
}

afterEach(() => {
  wrapper?.unmount();
  wrapper = null;
  document.body.style.overflow = '';
  document.body.innerHTML = '';
  localStorage.clear();
  vi.restoreAllMocks();
});

describe('AppLayout overlays', () => {
  it('filters and navigates from the command panel with keyboard input', async () => {
    setActivePinia(createPinia());
    localStorage.setItem('gate_token', 'test-token');
    const router = makeRouter();
    await router.push('/');
    await router.isReady();

    wrapper = mount(AppLayout, {
      attachTo: document.body,
      global: {
        plugins: [router],
        stubs: {
          RouterView: { template: '<div />' },
          GTooltip: { template: '<span><slot /></span>' },
        },
      },
    });
    await wrapper.vm.$nextTick();

    await wrapper.get('.topbar__hint').trigger('click');
    const input = document.body.querySelector<HTMLInputElement>('.command-dialog__search input');
    expect(input).not.toBeNull();
    expect(document.body.style.overflow).toBe('hidden');
    input!.value = '成本';
    input!.dispatchEvent(new Event('input', { bubbles: true }));
    await wrapper.vm.$nextTick();
    expect(document.body.querySelectorAll('.command-option')).toHaveLength(1);
    expect(document.body.querySelector('.command-option')?.textContent).toContain('成本');

    document.body.querySelector<HTMLButtonElement>('.command-option')!.click();
    await new Promise((resolve) => setTimeout(resolve, 0));
    await router.isReady();
    await wrapper.vm.$nextTick();
    expect(router.currentRoute.value.name).toBe('cost');
    expect(document.body.querySelector('.command-dialog')).toBeNull();
    expect(document.body.style.overflow).toBe('');
  });

  it('opens mobile navigation, traps the overlay, and closes with Escape', async () => {
    setActivePinia(createPinia());
    localStorage.setItem('gate_token', 'test-token');
    const router = makeRouter();
    await router.push('/');
    await router.isReady();

    wrapper = mount(AppLayout, {
      attachTo: document.body,
      global: {
        plugins: [router],
        stubs: {
          RouterView: { template: '<div />' },
          GTooltip: { template: '<span><slot /></span>' },
        },
      },
    });
    await wrapper.vm.$nextTick();

    await wrapper.get('.mobile-nav-toggle').trigger('click');
    const panel = document.body.querySelector<HTMLElement>('#mobile-workspace-nav');
    expect(panel).not.toBeNull();
    expect(document.body.style.overflow).toBe('hidden');
    await panel!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await wrapper.vm.$nextTick();
    expect(document.body.querySelector('#mobile-workspace-nav')).toBeNull();
    expect(document.body.style.overflow).toBe('');
  });
});
