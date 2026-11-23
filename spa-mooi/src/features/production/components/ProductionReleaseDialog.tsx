import { useEffect, useState } from 'react';
import { Check, LoaderCircle, Plus, Rocket, Tag, TriangleAlert } from 'lucide-react';
import { fetchProductionReleases } from '@/features/production/api/productionApi';
import { nextReleaseTag } from '@/features/production/lib/nextReleaseTag';
import { RELEASE_TAG_PATTERN } from '@/features/production/lib/releaseTagPattern';
import type { GithubRelease } from '@/features/production/types/GithubRelease';
import type { ReleaseChoice } from '@/features/production/types/ReleaseChoice';
import { fetchWorkItems } from '@/features/work/api/workApi';
import { Button } from '@/shared/components/Button';
import { Modal } from '@/shared/components/Modal';
import { cn } from '@/shared/utils/cn';
import { formatRelativeTime } from '@/shared/utils/formatRelativeTime';
import { useLiveStore } from '@/stores/liveStore';

interface ProductionReleaseDialogProps {
  projectId: string;
  defaultBranch: string | null;
  busy: boolean;
  error: string | null;
  onClose: () => void;
  onSelect: (choice: ReleaseChoice) => void;
}

/** Production always deploys an immutable GitHub release: an existing one, or a new one from the default branch. */
export const ProductionReleaseDialog = ({ projectId, defaultBranch, busy, error, onClose, onSelect }: ProductionReleaseDialogProps) => {
  const [releases, setReleases] = useState<GithubRelease[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [mode, setMode] = useState<'existing' | 'create'>('existing');
  const [selected, setSelected] = useState('');
  const [tag, setTag] = useState('');
  const items = useLiveStore((live) => live.work[projectId]?.items);
  const epoch = useLiveStore((live) => live.epoch);
  const [workCheck, setWorkCheck] = useState<{ epoch: number; error: string | null } | null>(null);
  const [workRetries, setWorkRetries] = useState(0);
  const [confirmedWarning, setConfirmedWarning] = useState<string | null>(null);

  // Read work items through the same project API and live store used by To-dos and Board.
  // A reconnect invalidates the check: changes missed while offline must be read again.
  useEffect(() => {
    if (mode !== 'create') return;
    let active = true;
    setWorkCheck(null);
    fetchWorkItems(projectId).then((workItems) => {
      if (!active) return;
      useLiveStore.getState().adoptWorkItems(projectId, workItems);
      setWorkCheck({ epoch, error: null });
    }).catch((failure: unknown) => {
      if (active) setWorkCheck({ epoch, error: failure instanceof Error ? failure.message : 'Could not load the project work.' });
    });
    return () => { active = false; };
  }, [projectId, mode, epoch, workRetries]);

  useEffect(() => {
    let active = true;
    fetchProductionReleases(projectId).then((items) => {
      if (!active) return;
      setReleases(items);
      setSelected(items[0]?.tag ?? '');
      setTag(nextReleaseTag(items[0]?.tag));
      if (items.length === 0) setMode('create');
    }).catch((failure: Error) => { if (active) setLoadError(failure.message); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [projectId]);

  const choice: ReleaseChoice = mode === 'existing' ? { tag: selected, create: false } : { tag: tag.trim(), create: true };
  const valid = choice.tag.length > 0 && (mode === 'existing' || RELEASE_TAG_PATTERN.test(choice.tag));
  const checkingWork = mode === 'create' && workCheck?.epoch !== epoch;
  const workError = workCheck?.epoch === epoch ? workCheck.error : null;
  const incompleteFeatures = (items ?? []).filter((item) => item.projectId === projectId && item.kind === 'feature' && item.status !== 'done');
  // Confirmation belongs to this release and warning; a newly pending feature requires consent again.
  const warningKey = mode === 'create' && !checkingWork && (workError || incompleteFeatures.length > 0)
    ? JSON.stringify([projectId, choice.tag, workError, incompleteFeatures.map(({ id, title }) => [id, title]).sort()]) : null;
  const canSelect = valid && !busy && !loading && !checkingWork && (!warningKey || confirmedWarning === warningKey);
  const modes = [
    { id: 'existing' as const, icon: Tag, title: 'Existing release', hint: `${releases.length} published`, disabled: releases.length === 0 },
    { id: 'create' as const, icon: Plus, title: 'New release', hint: `From ${defaultBranch ?? 'the default branch'}`, disabled: false },
  ];

  return <Modal open onClose={busy ? () => undefined : onClose} title="Deploy a release"
    description="Production always runs an immutable GitHub release. Redeploy a published version or publish a new one."
    footer={<><Button variant="ghost" onClick={onClose} disabled={busy}>Cancel</Button>
      <Button variant="brand" disabled={!canSelect} onClick={() => { if (canSelect) onSelect(choice); }}>
        {busy ? <LoaderCircle className="size-4 animate-spin" /> : <Rocket className="size-4" />}
        {busy ? 'Starting…' : mode === 'create' ? 'Publish' : 'Deploy'}
      </Button></>}>
    <div role="radiogroup" aria-label="Release source" className="grid grid-cols-2 gap-2 rounded-xl bg-surface-2 p-1">
      {modes.map(({ id, icon: Icon, title, hint, disabled }) => <button key={id} type="button" role="radio" aria-checked={mode === id}
        disabled={disabled || busy} onClick={() => {
          if (id !== mode) { setWorkCheck(null); setConfirmedWarning(null); setMode(id); }
        }}
        className={cn('flex min-w-0 items-center gap-2.5 rounded-control px-3 py-2.5 text-left transition disabled:opacity-45 focus-visible:outline-2 focus-visible:outline-brand',
          mode === id ? 'bg-surface text-ink shadow-sm' : 'text-ink-muted hover:text-ink')}>
        <Icon className="size-4 shrink-0" />
        <span className="min-w-0"><span className="block truncate text-sm font-bold">{title}</span>
          <span className="block truncate text-[11px] text-ink-subtle">{hint}</span></span>
      </button>)}
    </div>

    {loading ? <p className="mt-5 flex items-center gap-2 text-sm text-ink-muted"><LoaderCircle className="size-4 animate-spin" />Loading releases…</p>
      : mode === 'existing' ? <ul role="radiogroup" aria-label="Published releases" className="mt-5 flex max-h-[min(18rem,40dvh)] flex-col gap-1.5 overflow-y-auto overscroll-contain">
        {releases.map((release) => <li key={release.tag}>
          <button type="button" role="radio" aria-checked={selected === release.tag} disabled={busy} onClick={() => setSelected(release.tag)}
            className={cn('flex w-full items-center gap-3 rounded-control border px-3.5 py-3 text-left transition focus-visible:outline-2 focus-visible:outline-brand',
              selected === release.tag ? 'border-ink bg-surface' : 'border-line hover:bg-surface-2')}>
            <span className="min-w-0 flex-1">
              <span className="block truncate font-mono text-sm font-bold text-ink">{release.tag}</span>
              <span className="block truncate text-xs text-ink-muted">{release.name !== release.tag ? `${release.name} · ` : ''}
                {release.publishedAt ? `Published ${formatRelativeTime(release.publishedAt)}` : 'Published'}</span>
            </span>
            {selected === release.tag ? <Check className="size-4 shrink-0 text-ink" /> : null}
          </button>
        </li>)}
      </ul> : <label className="mt-5 flex flex-col gap-2">
        <span className="text-xs font-medium text-ink-muted">Version</span>
        <input autoFocus autoCapitalize="none" autoCorrect="off" spellCheck={false} value={tag} onChange={(event) => setTag(event.target.value)} disabled={busy}
          placeholder="v1.0.0" aria-invalid={tag.length > 0 && !valid}
          className="h-11 rounded-control border border-line bg-surface-2 px-3 font-mono text-sm text-ink outline-none focus:border-brand/50" />
        <span className="text-xs text-ink-subtle">Published on GitHub from the latest {defaultBranch ?? 'default branch'} commit when the deployment starts.</span>
      </label>}
    {mode === 'create' && checkingWork ? <p role="status" className="mt-4 flex items-center gap-2 text-sm text-ink-muted">
      <LoaderCircle className="size-4 animate-spin" />Checking project features…
    </p> : null}
    {warningKey ? <div role="alert" className="mt-4 rounded-control border border-warning/30 bg-warning-soft px-4 py-3 text-sm">
      <p className="flex items-start gap-2 font-semibold text-warning"><TriangleAlert className="mt-0.5 size-4 shrink-0" />
        {workError ? 'Feature status could not be verified' : `${incompleteFeatures.length} incomplete ${incompleteFeatures.length === 1 ? 'feature' : 'features'}`}
      </p>
      {workError ? <>
        <p className="mt-2 break-words text-ink-muted">{workError} You can retry or explicitly publish without verifying feature status.</p>
        <Button className="mt-2" size="sm" disabled={busy} onClick={() => {
          setWorkCheck(null); setConfirmedWarning(null); setWorkRetries((count) => count + 1);
        }}>Retry check</Button>
      </> : <>
        <p className="mt-2 text-ink-muted">Publishing from {defaultBranch ?? 'the default branch'} may include unfinished work from these features:</p>
        <ul className="mt-2 max-h-40 list-disc space-y-1 overflow-y-auto break-words pl-5 text-ink">
          {incompleteFeatures.map((feature) => <li key={feature.id}>{feature.title}</li>)}
        </ul>
      </>}
      <label className="mt-3 flex cursor-pointer items-start gap-2 text-ink">
        <input type="checkbox" className="mt-1 shrink-0 accent-brand" disabled={busy}
          checked={confirmedWarning === warningKey} onChange={(event) => setConfirmedWarning(event.target.checked ? warningKey : null)} />
        <span>{workError ? 'I understand feature status is unknown and want to publish anyway.' : 'I understand there are incomplete features and want to publish anyway.'}</span>
      </label>
    </div> : null}
    {loadError || error ? <p role="alert" className="mt-4 rounded-control border border-danger/30 bg-danger-soft px-4 py-2.5 text-sm text-danger">{error ?? loadError}</p> : null}
  </Modal>;
};
