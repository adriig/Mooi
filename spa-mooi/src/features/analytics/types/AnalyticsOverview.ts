import type { AnalyticsMetricValue } from '@/features/analytics/types/AnalyticsMetricValue';

export interface AnalyticsOverview {
  visitors: AnalyticsMetricValue;
  sessions: AnalyticsMetricValue;
  pageviews: AnalyticsMetricValue;
  clicks: AnalyticsMetricValue;
  /** Percent, 0-100. */
  bounceRate: AnalyticsMetricValue;
  avgVisitDurationMs: AnalyticsMetricValue;
  aiVisitors: AnalyticsMetricValue;
  /** Visitors seen in the last five minutes. */
  live: number;
}
