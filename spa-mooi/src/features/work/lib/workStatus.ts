import type { Tone } from '@/shared/types/Tone';
import type { WorkChangeStatus } from '@/features/work/types/WorkChangeRequest';
import type { WorkItem } from '@/features/work/types/WorkItem';
import type { WorkItemStatus } from '@/features/work/types/WorkItemStatus';
import type { WorkTask } from '@/features/work/types/WorkTask';
import type { WorkTaskStatus } from '@/features/work/types/WorkTaskStatus';

export const ITEM_STATUS: Record<WorkItemStatus, { label: string; tone: Tone }> = {
  planned: { label: 'Planned', tone: 'neutral' },
  running: { label: 'Running', tone: 'info' },
  paused: { label: 'Paused', tone: 'warning' },
  done: { label: 'Done', tone: 'success' },
};

export const TASK_STATUS: Record<WorkTaskStatus | 'blocked', { label: string; tone: Tone }> = {
  planned: { label: 'Ready', tone: 'neutral' },
  blocked: { label: 'Blocked', tone: 'neutral' },
  queued: { label: 'Queued', tone: 'accent' },
  running: { label: 'Running', tone: 'info' },
  merging: { label: 'Merging', tone: 'info' },
  review: { label: 'Awaiting merge', tone: 'warning' },
  attention: { label: 'Needs you', tone: 'danger' },
  done: { label: 'Done', tone: 'success' },
  cancelled: { label: 'Cancelled', tone: 'neutral' },
};

export const CHANGE_STATUS: Record<WorkChangeStatus, { label: string; tone: Tone }> = {
  queued: { label: 'Queued', tone: 'accent' },
  analyzing: { label: 'Analyzing', tone: 'info' },
  awaiting_confirmation: { label: 'Awaiting confirmation', tone: 'warning' },
  applied: { label: 'Applied', tone: 'success' },
  rejected: { label: 'Rejected', tone: 'neutral' },
};

/** A planned task whose dependencies are not all done yet reads as blocked. */
export const taskState = (task: WorkTask, tasks: WorkTask[]): WorkTaskStatus | 'blocked' => {
  if (task.status !== 'planned') return task.status;
  const done = new Set(tasks.filter((other) => other.status === 'done').map((other) => other.id));
  return task.dependsOn.every((id) => done.has(id)) ? 'planned' : 'blocked';
};

/** A task's settings may change until its session starts. */
export const taskEditable = (task: WorkTask): boolean =>
  task.status === 'planned' || task.status === 'queued' || task.status === 'attention';

export type OpenChangeStatus = 'queued' | 'analyzing' | 'awaiting_confirmation';
export const OPEN_CHANGE_STATUSES: OpenChangeStatus[] = ['queued', 'analyzing', 'awaiting_confirmation'];

/** Change requests still open, by status; applied and rejected ones never keep an item active. */
export const openChangeCounts = (item: WorkItem | null | undefined): { status: OpenChangeStatus; count: number }[] => {
  const changes = item?.changeRequests ?? [];
  return OPEN_CHANGE_STATUSES
    .map((status) => ({ status, count: changes.filter((change) => change.status === status).length }))
    .filter((entry) => entry.count > 0);
};

/** The item's task-execution state: a running item whose tasks are all done only waits on change requests. */
export const itemStatus = (item: WorkItem): { label: string; tone: Tone } => {
  const tasksComplete = item.tasks.length > 0 && item.tasks.every((task) => task.status === 'done');
  if (item.status === 'running' && tasksComplete && openChangeCounts(item).length > 0) {
    return { label: 'Tasks complete', tone: 'success' };
  }
  return ITEM_STATUS[item.status];
};
