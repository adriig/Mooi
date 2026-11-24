const KEY = 'mooi.analytics.project';

/**
 * Remembers on this device the project last opened on the Analytics page, so the menu entry lands
 * back on it. Storage may be unavailable (private mode), in which case the first web app opens.
 */
export const lastAnalyticsProject = {
  read: (): string | null => {
    try { return window.localStorage.getItem(KEY); } catch { return null; }
  },
  write: (projectId: string): void => {
    try { window.localStorage.setItem(KEY, projectId); } catch { /* a convenience, never state */ }
  },
};
