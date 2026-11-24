import { useId, useMemo } from 'react';
import { Link } from 'react-router-dom';
import { ArrowUpRight, ChevronRight, RefreshCw } from 'lucide-react';
import { projectPath } from '@/app/paths';
import type { AgentConnection } from '@/features/agents/types/AgentConnection';
import type { Project } from '@/features/projects/types/Project';
import { WorkList } from '@/features/work/components/WorkList';
import { WorkProgressBar } from '@/features/work/components/WorkProgressBar';
import { useProjectWork } from '@/features/work/hooks/useProjectWork';
import { countWork, workEntries } from '@/features/work/lib/workEntries';
import { Card } from '@/shared/components/Card';
import { Initials } from '@/shared/components/Initials';
import { useSeenOnce } from '@/shared/hooks/useSeenOnce';
import { cn } from '@/shared/utils/cn';
import { formatCount } from '@/shared/utils/formatCount';

interface ProjectWorkSectionProps {
  project: Project;
  connections: AgentConnection[];
  expanded: boolean;
  onToggle: () => void;
}

const headerAction =
  'inline-flex size-9 shrink-0 items-center justify-center rounded-control text-ink-subtle transition hover:bg-surface-2 hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand disabled:opacity-60';

/**
 * One project on the To-dos page: a header with its progress, expanding into the same list as the
 * project's own tab. Its work is read only once the row nears the screen, so the page opens at once
 * however many projects the workspace holds; changes then stream in live.
 */
export const ProjectWorkSection = ({ project, connections, expanded, onToggle }: ProjectWorkSectionProps) => {
  const { ref, seen } = useSeenOnce<HTMLDivElement>();
  const work = useProjectWork(project.id, seen || expanded);
  const { items, issues, loadError, reload } = work;
  const entries = useMemo(() => workEntries(items ?? [], issues ?? []), [items, issues]);
  const counts = useMemo(() => countWork(entries), [entries]);
  const bodyId = useId();
  const loaded = items !== null && issues !== null;

  const summary = !loaded
    ? (loadError ? { text: loadError, tone: 'text-danger' } : { text: 'Loading…', tone: 'text-ink-subtle' })
    : entries.length === 0 ? { text: 'Nothing planned', tone: 'text-ink-subtle' }
      : {
        text: `${formatCount(counts.done)} of ${formatCount(entries.length)} done${counts.inProgress > 0 ? ` · ${formatCount(counts.inProgress)} in progress` : ''}`,
        tone: 'text-ink-muted',
      };
  const remaining = counts.open + counts.inProgress;

  return (
    <Card as="li" className={cn('overflow-hidden transition-shadow', expanded && 'shadow-raised')}>
      <div ref={ref} className="flex items-center gap-1 py-2 pr-2 pl-2 sm:gap-2 sm:pr-3">
        <button type="button" onClick={onToggle} aria-expanded={expanded} aria-controls={bodyId}
          className="group/head flex min-h-12 min-w-0 flex-1 items-center gap-2.5 rounded-control px-1.5 py-1 text-left transition-colors hover:bg-surface-2/60 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand sm:gap-3">
          <ChevronRight className={cn('size-4 shrink-0 text-ink-subtle transition-transform duration-200 ease-out-expo group-hover/head:text-ink', expanded && 'rotate-90')} />
          <Initials value={project.name} />
          <span className="flex min-w-0 flex-1 flex-col gap-1">
            <span className="truncate text-[14px] font-semibold tracking-[-0.01em] text-ink">{project.name}</span>
            <span className={cn('truncate text-[12px] font-medium', summary.tone)}>{summary.text}</span>
          </span>
          {loaded && entries.length > 0 ? (
            <span className="hidden w-28 shrink-0 flex-col gap-1.5 md:flex">
              <span className="text-right font-mono text-[10.5px] font-medium text-ink-subtle tabular-nums">
                {Math.round((counts.done / entries.length) * 100)}%
              </span>
              <WorkProgressBar counts={counts} />
            </span>
          ) : null}
          {remaining > 0 ? (
            <span title={`${remaining.toLocaleString('en')} left to do`}
              className="shrink-0 rounded-full bg-surface-3 px-2 py-0.5 font-mono text-[11px] font-medium text-ink-muted tabular-nums">
              <span className="sr-only">Left to do: </span>
              {formatCount(remaining)}
            </span>
          ) : null}
        </button>
        <Link to={projectPath(project.id)} aria-label={`Open ${project.name}`} title="Open project" className={headerAction}>
          <ArrowUpRight className="size-4" />
        </Link>
      </div>

      {loaded && entries.length > 0 && !expanded ? <WorkProgressBar counts={counts} className="mx-4 mb-3 md:hidden" /> : null}

      {expanded ? (
        <div id={bodyId} className="flex animate-fade-in flex-col gap-3 border-t border-line px-3 pt-3 pb-4 sm:px-4">
          {!items ? (
            loadError ? (
              <div className="flex items-center justify-between gap-3 text-[13px] text-danger">
                <span>{loadError}</span>
                <button type="button" onClick={reload} className="inline-flex items-center gap-1 font-semibold underline-offset-2 hover:underline">
                  <RefreshCw className="size-3.5" /> Retry
                </button>
              </div>
            ) : (
              <div className="flex flex-col gap-3">
                {[0, 1].map((index) => <div key={index} className="h-14 animate-pulse-soft rounded-card bg-surface-2" />)}
              </div>
            )
          ) : <WorkList projectId={project.id} work={work} connections={connections} />}
        </div>
      ) : null}
    </Card>
  );
};
