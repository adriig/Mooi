import { ensureIssueItem, pauseWorkItem, startWorkItem } from '@/features/work/api/workApi';
import { WorkCard } from '@/features/work/components/WorkCard';
import { useProjectWork } from '@/features/work/hooks/useProjectWork';
import type { WorkEntry } from '@/features/work/types/WorkEntry';
import type { Project } from '@/features/projects/types/Project';

interface WorkBoardCardProps {
  project: Project;
  entry: WorkEntry;
  onOpen: () => void;
}

/** A card wired to its own project's start and pause actions. */
export const WorkBoardCard = ({ project, entry, onOpen }: WorkBoardCardProps) => {
  const { run, busy } = useProjectWork(project.id, false);
  const start = async () => {
    const item = entry.item ?? (entry.issue ? await run(() => ensureIssueItem(project.id, entry.issue!.number)) : null);
    if (item) await run(() => startWorkItem(project.id, item.id));
  };
  return (
    <WorkCard entry={entry} projectName={project.name} repository={project.fullName} busy={busy} onOpen={onOpen}
      onStart={() => { void start(); }}
      onPause={() => { if (entry.item) void run(() => pauseWorkItem(project.id, entry.item!.id)); }} />
  );
};
