import { useState } from 'react';
import { RotateCw } from 'lucide-react';
import { fetchAnalyticsSearch } from '@/features/analytics/api/analyticsApi';
import { AnalyticsGoogleErrorState } from '@/features/analytics/components/AnalyticsGoogleErrorState';
import { AnalyticsPositionChart } from '@/features/analytics/components/AnalyticsPositionChart';
import { AnalyticsSortableTable, type AnalyticsTableColumn } from '@/features/analytics/components/AnalyticsSortableTable';
import { useAnalyticsReport } from '@/features/analytics/hooks/useAnalyticsReport';
import { formatBucket, formatMetricCount, formatPosition, formatRatio } from '@/features/analytics/lib/analyticsFormat';
import type { GoogleErrorAction } from '@/features/analytics/lib/googleErrors';
import type { AnalyticsRange } from '@/features/analytics/types/AnalyticsRange';
import type { AnalyticsSearchRow } from '@/features/analytics/types/AnalyticsSearchRow';
import type { AnalyticsSearchDimension } from '@/features/analytics/types/AnalyticsSearchReport';
import type { AnalyticsSite } from '@/features/analytics/types/AnalyticsSite';
import { Card } from '@/shared/components/Card';
import { SegmentedControl } from '@/shared/components/SegmentedControl';

interface AnalyticsSearchSectionProps {
  projectId: string;
  range: AnalyticsRange;
  site: AnalyticsSite;
  /** Bumped by the panel's refresh button. */
  revision: number;
  onAction: (action: GoogleErrorAction) => void;
}

const COUNT = (row: AnalyticsSearchRow) => formatMetricCount(row.clicks);

const columns = (name: string): AnalyticsTableColumn<AnalyticsSearchRow>[] => [
  { id: 'key', label: name, value: (row) => row.key, render: (row) => row.key },
  { id: 'clicks', label: 'Clicks', value: (row) => row.clicks, render: COUNT },
  { id: 'impressions', label: 'Impressions', value: (row) => row.impressions, render: (row) => formatMetricCount(row.impressions) },
  { id: 'ctr', label: 'CTR', value: (row) => row.ctr, render: (row) => formatRatio(row.ctr) },
  { id: 'position', label: 'Position', value: (row) => row.position, render: (row) => formatPosition(row.position), ascending: true },
];

const TABLES = [
  { id: 'query', label: 'Queries' },
  { id: 'page', label: 'Pages' },
] as const;

const BREAKDOWNS = [
  { dimension: 'device', title: 'Devices', name: 'Device' },
  { dimension: 'country', title: 'Countries', name: 'Country' },
] as const;

const Tile = ({ label, value }: { label: string; value: string }) => (
  <Card as="li" className="flex flex-col gap-1.5 p-4">
    <span className="text-[12px] font-medium text-ink-muted">{label}</span>
    <span className="text-2xl font-semibold tracking-[-0.03em] tabular-nums">{value}</span>
  </Card>
);

const RowsCard = ({ projectId, range, site, revision, dimension, title, name, onAction, children }: AnalyticsSearchSectionProps & {
  dimension: AnalyticsSearchDimension; title: string; name: string; children?: React.ReactNode;
}) => {
  const { data, loading, error, errorCode, reload } = useAnalyticsReport(
    () => fetchAnalyticsSearch(projectId, range, dimension, dimension === 'query' || dimension === 'page' ? 25 : 10),
    `${projectId}|${range.from}|${range.to}|${site.searchConsoleSiteUrl}|${site.googleConnectionId}|${dimension}|${revision}`,
  );
  return (
    <Card as="section" className="flex min-w-0 flex-col gap-3 p-4 sm:p-5">
      <header className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="min-w-0 truncate text-sm font-semibold">{title}</h3>
        {children}
      </header>
      {error ? (
        errorCode ? <AnalyticsGoogleErrorState code={errorCode} message={error} onAction={(action) => (action === 'retry' ? reload() : onAction(action))} /> : (
          <div role="alert" className="flex flex-col items-start gap-2 text-[13px] text-danger">
            <span>{error}</span>
            <button type="button" onClick={reload} className="inline-flex items-center gap-1 text-ink-muted hover:text-ink"><RotateCw className="size-3.5" />Retry</button>
          </div>
        )
      ) : loading && !data ? (
        <ul aria-busy className="flex flex-col gap-2.5">{[0, 1, 2, 3].map((index) => <li key={index} className="h-6 animate-pulse-soft rounded-chip bg-surface-3" />)}</ul>
      ) : (
        <AnalyticsSortableTable rows={data?.rows ?? []} columns={columns(name)} rowKey={(row) => row.key} defaultSort="clicks"
          emptyText="No search data in this range." />
      )}
    </Card>
  );
};

