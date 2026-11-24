/** `bounceRate` is a 0..1 ratio and `averageSessionDuration` is in seconds. */
export interface AnalyticsGa4Metrics {
  activeUsers: number;
  sessions: number;
  screenPageViews: number;
  eventCount: number;
  bounceRate: number;
  averageSessionDuration: number;
}

export interface AnalyticsGa4Row {
  key: string;
  metrics: AnalyticsGa4Metrics;
}

export type AnalyticsGa4ReportKind = 'summary' | 'pages' | 'sources' | 'timeseries';

export interface AnalyticsGa4Report {
  propertyId: string;
  report: AnalyticsGa4ReportKind;
  from: string;
  to: string;
  totals: AnalyticsGa4Metrics;
  rows: AnalyticsGa4Row[];
  fetchedAt: string;
}
