import { useEffect, useState, type FormEvent } from 'react';
import { Link2, RefreshCw, Save, Unlink } from 'lucide-react';
import {
  fetchGoogleOAuthSettings,
  saveGoogleOAuthSettings,
} from '@/features/google/api/googleApi';
import { useGoogleConnections } from '@/features/google/hooks/useGoogleConnections';
import type { GoogleOAuthSettings } from '@/features/google/types/GoogleOAuthSettings';
import { Button } from '@/shared/components/Button';
import { Card } from '@/shared/components/Card';
import { formatDate } from '@/shared/utils/formatDate';

const callbackUrl = () => new URL(`${import.meta.env.BASE_URL}account/google/callback`, window.location.origin).toString();
const fieldClass = 'w-full rounded-control border border-line bg-surface-2 px-3 py-2.5 text-sm text-ink outline-none transition focus:border-brand/60';

export const GoogleConnectionsCard = () => {
  const { connections, loading, busy, error, reload, connect, disconnect } = useGoogleConnections();
  const [settings, setSettings] = useState<GoogleOAuthSettings | null>(null);
  const [clientId, setClientId] = useState('');
  const [clientSecret, setClientSecret] = useState('');
  const [settingsLoading, setSettingsLoading] = useState(true);
  const [settingsBusy, setSettingsBusy] = useState(false);
  const [settingsError, setSettingsError] = useState<string | null>(null);
  const [settingsNotice, setSettingsNotice] = useState<string | null>(null);
  const callback = callbackUrl();

  const reloadSettings = async () => {
    setSettingsLoading(true);
    setSettingsError(null);
    try {
      const result = await fetchGoogleOAuthSettings();
      setSettings(result);
      setClientId(result.clientId);
    } catch (failure) {
      setSettingsError(failure instanceof Error ? failure.message : 'Could not load Google OAuth settings.');
    } finally {
      setSettingsLoading(false);
    }
  };

  useEffect(() => { void reloadSettings(); }, []);

  const saveSettings = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setSettingsBusy(true);
    setSettingsError(null);
    setSettingsNotice(null);
    try {
      const result = await saveGoogleOAuthSettings({ clientId, clientSecret, redirectUri: callback });
      setSettings(result);
      setClientId(result.clientId);
      setClientSecret('');
      setSettingsNotice('Google OAuth settings saved. The client secret is encrypted and will not be shown again.');
    } catch (failure) {
      setSettingsError(failure instanceof Error ? failure.message : 'Could not save Google OAuth settings.');
    } finally {
      setSettingsBusy(false);
    }
  };

  const oauthReady = settings?.configured === true;

  return (
    <Card className="p-5 sm:p-6">
      <h2 className="text-sm font-semibold tracking-[-0.015em] text-ink">Google (Analytics &amp; Search Console)</h2>
      <p className="mt-2 text-sm leading-relaxed text-ink-muted">
        Link Google accounts to read production traffic and search positioning. You can connect several accounts;
        this is separate from signing in to Mooi.
      </p>
      <section className="mt-5 rounded-control border border-line bg-surface-2/40 p-4 sm:p-5" aria-labelledby="google-oauth-settings-title">
        <h3 id="google-oauth-settings-title" className="text-sm font-semibold text-ink">OAuth app setup</h3>
        <p className="mt-1 text-xs leading-relaxed text-ink-muted">
          Configure your Google Cloud OAuth 2.0 Web application client. Add the callback URL below to its
          authorized redirect URIs. Your secret is encrypted at rest and never returned to the browser.
        </p>
        {settings?.usingInstanceDefaults ? (
          <p className="mt-3 rounded-control border border-line bg-surface px-3 py-2 text-xs text-ink-muted">
            Mooi instance OAuth settings are the fallback. Saving here will configure your personal Google OAuth app.
          </p>
        ) : null}
        <form className="mt-4 flex flex-col gap-3" onSubmit={(event) => void saveSettings(event)}>
          <label className="flex flex-col gap-1.5">
            <span className="text-xs font-medium text-ink-muted">OAuth client ID</span>
            <input type="text" required maxLength={512} autoComplete="off" spellCheck={false}
              value={clientId} onChange={(event) => setClientId(event.target.value)}
              placeholder="…apps.googleusercontent.com" className={fieldClass} disabled={settingsBusy || settingsLoading} />
          </label>
          <label className="flex flex-col gap-1.5">
            <span className="text-xs font-medium text-ink-muted">OAuth client secret</span>
            <input type="password" autoComplete="new-password" maxLength={4096}
              value={clientSecret} onChange={(event) => setClientSecret(event.target.value)}
              placeholder={settings?.hasClientSecret && !settings.usingInstanceDefaults
                ? 'Leave blank to keep the saved secret'
                : 'Enter your Google OAuth client secret'}
              className={fieldClass} disabled={settingsBusy || settingsLoading} />
          </label>
          <label className="flex flex-col gap-1.5">
            <span className="text-xs font-medium text-ink-muted">Authorized redirect URI</span>
            <input type="url" readOnly value={callback} className={`${fieldClass} font-mono text-xs`} />
          </label>
          <p className="text-xs leading-relaxed text-ink-subtle">
            Enable the Google Analytics Data API, Google Analytics Admin API, and Search Console API in the same
            Google Cloud project. Your consent screen must include your account as a test user until the app is published.
            Changing the client may require re-linking accounts connected with the previous client.
          </p>
          {settingsError ? <p role="alert" className="rounded-control border border-danger/30 bg-danger-soft px-3 py-2 text-sm text-danger">{settingsError}</p> : null}
          {settingsNotice ? <p role="status" className="rounded-control border border-success/30 bg-success-soft px-3 py-2 text-sm text-success">{settingsNotice}</p> : null}
          <div className="flex flex-wrap items-center gap-3">
            <Button type="submit" variant="secondary" size="sm" disabled={settingsBusy || settingsLoading || !clientId.trim()}>
              <Save className="size-4" />{settingsBusy ? 'Saving…' : 'Save OAuth settings'}
            </Button>
            {!settingsLoading && !settings ? (
              <Button type="button" variant="ghost" size="sm" onClick={() => void reloadSettings()}>
                <RefreshCw className="size-4" />Load settings
              </Button>
            ) : null}
            {settingsLoading ? <span className="text-xs text-ink-subtle" role="status">Loading OAuth settings…</span> : null}
          </div>
        </form>
      </section>
      {error ? (
        <div className="mt-4 flex flex-wrap items-center gap-3" role="alert">
          <p className="min-w-0 flex-1 text-sm text-danger">{error}</p>
          <Button variant="secondary" size="sm" onClick={() => void reload()} disabled={busy || loading}>
            <RefreshCw className="size-4" />Try again
          </Button>
        </div>
      ) : null}
      {loading ? <p className="mt-4 text-sm text-ink-subtle" role="status">Loading Google accounts…</p> : null}
      {!loading && !error && connections.length === 0 ? (
        <p className="mt-4 text-sm text-ink-subtle">No Google accounts linked yet.</p>
      ) : null}
      <ul className="mt-4 divide-y divide-line">
        {connections.map((connection) => (
          <li key={connection.id} className="flex min-w-0 flex-col gap-3 py-4 sm:flex-row sm:items-start">
            <div className="min-w-0 flex-1">
              <p className="break-all text-sm font-medium text-ink">{connection.email}</p>
              <p className="mt-1 text-xs text-ink-subtle">Linked {formatDate(connection.linkedAt)}</p>
              <p className="mt-2 text-xs text-ink-muted">
                Analytics: {connection.scopes.includes('https://www.googleapis.com/auth/analytics.readonly') ? 'allowed' : 'not granted'}
                {' · '}Search Console: {connection.scopes.includes('https://www.googleapis.com/auth/webmasters.readonly') ? 'allowed' : 'not granted'}
              </p>
              {connection.reauthRequired ? (
                <p className="mt-2 text-sm text-danger">Google access has expired or been revoked. Re-link this account to continue.</p>
              ) : null}
            </div>
            <div className="flex shrink-0 flex-wrap gap-2">
              <Button variant="secondary" size="sm" disabled={busy || loading || settingsLoading || !oauthReady} onClick={() => void connect(connection.id)}>
                <RefreshCw className="size-4" />Re-link
              </Button>
              <Button variant="danger" size="sm" disabled={busy || loading} onClick={() => void disconnect(connection.id)}
                ariaLabel={`Unlink ${connection.email}`}>
                <Unlink className="size-4" />Unlink
              </Button>
            </div>
          </li>
        ))}
      </ul>
      {!oauthReady && !settingsLoading ? (
        <p className="mt-4 text-sm text-ink-subtle">Save a configured Google OAuth client above before linking an account.</p>
      ) : null}
      <Button variant="secondary" size="sm" className="mt-4" disabled={busy || loading || settingsLoading || !oauthReady} onClick={() => void connect()}>
        <Link2 className="size-4" />Connect Google
      </Button>
    </Card>
  );
};
