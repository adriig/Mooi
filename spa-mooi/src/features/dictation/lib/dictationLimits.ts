import { dictationUrl } from '@/features/dictation/lib/dictationUrl';

/** mic-speech's default, used until (or unless) the service tells its own limit. */
const DEFAULT_MAX_SECONDS = 300;

let maxSeconds: Promise<number> | null = null;

/**
 * The longest recording the service accepts, read once per page. Recording never waits for it: a
 * service that cannot answer gets the default, and is asked again on the next dictation.
 */
export const dictationMaxSeconds = (): Promise<number> => {
  maxSeconds ??= fetch(dictationUrl('limits'), { credentials: 'same-origin' })
    .then(async (response) => {
      const value = response.ok ? ((await response.json()) as { maxSeconds?: unknown }).maxSeconds : undefined;
      if (typeof value !== 'number' || value <= 0) throw new Error('invalid dictation limits');
      return value;
    })
    .catch(() => {
      maxSeconds = null;
      return DEFAULT_MAX_SECONDS;
    });
  return maxSeconds;
};
