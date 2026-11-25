import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { sessionPath } from '@/app/paths';
import { useSessionsStore } from '@/stores/sessionsStore';
import { backLinkState } from '@/shared/utils/backLinkState';
import { defineWorkIssue, ensureIssueItem, pauseWorkItem, startWorkItem } from '@/features/work/api/workApi';
import { WorkCard } from '@/features/work/components/WorkCard';
import { IssueDefinitionProposalDialog } from '@/features/work/components/IssueDefinitionProposalDialog';
import { useIssueDefinition } from '@/features/work/hooks/useIssueDefinition';
import { useProjectWork } from '@/features/work/hooks/useProjectWork';
import type { WorkEntry } from '@/features/work/types/WorkEntry';
import type { Project } from '@/features/projects/types/Project';

interface WorkBoardCardProps {
  project: Project;
  entry: WorkEntry;
  onOpen: () => void;
  onViewBoard?: () => void;
}

/** A card wired to its own project's start and pause actions. */
export const WorkBoardCard = ({ project, entry, onOpen, onViewBoard }: WorkBoardCardProps) => {
  const { run, busy } = useProjectWork(project.id, false);
  const navigate = useNavigate();
  const [defining, setDefining] = useState(false);
  const [defineError, setDefineError] = useState<string | null>(null);
  const [reviewing, setReviewing] = useState(false);
  const definition = useIssueDefinition(project.id, entry.issue?.number ?? 0, reviewing && Boolean(entry.issue));
  const start = async () => {
    const item = entry.item ?? (entry.issue ? await run(() => ensureIssueItem(project.id, entry.issue!.number)) : null);
    if (item) await run(() => startWorkItem(project.id, item.id));
  };
  const define = async () => {
    if (!entry.issue || defining) return;
    setDefining(true);
    setDefineError(null);
    try {
      const session = await defineWorkIssue(project.id, entry.issue.number);
      useSessionsStore.getState().upsertSession(session);
      navigate(sessionPath(session.projectId, session.id), { state: backLinkState({ to: '/todos', label: 'Planification' }) });
    } catch (error) {
      setDefineError((error as Error).message || 'Could not create the definition session.');
    } finally {
      setDefining(false);
    }
  };
  return (
    <div>
    <WorkCard entry={entry} projectName={project.name} repository={project.fullName} busy={busy || defining} onOpen={onOpen} onViewBoard={onViewBoard}
      onStart={() => { void start(); }}
      onDefine={() => { void define(); }} onReviewProposal={() => setReviewing(true)}
      onPause={() => { if (entry.item) void run(() => pauseWorkItem(project.id, entry.item!.id)); }} />
    {reviewing && entry.issue ? <IssueDefinitionProposalDialog issueNumber={entry.issue.number} definition={definition.definition}
      busy={definition.busy || defining} error={definition.error} onAccept={(proposalId) => { void definition.accept(proposalId); }}
      onReopen={(proposalId) => { void definition.reopen(proposalId); }} onDefine={() => { setReviewing(false); void define(); }}
      onClose={() => { setReviewing(false); definition.clearError(); }} /> : null}
    {defineError ? <div role="alert" className="mt-1 flex items-center justify-between gap-2 rounded-control bg-danger-soft px-2 py-1.5 text-xs text-danger">
      <span>{defineError}</span><button type="button" className="font-semibold underline" onClick={() => { void define(); }}>Retry</button>
    </div> : null}
    </div>
  );
};
