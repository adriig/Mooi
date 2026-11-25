import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { AlertCircle, ChevronDown, ChevronRight, LoaderCircle, Sparkles } from 'lucide-react';
import { createAnalyticsAnalysis, fetchAnalyticsAnalysis, generateAnalyticsAnalysis, listAnalyticsAnalyses, type AnalyticsAnalysis } from '@/features/analytics/api/analyticsApi';
import type { AnalyticsRange } from '@/features/analytics/types/AnalyticsRange';
import type { AnalyticsSite } from '@/features/analytics/types/AnalyticsSite';
import { useLiveStore } from '@/stores/liveStore';
import { Button } from '@/shared/components/Button';
import { Card } from '@/shared/components/Card';

const SOURCES = [
  ['tracker', 'Mooi tracker'], ['ga4', 'Google Analytics 4'], ['search_console', 'Search Console'],
  ['ai_referrals', 'AI referrals · tracker'], ['geo_visibility', 'AI visibility runs'], ['seo_geo_audits', 'SEO/GEO audits'],
] as const;
const SOURCE_LABEL: Record<string, string> = Object.fromEntries(SOURCES);
const STATUS_LABEL: Record<string, string> = {
  collecting: 'Collecting source data', queued: 'Waiting for an available analyst', running: 'Generating diagnosis',
  completed: 'Diagnosis ready', partial: 'Partial diagnosis', no_data: 'No usable data in this period',
  failed: 'Agent failed', invalid_output: 'Agent returned an invalid result', no_account: 'No eligible AI account', quota: 'Waiting for account quota',
};
const SOURCE_STATUS: Record<string, string> = { available: 'Available', partial: 'Partial', not_connected: 'Not connected', empty: 'No data', failed: 'Source failed' };
const periodKey = (p: { from: string; to: string; timezone: string }) => `${p.from}|${p.to}|${p.timezone}`;
const dateTime = (value: string | null | undefined) => value ? new Date(value).toLocaleString() : 'Unknown';
const priorityTone = (priority: string) => priority === 'high' ? 'text-danger' : priority === 'medium' ? 'text-warning' : 'text-ink-muted';

interface Props { projectId: string; range: AnalyticsRange; site: AnalyticsSite | null; onConfigure: () => void }

