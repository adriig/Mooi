import { lazy, Suspense, useState } from 'react';
import { Link } from 'react-router-dom';
import { CheckCircle2, ChevronRight, CircleDot, ExternalLink, Network, RefreshCw, Settings2, Trash2 } from 'lucide-react';
import { projectPath } from '@/app/paths';
import type { AgentConnection } from '@/features/agents/types/AgentConnection';
import {
  deleteWorkItem, deleteWorkTask, ensureIssueItem, pauseWorkItem, reassignWorkTasks, startWorkItem,
  updateWorkIssue, updateWorkItem,
  updateWorkTask,
} from '@/features/work/api/workApi';
import { TaskEditor } from '@/features/work/components/TaskEditor';
import { TaskQuickControls } from '@/features/work/components/TaskQuickControls';
import { CommitLink } from '@/features/work/components/CommitLink';
import { WorkRunButton } from '@/features/work/components/WorkRunButton';
import type { UseProjectWork } from '@/features/work/hooks/useProjectWork';
import { taskStyle } from '@/features/work/lib/taskCategories';
import { QA_BLOCKER_LABEL, qaBlocker, qaFindings } from '@/features/work/lib/qaTaskState';
import { entryDeployment } from '@/features/work/lib/workEntries';
import { WORK_KINDS } from '@/features/work/lib/workKinds';
import { ITEM_STATUS, TASK_STATUS, taskEditable, taskState } from '@/features/work/lib/workStatus';
import type { WorkEntry } from '@/features/work/types/WorkEntry';
import type { WorkItem } from '@/features/work/types/WorkItem';
import { Badge } from '@/shared/components/Badge';
import { Button } from '@/shared/components/Button';
import { Modal } from '@/shared/components/Modal';
import { cn } from '@/shared/utils/cn';
import { formatRelativeTime } from '@/shared/utils/formatRelativeTime';

const MarkdownContent = lazy(() => import('@/features/sessions/components/chat/MarkdownContent'));

interface WorkItemDialogProps {
  projectId: string;
  /** `owner/name` on GitHub, for the commit links. */
  repository?: string | null;
  entry: WorkEntry;
  /** Every feature of the project, to pick the one this feature waits for. */
  features: WorkItem[];
  connections: AgentConnection[];
  work: UseProjectWork;
  onClose: () => void;
}

/**
 * A feature, defect or debt item opened from To-dos: what it is about, its task breakdown with every
 * task's settings, and the control that starts it. Defects and debt show their GitHub issue; the
 * item that runs one is created the first time its settings are opened.
 */
