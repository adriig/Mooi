import { useEffect, useMemo, useState } from 'react';
import { Check, LoaderCircle, Plus, RefreshCw, Rocket, Tag, TriangleAlert } from 'lucide-react';
import { fetchProductionReleasePreview, fetchProductionReleases } from '@/features/production/api/productionApi';
import { nextReleaseTag } from '@/features/production/lib/nextReleaseTag';
import { RELEASE_TAG_PATTERN } from '@/features/production/lib/releaseTagPattern';
import type { GithubRelease } from '@/features/production/types/GithubRelease';
import type { ReleaseChoice } from '@/features/production/types/ReleaseChoice';
import type { ReleasePreview } from '@/features/production/types/ReleasePreview';
import { fetchWorkItems } from '@/features/work/api/workApi';
import type { WorkItem } from '@/features/work/types/WorkItem';
import { Button } from '@/shared/components/Button';
import { Modal } from '@/shared/components/Modal';
import { cn } from '@/shared/utils/cn';
import { formatRelativeTime } from '@/shared/utils/formatRelativeTime';
import { useLiveStore } from '@/stores/liveStore';

interface ProductionReleaseDialogProps {
  projectId: string;
  defaultBranch: string | null;
  initialReleaseTag?: string | null;
  busy: boolean;
  error: string | null;
  onClearError: () => void;
  onClose: () => void;
  onSelect: (choice: ReleaseChoice) => void;
}

interface KeyedLoad<T> {
  key: string;
  value: T | null;
  error: string | null;
}

const shortShaPattern = /^Merged as ([0-9a-f]{7,40})$/i;
const shaPattern = /^[0-9a-f]{40}$/i;

/**
 * Production always deploys an immutable GitHub release. The preview's exact commit list is joined
 * with project work items so a task/item appears only when its real merge commit enters this release.
 */
