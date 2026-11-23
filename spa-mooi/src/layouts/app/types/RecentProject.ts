import type { Project } from '@/features/projects/types/Project';
import type { Session } from '@/features/sessions/types/Session';
import type { WorkItem } from '@/features/work/types/WorkItem';

export interface RecentFeatureSessions {
  feature: WorkItem;
  sessions: Session[];
}

/** One project of the sidebar's Recent section with its open conversations. */
export interface RecentProject {
  project: Project;
  /** Conversations that do not belong to a feature. */
  sessions: Session[];
  features: RecentFeatureSessions[];
  sessionCount: number;
}
