import { Link2, RefreshCw, Unlink } from 'lucide-react';
import { useGoogleConnections } from '@/features/google/hooks/useGoogleConnections';
import { Button } from '@/shared/components/Button';
import { Card } from '@/shared/components/Card';
import { formatDate } from '@/shared/utils/formatDate';

export const GoogleConnectionsCard = () => {
  const { connections, loading, busy, error, reload, connect, disconnect } = useGoogleConnections();

  return (
    <Card className="p-5 sm:p-6">
      <h2 className="text-sm font-semibold tracking-[-0.015em] text-ink">Google (Analytics &amp; Search Console)</h2>
      <p className="mt-2 text-sm leading-relaxed text-ink-muted">
        Link Google accounts to read production traffic and search positioning. You can connect several accounts;
        this is separate from signing in to Mooi.
      </p>
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
              <Button variant="secondary" size="sm" disabled={busy || loading} onClick={() => void connect(connection.id)}>
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
      <Button variant="secondary" size="sm" className="mt-4" disabled={busy || loading} onClick={() => void connect()}>
        <Link2 className="size-4" />Connect Google
      </Button>
    </Card>
  );
};
