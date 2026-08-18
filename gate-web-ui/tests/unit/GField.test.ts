import { afterEach, describe, expect, it } from 'vitest';
import { mount, type VueWrapper } from '@vue/test-utils';
import GCheckbox from '@/components/ui/GCheckbox.vue';
import GField from '@/components/ui/GField.vue';

let wrappers: VueWrapper[] = [];

afterEach(() => {
  wrappers.forEach((wrapper) => wrapper.unmount());
  wrappers = [];
});

describe('GField', () => {
  it('connects a visible label to its control and exposes hints', () => {
    const wrapper = mount(GField, {
      props: { label: '项目名称', forId: 'project-name', hint: '会显示在项目看板。', required: true },
      slots: { default: '<input id="project-name" />' },
    });
    wrappers.push(wrapper);

    expect(wrapper.get('label').attributes('for')).toBe('project-name');
    expect(wrapper.get('.g-field__required').text()).toBe('*');
    expect(wrapper.get('.g-field__hint').text()).toContain('会显示');
  });

  it('renders errors instead of hints and marks the field as invalid', () => {
    const wrapper = mount(GField, {
      props: { label: '令牌', forId: 'token', hint: '不会上传。', error: '令牌不能为空。' },
      slots: { default: '<input id="token" />' },
    });
    wrappers.push(wrapper);

    expect(wrapper.classes()).toContain('g-field--error');
    expect(wrapper.get('[role="alert"]').text()).toBe('令牌不能为空。');
    expect(wrapper.find('.g-field__hint').exists()).toBe(false);
  });
});

describe('GCheckbox', () => {
  it('emits a boolean value while keeping the native checkbox semantics', async () => {
    const wrapper = mount(GCheckbox, {
      props: { modelValue: false, name: 'init-git' },
      slots: { default: '初始化仓库' },
    });
    wrappers.push(wrapper);

    const input = wrapper.get('input');
    expect(input.attributes('name')).toBe('init-git');
    expect(wrapper.text()).toContain('初始化仓库');
    await input.setValue(true);

    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([true]);
  });
});
