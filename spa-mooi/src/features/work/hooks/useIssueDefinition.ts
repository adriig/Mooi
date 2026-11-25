import { useCallback, useEffect, useState } from 'react';
import { acceptIssueDefinition, fetchIssueDefinition, reopenIssueDefinition, WorkApiError } from '@/features/work/api/workApi';
import type { IssueDefinition } from '@/features/work/api/workApi';
import { useLiveStore } from '@/stores/liveStore';

export const useIssueDefinition = (projectId: string, number: number, enabled = true) => {
  const definition = useLiveStore((live) => live.work[projectId]?.issueDefinitions?.[number] ?? null);
  const epoch = useLiveStore((live) => live.epoch);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const refresh = useCallback(async () => {
    const value = await fetchIssueDefinition(projectId, number);
    useLiveStore.setState((state) => {
      const current = state.work[projectId];
      return { work: { ...state.work, [projectId]: { ...(current ?? { items: null, issues: null }),
        issueDefinitions: { ...(current?.issueDefinitions ?? {}), [number]: value } } } };
    });
  }, [projectId, number]);

  useEffect(() => {
    if (!enabled) return undefined;
    let active = true;
    fetchIssueDefinition(projectId, number).then((value) => {
      if (!active) return;
      useLiveStore.setState((state) => {
        const current = state.work[projectId];
        return { work: { ...state.work, [projectId]: { ...(current ?? { items: null, issues: null }),
          issueDefinitions: { ...(current?.issueDefinitions ?? {}), [number]: value } } } };
      });
      setLoadError(null);
    }).catch((error: Error) => { if (active) setLoadError(error.message); });
    return () => { active = false; };
  }, [projectId, number, epoch, enabled]);

  const mutate = useCallback(async (action: () => Promise<{ definition: IssueDefinition; issues: import('@/features/work/types/WorkIssue').WorkIssue[]; items: import('@/features/work/types/WorkItem').WorkItem[] }>) => {
    setBusy(true); setActionError(null);
    try {
      const result = await action();
      useLiveStore.setState((state) => {
        const current = state.work[projectId] ?? { items: null, issues: null };
        let issues = current.issues;
        let items = current.items;
        for (const issue of result.issues) if (issues) {
          const index = issues.findIndex((candidate) => candidate.number === issue.number);
          issues = index < 0 ? [issue, ...issues] : issues.map((candidate) => candidate.number === issue.number ? issue : candidate);
        }
        for (const item of result.items) if (items) {
          const index = items.findIndex((candidate) => candidate.id === item.id);
          items = index < 0 ? [item, ...items] : items.map((candidate) => candidate.id === item.id ? item : candidate);
        }
        return { work: { ...state.work, [projectId]: { ...current, issues, items,
          issueDefinitions: { ...(current.issueDefinitions ?? {}), [number]: result.definition } } } };
      });
      return true;
    } catch (error) {
      setActionError(error instanceof WorkApiError && error.status === 409
        ? `This proposal is out of date. The latest revision has been loaded; review it and try again. (${error.message})`
        : error instanceof Error ? error.message : 'Something went wrong.');
      if (error instanceof WorkApiError && error.status === 409) void refresh().catch(() => undefined);
      return false;
    } finally { setBusy(false); }
  }, [number, projectId, refresh]);

  return { definition, loadError, actionError, busy,
    accept: (proposalId: string) => mutate(() => acceptIssueDefinition(projectId, number, proposalId)),
    reopen: (proposalId: string | null) => mutate(() => reopenIssueDefinition(projectId, number, proposalId)) };
};
