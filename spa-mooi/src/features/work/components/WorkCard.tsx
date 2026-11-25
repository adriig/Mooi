import { AlertTriangle, Network } from 'lucide-react';
import { CommitLink } from '@/features/work/components/CommitLink';
import { WorkRunButton } from '@/features/work/components/WorkRunButton';
import { Button } from '@/shared/components/Button';
import { entryDeployment } from '@/features/work/lib/workEntries';
import { mergeCommits } from '@/features/work/lib/mergeCommits';
import { WORK_KINDS } from '@/features/work/lib/workKinds';
import type { WorkEntry } from '@/features/work/types/WorkEntry';
import { Badge } from '@/shared/components/Badge';
import { Initials } from '@/shared/components/Initials';
import { toneStyles } from '@/shared/styles/toneStyles';
import { cn } from '@/shared/utils/cn';
import { formatRelativeTime } from '@/shared/utils/formatRelativeTime';

interface WorkCardProps {
  entry: WorkEntry;
  projectName: string;
  /** `owner/name`, for the commit links. */
  repository: string;
  busy: boolean;
  onOpen: () => void;
  onStart: () => void;
  onPause: () => void;
  onDefine?: () => void;
  onViewBoard?: () => void;
}

/** One feature, defect or debt on the board: what it is, how far it got, where it stands and its run control. */
export const WorkCard = ({ entry, projectName, repository, busy, onOpen, onStart, onPause, onDefine, onViewBoard }: WorkCardProps) => {
  const kind = WORK_KINDS[entry.kind];
  const Icon = kind.icon;
  const tasks = entry.item?.tasks ?? [];
  const done = tasks.filter((task) => task.status === 'done').length;
  const attention = tasks.filter((task) => task.status === 'attention' || task.status === 'review').length;
  const running = tasks.filter((task) => task.status === 'running' || task.status === 'merging').length;
  const commits = entry.column === 'pendingDeploy' || entry.column === 'deployed' ? mergeCommits(entry.item) : [];
  const deployment = entry.column === 'deployed' ? entryDeployment(entry) : null;
  const showRun = entry.column === 'defined' || entry.column === 'inProgress';

  return (
    <li className="flex min-w-0 flex-col gap-2 rounded-card border border-line bg-surface p-2 transition hover:border-line-strong">
      <button type="button" onClick={onOpen}
        className="flex min-w-0 flex-col gap-2 rounded-control px-1.5 py-1 text-left focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand">
        <span className="flex min-w-0 items-start gap-2">
          <span title={kind.label} className={cn('mt-px inline-flex size-6 shrink-0 items-center justify-center rounded-chip border', toneStyles(kind.tone))}>
            <Icon className="size-3.5" />
          </span>
          <span className="line-clamp-3 min-w-0 flex-1 text-[14px] leading-snug font-semibold tracking-[-0.01em] text-ink">{entry.title}</span>
        </span>
        <span className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-[12px] text-ink-subtle">
          <span className="flex min-w-0 max-w-full items-center gap-1.5">
            <Initials value={projectName} size="sm" />
            <span className="truncate font-medium text-ink-muted">{projectName}</span>
          </span>
          {entry.issue ? <span>#{entry.issue.number}</span> : null}
          {tasks.length > 0 ? <span>{done}/{tasks.length} tasks</span> : null}
          {running > 0 ? <span className="text-info">{running} running</span> : null}
          {attention > 0 ? <span className="font-semibold text-danger">{attention} need{attention === 1 ? 's' : ''} you</span> : null}
        </span>
      </button>

      {entry.item?.status === 'paused' ? (
        <div className="flex flex-col gap-1 px-1.5">
          <span><Badge tone="warning">Paused</Badge></span>
          {entry.item.pauseReason ? <span className="text-[12px] text-warning">{entry.item.pauseReason}</span> : null}
        </div>
      ) : null}

      {deployment ? (
        <div className="flex flex-wrap items-center gap-1.5 px-1.5">
          {deployment.state === 'deployed' && deployment.version
            ? <Badge tone="success">{deployment.version}</Badge>
            : <Badge tone="neutral">Unknown version</Badge>}
          {deployment.deployedAt ? (
            <span className="text-[12px] text-ink-subtle">{formatRelativeTime(deployment.deployedAt)}</span>
          ) : null}
          {deployment.state === 'deployed' && deployment.inCurrentRelease === false ? (
            <Badge tone="warning" icon={AlertTriangle}>Not in current release</Badge>
          ) : null}
        </div>
      ) : null}

      {commits.length > 0 ? (
        <div className="flex flex-wrap items-center gap-1.5 px-1.5">
          {commits.map((sha) => <CommitLink key={sha} repository={repository} sha={sha} />)}
        </div>
      ) : null}

      {showRun ? (
        <div className="flex justify-end gap-2 px-1">
          {onViewBoard && entry.kind === 'feature' && entry.item?.status === 'planned' ? (
            <Button size="sm" variant="secondary" disabled={busy} onClick={onViewBoard}>
              <Network className="size-4" /> View board
            </Button>
          ) : null}
          <WorkRunButton item={entry.item} busy={busy} onStart={onStart} onPause={onPause} />
        </div>
      ) : null}
      {entry.column === 'new' ? (
        <div className="flex justify-end px-1">
          <Button size="sm" variant="brand" disabled={busy} onClick={onDefine}>Define</Button>
        </div>
      ) : null}
    </li>
  );
};
