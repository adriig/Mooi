import { useMemo, useState } from 'react';
import { FolderGit2, Info } from 'lucide-react';
import { useProjects } from '@/features/projects/hooks/useProjects';
import { useSelectedProject } from '@/features/projects/hooks/useSelectedProject';
import { useAgentConnections } from '@/features/agents/hooks/useAgentConnections';
import { createWorkIssue } from '@/features/work/api/workApi';
import { IssueComposer } from '@/features/work/components/IssueComposer';
import { ProjectWorkLoader } from '@/features/work/components/ProjectWorkLoader';
import { TodosBoard } from '@/features/work/components/TodosBoard';
import { WorkProjectFilter } from '@/features/work/components/WorkProjectFilter';
import { useProjectWork } from '@/features/work/hooks/useProjectWork';
import { WORK_KINDS } from '@/features/work/lib/workKinds';
import type { WorkKindFilter } from '@/features/work/types/WorkKindFilter';
import { Button } from '@/shared/components/Button';
import { Card } from '@/shared/components/Card';
import { EmptyState } from '@/shared/components/EmptyState';
import { SegmentedControl } from '@/shared/components/SegmentedControl';
import { useAuthStore } from '@/stores/authStore';
import { useTodoProjectSelectionStore } from '@/stores/todoProjectSelectionStore';

/** Opens a defect or improvement on the chosen project; its work hook adopts the answer live. */
const ProjectIssueComposer = ({ projects, projectId, onProjectChange }: {
  projects: Parameters<typeof IssueComposer>[0]['projects'];
  projectId: string;
  onProjectChange: (projectId: string) => void;
}) => {
  const { run, busy, actionError } = useProjectWork(projectId, false);
  return (
    <div className="flex flex-col gap-2">
      <IssueComposer projectId={projectId} projects={projects} onProjectChange={onProjectChange} disabled={busy}
        onCreate={(draft) => run(() => createWorkIssue(projectId, draft))} />
      {actionError ? <p role="alert" className="rounded-control bg-danger-soft px-3 py-2 text-sm text-danger">{actionError}</p> : null}
    </div>
  );
};

/**
 * Every selected project's features, defects and improvements as one Kanban, from defined to deployed, so the
 * player follows and starts work across the workspace without switching projects.
 */
export const TodosPage = () => {
  const { projects, status, error, reload } = useProjects();
  const { projectId } = useSelectedProject();
  const { connections } = useAgentConnections();
  const playerId = useAuthStore((state) => state.player?.id);
  const selection = useTodoProjectSelectionStore((state) => playerId ? state.selections[playerId] : undefined);
  const select = useTodoProjectSelectionStore((state) => state.select);
  const [kind, setKind] = useState<WorkKindFilter>('all');
  const [composerProject, setComposerProject] = useState<string | null>(null);

  const selectedIds = useMemo(() => new Set(projectId ? [projectId] : selection ?? projects.map((project) => project.id)), [projectId, selection, projects]);
  const visibleProjects = useMemo(() => projects.filter((project) => selectedIds.has(project.id)), [projects, selectedIds]);
  const composerId = visibleProjects.find((project) => project.id === composerProject)?.id ?? visibleProjects[0]?.id;

  return (
    <div className="mx-auto flex w-full max-w-[1600px] flex-col gap-6 px-5 pt-7 pb-[calc(1.75rem+var(--safe-bottom))] sm:px-8 lg:px-10 lg:pt-10 lg:pb-10">
      <header>
        <p className="mb-2 font-mono text-[10.5px] font-medium tracking-[0.12em] text-ink-subtle uppercase">Workspace</p>
        <h1 className="text-[30px] leading-[1.1] font-semibold tracking-[-0.04em] text-ink sm:text-[34px]">To-dos</h1>
        <p className="mt-1.5 max-w-2xl text-sm leading-relaxed text-ink-muted">
          {projectId ? 'Features, defects and improvements of this project, from defined to deployed in production.' : 'Features, defects and improvements of every project, from defined to deployed in production.'}
        </p>
      </header>

      {error ? (
        <div role="alert" className="flex items-center justify-between gap-3 rounded-xl bg-danger-soft px-4 py-3 text-sm text-danger">
          <span>{error}</span><Button variant="secondary" size="sm" onClick={reload}>Retry</Button>
        </div>
      ) : null}

      {projects.length > 0 && !projectId ? (
        <div className="flex flex-col gap-2 lg:flex-row lg:items-center">
          <WorkProjectFilter projects={projects} selectedIds={selectedIds}
            onChange={(ids) => { if (playerId) select(playerId, ids); }} />
          <SegmentedControl value={kind} onChange={(id) => setKind(id as WorkKindFilter)} className="w-full lg:ml-auto lg:w-auto"
            items={[{ id: 'all', label: 'All' }, ...(['feature', 'defect', 'debt'] as const).map((id) => (
              { id, label: WORK_KINDS[id].plural }))]} />
        </div>
      ) : null}

      {(status === 'loading' || status === 'idle') && projects.length === 0 ? (
        <div className="flex flex-col gap-3">{[0, 1, 2].map((item) => <Card key={item} className="h-16 animate-pulse-soft" />)}</div>
      ) : projects.length === 0 ? (
        !error && <EmptyState icon={FolderGit2} title="No projects yet" description="Add a project to your workspace to plan its work here." />
      ) : visibleProjects.length === 0 || !composerId ? (
        <EmptyState icon={FolderGit2} title="No projects selected" description="Choose projects from the Projects dropdown to see their to-dos." />
      ) : (
        <>
          <ProjectIssueComposer projects={visibleProjects} projectId={composerId} onProjectChange={setComposerProject} />
          <p className="flex items-start gap-2 text-[12.5px] leading-relaxed text-ink-subtle">
            <Info className="mt-0.5 size-3.5 shrink-0" />
            Features are designed in a session: describe one to its agent and it breaks it into tasks here, ready to Start.
          </p>
          <div className="flex flex-col gap-2 empty:hidden">
            {visibleProjects.map((project) => <ProjectWorkLoader key={project.id} projectId={project.id} projectName={project.name} />)}
          </div>
          <TodosBoard projects={visibleProjects} kind={kind} connections={connections} />
        </>
      )}
    </div>
  );
};