/** Read-only, persisted diagnosis view. All actions call the shared Analytics diagnostic API. */
export const AnalyticsAnalysisPanel = ({ projectId, range, site, onConfigure }: Props) => {
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState<AnalyticsAnalysis[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const currentKey = periodKey(range);
  const current = items.find((item) => periodKey(item.period) === currentKey) ?? null;
  const selected = items.find((item) => item.id === selectedId) ?? current ?? items[0] ?? null;
  const activeAnalysisId = selected?.id ?? null;
  const liveRevision = useLiveStore((state) => state.analyticsAnalysisRevisions[activeAnalysisId ? `${projectId}:${activeAnalysisId}` : ''] ?? 0);
  const isCurrent = selected ? selected.projectId === projectId && periodKey(selected.period) === currentKey : false;
  const evidenceById = useMemo(() => new Map(selected?.evidence.map((item) => [item.id, item]) ?? []), [selected]);

  useEffect(() => {
    let active = true;
    listAnalyticsAnalyses(projectId).then((next) => { if (active) { setItems(next); setError(null); } })
      .catch((failure: Error) => { if (active) setError(failure.message); });
    return () => { active = false; };
  }, [projectId]);

  useEffect(() => {
    if (!activeAnalysisId) return;
    let active = true;
    fetchAnalyticsAnalysis(projectId, activeAnalysisId).then((next) => { if (active) setItems((old) => [next, ...old.filter((item) => item.id !== next.id)]); })
      .catch((failure: Error) => { if (active) setError(failure.message); });
    return () => { active = false; };
  }, [projectId, activeAnalysisId, liveRevision]);

  const act = async () => {
    setBusy(true); setError(null);
    try {
      let analysis = await createAnalyticsAnalysis(projectId, crypto.randomUUID(), { from: range.from, to: range.to, timezone: range.timezone });
      setItems((old) => [analysis!, ...old.filter((item) => item.id !== analysis!.id)]);
      setSelectedId(analysis.id);
      const updated = await generateAnalyticsAnalysis(projectId, analysis.id);
      setItems((old) => [updated, ...old.filter((item) => item.id !== updated.id)]);
      setSelectedId(updated.id);
    } catch (failure) { setError(failure instanceof Error ? failure.message : 'Could not run the diagnosis.'); }
    finally { setBusy(false); }
  };

  const refreshSelected = async () => {
    if (!selected) return;
    setBusy(true); setError(null);
    try {
      const updated = await fetchAnalyticsAnalysis(projectId, selected.id);
      if (updated.status === 'failed' || updated.status === 'invalid_output' || updated.status === 'no_account' || updated.status === 'quota') {
        const retried = await generateAnalyticsAnalysis(projectId, updated.id);
        setItems((old) => [retried, ...old.filter((item) => item.id !== retried.id)]);
      } else setItems((old) => [updated, ...old.filter((item) => item.id !== updated.id)]);
    } catch (failure) { setError(failure instanceof Error ? failure.message : 'Could not retry the diagnosis.'); }
    finally { setBusy(false); }
  };

  const configuringSources = [
    { id: 'tracker', connected: Boolean(site), label: 'Mooi tracker' },
    { id: 'ga4', connected: Boolean(site?.ga4PropertyId), label: 'Google Analytics 4' },
    { id: 'search_console', connected: Boolean(site?.searchConsoleSiteUrl), label: 'Search Console' },
    { id: 'ai_referrals', connected: Boolean(site), label: 'AI referrals · tracker' },
    { id: 'geo_visibility', connected: null, label: 'AI visibility runs' },
    { id: 'seo_geo_audits', connected: null, label: 'SEO/GEO audits' },
  ];
  const busyStatus = selected && isCurrent && ['collecting', 'queued', 'running'].includes(selected.status);
  const configuredSources = configuringSources.filter((source) => source.connected === true).length;

  return <section aria-labelledby="analytics-analysis-heading" className="flex flex-col gap-3">
    <div className="flex flex-wrap items-center justify-between gap-3">
      <div><h2 id="analytics-analysis-heading" className="text-sm font-semibold">Analytics diagnosis</h2>
        <p className="mt-1 text-xs text-ink-subtle">Read-only analysis of saved sources for the selected period.</p></div>
      <p className="text-xs text-ink-muted">{configuredSources}/4 connected · historical runs checked when requested</p>
      <Button variant="secondary" size="sm" onClick={() => setOpen((value) => !value)} aria-expanded={open}>
        <Sparkles className="size-4" />{open ? 'Hide diagnosis' : 'Analyze data'}
      </Button>
    </div>
    {open ? <Card className="flex flex-col gap-5 p-4 sm:p-5">
      <div>
        <h3 className="text-xs font-semibold uppercase tracking-wide text-ink-muted">Source coverage</h3>
        <ul className="mt-3 grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
          {configuringSources.map((source) => {
            const collected = current?.evidence.find((entry) => entry.source === source.id);
            const label = collected ? SOURCE_STATUS[collected.status] : source.connected === null ? 'Historical data checked when generated' : source.connected ? 'Connected · checked when generated' : 'Not connected';
            return <li key={source.id} className="flex min-w-0 items-start justify-between gap-2 rounded-control border border-line bg-surface-2 px-3 py-2 text-xs">
              <span>{source.label}</span><span className="text-right text-ink-muted">{label}</span>
            </li>;
          })}
        </ul>
        <p className="mt-2 text-xs text-ink-subtle">Unavailable sources remain explicit; they are never treated as zero. Historical GEO runs and audits are only read, never launched.</p>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <Button variant="brand" size="sm" onClick={act} disabled={busy || Boolean(busyStatus)}>
          {busy || busyStatus ? <LoaderCircle className="size-4 animate-spin" /> : <Sparkles className="size-4" />}
          {current ? 'Collect sources again' : 'Generate diagnosis'}
        </Button>
        {!site?.googleConnectionId || (!site?.ga4PropertyId && !site?.searchConsoleSiteUrl) ? <Button variant="secondary" size="sm" onClick={onConfigure}>Configure data sources</Button> : null}
      {selected && !isCurrent ? <span role="status" className="rounded-full bg-warning-soft px-2.5 py-1 text-xs font-medium text-warning">Previous diagnosis · another period</span> : null}
      </div>
      {error ? <div role="alert" className="flex items-center gap-2 text-sm text-danger"><AlertCircle className="size-4 shrink-0" />{error}<Button variant="secondary" size="sm" onClick={() => void (selected ? refreshSelected() : act())}>Retry</Button></div> : null}
      {selected ? <div className="flex flex-col gap-4 border-t border-line pt-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div><h3 className="text-sm font-semibold">{STATUS_LABEL[selected.status] ?? selected.status}</h3>
            <p className="mt-1 text-xs text-ink-muted">Period: {dateTime(selected.period.from)} – {dateTime(selected.period.to)} · {selected.period.timezone}</p></div>
          {busyStatus ? <span role="status" className="inline-flex items-center gap-2 text-xs text-ink-muted"><LoaderCircle className="size-4 animate-spin" />Progress updates automatically</span>
            : selected.status === 'failed' || selected.status === 'invalid_output' || selected.status === 'no_account' || selected.status === 'quota'
              ? <Button variant="secondary" size="sm" onClick={refreshSelected} disabled={busy}>Retry</Button> : null}
        </div>
        {selected.reason ? <p className="rounded-control bg-surface-2 p-3 text-sm text-ink-muted">{selected.reason}</p> : null}
        {selected.status === 'no_account' || selected.status === 'quota' ? <Link to="/account?tab=agents" className="self-start rounded-control border border-line px-3 py-2 text-xs font-medium text-ink hover:bg-surface-2 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2">Review agent accounts</Link> : null}
        {selected.status === 'no_data' ? <p role="status" className="text-sm text-ink-muted">No usable evidence was available for this period. A missing source is not a zero value.</p> : null}
        {selected.status === 'partial' ? <p role="status" className="text-sm text-warning">Some sources failed or returned limited data. Interpret the findings with the coverage notes below.</p> : null}
        {selected.evidence.length ? <div><h4 className="text-xs font-semibold uppercase tracking-wide text-ink-muted">Collected source results</h4>
          <ul className="mt-2 grid gap-2 sm:grid-cols-2">{selected.evidence.map((entry) => <li key={entry.id} className="rounded-control border border-line p-3 text-xs">
            <div className="flex justify-between gap-2"><strong>{SOURCE_LABEL[entry.source] ?? entry.source}</strong><span>{SOURCE_STATUS[entry.status]}</span></div>
            <p className="mt-1 text-ink-muted">Queried {dateTime(entry.queriedAt)}{entry.actualPeriod ? ` · data period ${dateTime(entry.actualPeriod.from)} – ${dateTime(entry.actualPeriod.to)} (${entry.actualPeriod.timezone})` : ''}</p>
            {entry.coverage.description ? <p className="mt-1">{entry.coverage.description}</p> : null}
            {entry.coverage.returnedRows !== null || entry.coverage.rowLimit !== null ? <p className="mt-1 text-ink-muted">Rows: {entry.coverage.returnedRows ?? 'unknown'} / limit {entry.coverage.rowLimit ?? 'unknown'}{entry.coverage.truncated ? ' · truncated' : ''}</p> : null}
            {entry.coverage.dataFetchedAt ? <p className="mt-1 text-ink-muted">Fresh as of {dateTime(entry.coverage.dataFetchedAt)}{entry.coverage.dataUntil ? ` · data through ${entry.coverage.dataUntil}` : ''}</p> : null}
            {entry.coverage.sampleSize !== null ? <p className="mt-1 text-ink-muted">Sample: {entry.coverage.sampleSize} {entry.coverage.sampleUnit ?? ''}</p> : null}
            {[...entry.coverage.limitations, ...entry.errors].map((note, index) => <p key={index} className="mt-1 text-warning">{note}</p>)}
          </li>)}</ul></div> : null}
        {selected.result ? <>
          <section><h4 className="text-sm font-semibold">Summary</h4><p className="mt-1 whitespace-pre-wrap text-sm text-ink-muted">{selected.result.summary}</p></section>
          <section><h4 className="text-sm font-semibold">Prioritized findings</h4>
            {selected.result.findings.length ? <ul className="mt-2 flex flex-col gap-3">{selected.result.findings.map((finding) => <li key={finding.id} className="rounded-control border border-line p-3">
              <div className="flex flex-wrap items-center gap-2"><span className={`text-xs font-bold uppercase ${priorityTone(finding.priority)}`}>{finding.priority} priority</span><strong className="text-sm">{finding.title}</strong></div>
              <p className="mt-2 whitespace-pre-wrap text-sm">{finding.interpretation}</p>
              <div className="mt-2 flex flex-wrap gap-2">{finding.evidence.map((reference) => { const evidence = evidenceById.get(reference.evidenceId); return <span key={reference.evidenceId} className="rounded-full bg-surface-2 px-2 py-1 text-xs text-ink-muted">{SOURCE_LABEL[evidence?.source ?? ''] ?? 'Source'} · {dateTime(reference.period.from)} – {dateTime(reference.period.to)}</span>; })}</div>
              {finding.limitations.length ? <p className="mt-2 text-xs text-ink-muted">Limitations: {finding.limitations.join(' · ')}</p> : null}
              <button type="button" className="mt-3 inline-flex items-center gap-1 text-xs font-medium text-brand-strong focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2" aria-expanded={Boolean(expanded[finding.id])} onClick={() => setExpanded((old) => ({ ...old, [finding.id]: !old[finding.id] }))}>
                {expanded[finding.id] ? <ChevronDown className="size-4" /> : <ChevronRight className="size-4" />}Evidence details
              </button>
              {expanded[finding.id] ? <pre className="mt-2 max-h-72 overflow-auto whitespace-pre-wrap break-words rounded-control bg-surface-2 p-3 text-xs">{JSON.stringify(finding.evidence.map((reference) => ({ reference, evidence: evidenceById.get(reference.evidenceId)?.data })), null, 2)}</pre> : null}
            </li>)}</ul> : <p className="mt-2 text-sm text-ink-muted">No supported findings were returned.</p>}
          </section>
          <section><h4 className="text-sm font-semibold">Recommendations</h4>
            {selected.result.recommendations.length ? <ul className="mt-2 flex flex-col gap-3">{selected.result.recommendations.map((recommendation, index) => <li key={`${index}-${recommendation.action}`} className="rounded-control border border-line p-3 text-sm">
              <p className={`text-xs font-bold uppercase ${priorityTone(recommendation.priority)}`}>{recommendation.priority} priority</p><p className="mt-2"><strong>Why:</strong> {recommendation.justification}</p><p className="mt-1"><strong>Action:</strong> {recommendation.action}</p><p className="mt-1"><strong>Verify:</strong> {recommendation.verification}</p>
              <p className="mt-2 text-xs text-ink-muted">Evidence: {recommendation.evidence.map((ref) => SOURCE_LABEL[evidenceById.get(ref.evidenceId)?.source ?? ''] ?? 'Source').join(', ')}</p>
            </li>)}</ul> : <p className="mt-2 text-sm text-ink-muted">No evidence-backed recommendations.</p>}
          </section>
          <section><h4 className="text-sm font-semibold">General suggestions · not measured</h4>
            {selected.result.generalSuggestions.length ? <ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-ink-muted">{selected.result.generalSuggestions.map((suggestion, index) => <li key={index}>{suggestion}</li>)}</ul> : <p className="mt-2 text-sm text-ink-muted">No general suggestions.</p>}
          </section>
        </> : null}
      </div> : null}
      {items.length > 1 ? <label className="flex flex-wrap items-center gap-2 border-t border-line pt-3 text-xs text-ink-muted">Previous diagnoses
        <select className="min-h-9 rounded-control border border-line bg-surface px-2 text-ink" value={selected?.id ?? ''} onChange={(event) => setSelectedId(event.target.value)}>
          {items.map((item) => <option key={item.id} value={item.id}>{dateTime(item.createdAt)} · {new Date(item.period.from).toLocaleDateString()}–{new Date(item.period.to).toLocaleDateString()} · {item.status}</option>)}
        </select>
      </label> : null}
    </Card> : null}
  </section>;
};
