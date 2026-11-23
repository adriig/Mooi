import { ArrowUpRight, FolderGit2, Network, RefreshCw } from 'lucide-react';
import { Link } from 'react-router-dom';
import { projectPath } from '@/app/paths';
import { WorkspaceCard } from '@/features/sessions/components/workspaces/WorkspaceCard';
import { WorkspaceStats } from '@/features/sessions/components/workspaces/WorkspaceStats';
import type { WorkspaceOverview } from '@/features/sessions/types/WorkspaceOverview';
import type { WorkItem } from '@/features/work/types/WorkItem';
import { Card } from '@/shared/components/Card';
import { CopyButton } from '@/shared/components/CopyButton';
import { EmptyState } from '@/shared/components/EmptyState';
import { Eyebrow } from '@/shared/components/Eyebrow';
import { Tooltip } from '@/shared/components/Tooltip';
import { cn } from '@/shared/utils/cn';
import { shortenPath } from '@/shared/utils/shortenPath';

interface WorkspacesOverviewProps {
  projectId: string;
  /** False for projects that are not web applications, which have no preview concept at all. */
  previewsEnabled: boolean;
  overview: WorkspaceOverview | null;
  loading: boolean;
  error: string | null;
  onReload: () => void;
  pendingFeatures: WorkItem[] | null;
  featuresLoading: boolean;
  featuresError: string | null;
  onReloadFeatures: () => void;
}

/** What sits under a project on the sessions pod: one isolated clone per live session. */
export const WorkspacesOverview = ({
  projectId,
  previewsEnabled,
  overview,
  loading,
  error,
  onReload,
  pendingFeatures,
  featuresLoading,
  featuresError,
  onReloadFeatures,
}: WorkspacesOverviewProps) => {
  if (!overview) {
    return (
      <div className="flex flex-col gap-4">
        <PendingFeatures
          projectId={projectId}
          features={pendingFeatures}
          loading={featuresLoading}
          error={featuresError}
          onReload={onReloadFeatures}
        />
        {error ? (
          <EmptyState icon={FolderGit2} title="Workspaces unavailable" description={error} />
        ) : (
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            {[0, 1, 2, 3].map((index) => (
              <Card key={index} className="h-[86px] animate-pulse-soft" />
            ))}
          </div>
        )}
      </div>
    );
  }

  return (
    <section className="flex flex-col gap-4">
      <PendingFeatures
        projectId={projectId}
        features={pendingFeatures}
        loading={featuresLoading}
        error={featuresError}
        onReload={onReloadFeatures}
      />
      <div className="flex flex-col gap-3 border-b border-line pb-3 sm:flex-row sm:items-center">
        <div className="min-w-0 flex-1">
          <Eyebrow>Clones on disk</Eyebrow>
          <p className="mt-1 flex min-w-0 items-center gap-1 font-mono text-[11px] text-ink-subtle">
            <Tooltip content={overview.root} focusable>
              <span className="truncate">{shortenPath(overview.root)}</span>
            </Tooltip>
            <CopyButton value={overview.root} label="Copy workspace root" className="size-7" />
          </p>
        </div>
        <button
          type="button"
          onClick={onReload}
          disabled={loading}
          className="inline-flex items-center gap-1.5 self-start rounded-control border border-line px-3 py-1.5 text-xs font-bold text-ink-muted transition hover:bg-surface-2 hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand disabled:opacity-60 sm:self-auto"
        >
          <RefreshCw className={cn('size-3.5', loading && 'animate-spin')} />
          Refresh
        </button>
      </div>

      {error ? <p className="text-[12px] font-bold text-danger">{error}</p> : null}

      <WorkspaceStats overview={overview} previewsEnabled={previewsEnabled} />

      {overview.workspaces.length > 0 ? (
        <ul className="grid gap-3 lg:grid-cols-2">
          {overview.workspaces.map((workspace) => (
            <WorkspaceCard
              key={workspace.sessionId}
              projectId={projectId}
              workspace={workspace}
              previewsEnabled={previewsEnabled}
            />
          ))}
        </ul>
      ) : (
        <EmptyState
          icon={FolderGit2}
          title="No clones yet"
          description="Every session gets its own isolated clone of this repository. Start one to see it here."
        />
      )}
    </section>
  );
};

