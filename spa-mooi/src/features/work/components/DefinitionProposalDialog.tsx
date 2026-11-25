import { lazy, Suspense } from 'react';
import { Link } from 'react-router-dom';
import { CheckCircle2, MessagesSquare, RotateCcw } from 'lucide-react';
import { sessionPath } from '@/app/paths';
import type { UseProjectDefinition } from '@/features/work/hooks/useProjectDefinition';
import { TASK_CATEGORIES } from '@/features/work/lib/taskCategories';
import type { ProjectDefinition } from '@/features/work/types/ProjectDefinition';
import { Badge } from '@/shared/components/Badge';
import { Button } from '@/shared/components/Button';
import { Modal } from '@/shared/components/Modal';
import { cn } from '@/shared/utils/cn';

const MarkdownContent = lazy(() => import('@/features/sessions/components/chat/MarkdownContent'));

interface DefinitionProposalDialogProps {
  projectId: string;
  definition: ProjectDefinition;
  controls: UseProjectDefinition;
  /** The session where the proposal was worked out, when this device remembers it. */
  sessionId: string | null;
  onDefine: () => void;
  onClose: () => void;
}

const Section = ({ title, children }: { title: string; children: React.ReactNode }) => (
  <section className="flex min-w-0 flex-col gap-2">
    <h3 className="font-mono text-[10.5px] font-medium tracking-[0.12em] text-ink-subtle uppercase">{title}</h3>
    {children}
  </section>
);

const Prose = ({ text }: { text: string }) => (
  <div className="text-sm leading-relaxed break-words text-ink-muted">
    <Suspense fallback={<p className="whitespace-pre-line">{text}</p>}><MarkdownContent text={text} /></Suspense>
  </div>
);

/**
 * The definition agent's current proposal, read in full: vision, architecture, decisions, the ordered roadmap
 * and the tasks of Fundaciones. Only here does the user accept it; reopening allows new proposals.
 */
export const DefinitionProposalDialog = ({ projectId, definition, controls, sessionId, onDefine, onClose }: DefinitionProposalDialogProps) => {
  const { busy, actionError } = controls;
  const proposal = definition.proposal;
  const accepted = proposal !== null && definition.accepted?.proposalId === proposal.proposalId;
  const defined = definition.state === 'DEFINED';
  const canAccept = proposal !== null && !defined;
  const accept = async () => { if (proposal && await controls.accept(proposal.proposalId)) onClose(); };
  const reopen = async () => { await controls.reopen(proposal?.proposalId ?? null); };

  return (
    <Modal open size="xl" title="Project definition"
      description={defined ? 'Accepted: Fundaciones and the roadmap exist. Reopen to let the agent propose changes.'
        : 'Review the agent’s proposal. Nothing is created until you accept it.'}
      onClose={onClose}
      footer={<div className="flex w-full flex-col-reverse gap-2 sm:flex-row sm:items-center sm:justify-end">
        {sessionId ? (
          <Link to={sessionPath(projectId, sessionId)} onClick={onClose}
            className="inline-flex h-10 items-center justify-center gap-2 rounded-control px-3 text-sm font-medium text-brand hover:underline sm:mr-auto">
            <MessagesSquare className="size-4" /> Open definition session
          </Link>
        ) : (
          <span className="sm:mr-auto"><Button variant="ghost" onClick={onDefine}><MessagesSquare className="size-4" /> Start a definition session</Button></span>
        )}
        {defined || definition.accepted ? (
          <Button variant="secondary" disabled={busy || !defined} onClick={() => { void reopen(); }}>
            <RotateCcw className="size-4" /> Reopen
          </Button>
        ) : null}
        <Button variant="brand" disabled={busy || !canAccept} onClick={() => { void accept(); }}>
          <CheckCircle2 className="size-4" /> {busy ? 'Working…' : 'Accept'}
        </Button>
      </div>}>
      <div className="flex flex-col gap-6">
        {actionError ? <p role="alert" className="rounded-control border border-danger/30 bg-danger-soft px-3 py-2 text-sm text-danger">{actionError}</p> : null}
        {!proposal ? (
          <p className="rounded-control border border-dashed border-line px-4 py-8 text-center text-sm text-ink-muted">
            There is no proposal yet. Talk to the definition agent: it saves a proposal here when it is ready.
          </p>
        ) : (
          <>
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone={accepted ? 'success' : 'warning'}>{accepted ? 'Accepted' : defined ? 'Newer than the accepted one' : 'Awaiting your decision'}</Badge>
              <span className="font-mono text-[11px] text-ink-subtle">{proposal.proposalId.slice(0, 8)}</span>
            </div>
            <Section title="Vision"><Prose text={proposal.vision} /></Section>
            <Section title="Architecture"><Prose text={proposal.architecture} /></Section>
            <Section title={`Decisions (${proposal.decisions.length})`}>
              <ul className="flex flex-col gap-2">
                {proposal.decisions.map((entry, index) => (
                  <li key={index} className="rounded-control border border-line bg-surface-2/50 px-3 py-2 text-sm">
                    <p className="font-medium text-ink">{entry.decision}</p>
                    <p className="mt-0.5 text-ink-muted">{entry.reason}</p>
                    <p className="mt-0.5 text-[12px] text-ink-subtle">Affects: {entry.affects}</p>
                  </li>
                ))}
              </ul>
            </Section>
            <Section title={`Roadmap (${proposal.roadmap.length})`}>
              <ol className="flex flex-col gap-2">
                {proposal.roadmap.map((entry, index) => (
                  <li key={entry.key} className="flex min-w-0 gap-3 rounded-control border border-line px-3 py-2 text-sm">
                    <span className="mt-0.5 font-mono text-[11px] text-ink-subtle tabular-nums">{index + 1}</span>
                    <span className="min-w-0">
                      <span className="block font-medium break-words text-ink">{entry.title}</span>
                      <span className="block break-words text-ink-muted">{entry.goal}</span>
                      <span className="block text-[12px] text-ink-subtle">
                        After: {entry.dependsOn.length > 0 ? entry.dependsOn.join(', ') : 'Fundaciones'}
                      </span>
                    </span>
                  </li>
                ))}
              </ol>
            </Section>
            <Section title={proposal.foundations.title}>
              <Prose text={proposal.foundations.spec} />
              <ul className="flex flex-col gap-2">
                {proposal.foundations.tasks.map((task) => {
                  const category = TASK_CATEGORIES[task.category];
                  const Icon = category.icon;
                  return (
                    <li key={task.key} className={cn('min-w-0 rounded-control border px-3 py-2 text-sm', category.node)}>
                      <div className="flex flex-wrap items-center gap-2">
                        <span className={cn('inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[11px] font-semibold', category.chip)}>
                          <Icon className="size-3" /> {category.label}
                        </span>
                        <span className="min-w-0 font-medium break-words text-ink">{task.title}</span>
                        {task.tests ? <Badge tone="info">Tests</Badge> : null}
                        {task.autoMerge ? <Badge>Auto-merge</Badge> : null}
                      </div>
                      <p className="mt-1.5 break-words text-ink-muted">{task.description}</p>
                      <p className="mt-1 text-[12px] break-words text-ink-subtle">Done when: {task.acceptance}</p>
                      {task.dependsOn.length > 0 ? <p className="mt-1 text-[12px] text-ink-subtle">After: {task.dependsOn.join(', ')}</p> : null}
                    </li>
                  );
                })}
              </ul>
            </Section>
          </>
        )}
      </div>
    </Modal>
  );
};
