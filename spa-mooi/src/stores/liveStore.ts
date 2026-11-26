import { create } from 'zustand';
import type { LiveOperationKind } from '@/features/live/types/LiveOperationKind';
import { applyPlatformSnapshot, applyPlatformStreamEvent } from '@/features/platform/lib/applyPlatformStreamEvent';
import type { PlatformEnvironmentVariable } from '@/features/platform/types/PlatformEnvironmentVariable';
import type { PlatformSnapshot } from '@/features/platform/types/PlatformSnapshot';
import type { PlatformStreamState } from '@/features/platform/types/PlatformStreamState';
import type { ProjectDefinition } from '@/features/work/types/ProjectDefinition';
import type { IssueDefinition } from '@/features/work/api/workApi';
import type { ProjectWork } from '@/features/work/types/ProjectWork';
import type { WorkIssue } from '@/features/work/types/WorkIssue';
import type { WorkItem } from '@/features/work/types/WorkItem';
import type { WorkSettings } from '@/features/work/types/WorkSettings';
import type { LiveState } from '@/stores/types/LiveState';

interface OperationPayload {
  projectId: string;
  snapshot: PlatformSnapshot;
  logs: string[];
}

const KINDS: LiveOperationKind[] = ['production', 'backup'];
const EMPTY: PlatformStreamState<PlatformSnapshot> = { snapshot: null, logs: [] };
const initial = {
  streamState: 'closed' as const,
  synced: false,
  operations: { production: {}, backup: {} },
  configurations: {},
  epoch: 0,
  finished: { production: 0, backup: 0 },
  developmentEnvironments: {},
  work: {},
  workSettings: {},
  analyticsRevisions: {},
  analyticsAnalysisRevisions: {},
  analyticsGeoRuns: {},
  analyticsGeoSuggestionRevisions: {},
};

const EMPTY_WORK: ProjectWork = { items: null, issues: null };

/** Inserts or replaces by id, keeping the list's order; a new item goes on top, like the server lists it. */
const upsertItem = (work: ProjectWork | undefined, item: WorkItem): ProjectWork => {
  const current = work ?? EMPTY_WORK;
  if (!current.items) return current;
  const index = current.items.findIndex((entry) => entry.id === item.id);
  const items = index < 0 ? [item, ...current.items] : current.items.map((entry) => (entry.id === item.id ? item : entry));
  return { ...current, items };
};

const upsertIssue = (work: ProjectWork | undefined, issue: WorkIssue): ProjectWork => {
  const current = work ?? EMPTY_WORK;
  if (!current.issues) return current;
  const index = current.issues.findIndex((entry) => entry.number === issue.number);
  const issues = index < 0 ? [issue, ...current.issues] : current.issues.map((entry) => (entry.number === issue.number ? issue : entry));
  return { ...current, issues };
};

const bump = (counters: Record<string, number>, ...keys: string[]): Record<string, number> =>
  Object.fromEntries([...Object.entries(counters), ...keys.map((key) => [key, (counters[key] ?? 0) + 1])]);

const byProject = (items: OperationPayload[]): Record<string, PlatformStreamState<PlatformSnapshot>> =>
  Object.fromEntries(items.map((item) => [item.projectId, { snapshot: item.snapshot, logs: item.logs }]));

const ended = (snapshot: PlatformSnapshot | null | undefined): boolean =>
  (snapshot as { state?: string } | null | undefined)?.state === 'succeeded'
  || (snapshot as { state?: string } | null | undefined)?.state === 'failed';

/**
 * Everything live outside session transcripts, folded from the player's channel: each project's
 * production and backup operation with its console, and revisions that tell views when a
 * configuration, an environment or a persisted history changed, whichever side changed it, and
 * each project's work (features, defects and debt) as last read or announced.
 * Session summaries from the same channel go to `sessionsStore`. Not persisted: server state.
 */
