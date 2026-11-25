import { RotateCw } from 'lucide-react';
import { fetchAnalyticsBreakdown } from '@/features/analytics/api/analyticsApi';
import { useAnalyticsReport } from '@/features/analytics/hooks/useAnalyticsReport';
import { formatMetricCount } from '@/features/analytics/lib/analyticsFormat';
import type { AnalyticsBreakdownRow } from '@/features/analytics/types/AnalyticsBreakdownRow';
import type { AnalyticsDimension } from '@/features/analytics/types/AnalyticsDimension';
import type { AnalyticsRange } from '@/features/analytics/types/AnalyticsRange';
import { Card } from '@/shared/components/Card';
import { AnalyticsSource } from '@/features/analytics/components/AnalyticsSource';

interface AnalyticsBreakdownCardProps {
  projectId: string;
  range: AnalyticsRange;
  dimension: AnalyticsDimension;
  title: string;
  /** Which count ranks and is drawn: visitors, page views or clicks. */
  measure: 'visitors' | 'pageviews' | 'clicks';
  /** Hide the placeholder bucket (countries the server could not resolve). */
  hideUnknown?: boolean;
  emptyText?: string;
}

const MEASURE_LABEL = { visitors: 'visitors', pageviews: 'page views', clicks: 'clicks' } as const;

/** A ranked bar list for one dimension; loads on its own so a slow report never blocks the others. */
export const AnalyticsBreakdownCard = ({ projectId, range, dimension, title, measure, hideUnknown = false, emptyText = 'Nothing recorded in this range.' }: AnalyticsBreakdownCardProps) => {
  const { data, loading, error, reload } = useAnalyticsReport<AnalyticsBreakdownRow[]>(
    () => fetchAnalyticsBreakdown(projectId, range, dimension),
    `${projectId}|${range.from}|${range.to}|${dimension}`,
    projectId,
  );
  const rows = (data ?? [])
    .filter((row) => !(hideUnknown && row.key === '(unknown)'))
    .sort((a, b) => b[measure] - a[measure])
    .filter((row) => row[measure] > 0);
  const top = rows[0]?.[measure] ?? 0;

  return (
    <Card as="section" className="flex flex-col gap-3 p-4 sm:p-5">
      <header className="flex items-baseline justify-between gap-2">
        <h3 className="min-w-0 truncate text-sm font-semibold">{title}</h3>
        <span className="shrink-0 text-[11px] text-ink-subtle">{MEASURE_LABEL[measure]}</span>
      </header>
      <AnalyticsSource name="Mooi tracker" />
      {error ? (
        <div role="alert" className="flex flex-col items-start gap-2 text-[13px] text-danger">
          <span>{error}</span>
          <button type="button" onClick={reload} className="inline-flex items-center gap-1 text-ink-muted hover:text-ink"><RotateCw className="size-3.5" />Retry</button>
        </div>
      ) : loading && !data ? (
        <ul aria-busy className="flex flex-col gap-2.5">
          {[0, 1, 2, 3].map((index) => <li key={index} className="h-6 animate-pulse-soft rounded-chip bg-surface-3" />)}
        </ul>
      ) : rows.length === 0 ? (
        <p className="py-4 text-center text-[13px] text-ink-muted">{emptyText}</p>
      ) : (
        <ul className="flex flex-col gap-1.5">
          {rows.map((row) => (
            <li key={row.key} className="group relative flex min-h-8 items-center gap-3 overflow-hidden rounded-chip px-2 text-[13px]" title={`${row.key}: ${row[measure].toLocaleString('en')} ${MEASURE_LABEL[measure]}`}>
              <span aria-hidden className="absolute inset-y-0 left-0 rounded-chip bg-brand/12 transition-[width]" style={{ width: `${Math.max(2, (row[measure] / top) * 100)}%` }} />
              <span className="relative min-w-0 flex-1 truncate font-mono text-[12px]">{row.key}</span>
              <span className="relative shrink-0 font-medium tabular-nums">{formatMetricCount(row[measure])}</span>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
};
