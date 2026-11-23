import type { WorkAssignment } from '@/features/work/types/WorkAssignment';
import type { WorkTaskCategory } from '@/features/work/types/WorkTaskCategory';

/** Fields left out stay as they are; `dependsOn` replaces the whole list. */
export interface WorkTaskUpdate {
  title?: string;
  category?: WorkTaskCategory;
  description?: string;
  acceptance?: string;
  assignment?: WorkAssignment;
  alternatives?: WorkAssignment[];
  autoMerge?: boolean;
  tests?: boolean;
  dependsOn?: string[];
}
