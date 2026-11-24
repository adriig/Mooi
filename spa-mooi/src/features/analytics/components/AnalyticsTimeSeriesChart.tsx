import { useMemo, useState, type KeyboardEvent, type MouseEvent } from 'react';
import { formatBucket, formatMetricCount, parseBucket } from '@/features/analytics/lib/analyticsFormat';
import type { AnalyticsSeriesPoint } from '@/features/analytics/types/AnalyticsSeriesPoint';

interface AnalyticsTimeSeriesChartProps {
  points: readonly AnalyticsSeriesPoint[];
  label: string;
}

const WIDTH = 100;
const HEIGHT = 100;

/** Rounds the axis maximum up to 1, 2, 5 × 10ⁿ so the gridlines read as plain numbers. */
const niceMax = (value: number): number => {
  if (value <= 1) return 1;
  const magnitude = 10 ** Math.floor(Math.log10(value));
  const step = [1, 2, 5, 10].find((candidate) => candidate * magnitude >= value) ?? 10;
  return step * magnitude;
};

/**
 * One metric over time as a responsive SVG area + line. Hover (mouse) previews a bucket, click / tap pins
 * it, ←/→ move through buckets; the readout below the plot is the accessible, exact value.
 */
export const AnalyticsTimeSeriesChart = ({ points, label }: AnalyticsTimeSeriesChartProps) => {
  const [hovered, setHovered] = useState<number | null>(null);
  const [pinned, setPinned] = useState<number | null>(null);
  const max = useMemo(() => niceMax(points.reduce((top, point) => Math.max(top, point.value), 0)), [points]);
  const hourly = useMemo(() => points.length > 1 && parseBucket(points[1].bucket).getTime() - parseBucket(points[0].bucket).getTime() < 86_400_000, [points]);
  const total = useMemo(() => points.reduce((sum, point) => sum + point.value, 0), [points]);

  const x = (index: number) => (points.length <= 1 ? WIDTH / 2 : (index / (points.length - 1)) * WIDTH);
  const y = (value: number) => HEIGHT - (value / max) * HEIGHT;
  const line = points.map((point, index) => `${index === 0 ? 'M' : 'L'}${x(index).toFixed(2)},${y(point.value).toFixed(2)}`).join(' ');
  const area = points.length > 0 ? `${line} L${x(points.length - 1).toFixed(2)},${HEIGHT} L${x(0).toFixed(2)},${HEIGHT} Z` : '';

  const shownIndex = hovered ?? pinned;
  const shown = shownIndex !== null ? points[shownIndex] ?? null : null;

  const indexAt = (event: MouseEvent<HTMLDivElement>): number => {
    const box = event.currentTarget.getBoundingClientRect();
    const ratio = box.width > 0 ? (event.clientX - box.left) / box.width : 0;
    return Math.min(points.length - 1, Math.max(0, Math.round(ratio * (points.length - 1))));
  };

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    const last = points.length - 1;
    const current = pinned ?? (event.key === 'ArrowLeft' ? last + 1 : -1);
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? last : current + (event.key === 'ArrowLeft' ? -1 : 1);
    setPinned(Math.min(last, Math.max(0, next)));
  };

  if (points.length === 0 || total === 0) {
    return <p className="px-1 py-10 text-center text-sm text-ink-muted">No {label.toLowerCase()} in this range.</p>;
  }

  return (
    <div className="flex min-w-0 flex-col gap-3">
      <div className="flex min-w-0 gap-2">
        <div aria-hidden className="flex w-9 shrink-0 flex-col justify-between py-px text-right font-mono text-[10px] text-ink-subtle tabular-nums">
          <span>{formatMetricCount(max)}</span>
          <span>{formatMetricCount(max / 2)}</span>
          <span>0</span>
        </div>
        <div
          role="group"
          tabIndex={0}
          aria-label={`${label} over time, ${points.length} points; use the left and right arrows to inspect one`}
          onKeyDown={onKeyDown}
          onPointerMove={(event) => { if (event.pointerType === 'mouse') setHovered(indexAt(event)); }}
          onPointerLeave={() => setHovered(null)}
          onClick={(event) => { const index = indexAt(event); setPinned((current) => (current === index ? null : index)); }}
          className="relative h-44 min-w-0 flex-1 cursor-crosshair touch-pan-y rounded-control focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand sm:h-56"
        >
          <div aria-hidden className="pointer-events-none absolute inset-0 flex flex-col justify-between">
            <span className="border-t border-dashed border-line" />
            <span className="border-t border-dashed border-line" />
            <span className="border-t border-line-strong" />
          </div>
          <svg aria-hidden viewBox={`0 0 ${WIDTH} ${HEIGHT}`} preserveAspectRatio="none" className="absolute inset-0 size-full overflow-visible">
            <path d={area} className="fill-brand/15" />
            <path d={line} fill="none" strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" vectorEffect="non-scaling-stroke" className="stroke-brand" />
            {shownIndex !== null && shown ? (
              <line x1={x(shownIndex)} x2={x(shownIndex)} y1={0} y2={HEIGHT} strokeWidth={1} vectorEffect="non-scaling-stroke" className="stroke-line-strong" />
            ) : null}
          </svg>
          {shownIndex !== null && shown ? (
            <span
              aria-hidden
              className="pointer-events-none absolute size-2.5 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-surface bg-brand"
              style={{ left: `${x(shownIndex)}%`, top: `${y(shown.value)}%` }}
            />
          ) : null}
        </div>
      </div>

      <p aria-live="polite" className="flex min-w-0 flex-wrap items-baseline gap-x-2 rounded-control border border-line bg-surface-2/60 px-3 py-2 text-[12px] text-ink-muted">
        <span className="font-semibold text-ink">{shown ? formatBucket(shown.bucket, hourly) : 'Total'}</span>
        <span className="font-mono text-ink tabular-nums">{(shown ? shown.value : total).toLocaleString('en')}</span>
        <span>{label.toLowerCase()}</span>
      </p>
    </div>
  );
};
