import { useCallback, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { BarChart3, RotateCw, Settings2 } from 'lucide-react';
import { fetchAnalyticsOverview, fetchAnalyticsSeries } from '@/features/analytics/api/analyticsApi';
import { AnalyticsBreakdownCard } from '@/features/analytics/components/AnalyticsBreakdownCard';
import { AnalyticsGa4Section } from '@/features/analytics/components/AnalyticsGa4Section';
import { AnalyticsGeoSection } from '@/features/analytics/components/AnalyticsGeoSection';
import { AnalyticsKpis } from '@/features/analytics/components/AnalyticsKpis';
import { AnalyticsRangePicker } from '@/features/analytics/components/AnalyticsRangePicker';
import { AnalyticsSearchSection } from '@/features/analytics/components/AnalyticsSearchSection';
import { AnalyticsSetupPanel } from '@/features/analytics/components/AnalyticsSetupPanel';
import { AnalyticsTimeSeriesChart } from '@/features/analytics/components/AnalyticsTimeSeriesChart';
import { useAnalyticsReport } from '@/features/analytics/hooks/useAnalyticsReport';
import { useAnalyticsSite } from '@/features/analytics/hooks/useAnalyticsSite';
import { useLiveVisitors } from '@/features/analytics/hooks/useLiveVisitors';
import type { GoogleErrorAction } from '@/features/analytics/lib/googleErrors';
import { analyticsRangeKey, resolveAnalyticsRange } from '@/features/analytics/lib/analyticsRange';
import type { AnalyticsMetric } from '@/features/analytics/types/AnalyticsMetric';
import type { AnalyticsOverview } from '@/features/analytics/types/AnalyticsOverview';
import type { AnalyticsRangeKey } from '@/features/analytics/types/AnalyticsRangeKey';
import type { AnalyticsSeriesPoint } from '@/features/analytics/types/AnalyticsSeriesPoint';
import { startGoogleAuthorization } from '@/features/google/api/googleApi';
import { Button } from '@/shared/components/Button';
import { Card } from '@/shared/components/Card';
import { EmptyState } from '@/shared/components/EmptyState';
import { SegmentedControl } from '@/shared/components/SegmentedControl';

interface AnalyticsPanelProps {
  projectId: string;
  onFix: (request: string) => void;
}

const METRICS: { id: AnalyticsMetric; label: string }[] = [
  { id: 'visitors', label: 'Visitors' },
  { id: 'pageviews', label: 'Page views' },
  { id: 'clicks', label: 'Clicks' },
];

const GROUP_TITLE = 'text-[13px] font-semibold tracking-[0.02em] text-ink-muted uppercase';

/**
 * The Analytics page of a web-application project: tracker setup, overview, traffic and clicks of its
 * production app. The range lives in the URL (`range`, and `from`/`to` for a custom one).
 */
export const AnalyticsPanel = ({ projectId, onFix }: AnalyticsPanelProps) => {
  const [searchParams, setSearchParams] = useSearchParams();
  const key = analyticsRangeKey(searchParams.get('range'));
  const fromParam = searchParams.get('from');
  const toParam = searchParams.get('to');
  // Presets end "now": recompute only when the choice changes or the user refreshes.
  const [refresh, setRefresh] = useState(0);
  const range = useMemo(() => resolveAnalyticsRange(key, fromParam, toParam), [key, fromParam, toParam, refresh]);
  const [metric, setMetric] = useState<AnalyticsMetric>('visitors');
  const [setupOpen, setSetupOpen] = useState(false);

  const siteState = useAnalyticsSite(projectId);
  const live = useLiveVisitors(projectId);
  const reportKey = `${projectId}|${range.from}|${range.to}`;
  const overview = useAnalyticsReport<AnalyticsOverview>(() => fetchAnalyticsOverview(projectId, range), reportKey, projectId);
  const series = useAnalyticsReport<AnalyticsSeriesPoint[]>(() => fetchAnalyticsSeries(projectId, range, metric), `${reportKey}|${metric}`, projectId);

  const update = useCallback((next: Record<string, string>) => {
    const params = new URLSearchParams(searchParams);
    params.delete('range'); params.delete('from'); params.delete('to');
    Object.entries(next).forEach(([name, value]) => params.set(name, value));
    setSearchParams(params, { replace: true });
  }, [searchParams, setSearchParams]);

  const onPreset = (next: AnalyticsRangeKey) => update({ range: next });
  const onCustom = (from: string, to: string) => update({ range: 'custom', from, to: to < from ? from : to });

  const hasData = overview.data ? overview.data.pageviews.value + overview.data.clicks.value + overview.data.visitors.value > 0 : null;
  const reload = () => { setRefresh((value) => value + 1); overview.reload(); series.reload(); };

  const onGoogleAction = (action: GoogleErrorAction) => {
    if (action === 'retry') { setRefresh((value) => value + 1); return; }
    if (action === 'pick') { setSetupOpen(true); return; }
    // connect / relink: the account's Google link flow; it returns to the account page.
    const connectionId = action === 'relink' ? siteState.site?.googleConnectionId ?? undefined : undefined;
    void startGoogleAuthorization(connectionId).then((url) => window.location.assign(url)).catch(() => setSetupOpen(true));
  };

  return (
    <div className="flex min-w-0 flex-col gap-6">
      <div className="flex min-w-0 flex-wrap items-center justify-between gap-3">
        <AnalyticsRangePicker range={range} onPreset={onPreset} onCustom={onCustom} />
        <div className="flex items-center gap-2">
          <span className="inline-flex h-9 items-center gap-2 rounded-full border border-line bg-surface px-3 text-[13px] text-ink-muted" title="Visitors in the last 5 minutes">
            <span aria-hidden className={`size-2 rounded-full ${live ? 'bg-success-dot' : 'bg-line-strong'}`} />
            <span className="font-medium text-ink tabular-nums">{live ?? '–'}</span> live
          </span>
          <Button variant="secondary" size="sm" onClick={reload} ariaLabel="Refresh analytics"><RotateCw className="size-4" /></Button>
          {hasData ? <Button variant="secondary" size="sm" onClick={() => setSetupOpen((open) => !open)}><Settings2 className="size-4" />Setup</Button> : null}
        </div>
      </div>

      {siteState.error ? (
        <Card className="flex flex-wrap items-center justify-between gap-3 p-5" >
          <p role="alert" className="text-sm text-danger">{siteState.error}</p>
          <Button variant="secondary" size="sm" onClick={siteState.reload}>Retry</Button>
        </Card>
      ) : siteState.loading && !siteState.site ? (
        <Card className="h-40 animate-pulse-soft" />
      ) : siteState.site && (hasData === false || setupOpen) ? (
        <AnalyticsSetupPanel projectId={projectId} site={siteState.site} busy={siteState.busy} error={siteState.actionError}
          onSaveOrigins={siteState.saveOrigins} onRegenerateKey={siteState.regenerateKey} onSaveGoogle={siteState.saveGoogle} />
      ) : null}

      {overview.error ? (
        <Card className="flex flex-wrap items-center justify-between gap-3 p-5">
          <p role="alert" className="text-sm text-danger">{overview.error}</p>
          <Button variant="secondary" size="sm" onClick={reload}>Retry</Button>
        </Card>
      ) : !overview.data ? (
        <div aria-busy className="flex flex-col gap-4">
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
            {[0, 1, 2, 3, 4].map((index) => <Card key={index} className="h-24 animate-pulse-soft" />)}
          </div>
          <Card className="h-72 animate-pulse-soft" />
        </div>
      ) : hasData === false ? (
        <EmptyState icon={BarChart3} title="No Mooi tracker data in this range yet"
          description="Install the Mooi tracker to measure production traffic and clicks, or pick a wider range. Google Search Console and Analytics reports below use their own Google data sources.">
          <Button variant="secondary" onClick={() => update({ range: '90d' })}>Show the last 90 days</Button>
        </EmptyState>
      ) : (
        <>
          <section aria-label="Overview" className="flex flex-col gap-4">
            <AnalyticsKpis overview={overview.data} />
            <Card className="flex flex-col gap-4 p-4 sm:p-5">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <h3 className="text-sm font-semibold">Over time</h3>
                <SegmentedControl items={METRICS} value={metric} onChange={(id) => setMetric(id as AnalyticsMetric)} />
              </div>
              {series.error ? <p role="alert" className="text-[13px] text-danger">{series.error}</p>
                : !series.data ? <div className="h-56 animate-pulse-soft rounded-control bg-surface-3" />
                : <AnalyticsTimeSeriesChart points={series.data} label={METRICS.find((item) => item.id === metric)?.label ?? ''} />}
            </Card>
          </section>

          <section aria-labelledby="analytics-traffic" className="flex flex-col gap-3">
            <div><h2 id="analytics-traffic" className={GROUP_TITLE}>Traffic · Mooi tracker</h2><p className="mt-1 text-xs text-ink-subtle">First-party production visits and clicks recorded by the installed Mooi snippet.</p></div>
            <div className="grid gap-3 md:grid-cols-2">
              <AnalyticsBreakdownCard projectId={projectId} range={range} dimension="page" title="Top pages" measure="pageviews" />
              <AnalyticsBreakdownCard projectId={projectId} range={range} dimension="referrer" title="Referrers" measure="visitors" />
              <AnalyticsBreakdownCard projectId={projectId} range={range} dimension="utm_campaign" title="UTM campaigns" measure="visitors" hideUnknown emptyText="No campaign-tagged visits." />
              <AnalyticsBreakdownCard projectId={projectId} range={range} dimension="country" title="Countries" measure="visitors" hideUnknown emptyText="Countries are unavailable behind this host." />
              <AnalyticsBreakdownCard projectId={projectId} range={range} dimension="device" title="Devices" measure="visitors" />
              <AnalyticsBreakdownCard projectId={projectId} range={range} dimension="browser" title="Browsers" measure="visitors" />
              <AnalyticsBreakdownCard projectId={projectId} range={range} dimension="os" title="Operating systems" measure="visitors" />
            </div>
          </section>

          <section aria-labelledby="analytics-clicks" className="flex flex-col gap-3">
            <h2 id="analytics-clicks" className={GROUP_TITLE}>Clicks</h2>
            <div className="grid gap-3 md:grid-cols-2">
              <AnalyticsBreakdownCard projectId={projectId} range={range} dimension="click_target" title="Top clicked elements" measure="clicks" emptyText="No clicks recorded in this range." />
              <AnalyticsBreakdownCard projectId={projectId} range={range} dimension="outbound" title="Outbound links" measure="clicks" emptyText="No outbound link clicks." />
            </div>
          </section>
        </>
      )}

      {siteState.site ? (
        <>
          <AnalyticsSearchSection projectId={projectId} range={range} site={siteState.site} revision={refresh} onAction={onGoogleAction} />
          <AnalyticsGa4Section projectId={projectId} range={range} site={siteState.site} revision={refresh} onAction={onGoogleAction} />
        </>
      ) : null}
      <AnalyticsGeoSection projectId={projectId} range={range} onFix={onFix} />
    </div>
  );
};
