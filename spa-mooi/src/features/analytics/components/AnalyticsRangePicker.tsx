import { RANGE_ITEMS } from '@/features/analytics/lib/analyticsRange';
import type { AnalyticsRange } from '@/features/analytics/types/AnalyticsRange';
import type { AnalyticsRangeKey } from '@/features/analytics/types/AnalyticsRangeKey';
import { SegmentedControl } from '@/shared/components/SegmentedControl';

interface AnalyticsRangePickerProps {
  range: AnalyticsRange;
  onPreset: (key: AnalyticsRangeKey) => void;
  onCustom: (from: string, to: string) => void;
}

const dateField = 'h-9 min-w-0 rounded-control border border-line bg-surface-2 px-2.5 text-[13px] text-ink outline-none focus:border-brand/50';

export const AnalyticsRangePicker = ({ range, onPreset, onCustom }: AnalyticsRangePickerProps) => (
  <div className="flex min-w-0 flex-wrap items-center gap-2">
    <SegmentedControl items={RANGE_ITEMS} value={range.key} onChange={(id) => (id === 'custom' ? onCustom(range.fromDate, range.toDate) : onPreset(id as AnalyticsRangeKey))} />
    {range.key === 'custom' ? (
      <div className="flex min-w-0 flex-wrap items-center gap-2 text-[13px] text-ink-muted">
        <input type="date" aria-label="From date" value={range.fromDate} max={range.toDate} onChange={(event) => event.target.value && onCustom(event.target.value, range.toDate)} className={dateField} />
        <span aria-hidden>→</span>
        <input type="date" aria-label="To date" value={range.toDate} min={range.fromDate} onChange={(event) => event.target.value && onCustom(range.fromDate, event.target.value)} className={dateField} />
      </div>
    ) : null}
  </div>
);
