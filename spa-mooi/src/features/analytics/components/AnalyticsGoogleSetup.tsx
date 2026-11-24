import { useState } from 'react';
import { KeyRound, Link2 } from 'lucide-react';
import { fetchAnalyticsGoogleProperties } from '@/features/analytics/api/analyticsApi';
import { AnalyticsGoogleErrorState } from '@/features/analytics/components/AnalyticsGoogleErrorState';
import { useAnalyticsReport } from '@/features/analytics/hooks/useAnalyticsReport';
import { googleErrorState } from '@/features/analytics/lib/googleErrors';
import type { AnalyticsGoogleSelection } from '@/features/analytics/types/AnalyticsGoogleSelection';
import type { AnalyticsSite } from '@/features/analytics/types/AnalyticsSite';
import { useGoogleConnections } from '@/features/google/hooks/useGoogleConnections';
import { Button } from '@/shared/components/Button';

interface AnalyticsGoogleSetupProps {
  projectId: string;
  site: AnalyticsSite;
  busy: boolean;
  onSave: (selection: AnalyticsGoogleSelection) => Promise<boolean>;
}

const SELECT = 'h-10 min-w-0 w-full rounded-control border border-line bg-surface-2 px-3 text-[13px] text-ink outline-none focus:border-brand/50 disabled:opacity-60';

