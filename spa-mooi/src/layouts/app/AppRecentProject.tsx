import { NavLink } from 'react-router-dom';
import { projectPath } from '@/app/paths';
import { AppRecentSession } from '@/layouts/app/AppRecentSession';
import type { RecentProject } from '@/layouts/app/types/RecentProject';
import { Initials } from '@/shared/components/Initials';
import { cn } from '@/shared/utils/cn';

interface AppRecentProjectProps {
  entry: RecentProject;
  onNavigate?: () => void;
}

/** A Recent project and, threaded beneath it, the conversations it still has open. */
export const AppRecentProject = ({ entry, onNavigate }: AppRecentProjectProps) => (
  <div>
    <NavLink
      to={projectPath(entry.project.id)}
      end
      onClick={onNavigate}
      className={({ isActive }) => cn(
        'flex h-9 items-center gap-2.5 rounded-control px-2.5 text-[13px] font-medium transition-colors duration-150 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand max-lg:h-11',
        isActive ? 'bg-surface text-ink shadow-card ring-1 ring-line' : 'text-ink-muted hover:bg-surface/60 hover:text-ink',
      )}
    >
      <Initials value={entry.project.name} size="sm" />
      <span className="min-w-0 flex-1 truncate">{entry.project.name}</span>
      {entry.sessionCount > 0 ? (
        <span className="shrink-0 text-[10px] font-semibold text-ink-subtle tabular-nums"
          aria-label={`${entry.sessionCount} open ${entry.sessionCount === 1 ? 'conversation' : 'conversations'}`}>
          {entry.sessionCount}
        </span>
      ) : null}
    </NavLink>

    {entry.sessionCount > 0 ? (
      <ul aria-label={`Open conversations in ${entry.project.name}`}
        className="mt-0.5 mb-1 ml-[22px] flex flex-col gap-0.5 border-l border-line pl-1.5">
        {entry.sessions.map((session) => (
          <AppRecentSession key={session.id} session={session} task={entry.taskBySessionId[session.id]} onNavigate={onNavigate} />
        ))}
        {entry.features.map(({ feature, sessions }) => (
          <li key={feature.id} className="mt-1 first:mt-0">
            <div className="truncate px-2.5 py-1 text-[10px] font-semibold uppercase tracking-wide text-ink-subtle"
              title={feature.title}>
              {feature.title}
            </div>
            <ul aria-label={`Sessions for ${feature.title}`} className="flex flex-col gap-0.5">
              {sessions.map((session) => (
                <AppRecentSession key={session.id} session={session} task={entry.taskBySessionId[session.id]} onNavigate={onNavigate} />
              ))}
            </ul>
          </li>
        ))}
      </ul>
    ) : null}
  </div>
);
