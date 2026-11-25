export interface GoogleOAuthSettings {
  clientId: string;
  redirectUri: string;
  configured: boolean;
  hasClientSecret: boolean;
  usingInstanceDefaults: boolean;
}

export interface SaveGoogleOAuthSettings {
  clientId: string;
  clientSecret: string;
  redirectUri: string;
}
