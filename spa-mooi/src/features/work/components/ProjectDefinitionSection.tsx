import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { FileCheck2, Landmark } from 'lucide-react';
import { sessionPath } from '@/app/paths';
import type { AgentConnection } from '@/features/agents/types/AgentConnection';
import type { Project } from '@/features/projects/types/Project';
import { NewSessionDialog } from '@/features/sessions/components/NewSessionDialog';
import { useProjectSessions } from '@/features/sessions/hooks/useProjectSessions';
import { DefinitionProposalDialog } from '@/features/work/components/DefinitionProposalDialog';
import { DefinitionRoadmapPanel } from '@/features/work/components/DefinitionRoadmapPanel';
import { useProjectDefinition } from '@/features/work/hooks/useProjectDefinition';
import { defineProjectMessage, designNextMessage } from '@/features/work/lib/definitionPrompts';
import { rememberDefinitionSession, rememberedDefinitionSession } from '@/features/work/lib/definitionSession';
import { roadmapState } from '@/features/work/components/DefinitionRoadmapPanel';
import { Badge } from '@/shared/components/Badge';
import { Button } from '@/shared/components/Button';
import { EmptyState } from '@/shared/components/EmptyState';
import { Initials } from '@/shared/components/Initials';
import { useLiveStore } from '@/stores/liveStore';
import { useSessionsStore } from '@/stores/sessionsStore';

interface ProjectDefinitionSectionProps {
  project: Project;
  connections: AgentConnection[];
  reloadConnections: () => Promise<void>;
  /** Shown even without a definition (a single project in view, or a new one). */
  alwaysVisible: boolean;
}

type Purpose = 'define' | 'design';

/**
 * A project's definition on Planification: the empty state of a new project, the Define project action
 * of an existing one, the proposal to review and the live roadmap with Design next.
 */
export const ProjectDefinitionSection = ({ project, connections, reloadConnections, alwaysVisible }: ProjectDefinitionSectionProps) => {
  const controls = useProjectDefinition(project.id);
  const { definition } = controls;
  const work = useLiveStore((live) => live.work[project.id]);
  const items = work?.items ?? null;
  const navigate = useNavigate();
  const { busy, actionError, create, clearActionError } = useProjectSessions(project.id);
  const sessions = useSessionsStore((state) => state.sessions);
  const [creating, setCreating] = useState<{ purpose: Purpose; message: string } | null>(null);
  const [reviewing, setReviewing] = useState(false);

  const isNew = work?.newProject === true && items !== null && !items.some((item) => item.kind === 'feature');
  const hasRoadmap = (definition?.roadmap.length ?? 0) > 0;
  const hasProposal = definition?.proposal != null;
  if (!alwaysVisible && !isNew && !hasRoadmap && !hasProposal) return null;

  const existing = (purpose: Purpose): string | null => {
    const id = rememberedDefinitionSession(project.id, purpose);
    return id && sessions.some((session) => session.id === id) ? id : null;
  };
  const open = (purpose: Purpose, message: string) => {
    const reopened = existing(purpose);
    if (reopened) navigate(sessionPath(project.id, reopened));
    else setCreating({ purpose, message });
  };
  const defineProject = () => open('define', defineProjectMessage(project.name, definition));
  const closeCreate = () => { setCreating(null); clearActionError(); };

  const designNext = () => {
    if (!definition) return;
    const index = definition.roadmap.findIndex((entry) => roadmapState(entry, items ?? []) === 'pending');
    const entry = definition.roadmap[index];
    if (!entry) return;
    const previous = index > 0 ? definition.roadmap[index - 1]! : null;
    // A designed entry is never re-opened as a fresh one: each Design next starts its own session.
    setCreating({ purpose: 'design', message: designNextMessage(project.name, entry,
      previous ? { title: previous.title, featureId: previous.featureId } : null, definition.featureId) });
  };

  const defined = definition?.state === 'DEFINED';
  const pendingProposal = hasProposal && definition?.proposal?.proposalId !== definition?.accepted?.proposalId;
  const showEmpty = isNew && !hasProposal && !hasRoadmap && !defined;

  return (
    <section aria-label={`${project.name} definition`} className="flex flex-col gap-3">
      {showEmpty ? (
        <EmptyState icon={Landmark} title="Define your project first"
          description="This project is new. Define it as a whole with an agent — vision, architecture, decisions and a roadmap — and the factory builds its foundations before the first feature.">
          <Button variant="brand" onClick={defineProject}>Define project</Button>
        </EmptyState>
      ) : (
        <div className="flex flex-col gap-2 rounded-card border border-line bg-surface px-3 py-2.5 sm:flex-row sm:items-center sm:gap-3">
          <span className="flex min-w-0 flex-1 items-center gap-2">
            <Initials value={project.name} size="sm" />
            <span className="truncate text-sm font-semibold text-ink">{project.name}</span>
            <Badge tone={defined ? 'success' : pendingProposal ? 'warning' : 'neutral'}>
              {defined ? (pendingProposal ? 'New proposal' : 'Defined') : pendingProposal ? 'Proposal ready' : 'Not defined'}
            </Badge>
          </span>
          <span className="flex flex-wrap items-center gap-2">
            {hasProposal || defined ? (
              <Button variant="secondary" size="sm" onClick={() => setReviewing(true)}>
                <FileCheck2 className="size-4" /> {pendingProposal && !defined ? 'Review proposal' : 'Project definition'}
              </Button>
            ) : null}
            <Button variant={hasProposal ? 'ghost' : 'secondary'} size="sm" onClick={defineProject}>
              {defined ? 'Redefine project' : 'Define project'}
            </Button>
          </span>
        </div>
      )}
      {controls.loadError && !definition ? <p role="status" className="text-[12.5px] text-ink-subtle">{controls.loadError}</p> : null}
      {hasRoadmap && definition ? (
        <DefinitionRoadmapPanel roadmap={definition.roadmap} items={items ?? []} busy={busy} onDesignNext={designNext} />
      ) : null}
      {reviewing && definition ? (
        <DefinitionProposalDialog projectId={project.id} definition={definition} controls={controls}
          sessionId={existing('define')} onDefine={() => { setReviewing(false); defineProject(); }}
          onClose={() => { setReviewing(false); controls.clearError(); }} />
      ) : null}
      <NewSessionDialog open={creating !== null} project={project} connections={connections} busy={busy}
        actionError={actionError} onRefreshConnections={reloadConnections} onClose={closeCreate}
        preferStrongModel branchPrefix={creating?.purpose === 'design' ? 'design' : 'define'}
        title={creating?.purpose === 'design' ? 'Design next feature' : 'Define project'}
        description={creating?.purpose === 'design'
          ? 'An orchestrator session designs the next pending roadmap entry as a feature.'
          : 'A definition session with a strong model: it converses with you and saves a proposal for review.'}
        initialMessage={creating?.message}
        onCreate={(request) => create(request).then((session) => {
          if (!session) return false;
          rememberDefinitionSession(project.id, creating?.purpose ?? 'define', session.id);
          navigate(sessionPath(project.id, session.id));
          return true;
        })} />
    </section>
  );
};
