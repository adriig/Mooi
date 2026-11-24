import type { AnalyticsRange } from '@/features/analytics/types/AnalyticsRange';
import type { AnalyticsRangeKey } from '@/features/analytics/types/AnalyticsRangeKey';

export const RANGE_ITEMS: { id: AnalyticsRangeKey; label: string }[] = [
  { id: '24h', label: '24h' },
  { id: '7d', label: '7d' },
  { id: '30d', label: '30d' },
  { id: '90d', label: '90d' },
  { id: 'custom', label: 'Custom' },
];

const HOUR = 3_600_000;
const DAY = 24 * HOUR;
const MAX_DAYS = 400;
const PRESETS: Record<Exclude<AnalyticsRangeKey, 'custom'>, number> = { '24h': DAY, '7d': 7 * DAY, '30d': 30 * DAY, '90d': 90 * DAY };
const DATE = /^\d{4}-\d{2}-\d{2}$/;

export const toDateInput = (date: Date): string => {
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
};

const parseLocalDate = (value: string | null): Date | null => {
  if (!value || !DATE.test(value)) return null;
  const date = new Date(`${value}T00:00:00`);
  return Number.isNaN(date.getTime()) ? null : date;
};

export const analyticsRangeKey = (value: string | null): AnalyticsRangeKey =>
  value === '24h' || value === '30d' || value === '90d' || value === 'custom' ? value : '7d';

/**
 * Resolves the URL's `range`, `from` and `to` into the instants the API takes. Presets end now; a custom
 * range covers whole local days from `from` to `to` inclusive. An invalid custom range falls back to 7 days.
 */
export const resolveAnalyticsRange = (key: AnalyticsRangeKey, fromParam: string | null, toParam: string | null, now = Date.now()): AnalyticsRange => {
  const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  if (key === 'custom') {
    const start = parseLocalDate(fromParam);
    const end = parseLocalDate(toParam);
    if (start && end && end >= start) {
      const exclusiveEnd = new Date(end.getFullYear(), end.getMonth(), end.getDate() + 1);
      if (exclusiveEnd.getTime() - start.getTime() <= MAX_DAYS * DAY) {
        return { key, from: start.toISOString(), to: exclusiveEnd.toISOString(), fromDate: toDateInput(start), toDate: toDateInput(end), timezone };
      }
    }
    key = '7d';
  }
  const span = PRESETS[key as Exclude<AnalyticsRangeKey, 'custom'>];
  return {
    key,
    from: new Date(now - span).toISOString(),
    to: new Date(now).toISOString(),
    fromDate: toDateInput(new Date(now - span)),
    toDate: toDateInput(new Date(now)),
    timezone,
  };
};

export const rangeParams = (range: Pick<AnalyticsRange, 'from' | 'to' | 'timezone'>): URLSearchParams =>
  new URLSearchParams({ from: range.from, to: range.to, tz: range.timezone });
