import type { WorkItemKind } from '@/features/work/types/WorkItemKind';
import type { WorkItemStatus } from '@/features/work/types/WorkItemStatus';
import type { WorkTask } from '@/features/work/types/WorkTask';

/** A feature, or a defect or debt issue Mooi runs, with its tasks. */
export interface WorkItem {
  id: string;
  projectId: string;
  kind: WorkItemKind;
  issueNumber: number | null;
  title: string;
  spec: string;
  status: WorkItemStatus;
  /** Another item of the project that must be done first. */
  dependsOn: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  tasks: WorkTask[];
}