interface PendingFeaturesProps {
  projectId: string;
  features: WorkItem[] | null;
  loading: boolean;
  error: string | null;
  onReload: () => void;
}

/** Features stay a single unit of work here; task progress is aggregated from the live work item. */
const PendingFeatures = ({ projectId, features, loading, error, onReload }: PendingFeaturesProps) => (
  <section aria-labelledby="pending-features-heading" className="flex flex-col gap-3">
    <div className="flex items-end justify-between gap-3 border-b border-line pb-3">
      <div>
        <Eyebrow>Software factory</Eyebrow>
        <h2 id="pending-features-heading" className="mt-1 text-sm font-semibold text-ink">Pending features</h2>
      </div>
      {error ? (
        <button
          type="button"
          onClick={onReload}
          className="inline-flex shrink-0 items-center gap-1.5 rounded-control px-2 py-1 text-xs font-semibold text-ink-muted transition hover:bg-surface-2 hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand"
        >
          <RefreshCw className="size-3.5" />
          Retry
        </button>
      ) : null}
    </div>

    {error ? (
      <p role="alert" className="text-[12px] font-bold text-danger">{error}</p>
    ) : loading ? (
      <div className="grid gap-3 sm:grid-cols-2">
        {[0, 1].map((index) => <Card key={index} className="h-[92px] animate-pulse-soft" />)}
      </div>
    ) : features && features.length > 0 ? (
      <ul aria-label="Pending features" className="grid gap-3 sm:grid-cols-2">
        {features.map((feature) => {
          const total = feature.tasks.length;
          const done = feature.tasks.filter((task) => task.status === 'done').length;
          const progress = total > 0 ? Math.round((done / total) * 100) : 0;

          return (
            <li key={feature.id}>
              <Link
                to={`${projectPath(projectId)}?tab=todos&work=${encodeURIComponent(feature.id)}`}
                className="group flex h-full min-w-0 flex-col gap-3 rounded-card border border-line bg-surface px-4 py-3 transition hover:border-brand/40 hover:bg-surface-2/50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand"
              >
                <span className="flex min-w-0 items-start gap-2.5">
                  <Network className="mt-0.5 size-4 shrink-0 text-brand" />
                  <span className="min-w-0 flex-1 truncate text-[13px] font-semibold text-ink">{feature.title}</span>
                  <ArrowUpRight className="size-4 shrink-0 text-ink-subtle transition group-hover:text-brand" />
                </span>
                <span className="flex flex-col gap-1.5">
                  <span className="flex items-center justify-between gap-2 text-[11px] text-ink-muted">
                    <span>{total > 0 ? `${done} of ${total} tasks done` : 'No tasks yet'}</span>
                    {total > 0 ? <span className="font-mono tabular-nums">{progress}%</span> : null}
                  </span>
                  {total > 0 ? (
                    <span
                      role="progressbar"
                      aria-label={`${feature.title} task completion`}
                      aria-valuemin={0}
                      aria-valuemax={total}
                      aria-valuenow={done}
                      className="h-1.5 overflow-hidden rounded-full bg-surface-3"
                    >
                      <span
                        className="block h-full rounded-full bg-success-dot transition-[width] duration-500 ease-out-expo"
                        style={{ width: `${progress}%` }}
                      />
                    </span>
                  ) : null}
                </span>
              </Link>
            </li>
          );
        })}
      </ul>
    ) : (
      <p className="rounded-card border border-dashed border-line px-4 py-5 text-center text-sm text-ink-muted">
        No pending features.
      </p>
    )}
  </section>
);
