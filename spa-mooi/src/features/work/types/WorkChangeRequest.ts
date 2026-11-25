import type { WorkAssignment } from '@/features/work/types/WorkAssignment';
import type { WorkTaskCategory } from '@/features/work/types/WorkTaskCategory';
import type { WorkTaskUpdate } from '@/features/work/types/WorkTaskUpdate';

export type WorkChangeStatus = 'queued' | 'analyzing' | 'awaiting_confirmation' | 'applied' | 'rejected';

/** A task the analyst proposes to add; mirrors the platform's task draft. */
export interface WorkChangeTaskDraft {
  key?: string | null;
  title?: string | null;
  category?: WorkTaskCategory | null;
  description?: string | null;
  acceptance?: string | null;
  assignment?: WorkAssignment | null;
  autoMerge?: boolean | null;
  tests?: boolean | null;
  dependsOn?: string[] | null;
}

/** One staged edit of a proposal; all of them apply together when the user accepts. */
export interface WorkChangeOperation {
  kind: 'spec' | 'add_task' | 'update_task' | 'delete_task';
  taskId?: string | null;
  item?: { title?: string | null; spec?: string | null; dependsOn?: string | null } | null;
  draft?: WorkChangeTaskDraft | null;
  task?: WorkTaskUpdate | null;
}

/** The analyst's textual proposal and the operations it staged. */
export interface WorkChangeProposal {
  text?: string | null;
  operations?: WorkChangeOperation[] | null;
}

/** A durable request to change an ongoing feature, analyzed at a safe point without pausing it. */
export interface WorkChangeRequest {
  id: string;
  itemId: string;
  request: string;
  status: WorkChangeStatus;
  analysis: string | null;
  proposal: WorkChangeProposal | null;
  /** The analyst session while `analyzing`; null otherwise. */
  sessionId: string | null;
  /** When the user accepted this proposal; a fresh analysis follows with that permission. */
  confirmedAt: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  finishedAt: string | null;
}
