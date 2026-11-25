import { lazy, Suspense, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowUpRight, Check, FileText, ListPlus, PencilLine, Send, Trash2, X, type LucideIcon } from 'lucide-react';
import { sessionPath } from '@/app/paths';
import { decideWorkChange, requestWorkChange } from '@/features/work/api/workApi';
import type { UseProjectWork } from '@/features/work/hooks/useProjectWork';
import { TASK_CATEGORIES } from '@/features/work/lib/taskCategories';
import { CHANGE_STATUS } from '@/features/work/lib/workStatus';
import type { WorkChangeOperation, WorkChangeRequest } from '@/features/work/types/WorkChangeRequest';
import type { WorkItem } from '@/features/work/types/WorkItem';
import { Badge } from '@/shared/components/Badge';
import { Button } from '@/shared/components/Button';
import { cn } from '@/shared/utils/cn';
import { formatRelativeTime } from '@/shared/utils/formatRelativeTime';

const MarkdownContent = lazy(() => import('@/features/sessions/components/chat/MarkdownContent'));

const FIELD_LABELS: Record<string, string> = {
  title: 'title',
  category: 'category',
  description: 'description',
  acceptance: 'acceptance criteria',
  assignment: 'model assignment',
  alternatives: 'alternatives',
  autoMerge: 'auto-merge',
  tests: 'tests',
  dependsOn: 'dependencies',
};

/** One line per staged operation, so a proposal can be reviewed without opening every task. */
const operationSummary = (operation: WorkChangeOperation, item: WorkItem): { icon: LucideIcon; label: string; detail?: string } => {
  const taskTitle = (id?: string | null) => item.tasks.find((task) => task.id === id)?.title ?? 'a task';
  switch (operation.kind) {
    case 'spec': {
      const spec = operation.item?.spec;
      return { icon: FileText, label: 'Update the feature spec', detail: spec ? `${spec.length.toLocaleString()} characters` : undefined };
    }
    case 'add_task': {
      const draft = operation.draft;
      const category = draft?.category ? TASK_CATEGORIES[draft.category] : null;
      const detail = [category?.label, draft?.tests ? 'with QA' : null].filter(Boolean).join(' · ');
      return { icon: ListPlus, label: `Add task: ${draft?.title || 'Untitled'}`, detail: detail || undefined };
    }
    case 'update_task': {
      const fields = Object.entries(operation.task ?? {})
        .filter(([, value]) => value !== null && value !== undefined)
        .map(([field]) => FIELD_LABELS[field])
        .filter(Boolean);
      return { icon: PencilLine, label: `Edit task: ${taskTitle(operation.taskId)}`, detail: fields.join(', ') || undefined };
    }
    case 'delete_task':
      return { icon: Trash2, label: `Remove task: ${taskTitle(operation.taskId)}` };
  }
};

interface ChangeCardProps {
  projectId: string;
  item: WorkItem;
  change: WorkChangeRequest;
  busy: boolean;
  run: UseProjectWork['run'];
}

