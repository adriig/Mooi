import { env } from '@/config/env';

/**
 * A speech service endpoint (`limits`, `transcriptions`) under its base URL. A path-only base (a
 * preview's `/preview/<id>/`) resolves against the current page and keeps its prefix.
 */
export const dictationUrl = (endpoint: string): string => {
  const base = new URL(env.speechBaseUrl, window.location.href);
  return new URL(`api/stt/${endpoint}`, base.href.endsWith('/') ? base : `${base.href}/`).toString();
};
