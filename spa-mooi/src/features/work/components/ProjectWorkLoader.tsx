import { RefreshCw } from 'lucide-react';
import { useProjectWork } from '@/features/work/hooks/useProjectWork';

interface ProjectWorkLoaderProps {
  projectId: string;
  projectName: string;
}

/**
 * Reads one selected project's work into the live store, which keeps it current afterwards, and shows
 * that project's load or issue errors compactly so the others stay visible.
 */
export const ProjectWorkLoader = ({ projectId, projectName }: ProjectWorkLoaderProps) => {
  const { loadError, issuesError, reload } = useProjectWork(projectId, true);
  if (!loadError && !issuesError) return null;
  return (
    <p role={loadError ? 'alert' : 'status'}
      className={`flex items-center justify-between gap-3 rounded-control px-3 py-2 text-[13px] ${loadError ? 'bg-danger-soft text-danger' : 'border border-warning/30 bg-warning-soft text-ink'}`}>
      <span className="min-w-0">
        <span className="font-semibold">{projectName}: </span>
        {loadError ?? `defects and improvements unavailable: ${issuesError}`}
      </span>
      {loadError ? (
        <button type="button" onClick={reload} className="inline-flex shrink-0 items-center gap-1 font-semibold underline-offset-2 hover:underline">
          <RefreshCw className="size-3.5" /> Retry
        </button>
      ) : null}
    </p>
  );
};
