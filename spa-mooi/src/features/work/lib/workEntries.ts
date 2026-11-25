import type { WorkColumn } from '@/features/work/types/WorkColumn';
import type { WorkEntry } from '@/features/work/types/WorkEntry';
import type { WorkIssue } from '@/features/work/types/WorkIssue';
import type { WorkItem, WorkItemDeployment } from '@/features/work/types/WorkItem';

export const WORK_COLUMNS: { id: WorkColumn; label: string }[] = [
  { id: 'new', label: 'New' },
  { id: 'defined', label: 'Defined' },
  { id: 'inProgress', label: 'In progress' },
  { id: 'pendingDeploy', label: 'Pending to deploy' },
  { id: 'deployed', label: 'Deployed' },
];

const UNKNOWN: WorkItemDeployment = { state: 'unknown', version: null, deployedAt: null, inCurrentRelease: null };

const itemColumn = (item: WorkItem): WorkColumn => {
  if (item.status === 'running' || item.status === 'paused') return 'inProgress';
  if (item.status === 'done') {
    const state = item.deployment?.state;
    return state === 'deployed' || state === 'unknown' ? 'deployed' : 'pendingDeploy';
  }
  return 'defined';
};

/** The deployment an entry shows: a closed issue without a Mooi item is a legacy, unknown one. */
export const entryDeployment = (entry: WorkEntry): WorkItemDeployment | null =>
  entry.item ? entry.item.deployment ?? null : entry.issue?.state === 'closed' ? UNKNOWN : null;

/** Work cards: every feature, and every defect and debt issue joined with the item that runs it. */
export const workEntries = (items: WorkItem[], issues: WorkIssue[]): WorkEntry[] => {
  const byIssue = new Map(items.filter((item) => item.issueNumber !== null).map((item) => [item.issueNumber, item]));
  const features: WorkEntry[] = items.filter((item) => item.kind === 'feature').map((item) => ({
    key: item.id, kind: 'feature', title: item.title, column: itemColumn(item), item, issue: null,
  }));
  const tracked: WorkEntry[] = issues.map((issue) => {
    const item = byIssue.get(issue.number) ?? null;
    const definitionState = item?.definitionState ?? issue.definitionState;
    const column: WorkColumn = issue.state === 'open' && definitionState !== 'DEFINED' ? 'new'
      : item ? itemColumn(item)
        : issue.state === 'closed' ? 'deployed' : 'defined';
    return { key: `#${issue.number}`, kind: issue.kind, title: issue.title, column, item, issue };
  });
  return [...features, ...tracked];
};

const time = (value: string | null | undefined): number => Date.parse(value ?? '') || 0;
const needsPlayer = (entry: WorkEntry): boolean =>
  (entry.item?.tasks.some((task) => task.status === 'attention' || task.status === 'review') ?? false)
  || (entry.item?.changeRequests?.some((change) => change.status === 'awaiting_confirmation') ?? false);
const finished = (entry: WorkEntry): number => time(entry.item?.finishedAt ?? entry.issue?.closedAt);

/** Each column's order: newest first; items needing the player lead in progress; unknown versions trail. */
export const sortColumn = (column: WorkColumn, entries: WorkEntry[]): WorkEntry[] => {
  const sorted = [...entries];
  switch (column) {
    case 'new':
      return sorted.sort((a, b) => time(b.issue?.createdAt) - time(a.issue?.createdAt));
    case 'defined':
      return sorted.sort((a, b) => time(b.item?.createdAt ?? b.issue?.createdAt) - time(a.item?.createdAt ?? a.issue?.createdAt));
    case 'inProgress':
      return sorted.sort((a, b) => Number(needsPlayer(b)) - Number(needsPlayer(a))
        || time(b.item?.startedAt) - time(a.item?.startedAt));
    case 'pendingDeploy':
      return sorted.sort((a, b) => finished(b) - finished(a));
    case 'deployed':
      return sorted.sort((a, b) => {
        const known = (entry: WorkEntry) => entryDeployment(entry)?.state === 'deployed';
        if (known(a) !== known(b)) return known(a) ? -1 : 1;
        return known(a)
          ? time(entryDeployment(b)?.deployedAt) - time(entryDeployment(a)?.deployedAt)
          : finished(b) - finished(a);
      });
  }
};
