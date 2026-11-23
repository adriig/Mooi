import type { WorkAssignment } from '@/features/work/types/WorkAssignment';
import type { WorkTaskCategory } from '@/features/work/types/WorkTaskCategory';
import type { WorkTaskStatus } from '@/features/work/types/WorkTaskStatus';

/** One task of a work item, run in its own session; `dependsOn` names tasks of the same item. */
export interface WorkTask {
  id: string;
  key: string;
  title: string;
  category: WorkTaskCategory;
  description: string;
  acceptance: string;
  assignment: WorkAssignment;
  /** Tried in order when the assigned account runs low on quota. */
  alternatives: WorkAssignment[];
  autoMerge: boolean;
  tests: boolean;
  /** The task this QA task checks. */
  qaOf: string | null;
  /** The QA task whose defect this task fixes. */
  fixOf: string | null;
  status: WorkTaskStatus;
  sessionId: string | null;
  attempts: number;
  report: string | null;
  dependsOn: string[];
  startedAt: string | null;
  finishedAt: string | null;
}
