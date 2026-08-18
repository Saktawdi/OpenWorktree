import { afterEach, describe, expect, it, vi } from 'vitest';
import { mount, type VueWrapper } from '@vue/test-utils';
import GModal from '@/components/ui/GModal.vue';

let wrapper: VueWrapper | null = null;

afterEach(() => {
  wrapper?.unmount();
  wrapper = null;
  document.body.style.overflow = '';
  document.body.innerHTML = '';
  vi.restoreAllMocks();
});

describe('GModal', () => {
  it('locks page scroll, focuses the close control, and restores focus on close', async () => {
    vi.spyOn(document, 'hasFocus').mockReturnValue(true);
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
    const opener = document.createElement('button');
    opener.textContent = '打开';
    document.body.append(opener);
    opener.focus();

    wrapper = mount(GModal, {
      attachTo: document.body,
      props: { show: true, title: '确认操作' },
      slots: { default: '<p>内容</p>', footer: '<button>确认</button>' },
    });
    await wrapper.vm.$nextTick();

    expect(document.body.style.overflow).toBe('hidden');
    const dialog = document.body.querySelector<HTMLElement>('[role="dialog"]');
    expect(dialog).not.toBeNull();
    expect(document.activeElement?.getAttribute('aria-label')).toBe('关闭');

    dialog!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await wrapper.vm.$nextTick();
    expect(wrapper.emitted('close')).toHaveLength(1);
    await wrapper.setProps({ show: false });
    await wrapper.vm.$nextTick();
    expect(document.body.style.overflow).toBe('');
    expect(document.activeElement).toBe(opener);
  });

  it('keeps tab focus inside the dialog', async () => {
    vi.spyOn(document, 'hasFocus').mockReturnValue(true);
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
    wrapper = mount(GModal, {
      attachTo: document.body,
      props: { show: true, title: '键盘导航' },
      slots: { default: '<input aria-label="字段" />', footer: '<button>保存</button>' },
    });
    await wrapper.vm.$nextTick();

    const dialog = document.body.querySelector<HTMLElement>('[role="dialog"]');
    expect(dialog).not.toBeNull();
    const close = document.body.querySelector<HTMLButtonElement>('button[aria-label="关闭"]');
    const save = document.body.querySelector<HTMLButtonElement>('button:not([aria-label="关闭"])');
    expect(close).not.toBeNull();
    expect(save).not.toBeNull();
    save!.focus();
    dialog!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true }));
    expect(document.activeElement).toBe(close);
  });
});
