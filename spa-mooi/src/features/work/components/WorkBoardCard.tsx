import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { sessionPath } from '@/app/paths';
import { useAgentConnections } from '@/features/agents/hooks/useAgentConnections';
import { createSession } from '@/features/sessions/api/sessionsApi';
import { useAgentModelChoice } from '@/features/sessions/hooks/useAgentModelChoice';
import { useSessionsStore } from '@/stores/sessionsStore';
import { backLinkState } from '@/shared/utils/backLinkState';
import { ensureIssueItem, pauseWorkItem, startWorkItem } from '@/features/work/api/workApi';
import { WorkCard } from '@/features/work/components/WorkCard';
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
  const { connections } = useAgentConnections();
  const choice = useAgentModelChoice(entry.column === 'new', connections);
  const [defining, setDefining] = useState(false);
  const [defineError, setDefineError] = useState<string | null>(null);
  const start = async () => {
    const item = entry.item ?? (entry.issue ? await run(() => ensureIssueItem(project.id, entry.issue!.number)) : null);
    if (item) await run(() => startWorkItem(project.id, item.id));
  };
  const define = async () => {
    if (!entry.issue || !choice.ready || defining) return;
    setDefining(true);
    setDefineError(null);
    try {
      const session = await createSession({
        projectId: project.id,
        provider: choice.provider,
        adapter: choice.adapter || undefined,
        connectionId: choice.connectionId,
        model: choice.model,
        effort: choice.effort || null,
        initialMessage: `Define issue #${entry.issue.number} (${entry.kind}): ${entry.title}\n\nIssue: ${entry.issue.htmlUrl ?? ''}\n${entry.issue.body}\n\nRead the repository without changing any files. Determine whether this report is valid, clarify scope and verifiable acceptance criteria, decide whether it is a defect or improvement, and recommend whether it should be split into issues or raised to a feature. Do not edit GitHub issues or create work items, tasks, or features. Present a reviewable proposal; persist changes only after the user accepts through the software factory API.`,
      });
      useSessionsStore.getState().upsertSession(session);
      navigate(sessionPath(session.projectId, session.id), { state: backLinkState({ to: '/todos', label: 'To-dos' }) });
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
      onDefine={() => { void define(); }}
      onPause={() => { if (entry.item) void run(() => pauseWorkItem(project.id, entry.item!.id)); }} />
    {defineError ? <div role="alert" className="mt-1 flex items-center justify-between gap-2 rounded-control bg-danger-soft px-2 py-1.5 text-xs text-danger">
      <span>{defineError}</span><button type="button" className="font-semibold underline" onClick={() => { void define(); }}>Retry</button>
    </div> : null}
    {entry.column === 'new' && !choice.ready && !defining ? <p className="mt-1 px-2 text-xs text-ink-subtle">Link an agent account to define this item.</p> : null}
    </div>
  );
};
