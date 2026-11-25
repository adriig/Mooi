import { useCallback, useEffect, useState } from 'react';
import {
  acceptProjectDefinition, fetchProjectDefinition, reopenProjectDefinition, WorkApiError,
} from '@/features/work/api/workApi';
import type { ProjectDefinition } from '@/features/work/types/ProjectDefinition';
import type { WorkItem } from '@/features/work/types/WorkItem';
import { useLiveStore } from '@/stores/liveStore';

export interface UseProjectDefinition {
  /** Null until first read. */
  definition: ProjectDefinition | null;
  loadError: string | null;
  busy: boolean;
  /** The last accept or reopen that failed; a 409 is explained as a stale proposal. */
  actionError: string | null;
  accept: (proposalId: string) => Promise<boolean>;
  reopen: (proposalId: string | null) => Promise<boolean>;
  clearError: () => void;
}

const STALE = 'The definition changed while you were reading it: the agent saved a newer proposal or it was accepted or reopened elsewhere. The latest one is shown now; review it and try again.';

/**
 * A project's definition: read once, then kept current by `work.definition.project`, whichever side
 * (the agent's chat or this view) changed it. Accepting also adopts the Fundaciones feature it creates.
 */
export const useProjectDefinition = (projectId: string, enabled = true): UseProjectDefinition => {
  const definition = useLiveStore((live) => live.work[projectId]?.definition ?? null);
  const epoch = useLiveStore((live) => live.epoch);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(() => fetchProjectDefinition(projectId)
    .then((value) => useLiveStore.getState().adoptProjectDefinition(projectId, value)), [projectId]);

  useEffect(() => {
    if (!enabled) return undefined;
    let active = true;
    fetchProjectDefinition(projectId)
      .then((value) => { if (active) { useLiveStore.getState().adoptProjectDefinition(projectId, value); setLoadError(null); } })
      .catch((failure: Error) => { if (active) setLoadError(failure.message); });
    return () => { active = false; };
  }, [projectId, enabled, epoch]);

  const mutate = useCallback(async (action: () => Promise<{ definition: ProjectDefinition; items: WorkItem[] }>) => {
    setBusy(true);
    setActionError(null);
    try {
      const result = await action();
      const live = useLiveStore.getState();
      live.adoptProjectDefinition(projectId, result.definition);
      for (const item of result.items) live.adoptWorkItem(item);
      return true;
    } catch (failure) {
      if (failure instanceof WorkApiError && failure.status === 409) {
        setActionError(`${STALE} (${failure.message})`);
        void refresh().catch(() => undefined);
      } else {
        setActionError(failure instanceof Error ? failure.message : 'Something went wrong.');
      }
      return false;
    } finally {
      setBusy(false);
    }
  }, [projectId, refresh]);

  return {
    definition, loadError, busy, actionError,
    accept: (proposalId) => mutate(() => acceptProjectDefinition(projectId, proposalId)),
    reopen: (proposalId) => mutate(() => reopenProjectDefinition(projectId, proposalId)),
    clearError: () => setActionError(null),
  };
};
