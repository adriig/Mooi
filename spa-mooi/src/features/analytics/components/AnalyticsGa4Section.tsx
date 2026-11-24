import { RotateCw } from 'lucide-react';
import { fetchAnalyticsGa4 } from '@/features/analytics/api/analyticsApi';
import { AnalyticsGoogleErrorState } from '@/features/analytics/components/AnalyticsGoogleErrorState';
import { AnalyticsSortableTable, type AnalyticsTableColumn } from '@/features/analytics/components/AnalyticsSortableTable';
import { AnalyticsTimeSeriesChart } from '@/features/analytics/components/AnalyticsTimeSeriesChart';
import { useAnalyticsReport } from '@/features/analytics/hooks/useAnalyticsReport';
import { formatMetricCount, formatRatio, formatVisitDuration } from '@/features/analytics/lib/analyticsFormat';
import type { GoogleErrorAction } from '@/features/analytics/lib/googleErrors';
import type { AnalyticsGa4ReportKind, AnalyticsGa4Row } from '@/features/analytics/types/AnalyticsGa4Report';
import type { AnalyticsRange } from '@/features/analytics/types/AnalyticsRange';
import type { AnalyticsSite } from '@/features/analytics/types/AnalyticsSite';
import { Card } from '@/shared/components/Card';

interface AnalyticsGa4SectionProps {
  projectId: string;
  range: AnalyticsRange;
  site: AnalyticsSite;
  revision: number;
  onAction: (action: GoogleErrorAction) => void;
}

const columns = (name: string): AnalyticsTableColumn<AnalyticsGa4Row>[] => [
  { id: 'key', label: name, value: (row) => row.key, render: (row) => row.key },
  { id: 'sessions', label: 'Sessions', value: (row) => row.metrics.sessions, render: (row) => formatMetricCount(row.metrics.sessions) },
  { id: 'users', label: 'Users', value: (row) => row.metrics.activeUsers, render: (row) => formatMetricCount(row.metrics.activeUsers) },
  { id: 'views', label: 'Views', value: (row) => row.metrics.screenPageViews, render: (row) => formatMetricCount(row.metrics.screenPageViews) },
];

const Tile = ({ label, value }: { label: string; value: string }) => (
  <Card as="li" className="flex flex-col gap-1.5 p-4">
    <span className="text-[12px] font-medium text-ink-muted">{label}</span>
    <span className="text-2xl font-semibold tracking-[-0.03em] tabular-nums">{value}</span>
  </Card>
);

const RowsCard = ({ projectId, range, site, revision, kind, title, name, onAction }: AnalyticsGa4SectionProps & { kind: AnalyticsGa4ReportKind; title: string; name: string }) => {
  const { data, loading, error, errorCode, reload } = useAnalyticsReport(
    () => fetchAnalyticsGa4(projectId, range, kind),
    `${projectId}|${range.from}|${range.to}|${site.ga4PropertyId}|${site.googleConnectionId}|${kind}|${revision}`,
  );
  return (
    <Card as="section" className="flex min-w-0 flex-col gap-3 p-4 sm:p-5">
      <h3 className="min-w-0 truncate text-sm font-semibold">{title}</h3>
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
        <AnalyticsSortableTable rows={data?.rows ?? []} columns={columns(name)} rowKey={(row) => row.key} defaultSort="sessions" emptyText="Nothing recorded in this range." />
      )}
    </Card>
  );
};

/** Google Analytics 4: summary KPIs, sessions over time, top pages and sources of the linked property. */
export const AnalyticsGa4Section = (props: AnalyticsGa4SectionProps) => {
  const { projectId, range, site, revision, onAction } = props;
  const ready = Boolean(site.googleConnectionId && site.ga4PropertyId);
  const missing = !site.googleConnectionId ? 'google_not_connected' : !site.ga4PropertyId ? 'google_property_not_selected' : null;
  const key = `${projectId}|${range.from}|${range.to}|${site.ga4PropertyId}|${site.googleConnectionId}|${revision}`;
  const summary = useAnalyticsReport(ready ? () => fetchAnalyticsGa4(projectId, range, 'summary') : null, `${key}|summary`);
  const series = useAnalyticsReport(ready ? () => fetchAnalyticsGa4(projectId, range, 'timeseries') : null, `${key}|timeseries`);
  const totals = summary.data?.totals;

  return (
    <section aria-labelledby="analytics-ga4" className="flex min-w-0 flex-col gap-3">
      <h2 id="analytics-ga4" className="text-[13px] font-semibold tracking-[0.02em] text-ink-muted uppercase">Google Analytics</h2>
      {missing ? (
        <AnalyticsGoogleErrorState code={missing} onAction={onAction} />
      ) : summary.error ? (
        <AnalyticsGoogleErrorState code={summary.errorCode} message={summary.error} onAction={(action) => (action === 'retry' ? summary.reload() : onAction(action))} />
      ) : !summary.data ? (
        <div aria-busy className="flex flex-col gap-3">
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">{[0, 1, 2, 3, 4, 5].map((index) => <Card key={index} className="h-24 animate-pulse-soft" />)}</div>
          <Card className="h-72 animate-pulse-soft" />
        </div>
      ) : (
        <>
          <p className="truncate font-mono text-[12px] text-ink-subtle">Property {summary.data.propertyId}</p>
          <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
            <Tile label="Users" value={formatMetricCount(totals?.activeUsers ?? 0)} />
            <Tile label="Sessions" value={formatMetricCount(totals?.sessions ?? 0)} />
            <Tile label="Page views" value={formatMetricCount(totals?.screenPageViews ?? 0)} />
            <Tile label="Events" value={formatMetricCount(totals?.eventCount ?? 0)} />
            <Tile label="Bounce rate" value={formatRatio(totals?.bounceRate ?? 0)} />
            <Tile label="Avg. session" value={formatVisitDuration((totals?.averageSessionDuration ?? 0) * 1000)} />
          </ul>
          <Card className="flex flex-col gap-4 p-4 sm:p-5">
            <h3 className="text-sm font-semibold">Sessions over time</h3>
            {series.error ? <p role="alert" className="text-[13px] text-danger">{series.error}</p>
              : !series.data ? <div className="h-56 animate-pulse-soft rounded-control bg-surface-3" />
              : <AnalyticsTimeSeriesChart label="Sessions" points={series.data.rows.map((row) => ({ bucket: row.key, value: row.metrics.sessions }))} />}
          </Card>
          <div className="grid gap-3 md:grid-cols-2">
            <RowsCard {...props} kind="pages" title="Top pages" name="Page" />
            <RowsCard {...props} kind="sources" title="Sources" name="Source" />
          </div>
        </>
      )}
    </section>
  );
};
