import type { WorkColumn } from '@/features/work/types/WorkColumn';
import type { WorkIssue } from '@/features/work/types/WorkIssue';
import type { WorkItem } from '@/features/work/types/WorkItem';

/** One Work card: a feature, or a defect or debt issue with the item that runs it, if any. */
export interface WorkEntry {
  key: string;
  kind: 'feature' | 'defect' | 'debt';
  title: string;
  column: WorkColumn;
  item: WorkItem | null;
  issue: WorkIssue | null;
}
