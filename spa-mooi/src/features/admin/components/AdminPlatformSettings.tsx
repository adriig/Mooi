import { useEffect, useState, type FormEvent } from 'react';
import { fetchPreviewDeploymentLimit, setPreviewDeploymentLimit } from '@/features/admin/api/adminApi';
import { Button } from '@/shared/components/Button';
import { Card } from '@/shared/components/Card';

/** Platform-wide runtime limits managed by administrators. */
export const AdminPlatformSettings = () => {
  const [limit, setLimit] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    let cancelled = false;
    fetchPreviewDeploymentLimit()
      .then((value) => { if (!cancelled) setLimit(String(value)); })
      .catch((failure: Error) => { if (!cancelled) setError(failure.message); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setSaving(true);
    setSaved(false);
    setError(null);
    try {
      const value = await setPreviewDeploymentLimit(Number(limit));
      setLimit(String(value));
      setSaved(true);
    } catch (failure) {
      setError((failure as Error).message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Card as="section" className="overflow-hidden">
      <header className="border-b border-line px-5 py-4 sm:px-6">
        <h2 className="text-[15px] font-semibold tracking-[-0.015em] text-ink">Preview deployments</h2>
        <p className="mt-0.5 text-[13px] text-ink-muted">
          Set how many development previews can run at once across the platform.
        </p>
      </header>
      <div className="p-5 sm:px-6">
        {loading ? <p role="status" className="text-sm text-ink-muted">Loading…</p> : (
          <form className="flex flex-wrap items-end gap-3" onSubmit={(event) => void save(event)}>
            <label className="flex flex-col gap-1 text-sm text-ink-muted">
              Maximum running previews
              <input type="number" min="1" max="100" step="1" required value={limit}
                onChange={(event) => { setLimit(event.target.value); setSaved(false); }}
                disabled={saving}
                className="w-40 rounded-control border border-line bg-surface-2 px-3 py-2 text-ink" />
            </label>
            <Button type="submit" variant="secondary"
              disabled={saving || !limit || Number(limit) < 1 || Number(limit) > 100}>
              {saving ? 'Saving…' : 'Save setting'}
            </Button>
            {saved && <span role="status" className="pb-2 text-sm text-success">Saved</span>}
          </form>
        )}
        {error && <p role="alert" className="mt-3 text-sm text-danger">{error}</p>}
        <p className="mt-3 max-w-2xl text-xs leading-relaxed text-ink-subtle">
          Applies to new previews across all users and projects. Lowering the limit does not stop previews already running;
          new previews can start again when capacity is available.
        </p>
      </div>
    </Card>
  );
};
