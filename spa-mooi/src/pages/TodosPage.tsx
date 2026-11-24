import { useMemo, useState } from 'react';
import { ChevronsDownUp, ChevronsUpDown, FolderGit2 } from 'lucide-react';
import { useProjects } from '@/features/projects/hooks/useProjects';
import { useAgentConnections } from '@/features/agents/hooks/useAgentConnections';
import { ProjectWorkSection } from '@/features/work/components/ProjectWorkSection';
import { WorkProjectFilter } from '@/features/work/components/WorkProjectFilter';
import { expandedWorkProjects } from '@/features/work/lib/expandedWorkProjects';
import { countWork, workEntries } from '@/features/work/lib/workEntries';
import { Button } from '@/shared/components/Button';
import { Card } from '@/shared/components/Card';
import { EmptyState } from '@/shared/components/EmptyState';
import { formatCount } from '@/shared/utils/formatCount';
import { useAuthStore } from '@/stores/authStore';
import { useLiveStore } from '@/stores/liveStore';
import { useTodoProjectSelectionStore } from '@/stores/todoProjectSelectionStore';

const Stat = ({ label, value }: { label: string; value: number }) => (
  <div>
    <dt className="font-mono text-[10.5px] font-medium tracking-[0.12em] text-ink-subtle uppercase">{label}</dt>
    <dd className="text-xl font-extrabold text-ink tabular-nums" title={value >= 1000 ? value.toLocaleString('en') : undefined}>
      {formatCount(value)}
    </dd>
  </div>
);

/**
 * Every project's features, defects and debt on one page, so the player follows and starts work
 * across the workspace without switching projects.
 */
export const TodosPage = () => {
  const { projects, status, error, reload } = useProjects();
  const work = useLiveStore((live) => live.work);
  const { connections } = useAgentConnections();
  const playerId = useAuthStore((state) => state.player?.id);
  const selection = useTodoProjectSelectionStore((state) => playerId ? state.selections[playerId] : undefined);
  const select = useTodoProjectSelectionStore((state) => state.select);
  const [expanded, setExpanded] = useState(() => new Set(expandedWorkProjects.read()));

  const selectedIds = useMemo(() => new Set(selection ?? projects.map((project) => project.id)), [selection, projects]);
  const visibleProjects = useMemo(() => projects.filter((project) => selectedIds.has(project.id)), [projects, selectedIds]);

  // Projects read so far: each one is read once its row nears the screen.
  const totals = useMemo(() => countWork(visibleProjects.flatMap((project) => {
    const current = work[project.id];
    return current?.items && current.issues ? workEntries(current.items, current.issues) : [];
  })), [visibleProjects, work]);

  const remember = (next: Set<string>) => {
    setExpanded(next);
    expandedWorkProjects.write([...next]);
  };
  const toggle = (projectId: string) => {
    const next = new Set(expanded);
    if (!next.delete(projectId)) next.add(projectId);
    remember(next);
  };
  const allExpanded = visibleProjects.length > 0 && visibleProjects.every((project) => expanded.has(project.id));
  const toggleAll = () => {
    const next = new Set(expanded);
    visibleProjects.forEach((project) => (allExpanded ? next.delete(project.id) : next.add(project.id)));
    remember(next);
  };

  return (
    <div className="mx-auto flex w-full max-w-5xl flex-col gap-6 px-5 pt-7 pb-[calc(1.75rem+var(--safe-bottom))] sm:px-8 lg:px-10 lg:pt-10 lg:pb-10">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="mb-2 font-mono text-[10.5px] font-medium tracking-[0.12em] text-ink-subtle uppercase">Workspace</p>
          <h1 className="text-[30px] leading-[1.1] font-semibold tracking-[-0.04em] text-ink sm:text-[34px]">To-dos</h1>
          <p className="mt-1.5 max-w-2xl text-sm leading-relaxed text-ink-muted">
            Features, defects and debt of every project. Expand a project to open its work and start it.
          </p>
        </div>
        <dl className="flex gap-6">
          <Stat label="In progress" value={totals.inProgress} />
          <Stat label="To do" value={totals.open} />
          <Stat label="Done" value={totals.done} />
        </dl>
      </header>

      {error ? (
        <div role="alert" className="flex items-center justify-between gap-3 rounded-xl bg-danger-soft px-4 py-3 text-sm text-danger">
          <span>{error}</span><Button variant="secondary" size="sm" onClick={reload}>Retry</Button>
        </div>
      ) : null}

      {projects.length > 0 ? (
        <div className="flex flex-wrap items-center gap-2">
          <WorkProjectFilter projects={projects} selectedIds={selectedIds}
            onChange={(ids) => { if (playerId) select(playerId, ids); }} />
          <Button variant="ghost" size="sm" onClick={toggleAll} disabled={visibleProjects.length === 0} className="ml-auto shrink-0">
            {allExpanded ? <ChevronsDownUp className="size-4" /> : <ChevronsUpDown className="size-4" />}
            {allExpanded ? 'Collapse all' : 'Expand all'}
          </Button>
        </div>
      ) : null}

      {(status === 'loading' || status === 'idle') && projects.length === 0 ? (
        <div className="flex flex-col gap-3">{[0, 1, 2].map((item) => <Card key={item} className="h-16 animate-pulse-soft" />)}</div>
      ) : projects.length === 0 ? (
        !error && <EmptyState icon={FolderGit2} title="No projects yet" description="Add a project to your workspace to plan its work here." />
      ) : visibleProjects.length === 0 ? (
        <EmptyState icon={FolderGit2} title="No projects selected" description="Choose projects from the Projects dropdown to see their to-dos." />
      ) : (
        <ul aria-label="Projects" className="flex flex-col gap-3">
          {visibleProjects.map((project) => (
            <ProjectWorkSection key={project.id} project={project} connections={connections}
              expanded={expanded.has(project.id)} onToggle={() => toggle(project.id)} />
          ))}
        </ul>
      )}
    </div>
  );
};
