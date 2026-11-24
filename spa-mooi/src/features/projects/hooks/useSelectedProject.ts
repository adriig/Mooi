import { useCallback } from 'react';
import type { Project } from '@/features/projects/types/Project';
import { useAuthStore } from '@/stores/authStore';
import { useProjectsStore } from '@/stores/projectsStore';
import { useSelectedProjectStore } from '@/stores/selectedProjectStore';

interface SelectedProject {
  /** Null shows all projects. Only ever a project of the synced list once it has loaded. */
  projectId: string | null;
  project: Project | null;
  /** Null selects all projects. */
  select: (projectId: string | null) => void;
}

/** The project chosen in the shell selector, for pages that narrow themselves to it. */
export const useSelectedProject = (): SelectedProject => {
  const playerId = useAuthStore((state) => state.player?.id);
  const stored = useSelectedProjectStore((state) => (playerId ? state.selections[playerId] : undefined));
  const project = useProjectsStore((state) => state.projects.find((candidate) => candidate.id === stored)) ?? null;
  const choose = useSelectedProjectStore((state) => state.select);
  const select = useCallback((projectId: string | null) => {
    if (playerId) choose(playerId, projectId);
  }, [choose, playerId]);

  return { projectId: project?.id ?? null, project, select };
};
