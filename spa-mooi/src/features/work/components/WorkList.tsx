import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Info } from 'lucide-react';
import type { AgentConnection } from '@/features/agents/types/AgentConnection';
import { createWorkIssue, ensureIssueItem, pauseWorkItem, startWorkItem } from '@/features/work/api/workApi';
import { IssueComposer } from '@/features/work/components/IssueComposer';
import { WorkEntryRow } from '@/features/work/components/WorkEntryRow';
import { WorkFilters } from '@/features/work/components/WorkFilters';
import { WorkItemDialog } from '@/features/work/components/WorkItemDialog';
import { WorkSettingsButton } from '@/features/work/components/WorkSettingsButton';
import type { UseProjectWork } from '@/features/work/hooks/useProjectWork';
import { WORK_KINDS } from '@/features/work/lib/workKinds';
import { countWork, workEntries } from '@/features/work/lib/workEntries';
import type { WorkEntry } from '@/features/work/types/WorkEntry';
import type { WorkFilter } from '@/features/work/types/WorkFilter';
import type { WorkKindFilter } from '@/features/work/types/WorkKindFilter';
import { SegmentedControl } from '@/shared/components/SegmentedControl';

interface WorkListProps {
  projectId: string;
  work: UseProjectWork;
  connections: AgentConnection[];
}

/**
 * Features, defects and debt of one project: filtered by kind and status, defects and debt opened
 * right here, each row opening its breakdown and starting with one click. Shared by the project's
 * Workspace To-dos page.
 */
export const WorkList = ({ projectId, work, connections }: WorkListProps) => {
  const { items, issues, issuesError, actionError, busy, run } = work;
  const [kind, setKind] = useState<WorkKindFilter>('all');
  const [filter, setFilter] = useState<WorkFilter>('all');
  const [openKey, setOpenKey] = useState<string | null>(null);
  const [searchParams, setSearchParams] = useSearchParams();
  const requestedWork = searchParams.get('work');

  const entries = useMemo(() => workEntries(items ?? [], issues ?? []), [items, issues]);
  const ofKind = useMemo(() => entries.filter((entry) => kind === 'all' || entry.kind === kind), [entries, kind]);
  const counts = useMemo(() => countWork(ofKind), [ofKind]);
  const visible = ofKind.filter((entry) => filter === 'all' || entry.status === filter);
  const open = entries.find((entry) => entry.key === openKey) ?? null;
  const features = (items ?? []).filter((item) => item.kind === 'feature');

  useEffect(() => {
    if (!requestedWork) return;
    const entry = entries.find((candidate) => candidate.item?.id === requestedWork);
    if (entry) {
      setOpenKey(entry.key);
      return;
    }
    if (items) {
      const next = new URLSearchParams(searchParams);
      next.delete('work');
      setSearchParams(next, { replace: true });
    }
  }, [entries, items, requestedWork, searchParams, setSearchParams]);

  const closeEntry = () => {
    setOpenKey(null);
    if (searchParams.has('work')) {
      const next = new URLSearchParams(searchParams);
      next.delete('work');
      setSearchParams(next, { replace: true });
    }
  };

  const start = async (entry: WorkEntry) => {
    const item = entry.item ?? (entry.issue ? await run(() => ensureIssueItem(projectId, entry.issue!.number)) : null);
    if (item) await run(() => startWorkItem(projectId, item.id));
  };

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-2 lg:flex-row lg:items-center lg:justify-between">
        <SegmentedControl value={kind} onChange={(id) => setKind(id as WorkKindFilter)} className="w-full lg:w-auto"
          items={[{ id: 'all', label: 'All' }, ...(['feature', 'defect', 'debt'] as const).map((id) => (
            { id, label: WORK_KINDS[id].plural, icon: WORK_KINDS[id].icon }))]} />
        <div className="flex items-center gap-2">
          <WorkFilters value={filter} counts={counts} onChange={setFilter} />
          <WorkSettingsButton projectId={projectId} />
        </div>
      </div>

      <IssueComposer projectId={projectId} disabled={busy} onCreate={(draft) => run(() => createWorkIssue(projectId, draft))} />
      <p className="flex items-start gap-2 text-[12.5px] leading-relaxed text-ink-subtle">
        <Info className="mt-0.5 size-3.5 shrink-0" />
        Features are designed in a session: describe one to its agent and it breaks it into tasks here, ready to Start.
      </p>
      {actionError && !open ? <p role="alert" className="rounded-control bg-danger-soft px-3 py-2 text-sm text-danger">{actionError}</p> : null}
      {issuesError ? <p role="status" className="rounded-control border border-warning/30 bg-warning-soft px-3 py-2 text-[13px] text-ink">Defects and debt unavailable: {issuesError}</p> : null}

      {visible.length > 0 ? (
        <ul aria-label="Work items" className="flex flex-col gap-2">
          {visible.map((entry) => (
            <WorkEntryRow key={entry.key} entry={entry} busy={busy} onOpen={() => setOpenKey(entry.key)}
              onStart={() => { void start(entry); }}
              onPause={() => { if (entry.item) void run(() => pauseWorkItem(projectId, entry.item!.id)); }} />
          ))}
        </ul>
      ) : (
        <p className="rounded-card border border-dashed border-line px-4 py-8 text-center text-sm text-ink-muted">
          {entries.length === 0 ? 'Nothing planned yet.' : 'Nothing here with these filters.'}
        </p>
      )}

      {open ? (
        <WorkItemDialog projectId={projectId} entry={open} features={features} connections={connections} work={work}
          onClose={closeEntry} />
      ) : null}
    </div>
  );
};
