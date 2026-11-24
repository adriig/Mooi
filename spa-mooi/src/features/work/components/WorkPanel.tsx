import { ListTodo, RefreshCw } from 'lucide-react';
import type { AgentConnection } from '@/features/agents/types/AgentConnection';
import { WorkList } from '@/features/work/components/WorkList';
import { useProjectWork } from '@/features/work/hooks/useProjectWork';
import { Card } from '@/shared/components/Card';
import { EmptyState } from '@/shared/components/EmptyState';
import { Eyebrow } from '@/shared/components/Eyebrow';

interface WorkPanelProps {
  projectId: string;
  connections: AgentConnection[];
}

/**
 * One project's features, defects and debt within the workspace To-dos page. Changes made anywhere else (another
 * device, an agent, the runner) show up live. Mounted only while its tab is open.
 */
export const WorkPanel = ({ projectId, connections }: WorkPanelProps) => {
  const work = useProjectWork(projectId);

  if (!work.items) {
    if (work.loadError) return <EmptyState icon={ListTodo} title="Work unavailable" description={work.loadError} />;
    return (
      <Card className="flex flex-col gap-3 p-5">
        <div className="h-5 w-40 animate-pulse-soft rounded bg-surface-2" />
        <div className="h-11 animate-pulse-soft rounded-control bg-surface-2" />
        {[0, 1, 2].map((index) => <div key={index} className="h-14 animate-pulse-soft rounded-card bg-surface-2" />)}
      </Card>
    );
  }

  return (
    <section className="flex flex-col gap-4">
      <div className="flex items-center gap-3 border-b border-line pb-3">
        <div className="min-w-0 flex-1">
          <Eyebrow>Software factory</Eyebrow>
          <p className="mt-1 text-[12.5px] text-ink-subtle">Features live in Mooi; defects and debt are the repository's GitHub issues.</p>
        </div>
        <button type="button" onClick={work.reload} aria-label="Reload" title="Reload"
          className="inline-flex size-8 items-center justify-center rounded-control border border-line text-ink-muted transition hover:bg-surface-2 hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand">
          <RefreshCw className="size-3.5" />
        </button>
      </div>
      <WorkList projectId={projectId} work={work} connections={connections} />
    </section>
  );
};
