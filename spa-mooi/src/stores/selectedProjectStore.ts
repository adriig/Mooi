import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { env } from '@/config/env';

interface SelectedProjectState {
  /** The project chosen in the shell, per signed-in player; absent means all projects. */
  selections: Record<string, string | undefined>;
  select: (playerId: string, projectId: string | null) => void;
}

export const SELECTED_PROJECT_STORAGE_KEY = `${env.storagePrefix}:selected-project`;

/** Interface state of this device, not an account preference; kept apart for each player. */
export const useSelectedProjectStore = create<SelectedProjectState>()(
  persist(
    (set) => ({
      selections: {},
      select: (playerId, projectId) => set((state) => ({
        selections: { ...state.selections, [playerId]: projectId ?? undefined },
      })),
    }),
    { name: SELECTED_PROJECT_STORAGE_KEY },
  ),
);
