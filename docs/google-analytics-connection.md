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
