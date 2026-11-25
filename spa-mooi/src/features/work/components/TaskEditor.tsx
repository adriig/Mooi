import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowUpRight, Trash2 } from 'lucide-react';
import { sessionPath } from '@/app/paths';
import type { AgentConnection } from '@/features/agents/types/AgentConnection';
import { QaTaskDetails } from '@/features/work/components/QaTaskDetails';
import { TaskAssignmentFields } from '@/features/work/components/TaskAssignmentFields';
import { EDITABLE_CATEGORIES, TASK_CATEGORIES } from '@/features/work/lib/taskCategories';
import { TASK_STATUS, taskEditable, taskState } from '@/features/work/lib/workStatus';
import type { WorkAssignment } from '@/features/work/types/WorkAssignment';
import type { WorkTask } from '@/features/work/types/WorkTask';
import type { WorkTaskCategory } from '@/features/work/types/WorkTaskCategory';
import type { WorkTaskUpdate } from '@/features/work/types/WorkTaskUpdate';
import { Badge } from '@/shared/components/Badge';
import { Button } from '@/shared/components/Button';
import { cn } from '@/shared/utils/cn';

interface TaskEditorProps {
  projectId: string;
  task: WorkTask;
  tasks: WorkTask[];
  connections: AgentConnection[];
  busy: boolean;
  onSave: (update: WorkTaskUpdate) => Promise<unknown>;
  onDelete: () => void;
  onOpenTask: (task: WorkTask) => void;
}

const input = 'w-full min-w-0 rounded-control border border-line bg-surface-2 px-3 text-sm text-ink outline-none transition focus:border-brand disabled:cursor-not-allowed disabled:opacity-70';

const sameAssignment = (a: WorkAssignment, b: WorkAssignment) =>
  a.provider === b.provider && a.connectionId === b.connectionId && a.model === b.model && a.effort === b.effort;

/** Ids of the tasks that wait, directly or not, for `taskId`: picking one as a dependency would loop. */
const dependants = (taskId: string, tasks: WorkTask[]): Set<string> => {
  const found = new Set<string>();
  const queue = [taskId];
  while (queue.length > 0) {
    const current = queue.shift();
    tasks.forEach((task) => {
      if (current && task.dependsOn.includes(current) && !found.has(task.id)) {
        found.add(task.id);
        queue.push(task.id);
      }
    });
  }
  return found;
};

/**
 * Everything about one task, as the orchestrator prefilled it: what to do, how to check it, who
 * runs it and what it waits for. Editable until its session starts; afterwards it shows how it went.
 */
