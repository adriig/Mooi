import { Link, useLocation } from 'react-router-dom';
import { CircleCheck } from 'lucide-react';
import { sessionChatLabel } from '@/features/sessions/lib/sessionChatLabel';
import { sessionChatPath } from '@/features/sessions/lib/sessionChatPath';
import { sessionKindIcon } from '@/features/sessions/lib/sessionKindIcon';
import { sessionStatusLabel, sessionStatusPulses, sessionStatusTone } from '@/features/sessions/lib/sessionStatusTone';
import type { Session } from '@/features/sessions/types/Session';
import { TASK_CATEGORIES, taskStyle } from '@/features/work/lib/taskCategories';
import type { WorkTask } from '@/features/work/types/WorkTask';
import { StatusDot } from '@/shared/components/StatusDot';
import { cn } from '@/shared/utils/cn';

interface AppRecentSessionProps {
  session: Session;
  task?: WorkTask;
  onNavigate?: () => void;
}

/** One open conversation under its project: what it is, and what its agent is doing right now. */
export const AppRecentSession = ({ session, task, onNavigate }: AppRecentSessionProps) => {
  const { pathname, search } = useLocation();
  const to = sessionChatPath(session);
  const [path, query] = to.split('?');
  const active = pathname === path && (!query || search.includes(query));
  const taskCategory = task ? taskStyle(task) : TASK_CATEGORIES.dev;
  const Icon = session.kind === 'session' ? taskCategory.icon : sessionKindIcon(session.kind);
  const iconLabel = session.kind === 'session' ? taskCategory.label : session.kind;
  const label = sessionChatLabel(session);
  const blocked = session.status === 'waiting';
  const status = blocked ? 'Blocked' : sessionStatusLabel(session.status);

  return (
    <li>
      <Link
        to={to}
        onClick={onNavigate}
        title={`${label} · ${status}`}
        aria-current={active ? 'page' : undefined}
        className={cn(
          'flex min-h-11 items-center gap-2.5 rounded-control py-1.5 pr-2.5 pl-2.5 transition-colors duration-150 lg:min-h-10 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand',
          active ? 'bg-surface text-ink shadow-card ring-1 ring-line'
            : blocked ? 'bg-danger-soft/50 text-ink hover:bg-danger-soft'
              : 'text-ink-muted hover:bg-surface/60 hover:text-ink',
        )}
      >
        <span className="shrink-0" title={iconLabel} aria-hidden>
          <Icon className="size-3.5 text-ink-subtle" />
        </span>
        <span className="min-w-0 flex-1">
          <span className={cn('block truncate text-xs font-medium', session.kind === 'session' && 'font-mono')}>{label}</span>
          <span className={cn('mt-0.5 flex items-center gap-1.5 text-[11px] font-medium',
            blocked ? 'text-danger' : 'text-ink-subtle')}>
            {blocked
              ? <CircleCheck className="size-3.5 shrink-0" aria-hidden />
              : <StatusDot tone={sessionStatusTone(session.status)} pulse={sessionStatusPulses(session.status)} />}
            <span className="truncate">{status}</span>
          </span>
        </span>
      </Link>
    </li>
  );
};
