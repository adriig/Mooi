import { useEffect } from 'react';
import { TODO_PROJECT_SELECTION_STORAGE_KEY, useTodoProjectSelectionStore } from '@/stores/todoProjectSelectionStore';

/** Rehydrates the To-dos project filter when another browser window changes it. */
export const useTodoProjectSelectionStorageSync = (): void => {
  useEffect(() => {
    const onStorage = (event: StorageEvent) => {
      if (event.key === TODO_PROJECT_SELECTION_STORAGE_KEY) {
        void useTodoProjectSelectionStore.persist.rehydrate();
      }
    };

    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, []);
};
