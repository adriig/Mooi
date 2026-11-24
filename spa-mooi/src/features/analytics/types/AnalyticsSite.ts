/** The project's tracker configuration, as served by `/analytics/site`. */
export interface AnalyticsSite {
  siteKey: string;
  allowedOrigins: string[];
  snippet: string;
  ga4PropertyId: string | null;
  searchConsoleSiteUrl: string | null;
}
