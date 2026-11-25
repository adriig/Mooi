import { authenticatedFetch } from '@/features/auth/lib/authenticatedFetch';
import { rangeParams } from '@/features/analytics/lib/analyticsRange';
import type { AnalyticsBreakdownRow } from '@/features/analytics/types/AnalyticsBreakdownRow';
import type { AnalyticsDimension } from '@/features/analytics/types/AnalyticsDimension';
import type { AnalyticsMetric } from '@/features/analytics/types/AnalyticsMetric';
import type { AnalyticsOverview } from '@/features/analytics/types/AnalyticsOverview';
import type { AnalyticsRange } from '@/features/analytics/types/AnalyticsRange';
import type { AnalyticsSeriesPoint } from '@/features/analytics/types/AnalyticsSeriesPoint';
import type { AnalyticsSite } from '@/features/analytics/types/AnalyticsSite';
import { GoogleReportError } from '@/features/analytics/lib/googleErrors';
import type { AnalyticsGa4Report, AnalyticsGa4ReportKind } from '@/features/analytics/types/AnalyticsGa4Report';
import type { AnalyticsGoogleProperties } from '@/features/analytics/types/AnalyticsGoogleProperties';
import type { AnalyticsGoogleSelection } from '@/features/analytics/types/AnalyticsGoogleSelection';
import type { AnalyticsSearchDimension, AnalyticsSearchReport } from '@/features/analytics/types/AnalyticsSearchReport';
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

/** Google-backed reads put their `google_*` reason in the error message; keep it as a code. */
const readGoogle = async <T>(response: Response, fallback: string): Promise<T> => {
  if (!response.ok) {
    const message = await apiErrorMessage(response, fallback);
    throw new GoogleReportError(message, message.startsWith('google_') ? message : null);
  }
  return (await response.json()) as T;
};

export const updateAnalyticsGoogle = async (projectId: string, selection: AnalyticsGoogleSelection): Promise<AnalyticsSite> =>
  read(await authenticatedFetch(`${base(projectId)}/site`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(selection),
  }), 'Could not save the Google selection.');

export const fetchAnalyticsGoogleProperties = async (projectId: string, connectionId?: string): Promise<AnalyticsGoogleProperties> =>
  readGoogle(await authenticatedFetch(`${base(projectId)}/google/properties${connectionId ? `?connectionId=${encodeURIComponent(connectionId)}` : ''}`),
    'Could not list the Google properties.');

export const fetchAnalyticsSearch = async (projectId: string, range: AnalyticsRange, dimension: AnalyticsSearchDimension, limit = 25): Promise<AnalyticsSearchReport> =>
  readGoogle(await authenticatedFetch(`${base(projectId)}/search?${rangeParams(range)}&dimension=${dimension}&limit=${limit}`), 'Could not load Search Console.');

export const fetchAnalyticsGa4 = async (projectId: string, range: AnalyticsRange, report: AnalyticsGa4ReportKind, limit = 10): Promise<AnalyticsGa4Report> =>
  readGoogle(await authenticatedFetch(`${base(projectId)}/ga4?${rangeParams(range)}&report=${report}&limit=${limit}`), 'Could not load Google Analytics.');

export interface AnalyticsAnalysisPeriod { from: string; to: string; timezone: string }
export interface AnalyticsAnalysisEvidence {
  id: string; source: string; status: 'available' | 'partial' | 'not_connected' | 'empty' | 'failed';
  requestedPeriod: AnalyticsAnalysisPeriod; actualPeriod: AnalyticsAnalysisPeriod | null; queriedAt: string;
  coverage: { description: string; rowLimit: number | null; returnedRows: number | null; truncated: boolean | null;
    dataFetchedAt: string | null; dataUntil: string | null; sampleSize: number | null; sampleUnit: string | null; limitations: string[] };
  errors: string[]; executions: { id: string; createdAt: string | null; startedAt: string | null; finishedAt: string | null; status: string }[];
  data: unknown;
}
export interface AnalyticsAnalysisResult {
  summary: string;
  findings: { id: string; title: string; priority: 'high' | 'medium' | 'low'; interpretation: string;
    evidence: { evidenceId: string; period: AnalyticsAnalysisPeriod }[]; limitations: string[] }[];
  recommendations: { priority: 'high' | 'medium' | 'low'; justification: string; action: string; verification: string;
    evidence: { evidenceId: string; period: AnalyticsAnalysisPeriod }[] }[];
  generalSuggestions: string[];
}
export interface AnalyticsAnalysis {
  id: string; projectId: string; period: AnalyticsAnalysisPeriod;
  status: 'collecting' | 'queued' | 'running' | 'completed' | 'partial' | 'no_data' | 'failed' | 'invalid_output' | 'no_account' | 'quota';
  reason: string | null; revision: number; createdAt: string; updatedAt: string;
  evidence: AnalyticsAnalysisEvidence[]; result: AnalyticsAnalysisResult | null;
}
export const listAnalyticsAnalyses = async (projectId: string): Promise<AnalyticsAnalysis[]> =>
  read(await authenticatedFetch(`${base(projectId)}/analyses?limit=20`), 'Could not load analytics diagnoses.');
export const fetchAnalyticsAnalysis = async (projectId: string, id: string): Promise<AnalyticsAnalysis> =>
  read(await authenticatedFetch(`${base(projectId)}/analyses/${encodeURIComponent(id)}`), 'Could not refresh the analytics diagnosis.');
export const generateAnalyticsAnalysis = async (projectId: string, id: string): Promise<AnalyticsAnalysis> =>
  read(await authenticatedFetch(`${base(projectId)}/analyses/${encodeURIComponent(id)}/generate`, { method: 'POST' }), 'Could not generate the analytics diagnosis.');
export const createAnalyticsAnalysis = async (projectId: string, id: string, period: AnalyticsAnalysisPeriod): Promise<AnalyticsAnalysis> =>
  read(await authenticatedFetch(`${base(projectId)}/analyses`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ id, period }) }), 'Could not start the analytics diagnosis.');
