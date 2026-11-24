import { useCallback, useEffect, useState } from 'react';
import { useAnalyticsRevision } from '@/features/analytics/hooks/useAnalyticsRevision';
import { fetchAnalyticsSite, regenerateAnalyticsKey, updateAnalyticsGoogle, updateAnalyticsOrigins } from '@/features/analytics/api/analyticsApi';
import type { AnalyticsGoogleSelection } from '@/features/analytics/types/AnalyticsGoogleSelection';
import type { AnalyticsSite } from '@/features/analytics/types/AnalyticsSite';

interface UseAnalyticsSite {
  site: AnalyticsSite | null;
  loading: boolean;
  error: string | null;
  busy: boolean;
  actionError: string | null;
  reload: () => void;
  saveOrigins: (origins: string[]) => Promise<boolean>;
  regenerateKey: () => Promise<boolean>;
  saveGoogle: (selection: AnalyticsGoogleSelection) => Promise<boolean>;
}

/** The tracker configuration of one project; the server creates it on first read. */
export const useAnalyticsSite = (projectId: string | undefined): UseAnalyticsSite => {
  const liveRevision = useAnalyticsRevision(projectId);
  const [site, setSite] = useState<AnalyticsSite | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    if (!projectId) return;
    let active = true;
    setLoading(true);
    fetchAnalyticsSite(projectId)
      .then((next) => { if (active) { setSite(next); setError(null); } })
      .catch((failure: Error) => { if (active) setError(failure.message); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [projectId, revision, liveRevision]);

  const reload = useCallback(() => setRevision((value) => value + 1), []);

  const mutate = useCallback(async (request: () => Promise<AnalyticsSite>): Promise<boolean> => {
    setBusy(true);
    setActionError(null);
    try {
      setSite(await request());
      return true;
    } catch (failure) {
      setActionError(failure instanceof Error ? failure.message : 'Request failed.');
      return false;
    } finally {
      setBusy(false);
    }
  }, []);

  const saveOrigins = useCallback((origins: string[]) => (projectId ? mutate(() => updateAnalyticsOrigins(projectId, origins)) : Promise.resolve(false)), [projectId, mutate]);
  const regenerateKey = useCallback(() => (projectId ? mutate(() => regenerateAnalyticsKey(projectId)) : Promise.resolve(false)), [projectId, mutate]);
  const saveGoogle = useCallback((selection: AnalyticsGoogleSelection) => (projectId ? mutate(() => updateAnalyticsGoogle(projectId, selection)) : Promise.resolve(false)), [projectId, mutate]);

  return { site, loading, error, busy, actionError, reload, saveOrigins, regenerateKey, saveGoogle };
};
