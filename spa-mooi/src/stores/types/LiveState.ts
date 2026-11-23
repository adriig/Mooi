import type { LiveOperationKind } from '@/features/live/types/LiveOperationKind';
import type { PlatformEnvironmentVariable } from '@/features/platform/types/PlatformEnvironmentVariable';
import type { PlatformSnapshot } from '@/features/platform/types/PlatformSnapshot';
import type { PlatformStreamState } from '@/features/platform/types/PlatformStreamState';
import type { ProjectWork } from '@/features/work/types/ProjectWork';
import type { WorkIssue } from '@/features/work/types/WorkIssue';
import type { WorkItem } from '@/features/work/types/WorkItem';
import type { WorkSettings } from '@/features/work/types/WorkSettings';
import type { EventStreamState, StreamFrame } from '@/features/sessions/lib/openEventStream';

export interface LiveState {
  streamState: EventStreamState;
  /** The first `sync` arrived: lists can trust what they show. */
  synced: boolean;
  /** Bumped to reopen the channel at once. */
  generation: number;
  /** Live operation and console output per kind and project. */
  operations: Record<LiveOperationKind, Record<string, PlatformStreamState<PlatformSnapshot>>>;
  /** Per `<kind>:<projectId>`: bumped whenever that configuration or an environment it reads changes. */
  configurations: Record<string, number>;
  /** Bumped on every `sync`: what changed while disconnected is unknown, so every view reloads once. */
  epoch: number;
  /** Per kind: bumped whenever an operation of any project ends, so persisted histories reload. */
  finished: Record<LiveOperationKind, number>;
  /** Development variables per project, as last announced; absent until one changes. */
  developmentEnvironments: Record<string, PlatformEnvironmentVariable[]>;
  /** Each project's work (features, defects and debt) as last read or announced by any side. */
  work: Record<string, ProjectWork>;
  /** Each project's factory limits, once read or announced. */
  workSettings: Record<string, WorkSettings>;
  setStreamState: (streamState: EventStreamState) => void;
  /** Folds one frame of the player's channel that is not about sessions. */
  apply: (frame: StreamFrame) => void;
  adoptSnapshot: (kind: LiveOperationKind, projectId: string, snapshot: PlatformSnapshot) => void;
  adoptWorkItems: (projectId: string, items: WorkItem[]) => void;
  adoptWorkIssues: (projectId: string, issues: WorkIssue[]) => void;
  /** One item as an answer or an event brought it. */
  adoptWorkItem: (item: WorkItem) => void;
  adoptWorkIssue: (projectId: string, issue: WorkIssue) => void;
  adoptWorkSettings: (projectId: string, settings: WorkSettings) => void;
  reconnect: () => void;
  reset: () => void;
}
