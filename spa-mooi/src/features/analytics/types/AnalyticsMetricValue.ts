export interface AnalyticsMetricValue {
  value: number;
  previous: number | null;
  /** Null when the previous period had no data to compare with. */
  deltaPercent: number | null;
}
