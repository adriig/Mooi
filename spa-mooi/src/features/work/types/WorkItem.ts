import type { WorkItemKind } from '@/features/work/types/WorkItemKind';
import type { WorkItemStatus } from '@/features/work/types/WorkItemStatus';
import type { WorkTask } from '@/features/work/types/WorkTask';

export interface WorkItemDeployment {
  state: 'none' | 'pending' | 'deployed' | 'unknown';
  version: string | null;
  deployedAt: string | null;
  inCurrentRelease: boolean | null;
}

/** A feature, or a defect or debt issue Mooi runs, with its tasks. */
export interface WorkItem {
  id: string;
  projectId: string;
  kind: WorkItemKind;
  issueNumber: number | null;
  title: string;
  spec: string;
  status: WorkItemStatus;
  /** Features are always defined; issues require an accepted definition before Start. */
  definitionState?: 'NEW' | 'DEFINED';
  /** Why the platform paused it on its own (e.g. its factory credential was revoked); null for a manual pause. */
  pauseReason?: string | null;
  /** Another item of the project that must be done first. */
  dependsOn: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  tasks: WorkTask[];
  /** Where the item stands on its way to production; `none` until it is done. */
  deployment?: WorkItemDeployment | null;
}
