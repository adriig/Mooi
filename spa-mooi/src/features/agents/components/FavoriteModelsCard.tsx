import { useCallback, useEffect, useRef, useState } from 'react';
import { LoaderCircle, RefreshCw, Star } from 'lucide-react';
import { fetchFavoriteModels, saveFavoriteModels } from '@/features/agents/api/agentsApi';
import type { AgentConnection } from '@/features/agents/types/AgentConnection';
import type { AgentProvider } from '@/features/agents/types/AgentProvider';
import { fetchSessionProvider } from '@/features/sessions/api/sessionsApi';
import { Button } from '@/shared/components/Button';
import { Card } from '@/shared/components/Card';

interface FavoriteModelsCardProps {
  providers: AgentProvider[];
  connections: AgentConnection[];
}

interface CatalogModel {
  id: string;
  label: string;
}

interface CatalogState {
  models: CatalogModel[];
  loading: boolean;
  error: string | null;
}

const sameModels = (left: string[], right: string[]) =>
  left.length === right.length && left.every((model, index) => model === right[index]);

export const FavoriteModelsCard = ({ providers, connections }: FavoriteModelsCardProps) => {
  const [saved, setSaved] = useState<Record<string, string[]> | null>(null);
  const [draft, setDraft] = useState<Record<string, string[]>>({});
  const [loadingFavorites, setLoadingFavorites] = useState(true);
  const [favoritesError, setFavoritesError] = useState<string | null>(null);
  const [catalogs, setCatalogs] = useState<Record<string, CatalogState>>({});
  const [savingProvider, setSavingProvider] = useState<string | null>(null);
  const [saveErrors, setSaveErrors] = useState<Record<string, string>>({});
  const [savedProvider, setSavedProvider] = useState<string | null>(null);
  const requestIds = useRef<Record<string, number>>({});

  const loadFavorites = useCallback(async () => {
    setLoadingFavorites(true);
    setFavoritesError(null);
    try {
      const favorites = await fetchFavoriteModels();
      setSaved(favorites);
      setDraft(favorites);
    } catch (failure) {
      setFavoritesError(failure instanceof Error ? failure.message : 'Could not load favorite models.');
    } finally {
      setLoadingFavorites(false);
    }
  }, []);

  useEffect(() => { void loadFavorites(); }, [loadFavorites]);

  const loadCatalog = useCallback(async (providerId: string) => {
    const requestId = (requestIds.current[providerId] ?? 0) + 1;
    requestIds.current[providerId] = requestId;
    setCatalogs((current) => ({
      ...current,
      [providerId]: { models: current[providerId]?.models ?? [], loading: true, error: null },
    }));

    const accounts = connections.filter((connection) =>
      connection.provider === providerId && !connection.stale);
    if (accounts.length === 0) {
      setCatalogs((current) => ({
        ...current,
        [providerId]: { models: [], loading: false, error: null },
      }));
      return;
    }

    const results = await Promise.all(accounts.map(async (account) => {
      try {
        const catalog = await fetchSessionProvider(providerId, account.id);
        return {
          models: catalog.unavailable ? [] : catalog.models.map(({ id, label }) => ({ id, label })),
          error: catalog.unavailable ?? null,
        };
      } catch (failure) {
        return {
          models: [] as CatalogModel[],
          error: failure instanceof Error ? failure.message : 'Could not load this provider catalog.',
        };
      }
    }));

    if (requestIds.current[providerId] !== requestId) return;
    const models = new Map<string, CatalogModel>();
    results.forEach((result) => result.models.forEach((model) => models.set(model.id, model)));
    const errors = [...new Set(results.map((result) => result.error).filter((error): error is string => Boolean(error)))];
    setCatalogs((current) => ({
      ...current,
      [providerId]: {
        models: [...models.values()].sort((left, right) => left.label.localeCompare(right.label)),
        loading: false,
        error: errors.length ? errors.join(' ') : null,
      },
    }));
  }, [connections]);

  const accountKey = providers.map((provider) =>
    `${provider.id}:${connections.filter((connection) =>
      connection.provider === provider.id && !connection.stale).map((connection) => connection.id).sort().join(',')}`
  ).join('|');

  useEffect(() => {
    providers.forEach((provider) => { void loadCatalog(provider.id); });
  }, [accountKey, loadCatalog, providers]);

  const toggleModel = (providerId: string, modelId: string, checked: boolean) => {
    setDraft((current) => {
      const selected = current[providerId] ?? [];
      const next = checked
        ? [...selected, modelId]
        : selected.filter((entry) => entry !== modelId);
      return { ...current, [providerId]: [...new Set(next)] };
    });
    setSaveErrors((current) => ({ ...current, [providerId]: '' }));
    setSavedProvider(null);
  };

  const saveProvider = async (providerId: string, modelIds: string[]) => {
    if (savingProvider) return;
    setSavingProvider(providerId);
    setSaveErrors((current) => ({ ...current, [providerId]: '' }));
    setSavedProvider(null);
    try {
      const result = await saveFavoriteModels(providerId, modelIds);
      setSaved((current) => ({ ...current, [providerId]: result }));
      setDraft((current) => ({ ...current, [providerId]: result }));
      setSavedProvider(providerId);
    } catch (failure) {
      setSaveErrors((current) => ({
        ...current,
        [providerId]: failure instanceof Error ? failure.message : 'Could not save favorite models.',
      }));
    } finally {
      setSavingProvider(null);
    }
  };

  return <Card className="p-5 sm:p-6">
    <div className="flex items-start gap-3">
      <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-brand-soft text-brand">
        <Star className="size-4" />
      </span>
      <div>
        <h2 className="text-sm font-semibold tracking-[-0.015em] text-ink">Favorite models</h2>
        <p className="mt-1 text-xs leading-relaxed text-ink-subtle">
          Choose models to prioritize for factory task assignments. Favorites are saved to your account and sync across devices.
        </p>
      </div>
    </div>

    {loadingFavorites && saved === null ? <div className="mt-5 flex items-center gap-2 text-sm text-ink-muted" role="status">
      <LoaderCircle className="size-4 animate-spin" /> Loading favorite models…
    </div> : null}

    {favoritesError ? <div className="mt-4 flex flex-wrap items-center gap-3">
      <p role="alert" className="min-w-0 flex-1 text-sm text-danger">{favoritesError}</p>
      <Button variant="secondary" size="sm" disabled={loadingFavorites} onClick={() => void loadFavorites()}>
        {loadingFavorites ? <LoaderCircle className="size-4 animate-spin" /> : <RefreshCw className="size-4" />}
        Try again
      </Button>
    </div> : null}

    {!loadingFavorites && !favoritesError && <div className="mt-5 divide-y divide-line">
      {providers.map((provider) => {
        const linkedAccounts = connections.filter((connection) => connection.provider === provider.id);
        const usableAccounts = linkedAccounts.filter((connection) => !connection.stale);
        const catalog = catalogs[provider.id];
        const selected = draft[provider.id] ?? [];
        const stored = saved?.[provider.id] ?? [];
        const dirty = !sameModels([...selected].sort(), [...stored].sort());
        const unavailableSelections = selected.filter((id) => !catalog?.models.some((model) => model.id === id));
        const saving = savingProvider === provider.id;

        return <section key={provider.id} className="py-5 first:pt-0 last:pb-0" aria-labelledby={`favorite-models-${provider.id}`}>
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="min-w-0">
              <h3 id={`favorite-models-${provider.id}`} className="text-[13px] font-bold text-ink">{provider.label}</h3>
              {linkedAccounts.length === 0 ? <p className="mt-1 text-xs leading-relaxed text-ink-muted">
                Connect a {provider.label} account in Agent accounts above to see its available models.
              </p> : usableAccounts.length === 0 ? <p className="mt-1 text-xs leading-relaxed text-ink-muted">
                Sign in again to a {provider.label} account in Agent accounts above to load its models.
              </p> : null}
            </div>
            {catalog?.loading ? <span className="inline-flex items-center gap-2 text-xs text-ink-subtle" role="status">
              <LoaderCircle className="size-4 animate-spin" /> Loading catalog
            </span> : null}
          </div>

          {catalog?.error ? <div className="mt-3 flex flex-wrap items-center gap-3">
            <p role="alert" className="min-w-0 flex-1 text-xs text-danger">{catalog.error}</p>
            <Button variant="secondary" size="sm" disabled={catalog.loading} onClick={() => void loadCatalog(provider.id)}>
              <RefreshCw className="size-4" /> Retry
            </Button>
          </div> : null}

          {catalog && !catalog.loading && catalog.models.length > 0 ? <fieldset className="mt-3">
            <legend className="sr-only">Favorite models for {provider.label}</legend>
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
              {catalog.models.map((model) => {
                const checked = selected.includes(model.id);
                return <label key={model.id} className="flex min-h-11 cursor-pointer items-start gap-3 rounded-xl border border-line bg-surface-1 px-3 py-2.5 transition-colors hover:bg-surface-2 focus-within:ring-2 focus-within:ring-brand">
                  <input
                    type="checkbox"
                    className="mt-0.5 size-4 shrink-0 accent-brand focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand"
                    checked={checked}
                    disabled={saving}
                    onChange={(event) => toggleModel(provider.id, model.id, event.target.checked)}
                    aria-label={`${model.label} favorite`}
                  />
                  <span className="min-w-0">
                    <span className="block break-words text-xs font-semibold text-ink">{model.label}</span>
                    <span className="mt-0.5 block break-all font-mono text-[10px] text-ink-subtle">{model.id}</span>
                  </span>
                </label>;
              })}
            </div>
            {unavailableSelections.length > 0 ? <p className="mt-2 text-xs text-ink-muted">
              Some saved favorites are no longer in the connected account catalogs. Saving your selection will remove them.
            </p> : null}
          </fieldset> : null}

          {catalog && !catalog.loading && catalog.models.length === 0 && usableAccounts.length > 0 && !catalog.error
            ? <p className="mt-3 text-xs text-ink-muted">No models are available in the connected account catalogs.</p> : null}

          {((catalog?.models.length ?? 0) > 0 && dirty) || stored.length > 0 || savedProvider === provider.id
            ? <div className="mt-3 flex flex-wrap items-center gap-3">
            {((catalog?.models.length ?? 0) > 0 && dirty) || (stored.length > 0 && selected.length === 0) ? <Button variant="brand" size="sm" disabled={saving}
              onClick={() => void saveProvider(provider.id, (catalog?.models ?? [])
                .map((model) => model.id).filter((id) => selected.includes(id)))}>
              {saving ? <LoaderCircle className="size-4 animate-spin" /> : null}
              Save favorites
            </Button> : null}
            {stored.length > 0 ? <Button variant="ghost" size="sm" disabled={saving}
              onClick={() => {
                setDraft((current) => ({ ...current, [provider.id]: [] }));
                void saveProvider(provider.id, []);
              }}>Remove all</Button> : null}
            {savedProvider === provider.id ? <span className="text-xs text-success" role="status">Saved</span> : null}
          </div> : null}

          {saveErrors[provider.id] ? <p role="alert" className="mt-3 text-sm text-danger">{saveErrors[provider.id]}</p> : null}
        </section>;
      })}
    </div>}
  </Card>;
};
