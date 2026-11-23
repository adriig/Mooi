import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { fetchAccountUsage } from '@/features/agents/api/agentsApi';
import type { AccountUsage } from '@/features/agents/types/AccountUsage';
import type { UsageMetric } from '@/features/sessions/types/UsageMetric';
import { useSessionsStore } from '@/stores/sessionsStore';

// The service caches readings for a minute; re-reading every few minutes keeps resets current.
const POLL_MS = 180_000;

interface UseAccountUsage {
  usage: AccountUsage | null;
  status: 'loading' | 'ready' | 'error';
  error: string | null;
  refreshing: boolean;
  refresh: () => void;
}

/**
 * One account's plan limits: read from the provider while the page is shown, and kept live by the
 * readings this account's open sessions stream (a window both report shows the newer value).
 */
export const useAccountUsage = (connectionId: string): UseAccountUsage => {
  const [usage, setUsage] = useState<AccountUsage | null>(null);
  const [status, setStatus] = useState<UseAccountUsage['status']>('loading');
  const [error, setError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const request = useRef(0);
  const sessions = useSessionsStore((state) => state.sessions);

  const load = useCallback(async (refresh: boolean) => {
    const id = ++request.current;
    if (refresh) setRefreshing(true);
    try {
      const result = await fetchAccountUsage(connectionId, refresh);
      if (id !== request.current) return;
      setUsage(result);
      setError(null);
      setStatus('ready');
    } catch (failure) {
      if (id !== request.current) return;
      setError((failure as Error).message);
      setStatus((current) => (current === 'ready' ? current : 'error'));
    } finally {
      if (id === request.current) setRefreshing(false);
    }
  }, [connectionId]);

  useEffect(() => {
    void load(false);
    const timer = window.setInterval(() => { if (document.visibilityState === 'visible') void load(false); }, POLL_MS);
    const onVisible = () => { if (document.visibilityState === 'visible') void load(false); };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisible);
      request.current += 1;
    };
  }, [load]);

  const live = useMemo(() => {
    if (!usage?.limits) return usage;
    const windows: Record<string, UsageMetric> = { ...usage.windows };
    for (const session of sessions) {
      if (session.connectionId !== connectionId) continue;
      for (const [key, metric] of Object.entries(session.usage?.quota ?? {})) {
        const current = windows[key];
        if (current && metric?.percent != null && metric.updatedAt > current.updatedAt) windows[key] = metric;
      }
    }
    return { ...usage, windows };
  }, [usage, sessions, connectionId]);

  const refresh = useCallback(() => { void load(true); }, [load]);

  return { usage: live, status, error, refreshing, refresh };
};
