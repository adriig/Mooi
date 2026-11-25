import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import type { AgentConnection } from '@/features/agents/types/AgentConnection';
import type { Project } from '@/features/projects/types/Project';
import { WorkBoardCard } from '@/features/work/components/WorkBoardCard';
import { WorkEntryDialog } from '@/features/work/components/WorkEntryDialog';
import { WorkFeatureGraphDialog } from '@/features/work/components/WorkFeatureGraphDialog';
import { sortColumn, workEntries, WORK_COLUMNS } from '@/features/work/lib/workEntries';
import type { WorkColumn } from '@/features/work/types/WorkColumn';
import type { WorkEntry } from '@/features/work/types/WorkEntry';
import type { WorkItem } from '@/features/work/types/WorkItem';
import type { WorkKindFilter } from '@/features/work/types/WorkKindFilter';
import { Button } from '@/shared/components/Button';
import { Tabs } from '@/shared/components/Tabs';
import { cn } from '@/shared/utils/cn';
import { formatCount } from '@/shared/utils/formatCount';
import { useLiveStore } from '@/stores/liveStore';

interface BoardEntry {
  project: Project;
  entry: WorkEntry;
}

interface TodosBoardProps {
  projects: Project[];
  kind: WorkKindFilter;
  connections: AgentConnection[];
}

const MIN_DEPLOYED = 5;
const entryKey = (project: Project, entry: WorkEntry) => `${project.id}:${entry.key}`;

/**
 * The Kanban of every selected project's work. Columns follow from state; the DEPLOYED column shows
 * only as many cards as the tallest other column (at least five) until asked for more, so history
 * never makes the board taller on its own.
 */
export const TodosBoard = ({ projects, kind, connections }: TodosBoardProps) => {
  const work = useLiveStore((live) => live.work);
  const [tab, setTab] = useState<WorkColumn>('defined');
  const [openKey, setOpenKey] = useState<string | null>(null);
  const [graphItem, setGraphItem] = useState<WorkItem | null>(null);
  const [searchParams, setSearchParams] = useSearchParams();
  const requestedWork = searchParams.get('work');

  const loaded = projects.length > 0 && projects.every((project) => work[project.id]?.items && work[project.id]?.issues);
  const all = useMemo<BoardEntry[]>(() => projects.flatMap((project) => {
    const current = work[project.id];
    return workEntries(current?.items ?? [], current?.issues ?? []).map((entry) => ({ project, entry }));
  }), [projects, work]);
  const ofKind = useMemo(() => all.filter(({ entry }) => kind === 'all' || entry.kind === kind), [all, kind]);

  const columns = useMemo(() => {
    const byColumn = (column: WorkColumn) => {
      const rows = ofKind.filter(({ entry }) => entry.column === column);
      const order = new Map(sortColumn(column, rows.map((row) => row.entry)).map((entry, index) => [entry, index]));
      return rows.sort((a, b) => order.get(a.entry)! - order.get(b.entry)!);
    };
    return { defined: byColumn('defined'), inProgress: byColumn('inProgress'),
      pendingDeploy: byColumn('pendingDeploy'), deployed: byColumn('deployed') };
  }, [ofKind]);

  const pageSize = Math.max(MIN_DEPLOYED, columns.defined.length, columns.inProgress.length, columns.pendingDeploy.length);
  const filterKey = `${kind}|${projects.map((project) => project.id).join(',')}`;
  const [paging, setPaging] = useState({ key: filterKey, pages: 1 });
  const pages = paging.key === filterKey ? paging.pages : 1;
  const shownDeployed = columns.deployed.slice(0, pageSize * pages);

  const open = openKey ? all.find((row) => entryKey(row.project, row.entry) === openKey) ?? null : null;

  useEffect(() => {
    if (!requestedWork) return;
    const found = all.find(({ entry }) => entry.item?.id === requestedWork);
    if (found) {
      setOpenKey(entryKey(found.project, found.entry));
      return;
    }
    if (loaded) {
      const next = new URLSearchParams(searchParams);
      next.delete('work');
      setSearchParams(next, { replace: true });
    }
  }, [all, loaded, requestedWork, searchParams, setSearchParams]);

  const close = () => {
    setOpenKey(null);
    if (searchParams.has('work')) {
      const next = new URLSearchParams(searchParams);
      next.delete('work');
      setSearchParams(next, { replace: true });
    }
  };

  return (
    <div className="flex flex-col gap-3">
      <Tabs ariaLabel="Columns" value={tab} onChange={(id) => setTab(id as WorkColumn)} className="border-b border-line lg:hidden"
        items={WORK_COLUMNS.map((column) => ({ id: column.id, label: column.label, count: columns[column.id].length }))} />
      <div className="grid min-w-0 grid-cols-1 gap-4 lg:grid-cols-4">
        {WORK_COLUMNS.map((column) => {
          const rows = column.id === 'deployed' ? shownDeployed : columns[column.id];
          const total = columns[column.id].length;
          return (
            <section key={column.id} aria-label={column.label}
              className={cn('min-w-0 flex-col gap-3 lg:flex', tab === column.id ? 'flex' : 'hidden')}>
              <h2 className="hidden items-center justify-between gap-2 rounded-control bg-surface-2 px-3 py-2 lg:flex">
                <span className="truncate font-mono text-[10.5px] font-medium tracking-[0.12em] text-ink-muted uppercase">{column.label}</span>
                <span title={total >= 1000 ? total.toLocaleString('en') : undefined}
                  className="shrink-0 rounded-full bg-surface-3 px-2 py-0.5 font-mono text-[11px] font-medium text-ink-muted tabular-nums">
                  {formatCount(total)}
                </span>
              </h2>
              {!loaded && rows.length === 0 ? (
                <div className="flex flex-col gap-2">
                  {[0, 1].map((index) => <div key={index} className="h-24 animate-pulse-soft rounded-card bg-surface-2" />)}
                </div>
              ) : rows.length > 0 ? (
                <ul className="flex flex-col gap-2">
                  {rows.map(({ project, entry }) => (
                    <WorkBoardCard key={entryKey(project, entry)} project={project} entry={entry}
                      onOpen={() => setOpenKey(entryKey(project, entry))}
                      onViewBoard={entry.kind === 'feature' && entry.item?.status === 'planned'
                        ? () => setGraphItem(entry.item)
                        : undefined} />
                  ))}
                </ul>
              ) : (
                <p className="rounded-card border border-dashed border-line px-3 py-6 text-center text-[13px] text-ink-subtle">Nothing here.</p>
              )}
              {column.id === 'deployed' && total > rows.length ? (
                <Button variant="secondary" size="sm" onClick={() => setPaging({ key: filterKey, pages: pages + 1 })}>Load more</Button>
              ) : null}
            </section>
          );
        })}
      </div>
      {open ? <WorkEntryDialog project={open.project} entry={open.entry} connections={connections} onClose={close} /> : null}
      {graphItem ? <WorkFeatureGraphDialog item={graphItem} onClose={() => setGraphItem(null)} /> : null}
    </div>
  );
};
