import type { AgentConnection } from '@/features/agents/types/AgentConnection';
import type { Project } from '@/features/projects/types/Project';
import { WorkItemDialog } from '@/features/work/components/WorkItemDialog';
import { useProjectWork } from '@/features/work/hooks/useProjectWork';
import type { WorkEntry } from '@/features/work/types/WorkEntry';

interface WorkEntryDialogProps {
  project: Project;
  entry: WorkEntry;
  connections: AgentConnection[];
  onClose: () => void;
}

/** Opens an entry's dialog with its own project's work actions; reads nothing, the board already did. */
export const WorkEntryDialog = ({ project, entry, connections, onClose }: WorkEntryDialogProps) => {
  const work = useProjectWork(project.id, false);
  const features = (work.items ?? []).filter((item) => item.kind === 'feature');
  return (
    <WorkItemDialog projectId={project.id} repository={project.fullName} entry={entry} features={features}
      connections={connections} work={work} onClose={onClose} />
  );
};
