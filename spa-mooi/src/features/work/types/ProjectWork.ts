import type { ProjectDefinition } from '@/features/work/types/ProjectDefinition';
import type { WorkIssue } from '@/features/work/types/WorkIssue';
import type { WorkItem } from '@/features/work/types/WorkItem';

/** A project's work as last read or announced: null until read. */
export interface ProjectWork {
  items: WorkItem[] | null;
  issues: WorkIssue[] | null;
  /** The platform sees a new project: no features and next to no code. */
  newProject?: boolean;
  /** The project's definition as last read or announced; null until read. */
  definition?: ProjectDefinition | null;
}
