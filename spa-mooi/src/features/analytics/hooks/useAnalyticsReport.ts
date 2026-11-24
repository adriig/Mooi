import { useEffect, useState } from 'react';

interface UseAnalyticsReport<T> {
  data: T | null;
  loading: boolean;
  error: string | null;
  reload: () => void;
}

/** One report for a project and range; keeps the previous data on screen while the next loads. */
export const useAnalyticsReport = <T,>(load: (() => Promise<T>) | null, key: string): UseAnalyticsReport<T> => {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    if (!load) return;
    let active = true;
    setLoading(true);
    load()
      .then((next) => { if (active) { setData(next); setError(null); } })
      .catch((failure: Error) => { if (active) setError(failure.message); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
    // `key` stands for everything `load` closes over.
  }, [key, revision]);

  return { data, loading, error, reload: () => setRevision((value) => value + 1) };
};