const ChangeCard = ({ projectId, item, change, busy, run }: ChangeCardProps) => {
  const state = CHANGE_STATUS[change.status];
  const operations = change.proposal?.operations ?? [];
  const proposalText = change.proposal?.text;
  const decide = (decision: 'accept' | 'reject') => {
    void run(() => decideWorkChange(projectId, item.id, change.id, decision));
  };

  return (
    <li className={cn('flex flex-col gap-2 rounded-card border bg-surface px-3 py-2.5',
      change.status === 'awaiting_confirmation' ? 'border-warning/40' : 'border-line')}>
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone={state.tone}>{state.label}</Badge>
        {change.createdAt ? <span className="text-xs text-ink-subtle">{formatRelativeTime(change.createdAt)}</span> : null}
        {change.confirmedAt ? (
          <span className="text-xs font-medium text-success">Accepted {formatRelativeTime(change.confirmedAt)}</span>
        ) : null}
        {change.status === 'analyzing' && change.sessionId ? (
          <Link to={sessionPath(projectId, change.sessionId)}
            className="inline-flex items-center gap-1 text-xs font-semibold text-brand underline-offset-4 hover:underline">
            Analyst session <ArrowUpRight className="size-3.5" />
          </Link>
        ) : null}
        {change.status === 'awaiting_confirmation' ? (
          <span className="ml-auto flex gap-2">
            <Button size="sm" variant="primary" disabled={busy} onClick={() => decide('accept')}>
              <Check className="size-4" /> Accept
            </Button>
            <Button size="sm" variant="danger" disabled={busy} onClick={() => decide('reject')}>
              <X className="size-4" /> Reject
            </Button>
          </span>
        ) : null}
      </div>

      <p className="text-sm leading-relaxed whitespace-pre-line text-ink">{change.request}</p>

      {change.analysis ? (
        <div className="flex flex-col gap-1">
          <span className="text-[11px] font-semibold tracking-[0.08em] text-ink-subtle uppercase">Analysis</span>
          <p className="rounded-control border border-line bg-surface-2 px-3 py-2 text-[13px] leading-relaxed whitespace-pre-line text-ink-muted">
            {change.analysis}
          </p>
        </div>
      ) : null}

      {proposalText || operations.length > 0 ? (
        <div className="flex flex-col gap-1.5">
          <span className="text-[11px] font-semibold tracking-[0.08em] text-ink-subtle uppercase">Proposal</span>
          {proposalText ? (
            <div className="rounded-control border border-line bg-surface-2 px-3 py-2 text-[13px] leading-relaxed text-ink">
              <Suspense fallback={<p className="whitespace-pre-line">{proposalText}</p>}>
                <MarkdownContent text={proposalText} />
              </Suspense>
            </div>
          ) : null}
          {operations.length > 0 ? (
            <ul className="flex flex-col gap-1.5">
              {operations.map((operation, index) => {
                const summary = operationSummary(operation, item);
                const Icon = summary.icon;
                return (
                  <li key={index} className="flex items-start gap-2 text-[13px] leading-relaxed">
                    <Icon className="mt-0.5 size-3.5 shrink-0 text-ink-subtle" />
                    <span className="min-w-0 text-ink-muted">
                      <span className="font-medium text-ink">{summary.label}</span>
                      {summary.detail ? ` · ${summary.detail}` : null}
                    </span>
                  </li>
                );
              })}
            </ul>
          ) : null}
        </div>
      ) : null}
    </li>
  );
};

interface WorkChangeRequestsProps {
  projectId: string;
  item: WorkItem;
  /** Running or paused features accept new requests; finished ones only show their history. */
  canRequest: boolean;
  busy: boolean;
  run: UseProjectWork['run'];
}

/**
 * Change requests on an ongoing feature: queue one in free text, follow its analysis and proposal
 * live, and decide the proposals that wait for confirmation. Everything arrives in the item's
 * `work.item` announcements; nothing is polled.
 */
export const WorkChangeRequests = ({ projectId, item, canRequest, busy, run }: WorkChangeRequestsProps) => {
  const [draft, setDraft] = useState('');
  const changes = item.changeRequests ?? [];

  const queue = async () => {
    const request = draft.trim();
    if (!request) return;
    const result = await run(() => requestWorkChange(projectId, item.id, request));
    if (result) setDraft('');
  };

  return (
    <section className="flex flex-col gap-2">
      <h3 className="text-xs font-semibold tracking-[0.08em] text-ink-subtle uppercase">Change requests</h3>
      {canRequest ? (
        <div className="flex flex-col gap-1.5">
          <div className="flex flex-col gap-2 sm:flex-row sm:items-end">
            <textarea value={draft} rows={3} maxLength={20000} disabled={busy}
              onChange={(event) => setDraft(event.target.value)}
              placeholder="Ask for a change to this feature while it runs…"
              aria-label="New change request"
              className="w-full min-w-0 flex-1 rounded-control border border-line bg-surface-2 px-3 py-2 text-sm text-ink outline-none transition focus:border-brand disabled:cursor-not-allowed disabled:opacity-70" />
            <Button variant="brand" size="sm" className="shrink-0" disabled={busy || draft.trim().length === 0}
              onClick={() => { void queue(); }}>
              <Send className="size-4" /> Queue request
            </Button>
          </div>
          <p className="text-xs leading-relaxed text-ink-subtle">
            An analyst adjusts the spec and unstarted tasks at a safe point; finished work is preserved.
            Scope changes wait for your confirmation.
          </p>
        </div>
      ) : null}
      {changes.length > 0 ? (
        <ul className="flex flex-col gap-2">
          {changes.map((change) => (
            <ChangeCard key={change.id} projectId={projectId} item={item} change={change} busy={busy} run={run} />
          ))}
        </ul>
      ) : null}
    </section>
  );
};