export const TaskEditor = ({ projectId, task, tasks, connections, busy, onSave, onDelete, onOpenTask }: TaskEditorProps) => {
  const editable = taskEditable(task);
  const qa = task.qaOf !== null;
  const [title, setTitle] = useState(task.title);
  const [category, setCategory] = useState<WorkTaskCategory>(task.category);
  const [description, setDescription] = useState(task.description);
  const [acceptance, setAcceptance] = useState(task.acceptance);
  const [assignment, setAssignment] = useState<WorkAssignment>(task.assignment);
  const [dependsOn, setDependsOn] = useState<string[]>(task.dependsOn);

  // A change made elsewhere (another device, an agent, the quick controls) resets the draft.
  useEffect(() => {
    setTitle(task.title);
    setCategory(task.category);
    setDescription(task.description);
    setAcceptance(task.acceptance);
    setAssignment(task.assignment);
    setDependsOn(task.dependsOn);
  }, [task]);

  const excluded = useMemo(() => dependants(task.id, tasks), [task.id, tasks]);
  const candidates = tasks.filter((other) => other.id !== task.id && !excluded.has(other.id)
    && !(qa && other.id === task.qaOf));

  const update: WorkTaskUpdate = {
    ...(title.trim() !== task.title ? { title: title.trim() } : {}),
    ...(category !== task.category ? { category } : {}),
    ...(description !== task.description ? { description } : {}),
    ...(acceptance !== task.acceptance ? { acceptance } : {}),
    ...(!sameAssignment(assignment, task.assignment) ? { assignment } : {}),
    ...(dependsOn.join() !== task.dependsOn.join() ? { dependsOn: dependsOn.filter((id) => id !== task.qaOf) } : {}),
  };
  const dirty = Object.keys(update).length > 0;
  const state = TASK_STATUS[taskState(task, tasks)];
  const attempts = task.attempts - (task.attemptsBase ?? 0);

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone={state.tone}>{state.label}</Badge>
        {attempts > 0 ? <span className="text-xs text-ink-subtle">Attempt {attempts + 1}</span> : null}
        {(task.segments ?? 0) > 0 ? <span className="text-xs text-ink-subtle">Segment {(task.segments ?? 0) + 1}</span> : null}
        {task.sessionId ? (
          <Link to={sessionPath(projectId, task.sessionId)}
            className="inline-flex items-center gap-1 text-xs font-semibold text-brand underline-offset-4 hover:underline">
            Open its session <ArrowUpRight className="size-3.5" />
          </Link>
        ) : null}
      </div>
      {task.report && (!qa || task.contextHandoff) ? (
        <p className={cn('rounded-control border px-3 py-2 text-[13px] leading-relaxed whitespace-pre-line',
          task.status === 'attention' ? 'border-danger/30 bg-danger-soft text-ink' : 'border-line bg-surface-2 text-ink-muted')}>
          {task.report}
        </p>
      ) : null}
      <QaTaskDetails task={task} tasks={tasks} onOpenTask={onOpenTask} />

      <div className="grid gap-3 sm:grid-cols-[1fr_12rem]">
        <label className="flex min-w-0 flex-col gap-1.5">
          <span className="text-xs font-medium text-ink-muted">Title</span>
          <input className={cn(input, 'h-10')} value={title} disabled={!editable} maxLength={300}
            onChange={(event) => setTitle(event.target.value)} />
        </label>
        <label className="flex min-w-0 flex-col gap-1.5">
          <span className="text-xs font-medium text-ink-muted">Category</span>
          <select className={cn(input, 'h-10')} value={category} disabled={!editable || qa}
            onChange={(event) => setCategory(event.target.value as WorkTaskCategory)}>
            {(qa ? ['qa' as const] : EDITABLE_CATEGORIES).map((id) => (
              <option key={id} value={id}>{TASK_CATEGORIES[id].label}</option>
            ))}
          </select>
        </label>
      </div>

      <label className="flex flex-col gap-1.5">
        <span className="text-xs font-medium text-ink-muted">{qa ? 'What to check' : 'Description'}</span>
        <textarea className={cn(input, 'min-h-28 py-2 leading-relaxed')} value={description} disabled={!editable}
          onChange={(event) => setDescription(event.target.value)} />
      </label>
      <label className="flex flex-col gap-1.5">
        <span className="text-xs font-medium text-ink-muted">Acceptance criteria</span>
        <textarea className={cn(input, 'min-h-20 py-2 leading-relaxed')} value={acceptance} disabled={!editable}
          onChange={(event) => setAcceptance(event.target.value)} />
      </label>

      <div className="flex flex-col gap-1.5">
        <span className="text-xs font-medium text-ink-muted">Runs with</span>
        <TaskAssignmentFields value={assignment} connections={connections} disabled={!editable} onChange={setAssignment} />
      </div>

      {candidates.length > 0 ? (
        <fieldset className="flex flex-col gap-1.5">
          <legend className="mb-1.5 text-xs font-medium text-ink-muted">Waits for</legend>
          <div className="flex flex-wrap gap-1.5">
            {candidates.map((other) => {
              const checked = dependsOn.includes(other.id);
              const style = TASK_CATEGORIES[other.category];
              return (
                <label key={other.id} className={cn('inline-flex max-w-full cursor-pointer items-center gap-2 rounded-chip border px-2.5 py-1.5 text-[12px] font-medium transition',
                  checked ? style.chip : 'border-line bg-surface text-ink-muted hover:text-ink', !editable && 'cursor-not-allowed opacity-70')}>
                  <input type="checkbox" className="size-3.5 accent-brand" checked={checked} disabled={!editable}
                    onChange={() => setDependsOn(checked ? dependsOn.filter((id) => id !== other.id) : [...dependsOn, other.id])} />
                  <span className="truncate">{other.title}</span>
                </label>
              );
            })}
          </div>
        </fieldset>
      ) : null}

      {editable ? (
        <div className="flex flex-wrap items-center justify-between gap-2 border-t border-line pt-3">
          <Button variant="ghost" size="sm" onClick={onDelete} disabled={busy} className="text-danger hover:text-danger">
            <Trash2 className="size-4" /> Remove task
          </Button>
          <div className="flex gap-2">
            <Button variant="secondary" size="sm" disabled={!dirty || busy} onClick={() => {
              setTitle(task.title); setCategory(task.category); setDescription(task.description);
              setAcceptance(task.acceptance); setAssignment(task.assignment); setDependsOn(task.dependsOn);
            }}>Discard</Button>
            <Button size="sm" disabled={!dirty || busy || !title.trim()} onClick={() => { void onSave(update); }}>Save task</Button>
          </div>
        </div>
      ) : null}
    </div>
  );
};
