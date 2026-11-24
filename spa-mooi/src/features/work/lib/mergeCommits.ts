import type { WorkItem } from '@/features/work/types/WorkItem';

/** The distinct merge commits of an item's development and fix tasks. */
export const mergeCommits = (item: WorkItem | null): string[] =>
  [...new Set((item?.tasks ?? []).flatMap((task) => task.mergeSha ? [task.mergeSha] : []))];
