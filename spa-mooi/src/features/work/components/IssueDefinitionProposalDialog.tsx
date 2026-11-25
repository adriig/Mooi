import { CheckCircle2, MessagesSquare, RotateCcw } from 'lucide-react';
import type { IssueDefinition, IssueDefinitionProposal } from '@/features/work/api/workApi';
import { TASK_CATEGORIES } from '@/features/work/lib/taskCategories';
import { Badge } from '@/shared/components/Badge';
import { Button } from '@/shared/components/Button';
import { Modal } from '@/shared/components/Modal';

interface Props {
  issueNumber: number;
  definition: IssueDefinition | null;
  busy: boolean;
  error: string | null;
  onAccept: (proposalId: string) => void;
  onReopen: (proposalId: string | null) => void;
  onDefine: () => void;
  onClose: () => void;
}

const Field = ({ label, children }: { label: string; children: React.ReactNode }) => (
  <section className="flex flex-col gap-1.5"><h3 className="font-mono text-[10.5px] font-medium tracking-[0.12em] text-ink-subtle uppercase">{label}</h3>
    <div className="whitespace-pre-line break-words text-sm leading-relaxed text-ink-muted">{children}</div></section>
);

const complete = (proposal: IssueDefinitionProposal) => Boolean(proposal.title.trim()
  && (!proposal.valid || (proposal.description.trim() && proposal.acceptance.trim()))
  && (proposal.breakdown ?? []).every((part) => part.title.trim() && part.description.trim() && part.acceptance.trim())
  && (!proposal.feature || (proposal.feature.title.trim() && proposal.feature.spec.trim()
    && (proposal.feature.tasks ?? []).every((task) => task.title.trim() && task.description.trim() && task.acceptance.trim()))));

export const IssueDefinitionProposalDialog = ({ issueNumber, definition, busy, error, onAccept, onReopen, onDefine, onClose }: Props) => {
  const proposal = definition?.proposal ?? null;
  const accepted = Boolean(proposal && definition?.accepted?.proposalId === proposal.proposalId);
  const canAccept = Boolean(proposal && definition?.state === 'NEW' && !accepted && complete(proposal));
  const category = proposal ? TASK_CATEGORIES[proposal.category] : null;

  return <Modal open size="xl" title={`Issue #${issueNumber} definition`}
    description={definition?.state === 'DEFINED' ? 'This definition has been accepted. Reopen it to return the issue to NEW.' : 'Review the proposal. It only changes the issue after you accept it.'}
    onClose={onClose} footer={<div className="flex w-full flex-col-reverse gap-2 sm:flex-row sm:items-center sm:justify-end">
      <Button variant="ghost" onClick={onDefine}><MessagesSquare className="size-4" /> Start a definition session</Button>
      <span className="sm:mr-auto" />
      {(definition?.state === 'DEFINED' || definition?.accepted) ? <Button variant="secondary" disabled={busy || definition?.state !== 'DEFINED'} onClick={() => onReopen(proposal?.proposalId ?? null)}><RotateCcw className="size-4" /> Reopen</Button> : null}
      <Button variant="brand" disabled={busy || !canAccept} onClick={() => proposal && onAccept(proposal.proposalId)}><CheckCircle2 className="size-4" /> {busy ? 'Working…' : 'Accept'}</Button>
    </div>}>
    <div className="flex flex-col gap-5">
      {error ? <p role="alert" className="rounded-control border border-danger/30 bg-danger-soft px-3 py-2 text-sm text-danger">{error}</p> : null}
      {accepted ? <div role="status" className="rounded-control border border-line bg-surface-2 px-3 py-2 text-sm">
        {definition?.issueNumbers?.length ? <p>Created issues: {definition.issueNumbers.map((number) => `#${number}`).join(', ')}</p> : null}
        {definition?.featureId ? <p>Promoted to feature {definition.featureId}.</p> : null}
        {proposal && !proposal.valid ? <p>This invalid report was closed.</p> : null}
      </div> : null}
      {!proposal ? <p className="rounded-control border border-dashed border-line px-4 py-8 text-center text-sm text-ink-muted">There is no proposal yet. Start a definition session; its proposal will appear here.</p> : <>
        <div className="flex flex-wrap items-center gap-2"><Badge tone={accepted ? 'success' : 'warning'}>{accepted ? 'Accepted' : 'Awaiting your decision'}</Badge><Badge>{proposal.kind === 'debt' ? 'Improvement' : 'Defect'}</Badge>{category ? <Badge>{category.label}</Badge> : null}</div>
        <Field label="Title">{proposal.title}</Field>
        <Field label="Description">{proposal.description || '—'}</Field>
        <Field label="Validity">{proposal.valid ? 'Valid report' : 'Invalid report; accepting closes the issue.'}</Field>
        <Field label="Acceptance criteria">{proposal.acceptance || '—'}</Field>
        {proposal.breakdown?.length ? <Field label={`Breakdown (${proposal.breakdown.length})`}><ul className="flex flex-col gap-2">{proposal.breakdown.map((part, index) => <li key={`${part.title}-${index}`} className="rounded-control border border-line px-3 py-2"><strong className="text-ink">{part.title}</strong><p>{part.description}</p><p className="text-xs">Done when: {part.acceptance} · {part.kind === 'debt' ? 'Improvement' : 'Defect'} · {TASK_CATEGORIES[part.category].label}</p></li>)}</ul></Field> : null}
        {proposal.feature ? <Field label="Feature promotion"><strong className="text-ink">{proposal.feature.title}</strong><p>{proposal.feature.spec}</p>{proposal.feature.tasks ? <p>{proposal.feature.tasks.length} tasks</p> : null}</Field> : null}
      </>}
    </div>
  </Modal>;
};
