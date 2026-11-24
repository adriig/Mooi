export interface AnalyticsGa4PropertyOption {
  propertyId: string;
  displayName: string;
  accountName: string;
  streamUris: string[];
  suggested: boolean;
}

export interface AnalyticsSearchConsoleSiteOption {
  siteUrl: string;
  permissionLevel: string;
  suggested: boolean;
}

/** A source the connection cannot list carries its error code instead of failing the whole response. */
export interface AnalyticsGoogleProperties {
  connectionId: string;
  ga4Properties: AnalyticsGa4PropertyOption[];
  ga4Error: string | null;
  searchConsoleSites: AnalyticsSearchConsoleSiteOption[];
  searchConsoleError: string | null;
  selectedGa4PropertyId: string | null;
  selectedSearchConsoleSiteUrl: string | null;
  fetchedAt: string;
}
