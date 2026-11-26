import { useCallback, useEffect, useMemo, useState } from 'react';
import { ArrowDown, ArrowUp, CircleHelp, LoaderCircle, Play, Plus, Search, Trash2 } from 'lucide-react';
import { fetchAnalyticsBreakdown, fetchAnalyticsOverview } from '@/features/analytics/api/analyticsApi';
import { GeoSuggestionPanel } from '@/features/analytics/components/GeoSuggestionPanel';
import { AnalyticsSource } from '@/features/analytics/components/AnalyticsSource';
import { geoApi, type GeoAudit, type GeoPrompt, type GeoRun, type GeoRunDetail, type GeoSettings, type GeoSummary } from '@/features/analytics/api/geoApi';
import { AnalyticsTimeSeriesChart } from '@/features/analytics/components/AnalyticsTimeSeriesChart';
import type { AnalyticsRange } from '@/features/analytics/types/AnalyticsRange';
import type { AnalyticsSeriesPoint } from '@/features/analytics/types/AnalyticsSeriesPoint';
import { Button } from '@/shared/components/Button';
import { Card } from '@/shared/components/Card';
import { useLiveStore } from '@/stores/liveStore';

interface Props { projectId: string; range: AnalyticsRange; onFix: (request: string) => void }
const columns = 'grid gap-3 md:grid-cols-2';
const caption = 'text-[11px] font-medium uppercase tracking-wide text-ink-subtle';
const errorText = (value: unknown) => value instanceof Error ? value.message : 'Something went wrong.';
const dateLabel = (value: string | null) => value ? new Date(value).toLocaleString() : 'Not scheduled';

