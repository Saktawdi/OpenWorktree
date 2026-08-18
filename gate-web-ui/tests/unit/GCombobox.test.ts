import { afterEach, describe, expect, it } from 'vitest';
import { mount, type VueWrapper } from '@vue/test-utils';
import GCombobox from '@/components/ui/GCombobox.vue';

const options = [
  { label: 'CLI 默认设置', value: '__cli_default__' },
  { label: 'opencode/big-pickle', value: 'opencode/big-pickle' },
  { label: 'opencode/hy3-free', value: 'opencode/hy3-free' },
];

let wrapper: VueWrapper | null = null;

afterEach(() => {
  wrapper?.unmount();
  wrapper = null;
  document.body.innerHTML = '';
});

describe('GCombobox', () => {
  it('filters model options from the search field', async () => {
    wrapper = mount(GCombobox, {
      attachTo: document.body,
      props: { modelValue: '__cli_default__', options },
    });

    await wrapper.get('[role="combobox"]').trigger('click');
    const search = document.body.querySelector<HTMLInputElement>('input[type="search"]');
    expect(search).not.toBeNull();
    await search!.focus();
    search!.value = 'hy3';
    search!.dispatchEvent(new Event('input', { bubbles: true }));
    await wrapper.vm.$nextTick();

    const visibleOptions = Array.from(document.body.querySelectorAll<HTMLElement>('[role="option"]'));
    expect(visibleOptions).toHaveLength(1);
    expect(visibleOptions[0]?.textContent).toContain('opencode/hy3-free');
  });

  it('supports arrow-key selection and emits the chosen value', async () => {
    wrapper = mount(GCombobox, {
      attachTo: document.body,
      props: { modelValue: '__cli_default__', options },
    });

    await wrapper.get('[role="combobox"]').trigger('click');
    const search = document.body.querySelector<HTMLInputElement>('input[type="search"]');
    search!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    search!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await wrapper.vm.$nextTick();

    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['opencode/big-pickle']);
  });
});
