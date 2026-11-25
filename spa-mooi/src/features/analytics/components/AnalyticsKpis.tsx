import { ArrowDownRight, ArrowUpRight } from 'lucide-react';
import { formatMetricCount, formatPercent, formatVisitDuration } from '@/features/analytics/lib/analyticsFormat';
import type { AnalyticsMetricValue } from '@/features/analytics/types/AnalyticsMetricValue';
import type { AnalyticsOverview } from '@/features/analytics/types/AnalyticsOverview';
import { Card } from '@/shared/components/Card';
import { AnalyticsSource } from '@/features/analytics/components/AnalyticsSource';
import { cn } from '@/shared/utils/cn';

interface Tile {
  label: string;
  metric: AnalyticsMetricValue;
  format: (value: number) => string;
  /** Rising is bad (bounce rate). */
  inverse?: boolean;
}

const Delta = ({ metric, inverse }: { metric: AnalyticsMetricValue; inverse?: boolean }) => {
  if (metric.deltaPercent === null) {
    return <span className="text-[12px] text-ink-subtle" title="Nothing to compare with in the previous period">New</span>;
  }
  const delta = metric.deltaPercent;
  if (Math.abs(delta) < 0.05) return <span className="text-[12px] text-ink-subtle">0%</span>;
  const up = delta > 0;
  const good = up !== Boolean(inverse);
  const Icon = up ? ArrowUpRight : ArrowDownRight;
  return (
    <span className={cn('inline-flex items-center gap-0.5 text-[12px] font-medium tabular-nums', good ? 'text-success' : 'text-danger')}
      title={`Previous period: ${metric.previous ?? 0}`}>
      <Icon aria-hidden className="size-3.5" />
      {Math.abs(delta).toFixed(Math.abs(delta) >= 10 ? 0 : 1)}%
      <span className="sr-only">{up ? ' up' : ' down'} versus the previous period</span>
    </span>
  );
};

export const AnalyticsKpis = ({ overview }: { overview: AnalyticsOverview }) => {
  const tiles: Tile[] = [
    { label: 'Visitors', metric: overview.visitors, format: formatMetricCount },
    { label: 'Page views', metric: overview.pageviews, format: formatMetricCount },
    { label: 'Clicks', metric: overview.clicks, format: formatMetricCount },
    { label: 'Bounce rate', metric: overview.bounceRate, format: formatPercent, inverse: true },
    { label: 'Avg. visit', metric: overview.avgVisitDurationMs, format: formatVisitDuration },
  ];

  return (
    <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
      {tiles.map((tile) => (
        <Card as="li" key={tile.label} className="flex flex-col gap-1.5 p-4">
          <span className="text-[12px] font-medium text-ink-muted">{tile.label}</span>
          <span className="text-2xl font-semibold tracking-[-0.03em] tabular-nums" title={tile.metric.value.toLocaleString('en')}>{tile.format(tile.metric.value)}</span>
          <Delta metric={tile.metric} inverse={tile.inverse} />
          <AnalyticsSource name="Mooi tracker" />
        </Card>
      ))}
    </ul>
  );
};