export const ProductionReleaseDialog = ({
  projectId, defaultBranch, initialReleaseTag, busy, error, onClearError, onClose, onSelect,
}: ProductionReleaseDialogProps) => {
  const [releases, setReleases] = useState<GithubRelease[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [releaseRetries, setReleaseRetries] = useState(0);
  const [mode, setMode] = useState<'existing' | 'create'>('existing');
  const [selected, setSelected] = useState(initialReleaseTag ?? '');
  const [tag, setTag] = useState('');
  const epoch = useLiveStore((live) => live.epoch);
  const liveWorkItems = useLiveStore((live) => live.work[projectId]?.items);
  const [workLoad, setWorkLoad] = useState<KeyedLoad<WorkItem[]>>({ key: '', value: null, error: null });
  const [previewLoad, setPreviewLoad] = useState<KeyedLoad<ReleasePreview>>({ key: '', value: null, error: null });
  const [refreshAttempt, setRefreshAttempt] = useState(0);
  const [confirmedWarning, setConfirmedWarning] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setLoadError(null);
    fetchProductionReleases(projectId).then((items) => {
      if (!active) return;
      setReleases(items);
      setSelected((current) => {
        if (initialReleaseTag && items.some((item) => item.tag === initialReleaseTag)) return initialReleaseTag;
        if (current && items.some((item) => item.tag === current)) return current;
        return items[0]?.tag ?? '';
      });
      setTag(nextReleaseTag(items[0]?.tag));
      if (items.length === 0) setMode('create');
    }).catch((failure: unknown) => {
      if (active) setLoadError(failure instanceof Error ? failure.message : 'Could not load GitHub releases.');
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [projectId, initialReleaseTag, releaseRetries]);

  const candidateTag = mode === 'existing' ? selected : tag.trim();
  const valid = candidateTag.length > 0 && (mode === 'existing' || RELEASE_TAG_PATTERN.test(candidateTag));
  const selectionReady = mode === 'create' ? valid : selected.length > 0;
  const requestKey = JSON.stringify([projectId, mode, candidateTag, epoch, refreshAttempt]);

  // Re-read both sources when the candidate changes or the live connection returns. A result from
  // another release, project, or connection epoch is never used to authorize deployment.
  useEffect(() => {
    if (!selectionReady) return;
    let active = true;
    const fetchKey = requestKey;
    setWorkLoad({ key: fetchKey, value: null, error: null });
    fetchWorkItems(projectId).then((workItems) => {
      if (!active) return;
      const projectItems = workItems.filter((item) => item.projectId === projectId);
      useLiveStore.getState().adoptWorkItems(projectId, projectItems);
      setWorkLoad({ key: fetchKey, value: projectItems, error: null });
    }).catch((failure: unknown) => {
      if (active) setWorkLoad({
        key: fetchKey, value: null,
        error: failure instanceof Error ? failure.message : 'Could not load the project work.',
      });
    });
    return () => { active = false; };
  }, [projectId, requestKey, selectionReady]);

  useEffect(() => {
    if (!selectionReady) return;
    let active = true;
    const fetchKey = requestKey;
    setPreviewLoad({ key: fetchKey, value: null, error: null });
    fetchProductionReleasePreview(projectId, { tag: candidateTag, create: mode === 'create' }).then((preview) => {
      if (active) setPreviewLoad({ key: fetchKey, value: preview, error: null });
    }).catch((failure: unknown) => {
      if (active) setPreviewLoad({
        key: fetchKey, value: null,
        error: failure instanceof Error ? failure.message : 'Could not compare this release with production.',
      });
    });
    return () => { active = false; };
  }, [projectId, mode, candidateTag, requestKey, selectionReady]);

  const workCurrent = selectionReady && workLoad.key === requestKey;
  const previewCurrent = selectionReady && previewLoad.key === requestKey;
  const workItems = workCurrent && workLoad.value !== null
    ? liveWorkItems?.filter((item) => item.projectId === projectId) ?? workLoad.value : null;
  const preview = previewCurrent ? previewLoad.value : null;
  const workError = workCurrent ? workLoad.error : null;
  const previewError = previewCurrent ? previewLoad.error : null;
  const checkingWork = selectionReady && (!workCurrent || (!workItems && !workError));
  const checkingPreview = selectionReady && (!previewCurrent || (!preview && !previewError));

  const incompleteFeatures = (workItems ?? []).filter((item) => item.kind === 'feature'
    && item.tasks.some((task) => task.status === 'done') && item.tasks.some((task) => task.status !== 'done'));
  // This is intentionally independent of the commit-accurate work-item preview.
  const warningKey = mode === 'create' && workCurrent && !checkingWork && incompleteFeatures.length > 0
    ? JSON.stringify([projectId, candidateTag, incompleteFeatures.map(({ id, title }) => [id, title]).sort()]) : null;

  const groupedCommits = useMemo(() => {
    if (!preview || !workItems) return [];
    const commitsBySha = new Map(preview.commits.map((commit) => [commit.sha.toLowerCase(), commit]));
    const uniquelyMatchesLegacyReport = (report: string | null): string | null => {
      if (!report) return null;
      const match = shortShaPattern.exec(report.trim());
      if (!match) return null;
      const prefix = match[1].toLowerCase();
      const matches = [...commitsBySha.keys()].filter((sha) => sha.startsWith(prefix));
      return matches.length === 1 ? matches[0] : null;
    };
    return [
      { kind: 'feature' as const, title: 'Feature' },
      { kind: 'defect' as const, title: 'Defect' },
      { kind: 'debt' as const, title: 'Debt' },
    ].map((group) => {
      const includedShas = new Set<string>();
      workItems.filter((item) => item.kind === group.kind).forEach((item) => item.tasks.forEach((task) => {
        let sha: string | null = null;
        if (task.mergeSha && shaPattern.test(task.mergeSha)) sha = task.mergeSha.toLowerCase();
        // Older completed tasks only match when their report identifies one and only one commit.
        else if (!task.mergeSha && task.status === 'done') sha = uniquelyMatchesLegacyReport(task.report);
        if (sha && commitsBySha.has(sha)) includedShas.add(sha);
      }));
      return {
        ...group,
        commits: preview.commits.filter((commit) => includedShas.has(commit.sha.toLowerCase())),
      };
    });
  }, [preview, workItems]);

  const refresh = () => {
    onClearError();
    setConfirmedWarning(null);
    setRefreshAttempt((attempt) => attempt + 1);
  };
  const canSelect = valid && !busy && !loading && !loadError && Boolean(preview) && Boolean(workItems)
    && !checkingPreview && !checkingWork && !previewError && !workError && !preview?.truncated
    && (!warningKey || confirmedWarning === warningKey);
  const modes = [
    { id: 'existing' as const, icon: Tag, title: 'Existing release', hint: `${releases.length} published`, disabled: releases.length === 0 },
    { id: 'create' as const, icon: Plus, title: 'New release', hint: `From ${defaultBranch ?? 'the default branch'}`, disabled: false },
  ];

  return <Modal open onClose={busy ? () => undefined : onClose} title="Deploy a release"
    description="Production always runs an immutable GitHub release. Review its work items against the version currently in production."
    size="lg"
    footer={<><Button variant="ghost" onClick={onClose} disabled={busy}>Cancel</Button>
      <Button variant="brand" disabled={!canSelect} onClick={() => {
        if (canSelect && preview) onSelect({ tag: candidateTag, create: mode === 'create', expectedSha: preview.targetSha });
      }}>
        {busy ? <LoaderCircle className="size-4 animate-spin" /> : <Rocket className="size-4" />}
        {busy ? 'Starting…' : mode === 'create' ? 'Publish' : 'Deploy'}
      </Button></>}>
    <div role="radiogroup" aria-label="Release source" className="grid grid-cols-2 gap-2 rounded-xl bg-surface-2 p-1">
      {modes.map(({ id, icon: Icon, title, hint, disabled }) => <button key={id} type="button" role="radio" aria-checked={mode === id}
        disabled={disabled || busy} onClick={() => {
          if (id !== mode) { setConfirmedWarning(null); setMode(id); }
        }}
        className={cn('flex min-w-0 items-center gap-2.5 rounded-control px-3 py-2.5 text-left transition disabled:opacity-45 focus-visible:outline-2 focus-visible:outline-brand',
          mode === id ? 'bg-surface text-ink shadow-sm' : 'text-ink-muted hover:text-ink')}>
        <Icon className="size-4 shrink-0" />
        <span className="min-w-0"><span className="block truncate text-sm font-bold">{title}</span>
          <span className="block truncate text-[11px] text-ink-subtle">{hint}</span></span>
      </button>)}
    </div>

    {loading ? <p role="status" className="mt-5 flex items-center gap-2 text-sm text-ink-muted"><LoaderCircle className="size-4 animate-spin" />Loading releases…</p>
      : loadError ? <div role="alert" className="mt-5 rounded-control border border-danger/30 bg-danger-soft p-4 text-sm text-danger">
        <p>{loadError}</p><Button className="mt-2" size="sm" disabled={busy} onClick={() => setReleaseRetries((count) => count + 1)}>Retry releases</Button>
      </div>
        : mode === 'existing' ? <ul role="radiogroup" aria-label="Published releases" className="mt-5 flex max-h-[min(18rem,35dvh)] flex-col gap-1.5 overflow-y-auto overscroll-contain">
          {releases.map((release) => <li key={release.tag}>
            <button type="button" role="radio" aria-checked={selected === release.tag} disabled={busy} onClick={() => {
              if (selected !== release.tag) { setSelected(release.tag); setConfirmedWarning(null); }
            }}
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
          <span className="text-xs text-ink-subtle">Published from the latest {defaultBranch ?? 'default branch'} commit when the deployment starts.</span>
        </label>}

    {selectionReady ? <section aria-labelledby="release-work-heading" className="mt-5 rounded-xl border border-line bg-surface-2/40 p-4 sm:p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 id="release-work-heading" className="text-sm font-bold text-ink">Work items in this release</h3>
          <p className="mt-1 text-xs leading-relaxed text-ink-muted">
            {preview?.baseTag ? `Compared with ${preview.baseTag}, currently in production.` : 'First production release; showing work merged in its history.'}
          </p>
        </div>
        <Button variant="secondary" size="sm" disabled={busy || checkingPreview || checkingWork} onClick={refresh}>
          <RefreshCw className="size-3.5" />Refresh preview
        </Button>
      </div>

      {checkingPreview || checkingWork ? <p role="status" className="mt-4 flex items-center gap-2 text-sm text-ink-muted">
        <LoaderCircle className="size-4 animate-spin" />{checkingPreview ? 'Comparing release with production…' : 'Loading project work items…'}
      </p> : null}
      {previewError ? <div role="alert" className="mt-4 rounded-control border border-danger/30 bg-danger-soft p-3 text-sm text-danger">
        <p className="break-words">{previewError}</p>
        <Button className="mt-2" size="sm" disabled={busy} onClick={refresh}>Retry comparison</Button>
      </div> : null}
      {workError ? <div role="alert" className="mt-4 rounded-control border border-danger/30 bg-danger-soft p-3 text-sm text-danger">
        <p className="break-words">Project work items could not be loaded: {workError}</p>
        <Button className="mt-2" size="sm" disabled={busy} onClick={refresh}>Retry work items</Button>
      </div> : null}
      {preview?.truncated ? <p role="alert" className="mt-4 rounded-control border border-warning/30 bg-warning-soft p-3 text-sm text-warning">
        The release history is too large to verify every work item. Refresh or try again later; deployment is disabled until the full comparison is available.
      </p> : null}

      {preview && workItems && !preview.truncated ? <>
        <p className="mt-4 text-xs text-ink-subtle">
          {preview.comparison === 'identical'
            ? 'This is the release currently in production. It has no new work items.'
            : preview.comparison === 'initial'
              ? 'No release is in production yet; these items have merge commits in the selected release history.'
              : 'Only items with merge commits entering this release are listed.'}
        </p>
        <p className="mt-1 font-mono text-[11px] text-ink-subtle">Target commit {preview.targetSha.slice(0, 12)}</p>
        <ul aria-label="Work items in this release" className="mt-3 max-h-[min(18rem,35dvh)] divide-y divide-line overflow-y-auto overscroll-contain rounded-control border border-line bg-surface">
          {groupedCommits.flatMap(({ kind, title, commits }) => commits.map((commit) => (
            <li key={`${kind}-${commit.sha}`} className="flex min-w-0 items-start gap-2 px-3 py-2.5 text-sm">
              <span className={cn('shrink-0 font-semibold',
                kind === 'feature' ? 'text-brand-strong' : kind === 'defect' ? 'text-danger' : 'text-accent')}>
                {title}
              </span>
              <span className="min-w-0 break-words text-ink">{commit.title}</span>
            </li>
          )))}
          {groupedCommits.every(({ commits }) => commits.length === 0)
            ? <li className="px-3 py-3 text-sm text-ink-subtle">No matching work items.</li> : null}
        </ul>
      </> : null}
    </section> : null}

    {mode === 'create' && warningKey ? <div role="alert" className="mt-4 rounded-control border border-warning/30 bg-warning-soft px-4 py-3 text-sm">
      <p className="flex items-start gap-2 font-semibold text-warning"><TriangleAlert className="mt-0.5 size-4 shrink-0" />
        {`${incompleteFeatures.length} incomplete ${incompleteFeatures.length === 1 ? 'feature' : 'features'}`}
      </p>
      <p className="mt-2 text-ink-muted">Publishing from {defaultBranch ?? 'the default branch'} may include unfinished work from these features:</p>
      <ul className="mt-2 max-h-40 list-disc space-y-1 overflow-y-auto break-words pl-5 text-ink">
        {incompleteFeatures.map((feature) => <li key={feature.id}>{feature.title}</li>)}
      </ul>
      <label className="mt-3 flex cursor-pointer items-start gap-2 text-ink">
        <input type="checkbox" className="mt-1 shrink-0 accent-brand" disabled={busy}
          checked={confirmedWarning === warningKey} onChange={(event) => setConfirmedWarning(event.target.checked ? warningKey : null)} />
        <span>I understand there are incomplete features and want to publish anyway.</span>
      </label>
    </div> : null}

    {error ? <div role="alert" className="mt-4 rounded-control border border-danger/30 bg-danger-soft px-4 py-3 text-sm text-danger">
      <p className="break-words">{error}</p>
      <Button className="mt-2" size="sm" disabled={busy} onClick={refresh}>Refresh and confirm the release again</Button>
    </div> : null}
  </Modal>;
};
