import { useState } from 'react';
import { KeyRound, Plus, X } from 'lucide-react';
import { AnalyticsGoogleSetup } from '@/features/analytics/components/AnalyticsGoogleSetup';
import type { AnalyticsGoogleSelection } from '@/features/analytics/types/AnalyticsGoogleSelection';
import type { AnalyticsSite } from '@/features/analytics/types/AnalyticsSite';
import { Button } from '@/shared/components/Button';
import { Card } from '@/shared/components/Card';
import { CopyButton } from '@/shared/components/CopyButton';
import { Modal } from '@/shared/components/Modal';

interface AnalyticsSetupPanelProps {
  projectId: string;
  site: AnalyticsSite;
  busy: boolean;
  error: string | null;
  onSaveOrigins: (origins: string[]) => Promise<boolean>;
  onRegenerateKey: () => Promise<boolean>;
  onSaveGoogle: (selection: AnalyticsGoogleSelection) => Promise<boolean>;
}

const STEPS = [
  'Paste the snippet inside the <head> of your production app, on every page.',
  'Deploy the app. Visits and clicks start showing up here within a minute.',
  'Tag any element with data-mooi-track="name" to count its clicks under that name.',
];

/** Everything needed to install the tracker; shown open while the project has no events. */
export const AnalyticsSetupPanel = ({ projectId, site, busy, error, onSaveOrigins, onRegenerateKey, onSaveGoogle }: AnalyticsSetupPanelProps) => {
  const [origins, setOrigins] = useState<string[] | null>(null);
  const [draft, setDraft] = useState('');
  const [confirmOpen, setConfirmOpen] = useState(false);
  const current = origins ?? site.allowedOrigins;
  const dirty = origins !== null && (origins.length !== site.allowedOrigins.length || origins.some((origin, index) => origin !== site.allowedOrigins[index]));

  const add = () => {
    const value = draft.trim();
    if (!value) return;
    if (!current.includes(value)) setOrigins([...current, value]);
    setDraft('');
  };

  const save = () => {
    const pending = draft.trim();
    const next = pending && !current.includes(pending) ? [...current, pending] : current;
    void onSaveOrigins(next).then((saved) => { if (saved) { setOrigins(null); setDraft(''); } });
  };

  return (
    <Card as="section" className="flex flex-col gap-5 p-5 sm:p-6">
      <div>
        <h3 className="text-base font-semibold tracking-[-0.015em]">Install the tracker</h3>
        <p className="mt-1 text-sm text-ink-muted">A tiny cookieless script for your production app. Development previews are never measured.</p>
      </div>

      <div className="flex min-w-0 items-start gap-2 rounded-control border border-line bg-surface-2 py-2 pr-1 pl-3">
        <pre className="min-w-0 flex-1 overflow-x-auto py-1 font-mono text-[12px] leading-relaxed text-ink" aria-label="Tracker snippet"><code>{site.snippet}</code></pre>
        <CopyButton value={site.snippet} label="Copy snippet" />
      </div>

      <ol className="flex list-decimal flex-col gap-1 pl-5 text-[13px] text-ink-muted">
        {STEPS.map((step) => <li key={step}>{step}</li>)}
      </ol>

      <div className="flex flex-col gap-2.5">
        <h4 className="text-sm font-semibold">Allowed origins</h4>
        <p className="text-[13px] text-ink-muted">Only pages served from these origins can send events. Prefilled from your production targets.</p>
        {current.length === 0 ? <p className="text-[13px] text-warn">No origin yet: events are dropped until you add one.</p> : (
          <ul className="flex flex-wrap gap-2">
            {current.map((origin) => (
              <li key={origin} className="inline-flex max-w-full items-center gap-1 rounded-full border border-line bg-surface-2 py-1 pr-1 pl-3 font-mono text-[12px]">
                <span className="min-w-0 truncate">{origin}</span>
                <button type="button" aria-label={`Remove ${origin}`} onClick={() => setOrigins(current.filter((item) => item !== origin))}
                  className="inline-flex size-6 items-center justify-center rounded-full text-ink-subtle hover:bg-surface-3 hover:text-ink focus-visible:outline-2 focus-visible:outline-brand">
                  <X className="size-3.5" />
                </button>
              </li>
            ))}
          </ul>
        )}
        <form className="flex flex-wrap gap-2" onSubmit={(event) => { event.preventDefault(); add(); }}>
          <input value={draft} onChange={(event) => setDraft(event.target.value)} placeholder="https://example.com" aria-label="New allowed origin"
            autoCapitalize="none" autoCorrect="off" spellCheck={false} disabled={busy}
            className="h-10 min-w-0 flex-1 basis-56 rounded-control border border-line bg-surface-2 px-3 font-mono text-[13px] text-ink outline-none focus:border-brand/50" />
          <Button type="submit" variant="secondary" disabled={busy || !draft.trim()}><Plus className="size-4" />Add</Button>
          <Button variant="brand" onClick={save} disabled={busy || (!dirty && !draft.trim())}>Save origins</Button>
        </form>
      </div>

      <AnalyticsGoogleSetup projectId={projectId} site={site} busy={busy} onSave={onSaveGoogle} />

      {error ? <p role="alert" className="text-[13px] text-danger">{error}</p> : null}

      <div className="flex flex-wrap items-center justify-between gap-3 border-t border-line pt-4">
        <p className="min-w-0 text-[13px] text-ink-muted">Site key <span className="font-mono text-ink">{site.siteKey.slice(0, 6)}…</span></p>
        <Button variant="secondary" size="sm" onClick={() => setConfirmOpen(true)} disabled={busy}><KeyRound className="size-4" />Regenerate key</Button>
      </div>

      <Modal open={confirmOpen} onClose={() => setConfirmOpen(false)} title="Regenerate the site key?"
        description="The current snippet stops working immediately. Install the new snippet in your app to keep measuring."
        footer={(
          <>
            <Button variant="secondary" onClick={() => setConfirmOpen(false)} disabled={busy}>Cancel</Button>
            <Button variant="danger" disabled={busy} onClick={() => void onRegenerateKey().then(() => setConfirmOpen(false))}>Regenerate key</Button>
          </>
        )}
      >
        <p className="text-sm text-ink-muted">Past events are kept; only new events need the new key.</p>
      </Modal>
    </Card>
  );
};
