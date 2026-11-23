import type { WorkIssue } from '@/features/work/types/WorkIssue';
import type { WorkItem } from '@/features/work/types/WorkItem';

/** A project's work as last read or announced: null until read. */
export interface ProjectWork {
  items: WorkItem[] | null;
  issues: WorkIssue[] | null;
}
