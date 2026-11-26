import type { DevBlockerHandoff, DevBlockerKind, WorkTask } from '@/features/work/types/WorkTask';

export const DEV_BLOCKER_LABEL: Record<DevBlockerKind, string> = {
  product: 'Product work',
  platform: 'Missing platform capability',
};

/** The blocker holding a development task in attention now; legacy blocked reports have none. */
export const devBlocker = (task: WorkTask): DevBlockerHandoff | null => {
  const blocker = task.blockerHandoff;
  if (task.qaOf || task.status !== 'attention' || !blocker || typeof blocker !== 'object' || !blocker.active) return null;
  return blocker;
};

/** Only an unresolved product blocker turns into a follow-up issue, and only once. */
export const canCreateFollowUp = (task: WorkTask): boolean =>
  devBlocker(task)?.kind === 'product' && !task.followUpIssue;
