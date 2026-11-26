import { authenticatedFetch } from '@/features/auth/lib/authenticatedFetch';
import { sessionsFetch } from '@/features/sessions/lib/sessionsFetch';
import { rangeParams } from '@/features/analytics/lib/analyticsRange';
import type { AnalyticsRange } from '@/features/analytics/types/AnalyticsRange';
import { apiErrorMessage } from '@/shared/utils/apiErrorMessage';

const base = (projectId: string) => `/me/projects/${encodeURIComponent(projectId)}/analytics`;
const read = async <T>(request: Promise<Response>, fallback: string): Promise<T> => {
  const response = await request;
  if (!response.ok) throw new Error(await apiErrorMessage(response, fallback));
  return response.status === 204 ? undefined as T : response.json() as Promise<T>;
};
const suggestions = (projectId: string) => `/projects/${encodeURIComponent(projectId)}/analytics/geo/suggestions`;
const json = (body: unknown) => ({ method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });

export const geoApi = {
  settings: (projectId: string) => read<GeoSettings>(authenticatedFetch(`${base(projectId)}/geo/settings`), 'Could not load GEO settings.'),
  saveSettings: (projectId: string, settings: GeoSettings) => read<GeoSettings>(authenticatedFetch(`${base(projectId)}/geo/settings`, json(settings)), 'Could not save GEO settings.'),
  prompts: (projectId: string) => read<GeoPrompt[]>(authenticatedFetch(`${base(projectId)}/geo/prompts`), 'Could not load prompts.'),
  addPrompt: (projectId: string, text: string) => read<GeoPrompt>(authenticatedFetch(`${base(projectId)}/geo/prompts`, { ...json({ text, enabled: true, position: 999 }), method: 'POST' }), 'Could not add prompt.'),
  editPrompt: (projectId: string, prompt: GeoPrompt, patch: Partial<Pick<GeoPrompt, 'text' | 'enabled' | 'position'>>) => read<GeoPrompt>(authenticatedFetch(`${base(projectId)}/geo/prompts/${prompt.id}`, { ...json(patch), method: 'PATCH' }), 'Could not update prompt.'),
  deletePrompt: (projectId: string, id: string) => read<void>(authenticatedFetch(`${base(projectId)}/geo/prompts/${id}`, { method: 'DELETE' }), 'Could not delete prompt.'),
  run: (projectId: string) => read<GeoRun>(authenticatedFetch(`${base(projectId)}/geo/runs`, { method: 'POST' }), 'Could not start a visibility run.'),
  runs: (projectId: string) => read<GeoRun[]>(authenticatedFetch(`${base(projectId)}/geo/runs?limit=30`), 'Could not load runs.'),
  runDetail: (projectId: string, id: string) => read<GeoRunDetail>(authenticatedFetch(`${base(projectId)}/geo/runs/${id}`), 'Could not load run results.'),
  summary: (projectId: string, range: AnalyticsRange) => read<GeoSummary>(authenticatedFetch(`${base(projectId)}/geo/summary?${rangeParams(range)}`), 'Could not load visibility summary.'),
  audit: (projectId: string) => read<GeoAudit>(authenticatedFetch(`${base(projectId)}/audits`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' }), 'Could not run the audit.'),
  /** Settings and prompts read at once with the revision a suggestion accept is checked against. */
  config: (projectId: string) => read<GeoConfig>(authenticatedFetch(`${base(projectId)}/geo/config`), 'Could not load the AI visibility configuration.'),
  /** Starts (or, with the same id, re-reads) a "Propose with AI" draft; nothing is saved. */
  generateSuggestions: (projectId: string, id: string, site?: string) => read<GeoSuggestionGeneration>(sessionsFetch(`${suggestions(projectId)}/generations`, { ...json({ id, ...(site ? { site } : {}) }), method: 'POST' }), 'Could not generate suggestions.'),
  suggestionGeneration: (projectId: string, id: string) => read<GeoSuggestionGeneration>(sessionsFetch(`${suggestions(projectId)}/generations/${encodeURIComponent(id)}`), 'Could not load the suggestions.'),
  /** Appends the reviewed selection; a 409 means the configuration changed since `baseRevision` was read. */
  acceptSuggestions: (projectId: string, selection: GeoSuggestionAccept) => read<GeoSuggestionAcceptResult>(sessionsFetch(`${suggestions(projectId)}/accept`, { ...json(selection), method: 'POST' }), 'Could not save the selected suggestions.'),
  audits: (projectId: string) => read<GeoAudit[]>(authenticatedFetch(`${base(projectId)}/audits?limit=20`), 'Could not load audit history.'),
};

export interface GeoCompetitor { name: string; domain: string }
export interface GeoSettings { brandTerms: string[]; competitors: GeoCompetitor[]; weeklyEnabled: boolean; nextRunAt: string | null }
export interface GeoPrompt { id: string; text: string; enabled: boolean; position: number }
export interface GeoRun { id: string; trigger: string; status: string; createdAt: string; startedAt: string | null; finishedAt: string | null; reason: string | null }
export interface GeoResult { id: string; promptId: string; provider: string; model: string; mentioned: boolean; cited: boolean; position: number | null; citedUrls: string[]; competitorsMentioned: string[]; excerpt: string | null; error: string | null }
export interface GeoRunDetail { run: GeoRun; results: GeoResult[] }
export interface GeoVoice { provider: string; model: string; mentionedPrompts: number; answeredPrompts: number; shareOfVoice: number; citedPrompts: number; citationRate: number; averageFirstMentionPosition: number | null; competitorMentions: Record<string, number> }
export interface GeoTrendPoint { runId: string; createdAt: string; provider: string; model: string; mentionedPrompts: number; answeredPrompts: number; shareOfVoice: number; citedPrompts: number; citationRate: number }
export interface GeoSummary { from: string; to: string; visibility: GeoVoice[]; trend: GeoTrendPoint[] }
export interface GeoAuditFinding { id: string; category: string; severity: string; title: string; detail: string; fix: string }
export interface GeoAudit { id: string; url: string; score: number; findings: GeoAuditFinding[]; createdAt: string }

export interface GeoConfig { settings: GeoSettings; prompts: GeoPrompt[]; revision: string }
export interface GeoSuggestionReference { page: string; url: string; quote: string }
export interface GeoSuggestedTerm { id: string; term: string; rationale: string; inferred: boolean; references: GeoSuggestionReference[] }
export interface GeoSuggestedCompetitor { id: string; name: string; domain: string; relationship: string; rationale: string; references: GeoSuggestionReference[] }
export interface GeoSuggestedPrompt { id: string; text: string; kind: 'editorial_proposal'; rationale: string; references: GeoSuggestionReference[] }
export interface GeoSuggestionResult {
  brandTerms: GeoSuggestedTerm[]; competitors: GeoSuggestedCompetitor[]; prompts: GeoSuggestedPrompt[];
  omitted: { kind: 'brand_term' | 'competitor' | 'prompt'; value: string; reason: string }[];
  limitations: { source: 'platform' | 'agent'; code: string | null; text: string }[];
  uncertainties: string[];
  room: { brandTerms: number; competitors: number; prompts: number };
  configRevision: string | null;
}
export interface GeoSuggestionSite { id: string; origin: string; host: string; startUrl: string; targets: string[] }
export interface GeoSuggestionEvidence {
  status: string; reasons: string[]; site: GeoSuggestionSite | null; sites: GeoSuggestionSite[]; domain: string | null;
  collectedAt: string | null; finishedAt: string | null; limits: Record<string, number | string> | null;
  coverage: { pagesCollected: number; pagesAttempted: number; pagesSkipped: number; limitations: string[] } | null;
  pages: { ref: string | null; url: string; finalUrl: string | null; source: string; httpStatus: number | null; outcome: string; fetchedAt: string | null; title: string | null }[];
}
export type GeoSuggestionStatus = 'collecting' | 'generating' | 'completed' | 'partial' | 'empty' | 'production_unconfigured'
  | 'selection_required' | 'restricted' | 'inaccessible' | 'insufficient_evidence' | 'no_account' | 'quota' | 'invalid_output' | 'failed' | 'superseded';
export interface GeoSuggestionGeneration {
  id: string; projectId: string; site: string | null; status: GeoSuggestionStatus; reason: string | null;
  createdAt: string; updatedAt: string; configRevision: string | null; engine: { provider: string; model: string } | null;
  evidence: GeoSuggestionEvidence | null; result: GeoSuggestionResult | null;
}
export interface GeoSuggestionAccept { id: string; baseRevision: string; generationId?: string; brandTerms: string[]; competitors: GeoCompetitor[]; prompts: string[] }
export interface GeoSuggestionAcceptResult {
  status: 'applied' | 'replayed';
  added: { brandTerms: string[]; competitors: GeoCompetitor[]; prompts: GeoPrompt[] };
  skipped: { kind: string; value: string; reason: string }[];
  config: GeoConfig;
}
