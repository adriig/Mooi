import { useEffect } from 'react';
import { useAuthStore } from '@/stores/authStore';
import { useProjectsStore } from '@/stores/projectsStore';
import { SELECTED_PROJECT_STORAGE_KEY, useSelectedProjectStore } from '@/stores/selectedProjectStore';

/**
 * Clears a remembered project that is no longer among the synced ones, and follows the selection
 * when another window of this device changes it.
 */
export const useSelectedProjectSync = (): void => {
  const playerId = useAuthStore((state) => state.player?.id);
  const projects = useProjectsStore((state) => state.projects);
  const status = useProjectsStore((state) => state.status);
  const stored = useSelectedProjectStore((state) => (playerId ? state.selections[playerId] : undefined));

  useEffect(() => {
    if (!playerId || !stored || status !== 'ready') return;
    if (!projects.some((project) => project.id === stored)) {
      useSelectedProjectStore.getState().select(playerId, null);
    }
  }, [playerId, projects, status, stored]);

  useEffect(() => {
    const onStorage = (event: StorageEvent) => {
      if (event.key === SELECTED_PROJECT_STORAGE_KEY) void useSelectedProjectStore.persist.rehydrate();
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, []);
};