export const WorkItemDialog = ({ projectId, repository, entry, features, connections, work, onClose }: WorkItemDialogProps) => {
  const { run, busy, actionError } = work;
  const item = entry.item;
  const [openTask, setOpenTask] = useState<string | null>(null);
  const [expanded, setExpanded] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [confirmReassign, setConfirmReassign] = useState(false);
  const [reassignMessage, setReassignMessage] = useState<string | null>(null);
  const kind = WORK_KINDS[entry.kind];
  const text = item?.spec || entry.issue?.body || '';
  const tasks = item?.tasks ?? [];
  const done = tasks.filter((task) => task.status === 'done').length;
  const status = item ? ITEM_STATUS[item.status] : entry.issue?.state === 'closed' ? ITEM_STATUS.done : ITEM_STATUS.planned;
  const deployment = entryDeployment(entry);
  const hasReassignableTasks = tasks.some((task) =>
    task.status === 'planned' || task.status === 'queued'
    || (task.status === 'attention' && task.sessionId === null));

  const recalculateAssignments = async () => {
    if (!item) return;
    const result = await run(() => reassignWorkTasks(projectId, item.id));
    setConfirmReassign(false);
    if (result) {
      const unchanged = result.eligibleCount - result.changedCount;
      setReassignMessage(
        `${result.changedCount} assignment${result.changedCount === 1 ? '' : 's'} updated from current quota; `
        + `${unchanged} unchanged.`,
      );
    }
  };

  const start = async () => {
    const target = item ?? (entry.issue ? await run(() => ensureIssueItem(projectId, entry.issue!.number)) : null);
    if (target) await run(() => startWorkItem(projectId, target.id));
  };

  return (
    <Modal open onClose={onClose} size="xl" title={entry.title}
      description={`${kind.label}${entry.issue ? ` #${entry.issue.number}` : ''}${item && tasks.length > 0 ? ` · ${done} of ${tasks.length} tasks done` : ''}`}>
      <div className="flex flex-col gap-5">
        <div className="flex flex-wrap items-center gap-2">
          <Badge tone={kind.tone} icon={kind.icon}>{kind.label}</Badge>
          <Badge tone={status.tone}>{status.label}</Badge>
          {deployment ? (
            <Badge tone={deployment.state === 'deployed' ? 'success' : 'neutral'}>
              {deployment.state === 'deployed' && deployment.version ? `Deployed ${deployment.version}`
                : deployment.state === 'unknown' ? 'Unknown version' : 'Pending to deploy'}
            </Badge>
          ) : null}
          {deployment?.state === 'deployed' && deployment.deployedAt ? (
            <span className="text-xs text-ink-subtle">{formatRelativeTime(deployment.deployedAt)}</span>
          ) : null}
          {deployment?.state === 'deployed' && deployment.inCurrentRelease === false ? (
            <Badge tone="warning">Not in current release</Badge>
          ) : null}
          {entry.issue?.htmlUrl ? (
            <a href={entry.issue.htmlUrl} target="_blank" rel="noreferrer"
              className="inline-flex items-center gap-1 text-xs font-semibold text-ink-muted hover:text-ink">
              GitHub #{entry.issue.number} <ExternalLink className="size-3" />
            </a>
          ) : null}
          {entry.issue && item && entry.kind !== 'feature' && hasReassignableTasks ? (
            <Button size="sm" variant="ghost" ariaLabel="Refresh model assignment" disabled={busy}
              onClick={() => { setReassignMessage(null); setConfirmReassign(true); }}>
              <RefreshCw className="size-4" /> <span className="hidden sm:inline">Refresh assignment</span>
            </Button>
          ) : null}
          {item && entry.kind === 'feature' ? (
            <Link to={`${projectPath(projectId)}?tab=board&feature=${item.id}${item.status === 'done' ? '&completed=1' : ''}`}
              className="inline-flex items-center gap-1 text-xs font-semibold text-brand underline-offset-4 hover:underline">
              <Network className="size-3.5" /> Open in Board
            </Link>
          ) : null}
          <span className="ml-auto flex gap-2">
            {entry.issue ? (
              <Button size="sm" variant="ghost" disabled={busy} onClick={() => {
                void run(() => updateWorkIssue(projectId, entry.issue!.number, { state: entry.issue!.state === 'open' ? 'closed' : 'open' }));
              }}>
                {entry.issue.state === 'open' ? <CheckCircle2 className="size-4" /> : <CircleDot className="size-4" />}
                {entry.issue.state === 'open' ? 'Close issue' : 'Reopen issue'}
              </Button>
            ) : null}
            {entry.issue?.state !== 'closed' || item?.status === 'running' ? (
              <WorkRunButton item={item} busy={busy} onStart={() => { void start(); }}
                onPause={() => { if (item) void run(() => pauseWorkItem(projectId, item.id)); }} />
            ) : null}
          </span>
        </div>

        {actionError ? <p role="alert" className="rounded-control bg-danger-soft px-3 py-2 text-sm text-danger">{actionError}</p> : null}
        {reassignMessage ? <p role="status" className="rounded-control bg-success-soft px-3 py-2 text-sm text-success">{reassignMessage}</p> : null}

        {text ? (
          <section className="relative rounded-card border border-line bg-surface-2/40 px-4 py-3 text-sm leading-relaxed text-ink">
            <div className={cn(!expanded && 'max-h-48 overflow-hidden')}>
              <Suspense fallback={<p className="whitespace-pre-line">{text}</p>}><MarkdownContent text={text} /></Suspense>
            </div>
            {text.length > 600 ? (
              <button type="button" onClick={() => setExpanded((value) => !value)}
                className="mt-2 text-xs font-semibold text-brand underline-offset-4 hover:underline">
                {expanded ? 'Show less' : 'Show all'}
              </button>
            ) : null}
          </section>
        ) : null}

        {item && entry.kind === 'feature' && features.length > 1 ? (
          <label className="flex flex-col gap-1.5 sm:max-w-sm">
            <span className="text-xs font-medium text-ink-muted">Waits for feature</span>
            <select value={item.dependsOn ?? ''} disabled={busy || item.status === 'done'}
              onChange={(event) => { void run(() => updateWorkItem(projectId, item.id, { dependsOn: event.target.value })); }}
              className="h-10 rounded-control border border-line bg-surface-2 px-3 text-sm text-ink outline-none focus:border-brand">
              <option value="">None: it can start right away</option>
              {features.filter((feature) => feature.id !== item.id).map((feature) => (
                <option key={feature.id} value={feature.id}>{feature.title}</option>
              ))}
            </select>
          </label>
        ) : null}

        {!item && entry.issue ? (
          <div className="flex flex-col items-start gap-2 rounded-card border border-dashed border-line px-4 py-4">
            <p className="text-sm text-ink-muted">Mooi runs it as one development task, with a QA task when it needs tests.</p>
            <Button size="sm" variant="secondary" disabled={busy}
              onClick={() => { void run(() => ensureIssueItem(projectId, entry.issue!.number)); }}>
              <Settings2 className="size-4" /> Configure how it runs
            </Button>
          </div>
        ) : null}

        {item ? (
          <section className="flex flex-col gap-2">
            <h3 className="text-xs font-semibold tracking-[0.08em] text-ink-subtle uppercase">Tasks</h3>
            <ul className="flex flex-col gap-2">
              {tasks.map((task) => {
                const category = taskStyle(task);
                const state = TASK_STATUS[taskState(task, tasks)];
                const open = openTask === task.id;
                const Icon = category.icon;
                const blocker = qaBlocker(task);
                const findings = qaFindings(task);
                const qaOrigin = task.fixOf ? tasks.find((candidate) => candidate.id === task.fixOf) : null;
                const findingTitle = qaOrigin
                  ? qaFindings(qaOrigin).find((finding) => finding.id === task.findingId)?.title
                  : null;
                return (
                  <li key={task.id} className={cn('overflow-hidden rounded-card border bg-surface', open ? 'border-line-strong shadow-raised' : 'border-line')}>
                    <div className="flex flex-wrap items-center gap-2 px-3 py-2.5 sm:flex-nowrap">
                      <button type="button" onClick={() => setOpenTask(open ? null : task.id)} aria-expanded={open}
                        className="flex min-w-0 flex-1 items-center gap-2.5 text-left">
                        <ChevronRight className={cn('size-4 shrink-0 text-ink-subtle transition-transform', open && 'rotate-90')} />
                        <span className={cn('inline-flex size-7 shrink-0 items-center justify-center rounded-chip border', category.chip)}
                          title={category.label}><Icon className="size-3.5" /></span>
                        <span className="flex min-w-0 flex-1 flex-col gap-1">
                          <span className={cn('truncate text-sm font-semibold text-ink', task.status === 'done' && 'text-ink-muted line-through decoration-ink-subtle')}>
                            {task.title}
                          </span>
                          {blocker ? (
                            <span title={blocker.cause} className="line-clamp-2 text-[11px] leading-relaxed text-warning">
                              {blocker.category ? `${QA_BLOCKER_LABEL[blocker.category]} blocker: ` : 'QA blocker: '}{blocker.cause}
                            </span>
                          ) : findings.length > 0 ? (
                            <span className="line-clamp-2 text-[11px] leading-relaxed text-danger">
                              {findings.map((finding) => `${finding.priority}: ${finding.title}`).join(' · ')}
                            </span>
                          ) : task.fixOf ? (
                            <span className="line-clamp-1 text-[11px] text-danger">
                              {findingTitle ? `Fixes: ${findingTitle}` : 'QA fix'}
                            </span>
                          ) : null}
                        </span>
                      </button>
                      {blocker ? (
                        <Badge tone="warning">
                          Blocked{blocker.category ? ` · ${QA_BLOCKER_LABEL[blocker.category]}` : ''}
                        </Badge>
                      ) : <Badge tone={state.tone}>{state.label}</Badge>}
                      {task.mergeSha ? (
                        <span className="inline-flex items-center gap-1.5">
                          <CommitLink repository={repository} sha={task.mergeSha} />
                          {task.deployedVersion ? <Badge tone="success">{task.deployedVersion}</Badge>
                            : deployment?.state === 'unknown' ? <Badge tone="neutral">Unknown</Badge>
                              : <Badge tone="neutral">Pending</Badge>}
                        </span>
                      ) : null}
                      {findings.length > 0 ? (
                        <Badge tone="danger">{findings.length} finding{findings.length === 1 ? '' : 's'}</Badge>
                      ) : null}
                      <TaskQuickControls task={task} connections={connections} editable={taskEditable(task) && !busy}
                        onUpdate={(update) => { void run(() => updateWorkTask(projectId, item.id, task.id, update)); }} />
                    </div>
                    {open ? (
                      <div className="animate-fade-in border-t border-line px-4 py-4">
                        <TaskEditor projectId={projectId} task={task} tasks={tasks} connections={connections} busy={busy}
                          onSave={(update) => run(() => updateWorkTask(projectId, item.id, task.id, update))}
                          onDelete={() => { void run(() => deleteWorkTask(projectId, item.id, task.id)); }}
                          onOpenTask={(target) => setOpenTask(target.id)} />
                      </div>
                    ) : null}
                  </li>
                );
              })}
            </ul>
          </section>
        ) : null}

        <Modal open={confirmReassign} title="Refresh model assignment"
          description="Check the latest provider quota and try this item's saved model assignment and alternatives again."
          onClose={() => setConfirmReassign(false)}
          footer={<>
            <Button variant="secondary" disabled={busy} onClick={() => setConfirmReassign(false)}>Cancel</Button>
            <Button variant="primary" disabled={busy} onClick={() => { void recalculateAssignments(); }}>
              <RefreshCw className="size-4" /> Refresh
            </Button>
          </>}>
          <p className="text-sm leading-relaxed text-ink-muted">
            Only tasks that have not started are considered. Running or finished tasks keep their assignment.
            This refreshes quota and uses the assignment expert's saved choices; it does not start work.
          </p>
        </Modal>

        {item && entry.kind === 'feature' && item.status !== 'running' ? (
          <div className="flex justify-end border-t border-line pt-3">
            {confirmDelete ? (
              <span className="flex items-center gap-2">
                <span className="text-sm text-ink-muted">Remove this feature and its tasks?</span>
                <Button size="sm" variant="ghost" onClick={() => setConfirmDelete(false)}>Keep</Button>
                <Button size="sm" variant="danger" disabled={busy} onClick={() => {
                  void run(async () => { await deleteWorkItem(projectId, item.id); onClose(); work.reload(); });
                }}>Remove</Button>
              </span>
            ) : (
              <Button size="sm" variant="ghost" className="text-danger hover:text-danger" onClick={() => setConfirmDelete(true)}>
                <Trash2 className="size-4" /> Remove feature
              </Button>
            )}
          </div>
        ) : null}
      </div>
    </Modal>
  );
};
