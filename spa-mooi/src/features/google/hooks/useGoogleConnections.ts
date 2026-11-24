import { useCallback, useEffect, useState } from 'react';
import { disconnectGoogle, fetchGoogleConnections, startGoogleAuthorization } from '@/features/google/api/googleApi';
import type { GoogleConnection } from '@/features/google/types/GoogleConnection';

export const useGoogleConnections = () => {
  const [connections, setConnections] = useState<GoogleConnection[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setConnections(await fetchGoogleConnections());
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not load Google connections.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void reload(); }, [reload]);
  useEffect(() => {
    const onFocus = () => { void reload(); };
    window.addEventListener('focus', onFocus);
    return () => window.removeEventListener('focus', onFocus);
  }, [reload]);

  const connect = async (id?: string) => {
    setBusy(true);
    setError(null);
    try {
      window.location.assign(await startGoogleAuthorization(id));
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not start Google authorization.');
      setBusy(false);
    }
  };

  const disconnect = async (id: string) => {
    setBusy(true);
    setError(null);
    try {
      await disconnectGoogle(id);
      setConnections((current) => current.filter((connection) => connection.id !== id));
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not unlink Google.');
    } finally {
      setBusy(false);
    }
  };

  return { connections, loading, busy, error, reload, connect, disconnect };
};
