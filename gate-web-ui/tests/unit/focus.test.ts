import { afterEach, describe, expect, it, vi } from 'vitest';
import { focusWhenPageActive } from '@/utils/focus';

afterEach(() => {
  vi.restoreAllMocks();
  document.body.innerHTML = '';
});

describe('focusWhenPageActive', () => {
  it('focuses an element while the page is visible and active', () => {
    const button = document.createElement('button');
    document.body.append(button);
    vi.spyOn(document, 'hasFocus').mockReturnValue(true);
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
    const focus = vi.spyOn(button, 'focus');

    expect(focusWhenPageActive(button)).toBe(true);
    expect(focus).toHaveBeenCalledWith({ preventScroll: true });
  });

  it('does not request focus after the user switches away', () => {
    const button = document.createElement('button');
    document.body.append(button);
    vi.spyOn(document, 'hasFocus').mockReturnValue(false);
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');
    const focus = vi.spyOn(button, 'focus');

    expect(focusWhenPageActive(button)).toBe(false);
    expect(focus).not.toHaveBeenCalled();
  });
});
