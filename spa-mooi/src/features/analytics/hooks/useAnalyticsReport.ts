import { useEffect, useState } from 'react';
import { useAnalyticsRevision } from '@/features/analytics/hooks/useAnalyticsRevision';
import { GoogleReportError } from '@/features/analytics/lib/googleErrors';

interface UseAnalyticsReport<T> {
  data: T | null;
  loading: boolean;
  error: string | null;
  /** The `google_*` reason when a Google-backed report failed with one. */
  errorCode: string | null;
  reload: () => void;
}

/** One report for a project and range; keeps the previous data on screen while the next loads. */
export const useAnalyticsReport = <T,>(load: (() => Promise<T>) | null, key: string, projectId: string): UseAnalyticsReport<T> => {
  const liveRevision = useAnalyticsRevision(projectId);
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [errorCode, setErrorCode] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    if (!load) return;
    let active = true;
    setLoading(true);
    load()
      .then((next) => { if (active) { setData(next); setError(null); setErrorCode(null); } })
      .catch((failure: Error) => {
        if (!active) return;
        setError(failure.message);
        setErrorCode(failure instanceof GoogleReportError ? failure.code : null);
      })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
    // `key` stands for everything `load` closes over.
  }, [key, revision, liveRevision]);

  return { data, loading, error, errorCode, reload: () => setRevision((value) => value + 1) };
};
