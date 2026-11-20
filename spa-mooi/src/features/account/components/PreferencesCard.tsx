import { useState } from 'react';
import { MoonStar, Sun } from 'lucide-react';
import { authenticatedFetch } from '@/features/auth/lib/authenticatedFetch';
import type { AuthPlayer } from '@/features/auth/types/AuthPlayer';
import { useAuthStore } from '@/stores/authStore';
import { Card } from '@/shared/components/Card';
import { SegmentedControl } from '@/shared/components/SegmentedControl';
import type { SegmentItem } from '@/shared/types/SegmentItem';
import type { Theme } from '@/shared/types/Theme';
import { useThemeStore } from '@/stores/themeStore';

const themes: SegmentItem[] = [
  { id: 'light', label: 'Light', icon: Sun },
  { id: 'dark', label: 'Dark', icon: MoonStar },
];

export const PreferencesCard = () => {
  const theme = useThemeStore((state) => state.theme);
  const setTheme = useThemeStore((state) => state.setTheme);
  const player = useAuthStore((state) => state.player);
  const setPlayer = useAuthStore((state) => state.setPlayer);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const setDeleteMergedBranch = async (enabled: boolean) => {
    if (!player || saving) return;
    setSaving(true);
    setError(null);
    try {
      const response = await authenticatedFetch('/me/preferences/delete-merged-branch', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ enabled }),
      });
      if (!response.ok) throw new Error('Could not save this preference.');
      const body = await response.json() as { player: AuthPlayer };
      setPlayer(body.player);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not save this preference.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Card className="p-5 sm:p-6">
      <h2 className="text-sm font-semibold tracking-[-0.015em] text-ink">Preferences</h2>

      <p className="mt-4 text-[13px] font-bold text-ink">Appearance</p>

      <div className="sm:flex sm:items-center sm:justify-between sm:gap-6">
        <p className="mt-1.5 text-[13px] leading-relaxed text-ink-muted">
          The preference is per device: it does not travel with your account.
        </p>

        <SegmentedControl
          items={themes}
          value={theme}
          onChange={(id) => setTheme(id as Theme)}
          className="mt-4 flex w-full sm:mt-0 sm:w-auto sm:shrink-0"
        />

      <div className="mt-6 border-t border-line pt-4">
        <label className="flex items-start gap-3">
          <input type="checkbox" className="mt-1 accent-brand" checked={Boolean(player?.deleteMergedBranch)}
            disabled={saving} onChange={(event) => void setDeleteMergedBranch(event.target.checked)} />
          <span>
            <span className="block text-[13px] font-bold text-ink">Delete session branch after merge</span>
            <span className="mt-1 block text-[13px] leading-relaxed text-ink-muted">
              Remove the GitHub branch after its changes have been merged successfully.
            </span>
          </span>
        </label>
        {error ? <p role="alert" className="mt-2 text-sm text-danger">{error}</p> : null}
      </div>
      </div>
    </Card>
  );
};
