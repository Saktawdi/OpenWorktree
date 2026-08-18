import { afterEach, describe, expect, it } from 'vitest';
import { mount, type VueWrapper } from '@vue/test-utils';
import GEmpty from '@/components/ui/GEmpty.vue';
import GIcon from '@/components/ui/GIcon.vue';
import GSkeleton from '@/components/ui/GSkeleton.vue';

let wrappers: VueWrapper[] = [];

afterEach(() => {
  wrappers.forEach((wrapper) => wrapper.unmount());
  wrappers = [];
});

describe('GSkeleton', () => {
  it('renders a single shape by default and hides it from assistive tech', () => {
    const wrapper = mount(GSkeleton);
    wrappers.push(wrapper);

    expect(wrapper.findAll('.g-skeleton')).toHaveLength(1);
    expect(wrapper.get('.g-skeleton').attributes('aria-hidden')).toBe('true');
    expect(wrapper.find('.g-skeleton-group').exists()).toBe(false);
  });

  it('renders one line per requested count with a shortened last line', () => {
    const wrapper = mount(GSkeleton, { props: { lines: 3 } });
    wrappers.push(wrapper);

    const lines = wrapper.findAll('.g-skeleton--text');
    expect(lines).toHaveLength(3);
    expect(lines[2]!.classes()).toContain('g-skeleton--text-last');
    expect(lines[0]!.classes()).not.toContain('g-skeleton--text-last');
  });

  it('applies explicit sizing to block and circle variants', () => {
    const block = mount(GSkeleton, { props: { variant: 'block', height: '180px' } });
    wrappers.push(block);
    const circle = mount(GSkeleton, { props: { variant: 'circle', width: '40px' } });
    wrappers.push(circle);

    expect(block.get('.g-skeleton').attributes('style')).toContain('height: 180px');
    expect(circle.get('.g-skeleton').attributes('style')).toContain('width: 40px');
    expect(circle.get('.g-skeleton').classes()).toContain('g-skeleton--circle');
  });
});

describe('GEmpty', () => {
  it('composes title, hint, icon and an action slot', () => {
    const wrapper = mount(GEmpty, {
      props: { icon: 'folder', title: '还没有可展示的项目', hint: '创建项目后再开始管理工单。' },
      slots: { default: '<button>创建项目</button>' },
      global: { components: { GIcon } },
    });
    wrappers.push(wrapper);

    expect(wrapper.get('.g-empty__title').text()).toBe('还没有可展示的项目');
    expect(wrapper.get('.g-empty__hint').text()).toContain('创建项目');
    expect(wrapper.get('.g-empty__action').find('button').exists()).toBe(true);
    expect(wrapper.find('.g-empty__icon svg').exists()).toBe(true);
  });

  it('omits hint and action markup when not provided', () => {
    const wrapper = mount(GEmpty, { props: { title: '暂无数据' }, global: { components: { GIcon } } });
    wrappers.push(wrapper);

    expect(wrapper.find('.g-empty__hint').exists()).toBe(false);
    expect(wrapper.find('.g-empty__action').exists()).toBe(false);
  });
});
