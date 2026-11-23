const KEY = 'mooi.sessions.previewSplit';

/** Share of the workspace width the docked preview may take, and where it starts. */
export const PREVIEW_SPLIT_MIN = 0.25;
export const PREVIEW_SPLIT_MAX = 0.75;
export const PREVIEW_SPLIT_DEFAULT = 0.5;

export interface PreviewSplit {
  /** Whether the preview is docked to the right of the other session tabs. */
  docked: boolean;
  /** Fraction of the workspace width the docked preview takes. */
  share: number;
}

export const clampPreviewShare = (share: number): number =>
  Math.min(PREVIEW_SPLIT_MAX, Math.max(PREVIEW_SPLIT_MIN, share));

/**
 * Remembers on this device whether the player docks the preview beside the session, and how wide.
 * Storage may be unavailable (private mode), in which case the preview simply starts as a tab.
 */
export const previewSplit = {
  read: (): PreviewSplit => {
    try {
      const value: unknown = JSON.parse(window.localStorage.getItem(KEY) ?? 'null');
      if (value && typeof value === 'object') {
        const { docked, share } = value as Record<string, unknown>;
        return {
          docked: docked === true,
          share: typeof share === 'number' && Number.isFinite(share) ? clampPreviewShare(share) : PREVIEW_SPLIT_DEFAULT,
        };
      }
    } catch { /* fall back to the defaults */ }
    return { docked: false, share: PREVIEW_SPLIT_DEFAULT };
  },
  write: (split: PreviewSplit): void => {
    try { window.localStorage.setItem(KEY, JSON.stringify(split)); } catch { /* a convenience, never state */ }
  },
};
