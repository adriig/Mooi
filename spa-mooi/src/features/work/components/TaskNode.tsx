import { memo } from 'react';
import { Handle, Position, type Node, type NodeProps } from '@xyflow/react';
import { AlertCircle, CheckCircle2, Clock, GitMerge, Loader2, Lock, Wrench } from 'lucide-react';
import type { AgentConnection } from '@/features/agents/types/AgentConnection';
import { TaskQuickControls } from '@/features/work/components/TaskQuickControls';
import { NODE_HEIGHT, NODE_WIDTH } from '@/features/work/lib/layoutTaskGraph';
import { TASK_CATEGORIES } from '@/features/work/lib/taskCategories';
import { TASK_STATUS, taskEditable, taskState } from '@/features/work/lib/workStatus';
import type { WorkTask } from '@/features/work/types/WorkTask';
import type { WorkTaskUpdate } from '@/features/work/types/WorkTaskUpdate';
import { cn } from '@/shared/utils/cn';

export type TaskNodeData = {
  task: WorkTask;
  tasks: WorkTask[];
  connections: AgentConnection[];
  busy: boolean;
  onUpdate: (task: WorkTask, update: WorkTaskUpdate) => void;
  onOpen: (task: WorkTask) => void;
};

export type TaskFlowNode = Node<TaskNodeData, 'task'>;

const STATE_ICON = {
  planned: Clock, blocked: Lock, queued: Clock, running: Loader2, merging: GitMerge, review: GitMerge,
  attention: AlertCircle, done: CheckCircle2,
};

/**
 * One task on the Board, tinted by its category. Auto-merge, tests and who runs it change right on
 * the node; a click opens the whole task. Finished tasks keep their place, checked and struck through.
 */
export const TaskNode = memo(({ data }: NodeProps<TaskFlowNode>) => {
  const { task, tasks, connections, busy, onUpdate, onOpen } = data;
  const category = TASK_CATEGORIES[task.category];
  const state = taskState(task, tasks);
  const status = TASK_STATUS[state];
  const StateIcon = STATE_ICON[state];
  const CategoryIcon = category.icon;
  const done = task.status === 'done';

  return (
    <div style={{ width: NODE_WIDTH, minHeight: NODE_HEIGHT }} onClick={() => onOpen(task)}
      className={cn('flex cursor-pointer flex-col gap-2 rounded-card border-2 bg-surface p-3 shadow-card transition hover:shadow-raised',
        category.node, done && 'opacity-75', state === 'attention' && 'ring-2 ring-danger/50')}>
      <Handle type="target" position={Position.Left} className="!size-2 !border-0 !bg-line-strong" />
      <div className="flex items-center gap-1.5">
        <span className={cn('inline-flex h-5 items-center gap-1 rounded-full border px-1.5 text-[10px] font-semibold', category.chip)}>
          <CategoryIcon className="size-3" /> {category.label}
        </span>
        {task.fixOf ? (
          <span className="inline-flex h-5 items-center gap-1 rounded-full border border-danger/30 bg-danger-soft px-1.5 text-[10px] font-semibold text-danger">
            <Wrench className="size-3" /> Fix
          </span>
        ) : null}
        <span className={cn('ml-auto inline-flex items-center gap-1 text-[10.5px] font-semibold',
          { neutral: 'text-ink-subtle', info: 'text-info', success: 'text-success', warning: 'text-warning', danger: 'text-danger', accent: 'text-accent', brand: 'text-brand' }[status.tone])}>
          <StateIcon className={cn('size-3.5', state === 'running' && 'animate-spin')} />
          {status.label}
        </span>
      </div>
      <p className={cn('line-clamp-2 text-[13px] leading-snug font-semibold text-ink', done && 'text-ink-muted line-through decoration-ink-subtle')}>
        {done ? <CheckCircle2 className="mr-1 inline size-3.5 -translate-y-px text-success" /> : null}
        {task.title}
      </p>
      <TaskQuickControls task={task} connections={connections} editable={taskEditable(task) && !busy}
        onUpdate={(update) => onUpdate(task, update)} className="mt-auto" />
      <Handle type="source" position={Position.Right} className="!size-2 !border-0 !bg-line-strong" />
    </div>
  );
});

TaskNode.displayName = 'TaskNode';
