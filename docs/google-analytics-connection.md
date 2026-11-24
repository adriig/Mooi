# Google reporting connections

Account → Google links one or more Google accounts for Analytics and Search Console reporting.
These grants are independent of Google sign-in and agent provider accounts. Reporting features
must use `shared.GoogleTokens.accessToken(playerId, connectionId)` and must never send the
returned credential to a browser or log it.

## Instance configuration

Use a Google OAuth **Web application** client. `GOOGLE_CLIENT_ID` reuses the Google sign-in
client ID. Configure `GOOGLE_CLIENT_SECRET` and `GOOGLE_OAUTH_REDIRECT_URI` in the platform,
never in Git. Both new variables are optional at boot; linking returns a configuration error
until both are supplied. The root development Compose file reads the corresponding
`MOOI_DEVELOPMENT_...` variables as optional interpolations.

Register the exact public SPA callback URL as an authorized redirect URI in Google Cloud:
`{publicMooiBaseUrl}/account/google/callback`. Include any base path used to reach this
instance. The callback must be reachable in the same Mooi instance where authorization began.
It is a protected SPA route, which posts code and state to the API using Mooi authentication.

Enable the Google Analytics Data API, Google Analytics Admin API (property discovery), and
Search Console API for reporting consumers. Configure the OAuth consent screen and test users
or publish/verify the application as required by Google. The requested scopes are `openid`,
`email`, `analytics.readonly` and `webmasters.readonly`; offline consent and S256 PKCE are
always used. Declined reporting scopes appear as “not granted” on the card. See Google's
[web server OAuth documentation](https://developers.google.com/identity/protocols/oauth2/web-server).

## API and lifecycle

All routes require a signed-in Mooi player:

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/me/google/connections/authorize` | Return `authorizeUrl`; optional JSON `connectionId` re-links a specific account |
| POST | `/me/google/connections/callback` | Redeem JSON `code` and `state`; return connection metadata |
| GET | `/me/google/connections` | List the player's connection metadata, scopes and `reauthRequired` |
| DELETE | `/me/google/connections/{id}` | Revoke Google's refresh grant and delete the connection |

State is authenticated, encrypted, player-bound and expires after ten minutes. Google account
subject IDs identify grants within each player, so re-linking retains the connection ID and
original link time even if the email changes. Tokens are sealed with `Crypto.SecretBox`
before persistence; API payloads contain no credentials. Foreign or nonexistent connection
IDs return 404, including through the internal accessor.

The accessor refreshes five minutes before expiry under a database row lock. A refresh response
without a refresh token preserves the existing one. Google's `invalid_grant` commits the
reauthorization flag in its own transaction and raises HTTP 409 `google_reauth_required`;
transient failures return 502 and preserve the grant. Re-linking clears the flag. Unlinking
accepts an already-revoked token; transient revocation failures retain the row for retry.

Migration `049-create-google-connections.yaml` is registered in the master changelog; coordinate
its numbering with the separate analytics-site migration at merge time.

## Project reporting

`AnalyticsFeature` reads GA4 and Search Console for a project through the connection chosen on
its analytics site. Unlinking a connection clears it from every site (`051` foreign key,
`ON DELETE SET NULL`). All routes require the project owner; unowned projects and foreign
connections return 404.

| Method | Path | Purpose |
| --- | --- | --- |
| PATCH | `/me/projects/{projectId}/analytics/site` | `googleConnectionId`, `ga4PropertyId` (numeric or `properties/N`), `searchConsoleSiteUrl` (URL prefix or `sc-domain:`); empty string clears |
| GET | `/me/projects/{projectId}/analytics/google/properties?connectionId=` | GA4 properties (`accountSummaries` + web stream URIs) and Search Console sites, `suggested` when they match production target hosts; defaults to the site's connection |
| GET | `/me/projects/{projectId}/analytics/search?from&to&tz&dimension=query\|page\|country\|device\|date&limit` | Search Analytics rows `{key, clicks, impressions, ctr, position}`, `totals` and `dataUntil` (last day with data; Search Console lags ~2 days) |
| GET | `/me/projects/{projectId}/analytics/ga4?from&to&tz&report=summary\|pages\|sources\|timeseries&limit` | GA4 `runReport` rows and totals: `activeUsers`, `sessions`, `screenPageViews`, `eventCount`, `bounceRate` (0..1), `averageSessionDuration` (seconds) |

`from`/`to` accept `YYYY-MM-DD` (inclusive) or ISO instants (exclusive end, resolved in `tz`).
Responses are cached in memory per project, connection, property and parameters for 15 minutes
(bounded to 512 entries); `fetchedAt` tells when Google was read. A properties listing where only
one source fails returns the other with `ga4Error` or `searchConsoleError`.

Errors carry a stable code in the API error `message`:

| Status | Code | Meaning |
| --- | --- | --- |
| 409 | `google_not_connected` | No connection chosen for the project, or it was unlinked |
| 409 | `google_property_not_selected` | The GA4 property or Search Console site is not chosen yet |
| 409 | `google_reauth_required` | The grant was revoked, expired or lacks the reporting scope: link again |
| 403 | `google_permission_denied` | The Google account cannot read that property or site |
| 429 | `google_quota_exceeded` | Google API quota or rate limit reached |
| 503 | `google_api_disabled` | The API is not enabled on the instance's Google Cloud project |
| 400 | `google_bad_request` | Google rejected the report request |
