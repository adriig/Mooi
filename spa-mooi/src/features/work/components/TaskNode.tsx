import { memo } from 'react';
import { Handle, Position, type Node, type NodeProps } from '@xyflow/react';
import { AlertCircle, CheckCircle2, Clock, GitMerge, Loader2, Lock, RotateCcw } from 'lucide-react';
import type { AgentConnection } from '@/features/agents/types/AgentConnection';
import { TaskQuickControls } from '@/features/work/components/TaskQuickControls';
import { NODE_HEIGHT, NODE_WIDTH } from '@/features/work/lib/layoutTaskGraph';
import { QA_BLOCKER_LABEL, qaBlocker, qaFindings } from '@/features/work/lib/qaTaskState';
import { taskStyle } from '@/features/work/lib/taskCategories';
import { TASK_STATUS, taskEditable, taskState } from '@/features/work/lib/workStatus';
import type { WorkTask } from '@/features/work/types/WorkTask';
import type { WorkTaskUpdate } from '@/features/work/types/WorkTaskUpdate';
import { Button } from '@/shared/components/Button';
import { cn } from '@/shared/utils/cn';

export type TaskNodeData = {
  task: WorkTask;
  tasks: WorkTask[];
  connections: AgentConnection[];
  busy: boolean;
  onUpdate: (task: WorkTask, update: WorkTaskUpdate) => void;
  onOpen: (task: WorkTask) => void;
  onRetry: (task: WorkTask) => void;
};

export type TaskFlowNode = Node<TaskNodeData, 'task'>;

const STATE_ICON = {
  planned: Clock, blocked: Lock, queued: Clock, running: Loader2, merging: GitMerge, review: GitMerge,
  attention: AlertCircle, done: CheckCircle2,
};

/**
 * One task on the Board, tinted by its category or red for a QA defect. Auto-merge, tests and who runs it change right on
 * the node; a click opens the whole task. Finished tasks keep their place, checked and struck through.
 */
export const TaskNode = memo(({ data }: NodeProps<TaskFlowNode>) => {
  const { task, tasks, connections, busy, onUpdate, onOpen, onRetry } = data;
  const category = taskStyle(task);
  const state = taskState(task, tasks);
  const status = TASK_STATUS[state];
  const StateIcon = STATE_ICON[state];
  const CategoryIcon = category.icon;
  const done = task.status === 'done';
  const findings = qaFindings(task);
  const blocker = qaBlocker(task);
  const qaOrigin = task.fixOf ? tasks.find((candidate) => candidate.id === task.fixOf) : null;
  const findingTitle = qaOrigin
    ? qaFindings(qaOrigin).find((finding) => finding.id === task.findingId)?.title
    : null;

  return (
    <div style={{ width: NODE_WIDTH, minHeight: NODE_HEIGHT }}
      className={cn('flex cursor-pointer flex-col gap-2 rounded-card border-2 bg-surface p-3 shadow-card transition hover:shadow-raised',
        category.node, done && 'opacity-75', state === 'attention' && 'ring-2 ring-danger/50')}>
      <Handle type="target" position={Position.Left} className="!size-2 !border-0 !bg-line-strong" />
      <button type="button" onClick={() => onOpen(task)}
        aria-label={`Open task ${task.title}${findings.length ? `, ${findings.length} QA findings` : ''}${blocker ? ', QA blocked' : ''}`}
        className="flex min-w-0 flex-1 flex-col gap-2 rounded-control text-left focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand">
        <span className="flex items-center gap-1.5">
          <span className={cn('inline-flex h-5 items-center gap-1 rounded-full border px-1.5 text-[10px] font-semibold', category.chip)}>
            <CategoryIcon className="size-3" /> {category.label}
          </span>
          <span className={cn('ml-auto inline-flex items-center gap-1 text-[10.5px] font-semibold',
            { neutral: 'text-ink-subtle', info: 'text-info', success: 'text-success', warning: 'text-warning', danger: 'text-danger', accent: 'text-accent', brand: 'text-brand' }[status.tone])}>
            <StateIcon className={cn('size-3.5', state === 'running' && 'animate-spin')} />
            {status.label}
          </span>
        </span>
        <span className={cn('line-clamp-2 text-[13px] leading-snug font-semibold text-ink', done && 'text-ink-muted line-through decoration-ink-subtle')}>
          {done ? <CheckCircle2 className="mr-1 inline size-3.5 -translate-y-px text-success" /> : null}
          {task.title}
        </span>
        {(task.segments ?? 0) > 0 ? (
          <span className="text-[10.5px] text-ink-subtle">Segment {(task.segments ?? 0) + 1}</span>
        ) : null}
        {task.report && (task.contextHandoff || state === 'attention') ? (
          <span title={task.report} className="line-clamp-1 text-[10.5px] text-warning">{task.report}</span>
        ) : null}
        {blocker ? (
          <span title={blocker.cause} className="line-clamp-1 text-[10.5px] leading-snug text-warning">
            Blocked · {blocker.category ? `${QA_BLOCKER_LABEL[blocker.category]} · ` : ''}{blocker.cause}
          </span>
        ) : findings.length > 0 ? (
          <span className="text-[10.5px] font-semibold text-danger">
            {findings.length} QA finding{findings.length === 1 ? '' : 's'} · {findings[0].priority}: {findings[0].title}
          </span>
        ) : findingTitle ? (
          <span className="line-clamp-1 text-[10.5px] text-danger">Fixes: {findingTitle}</span>
        ) : null}
      </button>
      <TaskQuickControls task={task} connections={connections} editable={taskEditable(task) && !busy}
        onUpdate={(update) => onUpdate(task, update)} className="mt-auto" />
      {task.status === 'attention' && task.sessionId === null ? (
        <div className="flex justify-end" onClick={(event) => event.stopPropagation()}>
          <Button size="sm" variant="secondary" disabled={busy} onClick={() => onRetry(task)}
            ariaLabel="Restart this task">
            <RotateCcw className="size-3.5" /> Restart
          </Button>
        </div>
      ) : null}
      <Handle type="source" position={Position.Right} className="!size-2 !border-0 !bg-line-strong" />
    </div>
  );
});

TaskNode.displayName = 'TaskNode';