export const useLiveStore = create<LiveState>((set) => ({
  ...initial,
  generation: 0,

  setStreamState: (streamState) => set({ streamState }),

  apply: (frame) => set((state) => {
    const data = frame.data as Record<string, unknown>;
    if (frame.type === 'sync') {
      // What changed while disconnected is unknown: a new epoch reloads every view once.
      return {
        synced: true,
        epoch: state.epoch + 1,
        operations: { production: byProject(data.production as OperationPayload[]), backup: byProject(data.backups as OperationPayload[]) },
        finished: { production: state.finished.production + 1, backup: state.finished.backup + 1 },
      };
    }
    const projectId = String(data.projectId ?? '');
    if (frame.type === 'analytics.updated') {
      return { analyticsRevisions: bump(state.analyticsRevisions, projectId) };
    }
    if (frame.type === 'analytics.analysis') {
      return { analyticsAnalysisRevisions: bump(state.analyticsAnalysisRevisions, `${projectId}:${String(data.analysisId ?? '')}`) };
    }
    if (frame.type === 'analytics.geo.suggestion') {
      return { analyticsGeoSuggestionRevisions: bump(state.analyticsGeoSuggestionRevisions, `${projectId}:${String(data.generationId ?? '')}`) };
    }
    if (frame.type === 'analytics.geo.run') {
      return { analyticsGeoRuns: { ...state.analyticsGeoRuns, [projectId]: data } };
    }
    if (frame.type === 'work.item') {
      return { work: { ...state.work, [projectId]: upsertItem(state.work[projectId], data.item as WorkItem) } };
    }
    if (frame.type === 'work.definition.project') {
      return { work: { ...state.work, [projectId]: { ...(state.work[projectId] ?? EMPTY_WORK), definition: data.definition as ProjectDefinition } } };
    }
    if (frame.type === 'work.definition') {
      const current = state.work[projectId] ?? EMPTY_WORK;
      const number = Number(data.number);
      const hasProposal = Boolean((data.definition as IssueDefinition | undefined)?.proposal);
      const issues = current.issues?.map((issue) => issue.number === number ? { ...issue, hasProposal } : issue) ?? null;
      const items = current.items?.map((item) => item.issueNumber === number ? { ...item, hasProposal } : item) ?? null;
      return { work: { ...state.work, [projectId]: { ...current,
        issues, items,
        issueDefinitions: { ...(current.issueDefinitions ?? {}), [number]: data.definition as IssueDefinition } } } };
    }
    if (frame.type === 'work.removed') {
      const current = state.work[projectId];
      if (!current?.items) return state;
      return { work: { ...state.work, [projectId]: { ...current, items: current.items.filter((item) => item.id !== data.itemId) } } };
    }
    if (frame.type === 'work.settings') {
      return { workSettings: { ...state.workSettings, [projectId]: data.settings as WorkSettings } };
    }
    if (frame.type === 'work.issue') {
      return { work: { ...state.work, [projectId]: upsertIssue(state.work[projectId], data.issue as WorkIssue) } };
    }
    if (frame.type === 'development.environment') {
      return {
        developmentEnvironments: { ...state.developmentEnvironments, [projectId]: data.environment as PlatformEnvironmentVariable[] },
        configurations: bump(state.configurations, ...KINDS.map((kind) => `${kind}:${projectId}`)),
      };
    }
    const [prefix, action] = frame.type.split('.') as [LiveOperationKind, string];
    if (!KINDS.includes(prefix)) return state;
    if (action === 'configuration') return { configurations: bump(state.configurations, `${prefix}:${projectId}`) };
    const previous = state.operations[prefix][projectId] ?? EMPTY;
    const next = applyPlatformStreamEvent(prefix, previous, frame);
    return {
      operations: { ...state.operations, [prefix]: { ...state.operations[prefix], [projectId]: next } },
      ...(action === 'updated' && ended(next.snapshot) && !ended(previous.snapshot) ? { finished: { ...state.finished, [prefix]: state.finished[prefix] + 1 } } : {}),
    };
  }),

  adoptSnapshot: (kind, projectId, snapshot) => set((state) => ({
    operations: { ...state.operations, [kind]: { ...state.operations[kind],
      [projectId]: applyPlatformSnapshot(state.operations[kind][projectId] ?? EMPTY, snapshot) } },
  })),

  adoptWorkItems: (projectId, items, newProject) => set((state) => ({
    work: { ...state.work, [projectId]: { ...(state.work[projectId] ?? EMPTY_WORK), items,
      ...(newProject === undefined ? {} : { newProject }) } },
  })),

  adoptProjectDefinition: (projectId, definition) => set((state) => ({
    work: { ...state.work, [projectId]: { ...(state.work[projectId] ?? EMPTY_WORK), definition } },
  })),

  adoptWorkIssues: (projectId, issues) => set((state) => ({
    work: { ...state.work, [projectId]: { ...(state.work[projectId] ?? EMPTY_WORK), issues } },
  })),

  adoptWorkItem: (item) => set((state) => ({
    work: { ...state.work, [item.projectId]: upsertItem(state.work[item.projectId], item) },
  })),

  adoptWorkSettings: (projectId, settings) => set((state) => ({
    workSettings: { ...state.workSettings, [projectId]: settings },
  })),

  adoptWorkIssue: (projectId, issue) => set((state) => ({
    work: { ...state.work, [projectId]: upsertIssue(state.work[projectId], issue) },
  })),

  adoptIssueDefinition: (projectId, number, definition) => set((state) => {
    const current = state.work[projectId] ?? EMPTY_WORK;
    return { work: { ...state.work, [projectId]: { ...current,
      issueDefinitions: { ...(current.issueDefinitions ?? {}), [number]: definition } } } };
  }),

  reconnect: () => set((state) => ({ generation: state.generation + 1 })),

  reset: () => set(initial),
}));
