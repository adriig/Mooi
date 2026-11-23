import type { WorkIssue } from '@/features/work/types/WorkIssue';
import type { WorkItem } from '@/features/work/types/WorkItem';
import type { WorkEntryStatus } from '@/features/work/types/WorkEntryStatus';

/** One row of To-dos: a feature, or a defect or debt issue with the item that runs it, if any. */
export interface WorkEntry {
  key: string;
  kind: 'feature' | 'defect' | 'debt';
  title: string;
  status: WorkEntryStatus;
  item: WorkItem | null;
  issue: WorkIssue | null;
}
