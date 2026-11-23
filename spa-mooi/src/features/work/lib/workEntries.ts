import type { WorkEntry } from '@/features/work/types/WorkEntry';
import type { WorkEntryStatus } from '@/features/work/types/WorkEntryStatus';
import type { WorkIssue } from '@/features/work/types/WorkIssue';
import type { WorkItem } from '@/features/work/types/WorkItem';

const itemStatus = (item: WorkItem): WorkEntryStatus => {
  if (item.status === 'done') return 'done';
  if (item.status === 'running' || item.status === 'paused') {
    return 'inProgress';
  }
  return 'open';
};

const ORDER: Record<WorkEntryStatus, number> = { inProgress: 0, open: 1, done: 2 };

/**
 * The To-dos rows: every feature, and every defect and debt issue joined with the item that runs it.
 * Work under way comes first, then what is to do, newest on top, and done at the bottom.
 */
export const workEntries = (items: WorkItem[], issues: WorkIssue[]): WorkEntry[] => {
  const byIssue = new Map(items.filter((item) => item.issueNumber !== null).map((item) => [item.issueNumber, item]));
  const features: WorkEntry[] = items.filter((item) => item.kind === 'feature').map((item) => ({
    key: item.id, kind: 'feature', title: item.title, status: itemStatus(item), item, issue: null,
  }));
  const tracked: WorkEntry[] = issues.map((issue) => {
    const item = byIssue.get(issue.number) ?? null;
    const status: WorkEntryStatus = item ? itemStatus(item) : issue.state === 'closed' ? 'done' : 'open';
    return { key: `#${issue.number}`, kind: issue.kind, title: issue.title, status, item, issue };
  });
  const rank = (entry: WorkEntry) => Date.parse(entry.item?.createdAt ?? entry.issue?.createdAt ?? '') || 0;
  return [...features, ...tracked].sort((a, b) => ORDER[a.status] - ORDER[b.status] || rank(b) - rank(a));
};

export type WorkCounts = Record<WorkEntryStatus, number>;

export const countWork = (entries: WorkEntry[]): WorkCounts => {
  const counts: WorkCounts = { open: 0, inProgress: 0, done: 0 };
  for (const entry of entries) counts[entry.status] += 1;
  return counts;
};
