import { useEffect, useState } from 'react';
import { useAnalyticsRevision } from '@/features/analytics/hooks/useAnalyticsRevision';
import { fetchAnalyticsOverview } from '@/features/analytics/api/analyticsApi';

const POLL_MS = 30_000;
const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';

/** Visitors of the last five minutes, refreshed every 30 s only while the page is visible. */
export const useLiveVisitors = (projectId: string | undefined): number | null => {
  const liveRevision = useAnalyticsRevision(projectId);
  const [live, setLive] = useState<number | null>(null);

  useEffect(() => {
    if (!projectId) return;
    let active = true;
    let timer: number | undefined;

    const poll = () => {
      const now = Date.now();
      fetchAnalyticsOverview(projectId, { from: new Date(now - 3_600_000).toISOString(), to: new Date(now).toISOString(), timezone })
        .then((overview) => { if (active) setLive(overview.live); })
        .catch(() => undefined);
    };
    const stop = () => { window.clearInterval(timer); timer = undefined; };
    const start = () => {
      if (timer !== undefined || document.visibilityState !== 'visible') return;
      poll();
      timer = window.setInterval(poll, POLL_MS);
    };
    const onVisibility = () => (document.visibilityState === 'visible' ? start() : stop());

    start();
    document.addEventListener('visibilitychange', onVisibility);
    return () => { active = false; stop(); document.removeEventListener('visibilitychange', onVisibility); };
  }, [projectId, liveRevision]);

  return live;
};
