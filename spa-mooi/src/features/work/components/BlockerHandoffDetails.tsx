import { useState } from 'react';
import { CircleAlert, ExternalLink, FilePlus2 } from 'lucide-react';
import { DEV_BLOCKER_LABEL, canCreateFollowUp, devBlocker } from '@/features/work/lib/devBlocker';
import type { WorkTask } from '@/features/work/types/WorkTask';
import { Badge } from '@/shared/components/Badge';
import { Button } from '@/shared/components/Button';
import { Modal } from '@/shared/components/Modal';

interface BlockerHandoffDetailsProps {
  task: WorkTask;
  busy: boolean;
  onCreateFollowUp?: (kind: 'defect' | 'debt') => Promise<boolean>;
}

const Text = ({ label, value }: { label: string; value?: string }) => value?.trim() ? (
  <div className="min-w-0">
    <dt className="font-semibold text-ink-muted">{label}</dt>
    <dd className="mt-0.5 whitespace-pre-line break-words text-ink">{value}</dd>
  </div>
) : null;

const List = ({ label, values, code }: { label: string; values?: string[]; code?: boolean }) => values?.length ? (
  <div className="min-w-0">
    <dt className="font-semibold text-ink-muted">{label}</dt>
    <dd className="mt-0.5">
      <ul className="list-disc pl-4 text-ink">
        {values.map((value) => (
          <li key={value} className="break-words">{code ? <code className="text-[11.5px]">{value}</code> : value}</li>
        ))}
      </ul>
    </dd>
  </div>
) : null;

/**
 * The structured handoff of a development task blocked in attention, shared by Work and Board: what holds
 * it, whether it is product work or a missing platform capability, and the next step. A product blocker
 * can become one NEW follow-up issue, which still needs Define and acceptance before any work starts.
 */
export const BlockerHandoffDetails = ({ task, busy, onCreateFollowUp }: BlockerHandoffDetailsProps) => {
  const [confirm, setConfirm] = useState(false);
  const [kind, setKind] = useState<'defect' | 'debt'>('debt');
  const blocker = devBlocker(task);
  const followUp = task.followUpIssue;
  if (!blocker && !(followUp && task.status === 'attention')) return null;

  const create = async () => {
    if (!onCreateFollowUp) return;
    if (await onCreateFollowUp(kind)) setConfirm(false);
  };

  return (
    <section aria-label="Task blocker" className="rounded-control border border-warning/35 bg-warning-soft px-3 py-2.5">
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone="warning" icon={CircleAlert}>Blocked</Badge>
        {blocker ? (
          <Badge tone={blocker.kind === 'platform' ? 'info' : 'neutral'}>
            {blocker.kind ? DEV_BLOCKER_LABEL[blocker.kind] : 'Unclassified'}
          </Badge>
        ) : null}
        {blocker && blocker.revision > 1 ? <span className="text-xs text-ink-subtle">Revision {blocker.revision}</span> : null}
        {followUp ? (
          followUp.htmlUrl ? (
            <a href={followUp.htmlUrl} target="_blank" rel="noreferrer"
              className="ml-auto inline-flex items-center gap-1 text-xs font-semibold text-brand underline-offset-4 hover:underline">
              Follow-up #{followUp.number} <ExternalLink className="size-3.5" />
            </a>
          ) : <span className="ml-auto text-xs font-semibold text-ink-muted">Follow-up #{followUp.number}</span>
        ) : null}
      </div>
      {blocker ? (
        <dl className="mt-2 grid gap-2 text-[12px] leading-relaxed sm:grid-cols-2">
          <div className="sm:col-span-2"><Text label="Cause" value={blocker.cause || blocker.summary} /></div>
          {blocker.cause && blocker.summary && blocker.summary !== blocker.cause ? (
            <div className="sm:col-span-2"><Text label="Summary" value={blocker.summary} /></div>
          ) : null}
          <div className="sm:col-span-2"><Text label="Next step" value={blocker.nextStep} /></div>
          <List label="Obstacles" values={blocker.obstacles} />
          <List label="Verifications" values={blocker.verifications} />
          <List label="Files" values={blocker.files} code />
          <Text label="Preparation" value={blocker.preparation} />
        </dl>
      ) : null}
      {blocker?.kind === 'platform' ? (
        <p className="mt-2 text-xs text-ink-muted">
          A missing platform capability is not product work: it needs a Mooi change, not a follow-up issue in this project.
        </p>
      ) : null}
      {canCreateFollowUp(task) && onCreateFollowUp ? (
        <div className="mt-2 flex justify-end">
          <Button size="sm" variant="secondary" disabled={busy} onClick={() => setConfirm(true)}>
            <FilePlus2 className="size-3.5" /> Create follow-up issue
          </Button>
        </div>
      ) : null}
      <Modal open={confirm} title="Create follow-up issue"
        description="Opens one NEW GitHub issue prefilled from this blocker and linked to the task. Nothing starts: define it and accept its definition first."
        onClose={() => { if (!busy) setConfirm(false); }}
        footer={<>
          <Button variant="secondary" disabled={busy} onClick={() => setConfirm(false)}>Cancel</Button>
          <Button disabled={busy} onClick={() => { void create(); }}>Create issue</Button>
        </>}>
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1.5 text-xs font-medium text-ink-muted">Kind</legend>
          {(['debt', 'defect'] as const).map((option) => (
            <label key={option} className="inline-flex items-center gap-2 text-sm text-ink">
              <input type="radio" name="follow-up-kind" className="accent-brand" checked={kind === option}
                onChange={() => setKind(option)} />
              {option === 'debt' ? 'Improvement (debt)' : 'Defect'}
            </label>
          ))}
        </fieldset>
      </Modal>
    </section>
  );
};
