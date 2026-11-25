import { useEffect, useMemo, useState } from 'react';
import { LoaderCircle, RotateCcw, Sparkles, Trash2 } from 'lucide-react';
import { useAgentConnections } from '@/features/agents/hooks/useAgentConnections';
import { fetchProductionDeploymentDetail } from '@/features/production/api/productionHistoryApi';
import { PRODUCTION_DOCUMENTS } from '@/features/production/lib/productionDocuments';
import type { ProductionDeployment } from '@/features/production/types/ProductionDeployment';
import type { ProductionDeploymentDetail } from '@/features/production/types/ProductionDeploymentDetail';
import { DeploymentStateBadge } from '@/features/production/components/DeploymentStateBadge';
import { ProductionTargetLink } from '@/features/production/components/ProductionTargetLink';
import { Button } from '@/shared/components/Button';
import { Modal } from '@/shared/components/Modal';
import { cn } from '@/shared/utils/cn';
import { formatDate } from '@/shared/utils/formatDate';
import { formatDuration } from '@/shared/utils/formatDuration';
import { formatTime } from '@/shared/utils/formatTime';
import { fetchWorkItems } from '@/features/work/api/workApi';
import type { WorkItem } from '@/features/work/types/WorkItem';
import type { WorkEntry } from '@/features/work/types/WorkEntry';
import { WORK_KINDS } from '@/features/work/lib/workKinds';
import { WorkItemDialog } from '@/features/work/components/WorkItemDialog';
import { useProjectWork } from '@/features/work/hooks/useProjectWork';
import { useLiveStore } from '@/stores/liveStore';
import { Badge } from '@/shared/components/Badge';

interface ProductionDeploymentDetailDialogProps {
  deployment: ProductionDeployment;
  projectName: string;
  repository?: string | null;
  onClose: () => void;
  onRedeploy?: () => void;
  onFix?: () => void;
  onDelete?: () => Promise<void>;
}

type ProductionDocumentKey = 'manifest' | 'script' | 'statusScript';

