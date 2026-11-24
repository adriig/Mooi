import { useLiveStore } from '@/stores/liveStore';

/** Refresh after a chat change, and after reconnecting when events may have been missed. */
export const useAnalyticsRevision = (projectId: string | undefined): string =>
  useLiveStore((state) => `${state.epoch}:${projectId ? state.analyticsRevisions[projectId] ?? 0 : 0}`);
