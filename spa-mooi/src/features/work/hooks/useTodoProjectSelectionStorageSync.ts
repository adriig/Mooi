import { useEffect } from 'react';
import { TODO_PROJECT_SELECTION_STORAGE_KEY, useTodoProjectSelectionStore } from '@/stores/todoProjectSelectionStore';

/** Rehydrates the Work project filter after another window changes it or this window resumes. */
export const useTodoProjectSelectionStorageSync = (): void => {
  useEffect(() => {
    const refresh = () => {
      void useTodoProjectSelectionStore.persist.rehydrate();
    };
    const onStorage = (event: StorageEvent) => {
      if (event.key === TODO_PROJECT_SELECTION_STORAGE_KEY) {
        refresh();
      }
    };
    const onVisibilityChange = () => {
      if (document.visibilityState === 'visible') refresh();
    };

    window.addEventListener('storage', onStorage);
    window.addEventListener('focus', refresh);
    window.addEventListener('pageshow', refresh);
    document.addEventListener('visibilitychange', onVisibilityChange);
    return () => {
      window.removeEventListener('storage', onStorage);
      window.removeEventListener('focus', refresh);
      window.removeEventListener('pageshow', refresh);
      document.removeEventListener('visibilitychange', onVisibilityChange);
    };
  }, []);
};
