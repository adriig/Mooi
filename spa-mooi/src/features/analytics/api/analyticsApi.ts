import { authenticatedFetch } from '@/features/auth/lib/authenticatedFetch';
import { rangeParams } from '@/features/analytics/lib/analyticsRange';
import type { AnalyticsBreakdownRow } from '@/features/analytics/types/AnalyticsBreakdownRow';
import type { AnalyticsDimension } from '@/features/analytics/types/AnalyticsDimension';
import type { AnalyticsMetric } from '@/features/analytics/types/AnalyticsMetric';
import type { AnalyticsOverview } from '@/features/analytics/types/AnalyticsOverview';
import type { AnalyticsRange } from '@/features/analytics/types/AnalyticsRange';
import type { AnalyticsSeriesPoint } from '@/features/analytics/types/AnalyticsSeriesPoint';
import type { AnalyticsSite } from '@/features/analytics/types/AnalyticsSite';
import { apiErrorMessage } from '@/shared/utils/apiErrorMessage';

const base = (projectId: string) => `/me/projects/${encodeURIComponent(projectId)}/analytics`;

const read = async <T>(response: Response, fallback: string): Promise<T> => {
  if (!response.ok) throw new Error(await apiErrorMessage(response, fallback));
  return (await response.json()) as T;
};

export const fetchAnalyticsSite = async (projectId: string): Promise<AnalyticsSite> =>
  read(await authenticatedFetch(`${base(projectId)}/site`), 'Could not load the analytics setup.');

export const updateAnalyticsOrigins = async (projectId: string, allowedOrigins: string[]): Promise<AnalyticsSite> =>
  read(await authenticatedFetch(`${base(projectId)}/site`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ allowedOrigins }),
  }), 'Could not save the allowed origins.');

export const regenerateAnalyticsKey = async (projectId: string): Promise<AnalyticsSite> =>
  read(await authenticatedFetch(`${base(projectId)}/site/key`, { method: 'POST' }), 'Could not regenerate the site key.');

export const fetchAnalyticsOverview = async (projectId: string, range: Pick<AnalyticsRange, 'from' | 'to' | 'timezone'>): Promise<AnalyticsOverview> =>
  read(await authenticatedFetch(`${base(projectId)}/overview?${rangeParams(range)}`), 'Could not load the overview.');

export const fetchAnalyticsSeries = async (projectId: string, range: AnalyticsRange, metric: AnalyticsMetric): Promise<AnalyticsSeriesPoint[]> =>
  read(await authenticatedFetch(`${base(projectId)}/timeseries?${rangeParams(range)}&metric=${metric}`), 'Could not load the time series.');

export const fetchAnalyticsBreakdown = async (projectId: string, range: AnalyticsRange, dimension: AnalyticsDimension): Promise<AnalyticsBreakdownRow[]> =>
  read(await authenticatedFetch(`${base(projectId)}/breakdown?${rangeParams(range)}&dimension=${dimension}&limit=10`), 'Could not load the report.');