/** Search Console: totals, the position trend (inverted axis), top queries and pages, devices and countries. */
export const AnalyticsSearchSection = (props: AnalyticsSearchSectionProps) => {
  const { projectId, range, site, revision, onAction } = props;
  const [table, setTable] = useState<'query' | 'page'>('query');
  const ready = Boolean(site.googleConnectionId && site.searchConsoleSiteUrl);
  const daily = useAnalyticsReport(
    ready ? () => fetchAnalyticsSearch(projectId, range, 'date', 1000) : null,
    `${projectId}|${range.from}|${range.to}|${site.searchConsoleSiteUrl}|${site.googleConnectionId}|date|${revision}`,
  );
  const missing = !site.googleConnectionId ? 'google_not_connected' : !site.searchConsoleSiteUrl ? 'google_property_not_selected' : null;
  const totals = daily.data?.totals;

  return (
    <section aria-labelledby="analytics-search" className="flex min-w-0 flex-col gap-3">
      <h2 id="analytics-search" className="text-[13px] font-semibold tracking-[0.02em] text-ink-muted uppercase">Search positioning</h2>
      {missing ? (
        <AnalyticsGoogleErrorState code={missing} onAction={onAction} />
      ) : daily.error ? (
        <AnalyticsGoogleErrorState code={daily.errorCode} message={daily.error} onAction={(action) => (action === 'retry' ? daily.reload() : onAction(action))} />
      ) : !daily.data ? (
        <div aria-busy className="flex flex-col gap-3">
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">{[0, 1, 2, 3].map((index) => <Card key={index} className="h-24 animate-pulse-soft" />)}</div>
          <Card className="h-72 animate-pulse-soft" />
        </div>
      ) : (
        <>
          <p className="truncate font-mono text-[12px] text-ink-subtle" title={daily.data.siteUrl}>{daily.data.siteUrl}</p>
          <p className="rounded-control border border-line bg-surface-2/60 px-3 py-2 text-[12px] text-ink-muted" role="note">
            {daily.data.dataUntil
              ? `Search Console data lags by a couple of days: the latest day available is ${formatBucket(daily.data.dataUntil, false)}.`
              : 'Search Console has no data for this range yet. It lags by a couple of days.'}
          </p>
          <ul className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <Tile label="Clicks" value={formatMetricCount(totals?.clicks ?? 0)} />
            <Tile label="Impressions" value={formatMetricCount(totals?.impressions ?? 0)} />
            <Tile label="CTR" value={formatRatio(totals?.ctr ?? 0)} />
            <Tile label="Avg. position" value={formatPosition(totals?.position ?? 0)} />
          </ul>
          <Card className="flex flex-col gap-4 p-4 sm:p-5">
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <h3 className="text-sm font-semibold">Position over time</h3>
              <span className="text-[11px] text-ink-subtle">Lower is better</span>
            </div>
            <AnalyticsPositionChart rows={daily.data.rows} />
          </Card>
          <RowsCard {...props} dimension={table} title={table === 'query' ? 'Top queries' : 'Top pages'} name={table === 'query' ? 'Query' : 'Page'}>
            <SegmentedControl items={TABLES.map((item) => ({ id: item.id, label: item.label }))} value={table} onChange={(id) => setTable(id as 'query' | 'page')} />
          </RowsCard>
          <div className="grid gap-3 md:grid-cols-2">
            {BREAKDOWNS.map((item) => <RowsCard key={item.dimension} {...props} dimension={item.dimension} title={item.title} name={item.name} />)}
          </div>
        </>
      )}
    </section>
  );
};
