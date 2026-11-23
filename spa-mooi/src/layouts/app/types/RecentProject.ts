import type { Project } from '@/features/projects/types/Project';
import type { Session } from '@/features/sessions/types/Session';
import type { WorkItem } from '@/features/work/types/WorkItem';
import type { WorkTask } from '@/features/work/types/WorkTask';

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
  /** Task metadata lets session icons reflect their work category rather than their provider. */
  taskBySessionId: Record<string, WorkTask>;
  sessionCount: number;
}
