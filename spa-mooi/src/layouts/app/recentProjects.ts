import type { Project } from '@/features/projects/types/Project';
import type { Session } from '@/features/sessions/types/Session';
import type { ProjectWork } from '@/features/work/types/ProjectWork';
import type { RecentProject } from '@/layouts/app/types/RecentProject';

/**
 * Projects with open conversations always make the list, ahead of the rest and never cut by the
 * limit; quiet projects only fill what is left of it. Both keep the store's order, so a project
 * does not jump around while its agent works.
 */
export const recentProjects = (
  projects: Project[],
  sessions: Session[],
  limit: number,
  work: Record<string, ProjectWork>,
): RecentProject[] => {
  const open = sessions.filter((session) => session.status !== 'closed');
  const entries = projects.map((project) => {
    const projectSessions = open.filter((session) => session.projectId === project.id);
    const taskBySessionId = Object.fromEntries(
      (work[project.id]?.items ?? []).flatMap((item) =>
        item.tasks.flatMap((task) => task.sessionId ? [[task.sessionId, task] as const] : []),
      ),
    );
    const featureSessionIds = new Set<string>();
    const features = (work[project.id]?.items ?? [])
      .filter((item) => item.kind === 'feature')
      .flatMap((feature) => {
        const taskSessionIds = new Set(feature.tasks.flatMap((task) => task.sessionId ? [task.sessionId] : []));
        const sessionsForFeature = projectSessions.filter((session) => taskSessionIds.has(session.id));
        sessionsForFeature.forEach((session) => featureSessionIds.add(session.id));
        return sessionsForFeature.length > 0 ? [{ feature, sessions: sessionsForFeature }] : [];
      });
    return {
      project,
      sessions: projectSessions.filter((session) => !featureSessionIds.has(session.id)),
      features,
      taskBySessionId,
      sessionCount: projectSessions.length,
    };
  });
  const active = entries.filter((entry) => entry.sessionCount > 0);
  const quiet = entries.filter((entry) => entry.sessionCount === 0);

  return [...active, ...quiet.slice(0, Math.max(0, limit - active.length))];
};
