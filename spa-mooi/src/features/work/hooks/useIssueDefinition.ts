import { useCallback, useEffect, useState } from 'react';
import { acceptIssueDefinition, fetchIssueDefinition, reopenIssueDefinition, WorkApiError } from '@/features/work/api/workApi';
import type { IssueDefinition } from '@/features/work/api/workApi';
import type { WorkIssue } from '@/features/work/types/WorkIssue';
import type { WorkItem } from '@/features/work/types/WorkItem';
import { useLiveStore } from '@/stores/liveStore';

const staleMessage = 'The proposal changed while you were reviewing it. The latest proposal is loaded; review it and try again.';
type MutationResult = { definition: IssueDefinition; issues: WorkIssue[]; items: WorkItem[] };

export const useIssueDefinition = (projectId: string, number: number, enabled = true) => {
  const definition = useLiveStore((live) => live.work[projectId]?.issueDefinitions?.[number] ?? null);
  const epoch = useLiveStore((live) => live.epoch);
  const [busy, setBusy] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    useLiveStore.getState().adoptIssueDefinition(projectId, number, await fetchIssueDefinition(projectId, number));
  }, [projectId, number]);

  useEffect(() => {
    if (!enabled || !number) return undefined;
    let active = true;
    fetchIssueDefinition(projectId, number).then((value) => {
      if (active) {
        useLiveStore.getState().adoptIssueDefinition(projectId, number, value);
        setLoadError(null);
      }
    }).catch((failure: Error) => { if (active) setLoadError(failure.message); });
    return () => { active = false; };
  }, [projectId, number, enabled, epoch]);

  const mutate = useCallback(async (action: () => Promise<MutationResult>) => {
    setBusy(true);
    setActionError(null);
    try {
      const result = await action();
      const live = useLiveStore.getState();
      live.adoptIssueDefinition(projectId, number, result.definition);
      result.issues.forEach((issue) => live.adoptWorkIssue(projectId, issue));
      result.items.forEach((item) => live.adoptWorkItem(item));
      return true;
    } catch (failure) {
      if (failure instanceof WorkApiError && failure.status === 409) {
        setActionError(`${staleMessage} (${failure.message})`);
        void refresh().catch(() => undefined);
      } else setActionError(failure instanceof Error ? failure.message : 'Something went wrong.');
      return false;
    } finally { setBusy(false); }
  }, [projectId, number, refresh]);

  return {
    definition,
    busy,
    loadError,
    actionError,
    error: actionError ?? loadError,
    accept: (proposalId: string) => mutate(() => acceptIssueDefinition(projectId, number, proposalId)),
    reopen: (proposalId: string | null) => mutate(() => reopenIssueDefinition(projectId, number, proposalId)),
    clearError: () => { setLoadError(null); setActionError(null); },
  };
};