/** Connect Google, then pick the connection, GA4 property and Search Console site this project reports on. */
export const AnalyticsGoogleSetup = ({ projectId, site, busy, onSave }: AnalyticsGoogleSetupProps) => {
  const google = useGoogleConnections();
  const usable = google.connections.filter((connection) => !connection.reauthRequired);
  const [chosenConnection, setChosenConnection] = useState<string | null>(null);
  const connectionId = chosenConnection
    ?? (site.googleConnectionId && google.connections.some((connection) => connection.id === site.googleConnectionId) ? site.googleConnectionId : usable[0]?.id ?? null);
  const connection = google.connections.find((item) => item.id === connectionId) ?? null;
  const listable = connection !== null && !connection.reauthRequired;

  const properties = useAnalyticsReport(
    listable ? () => fetchAnalyticsGoogleProperties(projectId, connection.id) : null,
    `${projectId}|${connection?.id ?? ''}`,
    projectId,
  );
  const [ga4Draft, setGa4Draft] = useState<string | null>(null);
  const [consoleDraft, setConsoleDraft] = useState<string | null>(null);

  const payload = properties.data;
  // The saved choice only applies to the saved connection; another connection starts from its suggestions.
  const sameConnection = connectionId === site.googleConnectionId;
  const ga4 = ga4Draft ?? (sameConnection ? site.ga4PropertyId : null) ?? payload?.ga4Properties.find((item) => item.suggested)?.propertyId ?? '';
  const searchConsole = consoleDraft ?? (sameConnection ? site.searchConsoleSiteUrl : null) ?? payload?.searchConsoleSites.find((item) => item.suggested)?.siteUrl ?? '';
  const dirty = connectionId !== null && (!sameConnection || ga4 !== (site.ga4PropertyId ?? '') || searchConsole !== (site.searchConsoleSiteUrl ?? ''));

  const changeConnection = (id: string) => { setChosenConnection(id); setGa4Draft(null); setConsoleDraft(null); };
  const save = () => {
    if (!connectionId) return;
    void onSave({ googleConnectionId: connectionId, ga4PropertyId: ga4, searchConsoleSiteUrl: searchConsole })
      .then((saved) => { if (saved) { setGa4Draft(null); setConsoleDraft(null); } });
  };
  const onAction = (action: string) => {
    if (action === 'connect') void google.connect();
    else if (action === 'relink') void google.connect(connection?.id);
    else properties.reload();
  };

  return (
    <div className="flex flex-col gap-3 border-t border-line pt-4">
      <div>
        <h4 className="text-sm font-semibold">Google Analytics &amp; Search Console</h4>
        <p className="mt-1 text-[13px] text-ink-muted">Link a Google account to read this app&rsquo;s GA4 reports and search positioning (read-only).</p>
      </div>

      {google.error ? <p role="alert" className="text-[13px] text-danger">{google.error}</p> : null}

      {google.loading && google.connections.length === 0 ? (
        <div aria-busy className="h-10 animate-pulse-soft rounded-control bg-surface-3" />
      ) : google.connections.length === 0 ? (
        <div className="flex flex-wrap items-center gap-3">
          <p className="min-w-0 flex-1 basis-56 text-[13px] text-ink-muted">No Google account is linked yet.</p>
          <Button variant="secondary" size="sm" disabled={google.busy} onClick={() => void google.connect()}><Link2 className="size-4" />Connect Google</Button>
        </div>
      ) : (
        <div className="flex flex-col gap-3">
          <label className="flex min-w-0 flex-col gap-1.5 text-[12px] font-medium text-ink-muted">
            Google account
            <select className={SELECT} value={connectionId ?? ''} onChange={(event) => changeConnection(event.target.value)} disabled={busy}>
              {google.connections.map((item) => (
                <option key={item.id} value={item.id}>{item.email}{item.reauthRequired ? ' (re-link needed)' : ''}</option>
              ))}
            </select>
          </label>

          {connection?.reauthRequired ? (
            <AnalyticsGoogleErrorState code="google_reauth_required" onAction={onAction} />
          ) : properties.error ? (
            <AnalyticsGoogleErrorState code={properties.errorCode} message={properties.error} onAction={onAction} />
          ) : !payload ? (
            <div aria-busy className="h-24 animate-pulse-soft rounded-control bg-surface-3" />
          ) : (
            <div className="grid gap-3 md:grid-cols-2">
              <label className="flex min-w-0 flex-col gap-1.5 text-[12px] font-medium text-ink-muted">
                Google Analytics 4 property
                <select className={SELECT} value={ga4} onChange={(event) => setGa4Draft(event.target.value)} disabled={busy || payload.ga4Error !== null}>
                  <option value="">None</option>
                  {payload.ga4Properties.map((item) => (
                    <option key={item.propertyId} value={item.propertyId}>
                      {item.displayName} ({item.accountName}){item.suggested ? ' · suggested' : ''}
                    </option>
                  ))}
                </select>
                {payload.ga4Error ? <span className="text-[12px] font-normal text-warn">{googleErrorState(payload.ga4Error).description}</span>
                  : payload.ga4Properties.length === 0 ? <span className="text-[12px] font-normal">No GA4 property found for this account.</span> : null}
              </label>
              <label className="flex min-w-0 flex-col gap-1.5 text-[12px] font-medium text-ink-muted">
                Search Console site
                <select className={SELECT} value={searchConsole} onChange={(event) => setConsoleDraft(event.target.value)} disabled={busy || payload.searchConsoleError !== null}>
                  <option value="">None</option>
                  {payload.searchConsoleSites.map((item) => (
                    <option key={item.siteUrl} value={item.siteUrl}>{item.siteUrl}{item.suggested ? ' · suggested' : ''}</option>
                  ))}
                </select>
                {payload.searchConsoleError ? <span className="text-[12px] font-normal text-warn">{googleErrorState(payload.searchConsoleError).description}</span>
                  : payload.searchConsoleSites.length === 0 ? <span className="text-[12px] font-normal">No Search Console site found for this account.</span> : null}
              </label>
            </div>
          )}

          <div className="flex flex-wrap items-center gap-2">
            <Button variant="brand" onClick={save} disabled={busy || !dirty || !listable}>Save Google selection</Button>
            <Button variant="secondary" size="sm" disabled={google.busy} onClick={() => void google.connect()}><Link2 className="size-4" />Connect another account</Button>
            {connection?.reauthRequired ? (
              <Button variant="secondary" size="sm" disabled={google.busy} onClick={() => void google.connect(connection.id)}><KeyRound className="size-4" />Re-link</Button>
            ) : null}
          </div>
        </div>
      )}
    </div>
  );
};
