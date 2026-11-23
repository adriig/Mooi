import { useCallback, useEffect, useState } from 'react';
import { fetchWorkSettings, saveWorkSettings } from '@/features/work/api/workApi';
import type { WorkSettings } from '@/features/work/types/WorkSettings';
import { useLiveStore } from '@/stores/liveStore';

/** The project's factory limits, read when `enabled` and kept live from any side that changes them. */
export const useWorkSettings = (projectId: string, enabled: boolean) => {
  const settings = useLiveStore((live) => live.workSettings[projectId] ?? null);
  const epoch = useLiveStore((live) => live.epoch);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!enabled) return undefined;
    let active = true;
    fetchWorkSettings(projectId)
      .then((value) => { if (active) { useLiveStore.getState().adoptWorkSettings(projectId, value); setError(null); } })
      .catch((failure: Error) => { if (active) setError(failure.message); });
    return () => { active = false; };
  }, [projectId, enabled, epoch]);

  const save = useCallback(async (value: WorkSettings): Promise<boolean> => {
    setSaving(true);
    setError(null);
    try {
      useLiveStore.getState().adoptWorkSettings(projectId, await saveWorkSettings(projectId, value));
      return true;
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not save the settings.');
      return false;
    } finally {
      setSaving(false);
    }
  }, [projectId]);

  return { settings, error, saving, save };
};
