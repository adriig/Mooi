import { CHANGE_STATUS, openChangeCounts } from '@/features/work/lib/workStatus';
import type { WorkItem } from '@/features/work/types/WorkItem';
import { Badge } from '@/shared/components/Badge';

/** Open change requests of an item by status, apart from its task-execution state. */
export const WorkChangeBadges = ({ item }: { item: WorkItem | null | undefined }) => (
  <>
    {openChangeCounts(item).map(({ status, count }) => (
      <Badge key={status} tone={CHANGE_STATUS[status].tone}>
        {count} change{count === 1 ? '' : 's'} · {CHANGE_STATUS[status].label.toLowerCase()}
      </Badge>
    ))}
  </>
);
