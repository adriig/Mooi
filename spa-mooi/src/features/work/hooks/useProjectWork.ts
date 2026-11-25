import { useCallback, useEffect, useState } from 'react';
import { fetchProjectWork, fetchWorkIssues } from '@/features/work/api/workApi';
import type { WorkIssue } from '@/features/work/types/WorkIssue';
import type { WorkItem } from '@/features/work/types/WorkItem';
import { useLiveStore } from '@/stores/liveStore';

export interface UseProjectWork {
  /** Null until first read. */
  items: WorkItem[] | null;
  issues: WorkIssue[] | null;
  loadError: string | null;
  /** GitHub could not list the defects and debt (permissions, issues turned off): features still show. */
  issuesError: string | null;
  /** The last change that failed, for the view to show until the next one. */
  actionError: string | null;
  busy: boolean;
  /** Runs one change, adopting whatever item or issue it answers. */
  run: <T>(action: () => Promise<T>) => Promise<T | null>;
  reload: () => void;
}

/**
 * A project's work: its features and the defects and debt Mooi runs (from mic-mooi), and its defect
 * and debt issues (from GitHub). Nothing is read until `enabled`, so a page listing many projects
 * reads each one only once it is looked at; live announcements keep whatever was read up to date.
 */
export const useProjectWork = (projectId: string, enabled = true): UseProjectWork => {
  const work = useLiveStore((live) => live.work[projectId]);
  const epoch = useLiveStore((live) => live.epoch);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [issuesError, setIssuesError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [reloads, setReloads] = useState(0);

  useEffect(() => {
    if (!enabled) return undefined;
    let active = true;
    const live = useLiveStore.getState();
    const initialItems = fetchProjectWork(projectId)
      .then(({ items, newProject }) => { if (active) { live.adoptWorkItems(projectId, items, newProject ?? false); setLoadError(null); } })
      .catch((failure: Error) => { if (active) setLoadError(failure.message); });
    // Synchronization can close an item: adopt it after the initial snapshot to avoid restoring stale work.
    void initialItems.then(() => fetchWorkIssues(projectId))
      .then(({ issues, items }) => {
        if (!active) return;
        for (const item of items) live.adoptWorkItem(item);
        live.adoptWorkIssues(projectId, issues);
        setIssuesError(null);
      })
      .catch((failure: Error) => {
        if (!active) return;
        setIssuesError(failure.message);
        live.adoptWorkIssues(projectId, []);
      });
    return () => { active = false; };
  }, [projectId, enabled, epoch, reloads]);

  const run = useCallback(async <T,>(action: () => Promise<T>): Promise<T | null> => {
    setBusy(true);
    setActionError(null);
    try {
      const result = await action();
      adopt(projectId, result);
      return result;
    } catch (failure) {
      setActionError(failure instanceof Error ? failure.message : 'Something went wrong.');
      return null;
    } finally {
      setBusy(false);
    }
  }, [projectId]);

  return {
    items: work?.items ?? null,
    issues: work?.issues ?? null,
    loadError,
    issuesError,
    actionError,
    busy,
    run,
    reload: () => setReloads((count) => count + 1),
  };
};

/** Answers carry an item, an issue, or both: each goes into the live store as it is. */
const adopt = (projectId: string, result: unknown) => {
  if (!result || typeof result !== 'object') return;
  const live = useLiveStore.getState();
  const value = result as { id?: string; tasks?: unknown; issue?: WorkIssue | null; item?: WorkItem | null };
  if (value.id && Array.isArray(value.tasks)) {
    live.adoptWorkItem(value as WorkItem);
    return;
  }
  if (value.issue) live.adoptWorkIssue(projectId, value.issue);
  if (value.item) live.adoptWorkItem(value.item);
};