export const AnalyticsGeoSection = ({ projectId, range, onFix }: Props) => {
  const [settings, setSettings] = useState<GeoSettings | null>(null);
  const [prompts, setPrompts] = useState<GeoPrompt[]>([]);
  const [summary, setSummary] = useState<GeoSummary | null>(null);
  const [runs, setRuns] = useState<GeoRun[]>([]);
  const [audits, setAudits] = useState<GeoAudit[]>([]);
  const [details, setDetails] = useState<Record<string, GeoRunDetail>>({});
  const [aiSources, setAiSources] = useState<{ key: string; visitors: number }[]>([]);
  const [aiLandings, setAiLandings] = useState<{ key: string; visitors: number }[]>([]);
  const [totalVisitors, setTotalVisitors] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [promptDraft, setPromptDraft] = useState('');
  const [termsDraft, setTermsDraft] = useState('');
  const [competitorDraft, setCompetitorDraft] = useState('');
  const [openRun, setOpenRun] = useState<string | null>(null);
  const liveRun = useLiveStore((state) => state.analyticsGeoRuns[projectId]);

  const reload = useCallback(async () => {
    const [nextSettings, nextPrompts, nextSummary, nextRuns, nextAudits, sources, landings, nextOverview] = await Promise.all([
      geoApi.settings(projectId), geoApi.prompts(projectId), geoApi.summary(projectId, range), geoApi.runs(projectId), geoApi.audits(projectId),
      fetchAnalyticsBreakdown(projectId, range, 'ai_source'), fetchAnalyticsBreakdown(projectId, range, 'ai_landing'),
      fetchAnalyticsOverview(projectId, range),
    ]);
    setSettings(nextSettings); setPrompts(nextPrompts); setSummary(nextSummary); setRuns(nextRuns); setAudits(nextAudits); setTotalVisitors(nextOverview.visitors.value);
    setAiSources(sources.map((row) => ({ key: row.key, visitors: row.visitors })));
    setAiLandings(landings.map((row) => ({ key: row.key, visitors: row.visitors })));
    setTermsDraft(nextSettings.brandTerms.join(', '));
    setCompetitorDraft(nextSettings.competitors.map((item) => `${item.name}|${item.domain}`).join('\n'));
    setError(null);
  }, [projectId, range]);

  useEffect(() => { void reload().catch((failure) => setError(errorText(failure))); }, [reload]);
  const activeRun = runs.find((run) => ['queued', 'running'].includes(run.status)) ?? null;
  useEffect(() => {
    if (!activeRun) return undefined;
    const timer = window.setInterval(() => { void reload().catch(() => undefined); }, 5000);
    return () => window.clearInterval(timer);
  }, [activeRun?.id, reload]);
  useEffect(() => {
    if (!liveRun || String(liveRun.projectId ?? '') !== projectId) return;
    void reload().catch(() => undefined);
  }, [liveRun, projectId, reload]);

  const aiVisitors = aiSources.reduce((total, row) => total + row.visitors, 0);
  const overall = useMemo(() => {
    const answered = summary?.visibility.reduce((sum, item) => sum + item.answeredPrompts, 0) ?? 0;
    const mentioned = summary?.visibility.reduce((sum, item) => sum + item.mentionedPrompts, 0) ?? 0;
    const cited = summary?.visibility.reduce((sum, item) => sum + item.citedPrompts, 0) ?? 0;
    const positions = summary?.visibility.filter((item) => item.averageFirstMentionPosition != null) ?? [];
    return { share: answered ? mentioned / answered * 100 : 0, citation: answered ? cited / answered * 100 : 0,
      position: positions.length ? positions.reduce((sum, item) => sum + (item.averageFirstMentionPosition ?? 0), 0) / positions.length : null };
  }, [summary]);
  const trend: AnalyticsSeriesPoint[] = useMemo(() => {
    const byRun = new Map<string, { bucket: string; value: number; total: number }>();
    summary?.trend.forEach((point) => { const current = byRun.get(point.runId) ?? { bucket: point.createdAt, value: 0, total: 0 };
      current.value += point.mentionedPrompts; current.total += point.answeredPrompts; byRun.set(point.runId, current); });
    return [...byRun.values()].map((point) => ({ bucket: point.bucket, value: point.total ? point.value / point.total * 100 : 0 }));
  }, [summary]);

  const saveSettings = async (patch: Partial<GeoSettings>) => {
    if (!settings) return;
    setBusy(true); setError(null);
    try { setSettings(await geoApi.saveSettings(projectId, { ...settings, ...patch })); }
    catch (failure) { setError(errorText(failure)); }
    finally { setBusy(false); }
  };
  const addPrompt = async () => {
    if (!promptDraft.trim()) return;
    setBusy(true); setError(null);
    try { await geoApi.addPrompt(projectId, promptDraft.trim()); setPromptDraft(''); setPrompts(await geoApi.prompts(projectId)); }
    catch (failure) { setError(errorText(failure)); }
    finally { setBusy(false); }
  };
  const updatePrompt = async (prompt: GeoPrompt, patch: Partial<Pick<GeoPrompt, 'text' | 'enabled' | 'position'>>) => {
    try { await geoApi.editPrompt(projectId, prompt, patch); setPrompts(await geoApi.prompts(projectId)); }
    catch (failure) { setError(errorText(failure)); }
  };
  const runAudit = async () => {
    setBusy(true); setError(null);
    try { const audit = await geoApi.audit(projectId); setAudits((current) => [audit, ...current.filter((item) => item.id !== audit.id)]); }
    catch (failure) { setError(errorText(failure)); }
    finally { setBusy(false); }
  };
  const runNow = async () => {
    setBusy(true); setError(null);
    try { await geoApi.run(projectId); await reload(); }
    catch (failure) { setError(errorText(failure)); }
    finally { setBusy(false); }
  };

  return <section aria-labelledby="analytics-geo" className="flex flex-col gap-4">
    <div className="flex flex-wrap items-end justify-between gap-2"><div><h2 id="analytics-geo" className="text-[13px] font-semibold uppercase tracking-wide text-ink-muted">GEO · AI visibility</h2><p className="mt-1 text-sm text-ink-subtle">Two separate signals: AI referrals measured by the Mooi tracker, and brand visibility measured by running prompts through linked AI providers.</p></div></div>
    {error ? <p role="alert" className="rounded-control border border-danger/30 bg-danger-soft px-3 py-2 text-sm text-danger">{error}</p> : null}
    <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
      <Metric source="Mooi tracker referrers" title="AI referral visitors" value={aiVisitors.toLocaleString()} hint="Visits attributed to recognized AI referrers" />
      <Metric source="Mooi tracker" title="Share of tracked visits" value={totalVisitors ? `${(aiVisitors / totalVisitors * 100).toFixed(1)}%` : '—'} hint={`${totalVisitors.toLocaleString()} Mooi-tracked visitors`} />
      <Metric source="AI visibility prompt runs" title="Prompt share of voice" value={summary?.visibility.length ? `${overall.share.toFixed(1)}%` : '—'} hint="Brand mentions across answered prompts" />
      <Metric source="AI visibility prompt runs" title="Prompt citation rate" value={summary?.visibility.length ? `${overall.citation.toFixed(1)}%` : '—'} hint={overall.position == null ? 'No prompt run data for this range' : `Avg. first mention #${overall.position.toFixed(1)}`} />
    </div>
    <div className={columns}>
      <Card className="flex min-w-0 flex-col gap-3 p-4"><h3 className="text-sm font-semibold">AI referral sources</h3><AnalyticsSource name="Mooi tracker referrers" /><Rows rows={aiSources} empty="No recognized AI referrals in this range. These require the Mooi tracker on the production site." /></Card>
      <Card className="flex min-w-0 flex-col gap-3 p-4"><h3 className="text-sm font-semibold">AI landing pages</h3><AnalyticsSource name="Mooi tracker referrers" /><Rows rows={aiLandings} empty="No AI landing pages in this range." /></Card>
      <Card className="flex min-w-0 flex-col gap-3 p-4 md:col-span-2"><h3 className="text-sm font-semibold">Visibility trend</h3><AnalyticsSource name="AI visibility prompt runs" /><AnalyticsTimeSeriesChart points={trend} label="Share of voice (%)" /></Card>
    </div>
    <div className={columns}>
      <Card className="flex min-w-0 flex-col gap-3 p-4"><h3 className="text-sm font-semibold">Prompt results by AI engine</h3><AnalyticsSource name="AI visibility prompt runs" />{summary?.visibility.length ? summary.visibility.map((item) => <div key={`${item.provider}:${item.model}`} className="flex justify-between gap-3 text-sm"><span className="truncate">{item.provider} · {item.model}</span><span className="tabular-nums">{item.shareOfVoice.toFixed(1)}% <span className="text-ink-subtle">· cited {item.citationRate.toFixed(1)}%</span></span></div>) : <p className="text-sm text-ink-subtle">No prompt run data in this range. Configure prompts and run AI visibility to measure mentions and citations.</p>}</Card>
      <Card className="flex min-w-0 flex-col gap-3 p-4"><h3 className="text-sm font-semibold">Competitor mentions</h3><AnalyticsSource name="AI visibility prompt runs" /><Rows rows={Object.entries(summary?.visibility.reduce<Record<string, number>>((acc, item) => { Object.entries(item.competitorMentions).forEach(([name, count]) => { acc[name] = (acc[name] ?? 0) + count; }); return acc; }, {}) ?? {}).map(([key, visitors]) => ({ key, visitors }))} empty="No competitor mentions yet." /></Card>
    </div>

    <Card className="flex flex-col gap-4 p-4 sm:p-5">
      <div className="flex flex-wrap items-center justify-between gap-3"><div><h3 className="font-semibold">Manage AI visibility</h3><p className="mt-1 text-xs text-ink-subtle">Runs use your linked provider accounts and respect their available quota.</p></div><Button variant="brand" size="sm" onClick={() => void runNow()} disabled={busy || Boolean(activeRun)}><Play className="size-4" />{activeRun ? 'Run in progress' : 'Run now'}</Button></div>
      <AnalyticsSource name="AI visibility prompt runs · responses from linked AI providers" />
      {activeRun ? <p role="status" className="flex items-center gap-2 rounded-control bg-brand/10 px-3 py-2 text-sm text-ink"><LoaderCircle className="size-4 animate-spin text-brand" />{String(liveRun?.message ?? `Visibility run ${activeRun.status}…`)} · {activeRun.status}</p> : null}
      <GeoSuggestionPanel key={projectId} projectId={projectId} onSaved={() => void reload().catch(() => undefined)} />
      <div className={columns}>
        <label className="flex flex-col gap-2"><span className={caption}>Brand terms · comma separated</span><textarea value={termsDraft} onChange={(event) => setTermsDraft(event.target.value)} onBlur={() => void saveSettings({ brandTerms: termsDraft.split(',').map((value) => value.trim()).filter(Boolean) })} rows={2} className="rounded-control border border-line bg-surface-2 p-3 text-sm text-ink" placeholder="Your brand, product names" /></label>
        <label className="flex flex-col gap-2"><span className={caption}>Competitors · one name|domain per line</span><textarea value={competitorDraft} onChange={(event) => setCompetitorDraft(event.target.value)} onBlur={() => void saveSettings({ competitors: competitorDraft.split('\n').map((line) => line.trim()).filter(Boolean).map((line) => { const [name, domain] = line.split('|'); return { name: name.trim(), domain: (domain ?? '').trim() }; }).filter((item) => item.name && item.domain) })} rows={2} className="rounded-control border border-line bg-surface-2 p-3 text-sm text-ink" placeholder={'Acme|acme.com'} /></label>
      </div>
      <label className="flex flex-wrap items-center gap-2 text-sm"><input type="checkbox" checked={settings?.weeklyEnabled ?? false} onChange={(event) => void saveSettings({ weeklyEnabled: event.target.checked })} disabled={!settings || busy} className="accent-brand" /> Run weekly{settings?.weeklyEnabled ? <span className="text-xs text-ink-subtle">Next run: {dateLabel(settings.nextRunAt)}</span> : null}</label>
      <div className="flex flex-col gap-2"><span className={caption}>Tracked prompts · up to 20</span>{prompts.map((prompt, index) => <PromptRow key={prompt.id} prompt={prompt} index={index} count={prompts.length} onChange={(patch) => void updatePrompt(prompt, patch)} onDelete={() => void geoApi.deletePrompt(projectId, prompt.id).then(() => setPrompts((items) => items.filter((item) => item.id !== prompt.id))).catch((failure) => setError(errorText(failure)))} />)}
        <div className="flex gap-2"><input value={promptDraft} maxLength={500} onChange={(event) => setPromptDraft(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter') { event.preventDefault(); void addPrompt(); } }} placeholder="What would a customer ask an AI assistant?" className="min-w-0 flex-1 rounded-control border border-line bg-surface-2 px-3 py-2 text-sm text-ink" /><Button variant="secondary" size="sm" onClick={() => void addPrompt()} disabled={busy || !promptDraft.trim() || prompts.length >= 20}><Plus className="size-4" />Add</Button></div>
      </div>
      <div className="flex flex-col gap-2"><span className={caption}>Recent runs</span>{runs.length ? runs.map((run) => <div key={run.id} className="rounded-control border border-line p-3"><button className="flex w-full flex-wrap items-center justify-between gap-2 text-left text-sm" onClick={() => { setOpenRun(openRun === run.id ? null : run.id); if (!details[run.id]) void geoApi.runDetail(projectId, run.id).then((value) => setDetails((current) => ({ ...current, [run.id]: value }))).catch((failure) => setError(errorText(failure))); }}><span>{new Date(run.createdAt).toLocaleString()} · {run.trigger}</span><span className="text-ink-muted">{run.status}{run.reason ? ` · ${run.reason}` : ''}</span></button>{openRun === run.id ? <RunResults detail={details[run.id]} prompts={prompts} /> : null}</div>) : <p className="text-sm text-ink-subtle">No visibility runs yet.</p>}</div>
    </Card>

    <Card className="flex flex-col gap-4 p-4 sm:p-5"><div className="flex flex-wrap items-center justify-between gap-3"><div><h3 className="font-semibold">SEO & GEO audit</h3><p className="mt-1 text-xs text-ink-subtle">Checks production crawl access, metadata, structured data and server-rendered content.</p></div><Button variant="secondary" size="sm" onClick={() => void runAudit()} disabled={busy}><Search className="size-4" />Run audit</Button></div>
      <AnalyticsSource name="Mooi SEO & GEO audit of the production site" />
      {audits.length ? audits.map((audit) => <AuditCard key={audit.id} audit={audit} onFix={onFix} />) : <p className="text-sm text-ink-subtle">No audits yet. Run an audit to find SEO and AI crawler improvements.</p>}
    </Card>
  </section>;
};

const Metric = ({ title, value, hint, source }: { title: string; value: string; hint: string; source: string }) => <Card className="min-w-0 p-3 sm:p-4"><p className={caption}>{title}</p><p className="mt-1 truncate text-xl font-semibold tabular-nums text-ink">{value}</p><p className="mt-1 text-xs text-ink-subtle">{hint}</p><AnalyticsSource name={source} /></Card>;
const Rows = ({ rows, empty }: { rows: { key: string; visitors: number }[]; empty: string }) => rows.length ? <div className="flex flex-col gap-2">{rows.map((row) => <div key={row.key} className="flex justify-between gap-3 text-sm"><span className="min-w-0 truncate">{row.key}</span><span className="tabular-nums text-ink-muted">{row.visitors.toLocaleString()}</span></div>)}</div> : <p className="py-4 text-center text-sm text-ink-subtle">{empty}</p>;
const PromptRow = ({ prompt, index, count, onChange, onDelete }: { prompt: GeoPrompt; index: number; count: number; onChange: (patch: Partial<Pick<GeoPrompt, 'text' | 'enabled' | 'position'>>) => void; onDelete: () => void }) => <div className="flex min-w-0 flex-wrap items-center gap-2 rounded-control border border-line p-2"><input defaultValue={prompt.text} maxLength={500} onBlur={(event) => { if (event.target.value.trim() !== prompt.text) onChange({ text: event.target.value.trim() }); }} className="min-w-[12rem] flex-1 bg-transparent px-1 py-1 text-sm text-ink focus:outline-none" aria-label="Tracked prompt" /><label className="flex items-center gap-1 text-xs text-ink-muted"><input type="checkbox" checked={prompt.enabled} onChange={(event) => onChange({ enabled: event.target.checked })} className="accent-brand" />Enabled</label><Button variant="ghost" size="sm" ariaLabel="Move prompt up" disabled={index === 0} onClick={() => onChange({ position: Math.max(0, prompt.position - 1) })}><ArrowUp className="size-4" /></Button><Button variant="ghost" size="sm" ariaLabel="Move prompt down" disabled={index === count - 1} onClick={() => onChange({ position: prompt.position + 1 })}><ArrowDown className="size-4" /></Button><Button variant="ghost" size="sm" ariaLabel="Delete prompt" onClick={onDelete}><Trash2 className="size-4 text-danger" /></Button></div>;
const RunResults = ({ detail, prompts }: { detail?: GeoRunDetail; prompts: GeoPrompt[] }) => !detail ? <p className="mt-3 text-xs text-ink-subtle">Loading results…</p> : <div className="mt-3 flex flex-col gap-2">{detail.results.map((result) => <div key={result.id} className="grid gap-1 border-t border-line pt-2 text-xs sm:grid-cols-[minmax(10rem,1fr)_minmax(10rem,1fr)_auto] sm:items-start"><span className="font-medium text-ink">{prompts.find((prompt) => prompt.id === result.promptId)?.text ?? 'Prompt'}</span><span className="text-ink-muted">{result.provider} · {result.model}</span><span className="flex gap-2 text-ink-muted"><span>{result.error ?? (result.mentioned ? 'Mentioned' : 'Not mentioned')}</span>{result.cited ? <span className="text-success">Cited</span> : null}{result.position ? <span>#{result.position}</span> : null}</span>{result.excerpt ? <p className="text-ink-subtle sm:col-span-3">{result.excerpt}</p> : null}</div>)}</div>;
const AuditCard = ({ audit, onFix }: { audit: GeoAudit; onFix: (request: string) => void }) => {
  const grouped = audit.findings.reduce<Record<string, typeof audit.findings>>((acc, finding) => { (acc[finding.category] ??= []).push(finding); return acc; }, {});
  return <div className="rounded-control border border-line p-3"><div className="flex flex-wrap items-center gap-3"><div className="relative grid size-14 place-items-center rounded-full border-[5px] border-brand/30 text-sm font-bold text-ink"><span>{audit.score}</span></div><div><p className="font-medium">{audit.url}</p><p className="text-xs text-ink-subtle">Score · {new Date(audit.createdAt).toLocaleString()}</p></div></div><div className="mt-3 grid gap-3 md:grid-cols-2">{Object.entries(grouped).map(([category, findings]) => <section key={category} className="flex flex-col gap-2"><h4 className={caption}>{category}</h4>{findings.map((finding) => <div key={finding.id} className="rounded-control bg-surface-2 p-3"><div className="flex flex-wrap items-center justify-between gap-2"><p className="text-sm font-medium">{finding.title}</p><span className={`rounded-full px-2 py-0.5 text-[10px] uppercase ${finding.severity === 'critical' ? 'bg-danger-soft text-danger' : 'bg-warning-soft text-ink-muted'}`}>{finding.severity}</span></div><p className="mt-1 text-xs text-ink-muted">{finding.detail}</p><p className="mt-2 text-xs text-ink">Fix: {finding.fix}</p><Button variant="secondary" size="sm" className="mt-2" onClick={() => onFix(`Fix this ${finding.category} finding on the production site ${audit.url}: ${finding.title}. Finding: ${finding.detail}. Suggested fix: ${finding.fix}`)}><CircleHelp className="size-4" />Fix in a session</Button></div>)}</section>)}</div></div>;
};
