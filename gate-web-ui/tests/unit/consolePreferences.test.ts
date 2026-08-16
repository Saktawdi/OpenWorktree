import { beforeEach, describe, expect, it } from 'vitest';
import {
  resetConsolePreferences,
  saveConsolePreferences,
  toggleConsoleTheme,
  useConsolePreferences,
} from '@/composables/useConsolePreferences';

describe('console preferences', () => {
  beforeEach(() => {
    localStorage.clear();
    resetConsolePreferences();
  });

  it('persists preferences and applies document attributes', () => {
    saveConsolePreferences({
      density: 'compact',
      defaultTicketView: 'records',
      reduceMotion: true,
      theme: 'dark',
    });

    const { preferences } = useConsolePreferences();
    expect(preferences.density).toBe('compact');
    expect(preferences.defaultTicketView).toBe('records');
    expect(preferences.theme).toBe('dark');
    expect(document.documentElement.dataset.density).toBe('compact');
    expect(document.documentElement.dataset.theme).toBe('dark');
    expect(document.documentElement.style.colorScheme).toBe('dark');
    expect(document.documentElement.dataset.reduceMotion).toBe('true');
    expect(JSON.parse(localStorage.getItem('gate_console_preferences') ?? '{}')).toMatchObject({
      density: 'compact',
      defaultTicketView: 'records',
      reduceMotion: true,
      theme: 'dark',
    });
  });

  it('restores the default preference set', () => {
    saveConsolePreferences({
      density: 'compact',
      defaultTicketView: 'records',
      reduceMotion: true,
      theme: 'dark',
    });

    resetConsolePreferences();

    const { preferences } = useConsolePreferences();
    expect(preferences.density).toBe('comfortable');
    expect(preferences.defaultTicketView).toBe('board');
    expect(preferences.reduceMotion).toBe(false);
    expect(preferences.theme).toBe('light');
    expect(document.documentElement.dataset.theme).toBe('light');
    expect(document.documentElement.style.colorScheme).toBe('light');
    expect(document.documentElement.dataset.reduceMotion).toBeUndefined();
    expect(localStorage.getItem('gate_console_preferences')).toBeNull();
  });

  it('toggles the theme without changing other preferences', () => {
    saveConsolePreferences({
      density: 'compact',
      defaultTicketView: 'records',
      reduceMotion: false,
      theme: 'light',
    });

    expect(toggleConsoleTheme()).toBe('dark');
    expect(useConsolePreferences().preferences.density).toBe('compact');
    expect(document.documentElement.dataset.theme).toBe('dark');

    expect(toggleConsoleTheme()).toBe('light');
    expect(document.documentElement.dataset.theme).toBe('light');
  });
});