/** One recorded attempt: its result, the exact files it ran and its redacted output. */
export const ProductionDeploymentDetailDialog = ({ deployment, projectName, repository, onClose, onRedeploy, onFix, onDelete }: ProductionDeploymentDetailDialogProps) => {
  const [detail, setDetail] = useState<ProductionDeploymentDetail | null>(null);
  const [selected, setSelected] = useState<ProductionDocumentKey | 'output'>('output');
  const [error, setError] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [workItems, setWorkItems] = useState<WorkItem[] | null>(null);
  const [workError, setWorkError] = useState<string | null>(null);
  const [selectedItemId, setSelectedItemId] = useState<string | null>(null);
  const epoch = useLiveStore((live) => live.epoch);
  const liveItems = useLiveStore((live) => live.work[deployment.projectId]?.items);
  const work = useProjectWork(deployment.projectId, false);
  const { connections } = useAgentConnections();

  useEffect(() => {
    if (deployment.state !== 'succeeded') return;
    let active = true;
    setWorkItems(null);
    setWorkError(null);
    fetchWorkItems(deployment.projectId).then((items) => {
      if (!active) return;
      const scoped = items.filter((item) => item.projectId === deployment.projectId);
      useLiveStore.getState().adoptWorkItems(deployment.projectId, scoped);
      setWorkItems(scoped);
    }).catch((failure: unknown) => {
      if (active) setWorkError(failure instanceof Error ? failure.message : 'Could not load shipped work.');
    });
    return () => { active = false; };
  }, [deployment.projectId, deployment.state, epoch]);

  useEffect(() => {
    let active = true;
    void fetchProductionDeploymentDetail(deployment.projectId, deployment.operationId)
      .then((result) => { if (active) setDetail(result); })
      .catch((failure: Error) => { if (active) setError(failure.message); });
    return () => { active = false; };
  }, [deployment.projectId, deployment.operationId]);

  const remove = async () => {
    if (!onDelete) return;
    setDeleting(true); setError(null);
    try { await onDelete(); }
    catch (failure) { setError((failure as Error).message); setDeleting(false); }
  };

  const files = detail?.files;
  const tabs = [{ id: 'output' as const, label: 'Output' },
    ...PRODUCTION_DOCUMENTS.filter(({ key }) => key !== 'targets' && Boolean(files?.[key]))
      .map(({ key, name }) => ({ id: key as ProductionDocumentKey, label: name }))];
  const content = selected === 'output' ? (detail?.logs.join('\n') || 'No output was recorded.') : files?.[selected] ?? '';
  const finished = deployment.finishedAt;
  const releaseItems = useMemo(() => (liveItems ?? workItems ?? [])
    .filter((item) => item.projectId === deployment.projectId && item.deployment?.version === deployment.releaseTag)
    .sort((a, b) => a.kind.localeCompare(b.kind) || a.title.localeCompare(b.title)),
  [deployment.projectId, deployment.releaseTag, liveItems, workItems]);
  const selectedItem = releaseItems.find((item) => item.id === selectedItemId) ?? null;
  const entry: WorkEntry | null = selectedItem ? {
    key: selectedItem.id, kind: selectedItem.kind, title: selectedItem.title,
    column: 'deployed', item: selectedItem, issue: null,
  } : null;

  return <Modal open size="lg" onClose={deleting ? () => undefined : onClose} title={`${deployment.releaseTag} · ${projectName}`}
    description={`${formatDate(deployment.startedAt)} at ${formatTime(deployment.startedAt)}${finished ? ` · took ${formatDuration(deployment.startedAt, finished)}` : ''}`}
    footer={onDelete && deployment.state !== 'running' ? confirmDelete ? <>
      <Button variant="ghost" onClick={() => setConfirmDelete(false)} disabled={deleting}>Keep it</Button>
      <Button variant="danger" onClick={() => void remove()} disabled={deleting}>
        {deleting ? <LoaderCircle className="size-4 animate-spin" /> : <Trash2 className="size-4" />}Delete saved data</Button>
    </> : <Button variant="ghost" onClick={() => setConfirmDelete(true)} className="text-danger sm:mr-auto">
      <Trash2 className="size-4" />Delete from history</Button> : undefined}>
    <div className="flex flex-wrap items-center gap-2">
      <DeploymentStateBadge state={deployment.state} />
      {detail?.message ? <p className="min-w-0 flex-1 text-sm text-ink-muted">{detail.message}</p> : null}
      <div className="flex gap-2">
        {onFix && deployment.state === 'failed' ? <Button variant="secondary" size="sm" onClick={onFix}><Sparkles className="size-4" />Fix with agent</Button> : null}
        {onRedeploy && deployment.state !== 'running' ? <Button variant="secondary" size="sm" onClick={onRedeploy}><RotateCcw className="size-4" />Redeploy</Button> : null}
      </div>
    </div>
    {deployment.targets.length > 0 ? <div className="mt-4 flex flex-wrap items-center gap-2 rounded-control border border-line bg-surface-2 px-3.5 py-2.5">
      <span className="mr-1 text-xs font-semibold text-ink-muted">Deployed to</span>
      {deployment.targets.map((target) => <ProductionTargetLink key={`${target.label}:${target.url}`} target={target}
        className="rounded-full bg-surface px-2.5 py-1" />)}
    </div> : null}
    {confirmDelete ? <p className="mt-4 rounded-control bg-surface-2 px-3.5 py-2.5 text-xs leading-relaxed text-ink-muted">
      Removes this entry, its files and its output from Mooi. The deployed service and the current configuration stay in place.</p> : null}

    <section aria-labelledby="release-work-title" className="mt-5 rounded-card border border-line bg-surface-2 p-4">
      <h2 id="release-work-title" className="text-sm font-semibold text-ink">Included in this release</h2>
      {deployment.state !== 'succeeded' ? <p className="mt-2 text-sm text-ink-muted">Shipped work is available only for successful deployments.</p>
        : workError ? <p role="alert" className="mt-2 text-sm text-danger">{workError}</p>
          : !liveItems && !workItems ? <p className="mt-2 flex items-center gap-2 text-sm text-ink-muted"><LoaderCircle className="size-4 animate-spin" />Loading shipped work…</p>
            : releaseItems.length === 0 ? <p className="mt-2 text-sm text-ink-muted">No work items were first shipped in {deployment.releaseTag}.</p>
              : <ul className="mt-3 flex flex-col gap-2">{releaseItems.map((item) => {
                const kind = WORK_KINDS[item.kind];
                return <li key={item.id}><button type="button" onClick={() => setSelectedItemId(item.id)}
                  className="flex w-full flex-wrap items-center gap-2 rounded-control border border-line bg-surface px-3 py-2 text-left hover:border-line-strong focus-visible:outline-2 focus-visible:outline-brand">
                  <Badge tone={kind.tone}>{kind.label}</Badge><span className="min-w-0 flex-1 text-sm font-medium text-ink">{item.title}</span>
                  <span className="text-xs text-ink-muted">{projectName}</span>
                </button></li>;
              })}</ul>}
    </section>

    {error ? <p role="alert" className="mt-4 rounded-control border border-danger/30 bg-danger-soft px-4 py-2.5 text-sm text-danger">{error}</p>
      : !detail ? <p className="mt-5 flex items-center gap-2 text-sm text-ink-muted"><LoaderCircle className="size-4 animate-spin" />Loading deployment…</p>
        : <div className="mt-5">
          <div role="tablist" aria-label="Deployment content" className="flex flex-wrap gap-1.5">
            {tabs.map((tab) => <button key={tab.id} type="button" role="tab" aria-selected={selected === tab.id} onClick={() => setSelected(tab.id)}
              className={cn('rounded-lg px-3 py-1.5 font-mono text-xs font-bold transition',
                selected === tab.id ? 'bg-contrast text-contrast-ink' : 'bg-surface-2 text-ink-muted hover:text-ink')}>{tab.label}</button>)}
          </div>
          <pre role="tabpanel" className={cn('mt-3 max-h-[45dvh] overflow-auto rounded-xl p-4 font-mono text-xs leading-6 whitespace-pre-wrap break-all',
            selected === 'output' ? 'bg-neutral-950 text-neutral-100' : 'border border-line bg-surface-2 text-ink')}>{content}</pre>
          {!files ? <p className="mt-3 text-xs text-ink-subtle">This attempt predates saved file snapshots.</p> : null}
        </div>}
    {entry ? <WorkItemDialog projectId={deployment.projectId} repository={repository} entry={entry}
      features={releaseItems.filter((item) => item.kind === 'feature')} connections={connections} work={work}
      onClose={() => setSelectedItemId(null)} /> : null}
  </Modal>;
};
