/**
 * Background tabs must not request focus. Chromium may translate such requests
 * into a Windows taskbar attention flash even though the user switched away.
 */
export function focusWhenPageActive(element: HTMLElement | null | undefined): boolean {
  if (!element || typeof document === 'undefined') return false;
  if (document.visibilityState !== 'visible' || !document.hasFocus()) return false;
  element.focus({ preventScroll: true });
  return true;
}
