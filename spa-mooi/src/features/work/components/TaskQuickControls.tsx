import { useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Bot, ChevronDown, FlaskConical } from 'lucide-react';
import type { AgentConnection } from '@/features/agents/types/AgentConnection';
import { TaskAssignmentFields } from '@/features/work/components/TaskAssignmentFields';
import { accountName, assignmentAccount } from '@/features/work/lib/assignmentAccount';
import type { WorkTask } from '@/features/work/types/WorkTask';
import type { WorkTaskUpdate } from '@/features/work/types/WorkTaskUpdate';
import { useAnchoredPlacement } from '@/shared/hooks/useAnchoredPlacement';
import { useOnClickOutside } from '@/shared/hooks/useOnClickOutside';
import { useOnEscape } from '@/shared/hooks/useOnEscape';
import { cn } from '@/shared/utils/cn';

interface TaskQuickControlsProps {
  task: WorkTask;
  connections: AgentConnection[];
  editable: boolean;
  onUpdate: (update: WorkTaskUpdate) => void;
  className?: string;
}

const toggle = 'nodrag inline-flex size-7 shrink-0 items-center justify-center rounded-chip border transition focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand disabled:cursor-not-allowed';

/**
 * Auto-merge (the robot), tests and who runs the task, changeable without opening the task: the
 * Board's nodes and the breakdown's rows both carry them. A QA task has no merge and no tests of
 * its own, so it only shows who runs it.
 */
export const TaskQuickControls = ({ task, connections, editable, onUpdate, className }: TaskQuickControlsProps) => {
  const [open, setOpen] = useState(false);
  const anchor = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  const placement = useAnchoredPlacement(open, anchor, 320);
  useOnClickOutside(panel, () => setOpen(false), open);
  useOnEscape(() => setOpen(false), open);
  const account = assignmentAccount(task.assignment, connections);
  const qa = task.qaOf !== null;
  const model = task.assignment.model ?? 'Default model';

  return (
    <div className={cn('flex min-w-0 items-center gap-1.5', className)}>
      {!qa ? (
        <>
          <button type="button" disabled={!editable} aria-pressed={task.autoMerge}
            title={task.autoMerge ? 'Auto-merge on: merged as soon as it is done' : 'Auto-merge off: waits for your merge'}
            aria-label="Auto-merge" onClick={(event) => { event.stopPropagation(); onUpdate({ autoMerge: !task.autoMerge }); }}
            className={cn(toggle, task.autoMerge ? 'border-brand/40 bg-brand-soft text-brand-strong' : 'border-line bg-surface text-ink-subtle hover:text-ink')}>
            <Bot className="size-3.5" />
          </button>
          <button type="button" disabled={!editable} aria-pressed={task.tests}
            title={task.tests ? 'Tests on: a QA task checks it after its merge' : 'Tests off'}
            aria-label="Tests" onClick={(event) => { event.stopPropagation(); onUpdate({ tests: !task.tests }); }}
            className={cn(toggle, task.tests ? 'border-sky-500/40 bg-sky-500/12 text-sky-700 dark:text-sky-300' : 'border-line bg-surface text-ink-subtle hover:text-ink')}>
            <FlaskConical className="size-3.5" />
          </button>
        </>
      ) : null}
      <button ref={anchor} type="button" disabled={!editable} aria-expanded={open}
        title={`${account ? accountName(account) : 'No account'} · ${model}${task.assignment.effort ? ` · ${task.assignment.effort}` : ''}`}
        // The pointer going down here must not count as outside the panel, or a click would close and reopen it.
        onPointerDown={(event) => event.stopPropagation()}
        onClick={(event) => { event.stopPropagation(); setOpen((value) => !value); }}
        className="nodrag inline-flex h-7 min-w-0 items-center gap-1 rounded-chip border border-line bg-surface px-2 text-[11px] font-medium text-ink-muted transition hover:border-line-strong hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand disabled:cursor-default disabled:hover:border-line">
        <span className="truncate">{account ? account.label : 'Default'} · {model}</span>
        {task.assignment.effort ? <span className="shrink-0 text-ink-subtle">· {task.assignment.effort}</span> : null}
        {editable ? <ChevronDown className="size-3 shrink-0" /> : null}
      </button>
      {open ? createPortal(
        <div ref={panel} role="dialog" aria-label="Who runs this task" onClick={(event) => event.stopPropagation()}
          style={{ position: 'fixed', top: placement.top, left: Math.min(placement.left, window.innerWidth - 300), width: 288 }}
          className="z-50 animate-menu-in rounded-xl border border-line bg-surface p-3 shadow-pop">
          <TaskAssignmentFields compact value={task.assignment} connections={connections}
            onChange={(assignment) => onUpdate({ assignment })} />
        </div>,
        anchor.current?.closest('dialog') ?? document.body,
      ) : null}
    </div>
  );
};
