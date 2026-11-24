import { ChevronRight } from 'lucide-react';
import { WorkRunButton } from '@/features/work/components/WorkRunButton';
import { WORK_KINDS } from '@/features/work/lib/workKinds';
import { ITEM_STATUS } from '@/features/work/lib/workStatus';
import type { WorkEntry } from '@/features/work/types/WorkEntry';
import { Badge } from '@/shared/components/Badge';
import { cn } from '@/shared/utils/cn';

interface WorkEntryRowProps {
  entry: WorkEntry;
  busy: boolean;
  onOpen: () => void;
  onStart: () => void;
  onPause: () => void;
}

/** One feature, defect or debt: what it is, how far it got, and Start. */
export const WorkEntryRow = ({ entry, busy, onOpen, onStart, onPause }: WorkEntryRowProps) => {
  const kind = WORK_KINDS[entry.kind];
  const tasks = entry.item?.tasks ?? [];
  const done = tasks.filter((task) => task.status === 'done').length;
  const attention = tasks.filter((task) => task.status === 'attention' || task.status === 'review').length;
  const running = tasks.filter((task) => task.status === 'running' || task.status === 'merging').length;
  const status = entry.item ? ITEM_STATUS[entry.item.status]
    : entry.issue?.state === 'closed' ? ITEM_STATUS.done : null;
  const closed = entry.status === 'done';

  return (
    <li className="group flex items-center gap-2 rounded-card border border-line bg-surface py-2 pr-2 pl-2 transition hover:border-line-strong sm:gap-3 sm:pr-3">
      <button type="button" onClick={onOpen}
        className="flex min-h-12 min-w-0 flex-1 items-center gap-3 rounded-control px-1.5 py-1 text-left focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand">
        <span className="flex min-w-0 flex-1 flex-col gap-1">
          <span className={cn('truncate text-[14px] font-semibold tracking-[-0.01em] text-ink', closed && 'text-ink-muted line-through decoration-ink-subtle')}>
            {entry.title}
          </span>
          <span className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-[12px] text-ink-subtle">
            <span>{kind.label}{entry.issue ? ` #${entry.issue.number}` : ''}</span>
            {tasks.length > 0 ? <span>· {done}/{tasks.length} tasks</span> : null}
            {running > 0 ? <span className="text-info">· {running} running</span> : null}
            {attention > 0 ? <span className="font-semibold text-danger">· {attention} need{attention === 1 ? 's' : ''} you</span> : null}
          </span>
        </span>
        {status ? <Badge tone={status.tone} className="hidden sm:inline-flex">{status.label}</Badge> : null}
        <ChevronRight className="size-4 shrink-0 text-ink-subtle transition group-hover:translate-x-0.5 group-hover:text-ink" />
      </button>
      {!closed ? <WorkRunButton item={entry.item} busy={busy} onStart={onStart} onPause={onPause} /> : null}
    </li>
  );
};
