import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Link, Navigate, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ChartLine, FolderGit2 } from 'lucide-react';
import { ROUTES } from '@/app/routes';
import { analyticsPath, sessionPath } from '@/app/paths';
import { useAgentConnections } from '@/features/agents/hooks/useAgentConnections';
import { AnalyticsPanel } from '@/features/analytics/components/AnalyticsPanel';
import { lastAnalyticsProject } from '@/features/analytics/lib/lastAnalyticsProject';
import { useProjects } from '@/features/projects/hooks/useProjects';
import { NewSessionDialog } from '@/features/sessions/components/NewSessionDialog';
import { useProjectSessions } from '@/features/sessions/hooks/useProjectSessions';
import { Button } from '@/shared/components/Button';
import { Card } from '@/shared/components/Card';
import { EmptyState } from '@/shared/components/EmptyState';
import { buttonStyles } from '@/shared/styles/buttonStyles';

/**
 * Production analytics of the workspace's web applications, one project at a time. The project lives
 * in the path and the last one opened is remembered on the device, so the menu entry lands back on it;
 * the report range stays in the query and survives switching projects.
 */
export const AnalyticsPage = () => {
  const { projectId } = useParams();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { projects, status, error, reload } = useProjects();
  const webProjects = useMemo(() => projects.filter((project) => project.webApplication), [projects]);
  const project = webProjects.find((item) => item.id === projectId);
  const { busy, actionError, create, clearActionError } = useProjectSessions(project?.id);
  const { connections, reload: reloadConnections } = useAgentConnections();
  const [fixRequest, setFixRequest] = useState<string | null>(null);

  useEffect(() => { if (project) lastAnalyticsProject.write(project.id); }, [project]);

  const header = (
    <header className="flex flex-wrap items-end justify-between gap-4">
      <div>
        <p className="mb-2 font-mono text-[10.5px] font-medium tracking-[0.12em] text-ink-subtle uppercase">Workspace</p>
        <h1 className="text-[30px] leading-[1.1] font-semibold tracking-[-0.04em] text-ink sm:text-[34px]">Analytics</h1>
        <p className="mt-1.5 max-w-2xl text-sm leading-relaxed text-ink-muted">
          Traffic, clicks and search positioning of your web applications in production.
        </p>
      </div>
      {project ? (
        <label className="flex w-full min-w-0 items-center gap-2 sm:w-auto">
          <span className="sr-only">Project</span>
          <select value={project.id}
            onChange={(event) => navigate({ pathname: analyticsPath(event.target.value), search: searchParams.toString() })}
            className="h-10 w-full min-w-0 rounded-control border border-line bg-surface px-3 text-sm font-semibold text-ink outline-none focus:border-brand sm:w-72">
            {webProjects.map((item) => <option key={item.id} value={item.id}>{item.fullName}</option>)}
          </select>
        </label>
      ) : null}
    </header>
  );

  const shell = (content: ReactNode) => (
    <div className="mx-auto flex w-full max-w-6xl flex-col gap-6 px-5 pt-7 pb-[calc(1.75rem+var(--safe-bottom))] sm:px-8 lg:px-10 lg:pt-10 lg:pb-10">
      {header}
      {content}
    </div>
  );

  if (!project) {
    if ((status === 'loading' || status === 'idle') && projects.length === 0) {
      return shell(<Card className="h-44 animate-pulse-soft" />);
    }
    if (error && projects.length === 0) {
      return shell(
        <div role="alert" className="flex items-center justify-between gap-3 rounded-xl bg-danger-soft px-4 py-3 text-sm text-danger">
          <span>{error}</span><Button variant="secondary" size="sm" onClick={reload}>Retry</Button>
        </div>,
      );
    }
    if (webProjects.length === 0) {
      return shell(
        <EmptyState icon={ChartLine} title="No web applications yet"
          description="Analytics measures production web applications. Mark a project as a web application to see its traffic here.">
          <Link to={ROUTES.projects} className={buttonStyles('secondary', 'md')}>
            <FolderGit2 className="size-4" />
            Go to projects
          </Link>
        </EmptyState>,
      );
    }
    // No project chosen, or one that is gone or no longer a web app: open the remembered one or the first.
    const remembered = lastAnalyticsProject.read();
    const fallback = webProjects.find((item) => item.id === remembered) ?? webProjects[0];
    return <Navigate to={{ pathname: analyticsPath(fallback.id), search: searchParams.toString() }} replace />;
  }

  return shell(
    <>
      <AnalyticsPanel key={project.id} projectId={project.id} onFix={setFixRequest} />
      <NewSessionDialog
        open={fixRequest !== null}
        project={project}
        connections={connections}
        busy={busy}
        actionError={actionError}
        onRefreshConnections={reloadConnections}
        onClose={() => { setFixRequest(null); clearActionError(); }}
        onCreate={(request) => create(request).then((session) => {
          if (!session) return false;
          navigate(sessionPath(project.id, session.id));
          return true;
        })}
        initialMessage={fixRequest ?? undefined}
      />
    </>,
  );
};
