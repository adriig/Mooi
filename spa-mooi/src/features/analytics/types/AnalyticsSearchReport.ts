import type { AnalyticsSearchRow } from '@/features/analytics/types/AnalyticsSearchRow';

export type AnalyticsSearchDimension = 'query' | 'page' | 'country' | 'device' | 'date';

export interface AnalyticsSearchReport {
  siteUrl: string;
  dimension: AnalyticsSearchDimension;
  from: string;
  to: string;
  /** Latest day Search Console has data for; it lags by a couple of days. Null when it has none. */
  dataUntil: string | null;
  totals: AnalyticsSearchRow;
  rows: AnalyticsSearchRow[];
  fetchedAt: string;
}
