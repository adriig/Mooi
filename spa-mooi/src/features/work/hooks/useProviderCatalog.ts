import { useEffect, useState } from 'react';
import { fetchSessionProvider } from '@/features/sessions/api/sessionsApi';
import type { SessionProvider } from '@/features/sessions/types/SessionProvider';

// One read per account for the page's lifetime: a Board shows many tasks on the same few accounts.
const catalogs = new Map<string, Promise<SessionProvider>>();

const load = (provider: string, connectionId: string): Promise<SessionProvider> => {
  const key = `${provider}:${connectionId}`;
  let catalog = catalogs.get(key);
  if (!catalog) {
    catalog = fetchSessionProvider(provider, connectionId);
    catalogs.set(key, catalog);
    catalog.catch(() => catalogs.delete(key));
  }
  return catalog;
};

/** The models and efforts an account offers; undefined while loading or without an account. */
export const useProviderCatalog = (provider: string | null, connectionId: string | null, enabled = true) => {
  const [catalog, setCatalog] = useState<{ key: string; value: SessionProvider } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const key = provider && connectionId ? `${provider}:${connectionId}` : '';

  useEffect(() => {
    if (!enabled || !provider || !connectionId) return undefined;
    let active = true;
    setError(null);
    load(provider, connectionId)
      .then((value) => { if (active) setCatalog({ key: `${provider}:${connectionId}`, value }); })
      .catch((failure: Error) => { if (active) setError(failure.message); });
    return () => { active = false; };
  }, [provider, connectionId, enabled]);

  return { catalog: catalog?.key === key ? catalog.value : undefined, error };
};
