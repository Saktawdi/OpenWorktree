import { reactive, readonly } from 'vue';

export type ConsoleDensity = 'comfortable' | 'compact';
export type TicketDefaultView = 'board' | 'records';
export type ConsoleTheme = 'light' | 'dark';

export interface ConsolePreferences {
  density: ConsoleDensity;
  defaultTicketView: TicketDefaultView;
  reduceMotion: boolean;
  theme: ConsoleTheme;
}

const STORAGE_KEY = 'gate_console_preferences';

export const DEFAULT_CONSOLE_PREFERENCES: Readonly<ConsolePreferences> = {
  density: 'comfortable',
  defaultTicketView: 'board',
  reduceMotion: false,
  theme: 'light',
};

function readPreferences(): ConsolePreferences {
  if (typeof localStorage === 'undefined') return { ...DEFAULT_CONSOLE_PREFERENCES };

  try {
    const parsed = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '{}') as Partial<ConsolePreferences>;
    return {
      density: parsed.density === 'compact' ? 'compact' : 'comfortable',
      defaultTicketView: parsed.defaultTicketView === 'records' ? 'records' : 'board',
      reduceMotion: parsed.reduceMotion === true,
      theme: parsed.theme === 'dark' ? 'dark' : 'light',
    };
  } catch {
    return { ...DEFAULT_CONSOLE_PREFERENCES };
  }
}

const preferences = reactive<ConsolePreferences>(readPreferences());

export function applyConsolePreferences() {
  if (typeof document === 'undefined') return;

  document.documentElement.dataset.density = preferences.density;
  document.documentElement.dataset.theme = preferences.theme;
  document.documentElement.style.colorScheme = preferences.theme;
  if (preferences.reduceMotion) document.documentElement.dataset.reduceMotion = 'true';
  else delete document.documentElement.dataset.reduceMotion;
}

export function saveConsolePreferences(next: ConsolePreferences) {
  Object.assign(preferences, next);
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(preferences));
  } catch {
    // The preference remains active for this session when storage is unavailable.
  }
  applyConsolePreferences();
}

export function resetConsolePreferences() {
  Object.assign(preferences, DEFAULT_CONSOLE_PREFERENCES);
  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch {
    // Ignore unavailable storage and still reset the in-memory preference.
  }
  applyConsolePreferences();
  return { ...DEFAULT_CONSOLE_PREFERENCES };
}

export function toggleConsoleTheme() {
  saveConsolePreferences({
    ...preferences,
    theme: preferences.theme === 'dark' ? 'light' : 'dark',
  });
  return preferences.theme;
}

export function useConsolePreferences() {
  return {
    preferences: readonly(preferences),
    applyConsolePreferences,
    saveConsolePreferences,
    resetConsolePreferences,
    toggleConsoleTheme,
  };
}
