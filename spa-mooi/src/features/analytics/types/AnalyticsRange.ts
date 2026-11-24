import type { AnalyticsRangeKey } from '@/features/analytics/types/AnalyticsRangeKey';

/** A resolved reporting window: instants for the API plus the URL-facing choice. */
export interface AnalyticsRange {
  key: AnalyticsRangeKey;
  from: string;
  to: string;
  /** Local `YYYY-MM-DD` bounds, meaningful for `custom`. */
  fromDate: string;
  toDate: string;
  timezone: string;
}
