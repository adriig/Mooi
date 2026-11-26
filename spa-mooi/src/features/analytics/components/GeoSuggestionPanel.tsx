import { useCallback, useEffect, useRef, useState } from 'react';
import { AlertCircle, CheckCircle2, LoaderCircle, Sparkles, X } from 'lucide-react';
import {
  GeoApiError, geoApi, type GeoConfig, type GeoSuggestionAcceptResult, type GeoSuggestionEvidence, type GeoSuggestionGeneration,
  type GeoSuggestionReference, type GeoSuggestionSite, type GeoSuggestionStatus,
} from '@/features/analytics/api/geoApi';
import { Badge } from '@/shared/components/Badge';
import { Button } from '@/shared/components/Button';
import { useLiveStore } from '@/stores/liveStore';

interface Props { projectId: string; onSaved: () => void }
type Kind = 'brandTerms' | 'competitors' | 'prompts';
/** One editable, selectable draft element; `domain` only applies to competitors. */
interface DraftItem { id: string; text: string; domain: string; selected: boolean; rationale: string; references: GeoSuggestionReference[]; inferred: boolean; relationship: string }
type Draft = Record<Kind, DraftItem[]>;

const caption = 'text-[11px] font-medium uppercase tracking-wide text-ink-subtle';
const field = 'min-w-0 rounded-control border border-line bg-surface px-2 py-1.5 text-sm text-ink';
const errorText = (value: unknown) => value instanceof Error ? value.message : 'Something went wrong.';
const dateTime = (value: string | null | undefined) => value ? new Date(value).toLocaleString() : 'Unknown';
const KIND_LABEL: Record<Kind, string> = { brandTerms: 'Brand terms', competitors: 'Competitors', prompts: 'Tracked prompts' };
const WORKING: GeoSuggestionStatus[] = ['collecting', 'generating'];
const DRAFTING: GeoSuggestionStatus[] = ['completed', 'partial', 'empty'];

/** Status → what happened and what the person can do next. Manual editing stays available in every case. */
const STATE_COPY: Partial<Record<GeoSuggestionStatus, { title: string; hint: string }>> = {
  production_unconfigured: { title: 'Production is not configured', hint: 'Suggestions read the public production site. Configure a production deployment first, or fill in the fields below by hand.' },
  restricted: { title: 'The site restricts crawling', hint: 'robots.txt or noindex rules keep Mooi from reading the public pages, so nothing was proposed. Add the terms by hand below.' },
  inaccessible: { title: 'The production site could not be reached', hint: 'Check that the production site is online and public, then try again.' },
  insufficient_evidence: { title: 'Not enough public information', hint: 'The site did not offer enough readable content to support suggestions, so the AI was not asked. Add terms by hand or try again once the site has more public content.' },
  no_account: { title: 'No eligible AI account', hint: 'Link a Claude, Codex, Antigravity or OpenCode account in your account settings, then try again.' },
  quota: { title: 'AI accounts are out of quota', hint: 'Every linked account is spared or has reached its limits. Try again when a quota window resets.' },
  invalid_output: { title: 'The AI returned an unusable result', hint: 'Its answer did not pass validation against the pages consulted. Nothing was kept; try again.' },
  failed: { title: 'The proposal failed', hint: 'A recoverable error stopped the proposal. Your saved configuration is untouched; try again.' },
  superseded: { title: 'Replaced by a newer proposal', hint: 'A newer proposal for this project replaced this one.' },
};

const toDraft = (generation: GeoSuggestionGeneration): Draft => {
  const result = generation.result;
  const item = (id: string, text: string, rest: Partial<DraftItem>): DraftItem => ({ id, text, domain: '', selected: true, rationale: '', references: [], inferred: false, relationship: '', ...rest });
  return {
    brandTerms: (result?.brandTerms ?? []).map((term) => item(term.id, term.term, { rationale: term.rationale, references: term.references, inferred: term.inferred })),
    competitors: (result?.competitors ?? []).map((value) => item(value.id, value.name, { domain: value.domain, rationale: value.rationale, references: value.references, relationship: value.relationship })),
    prompts: (result?.prompts ?? []).map((prompt) => item(prompt.id, prompt.text, { rationale: prompt.rationale, references: prompt.references })),
  };
};
const norm = (value: string) => value.trim().toLowerCase();
const existsIn = (kind: Kind, entry: DraftItem, config: GeoConfig | null) => {
  if (!config) return false;
  if (kind === 'brandTerms') return config.settings.brandTerms.some((term) => norm(term) === norm(entry.text));
  if (kind === 'competitors') return config.settings.competitors.some((value) => norm(value.domain) === norm(entry.domain) || norm(value.name) === norm(entry.text));
  return config.prompts.some((prompt) => norm(prompt.text) === norm(entry.text));
};

/**
 * "Propose with AI": generates a draft from the public production site and lets the person review, edit,
 * select and explicitly save it. Nothing here writes configuration except {@link save}, which only runs from
 * the Save button. The component is keyed by project by its parent; a site change or a discard also invalidates
 * anything in flight through `token`.
 */
export const GeoSuggestionPanel = ({ projectId, onSaved }: Props) => {
  const [open, setOpen] = useState(false);
  const [site, setSite] = useState('');
  const [sites, setSites] = useState<GeoSuggestionSite[]>([]);
  const [generation, setGeneration] = useState<GeoSuggestionGeneration | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [config, setConfig] = useState<GeoConfig | null>(null);
  const [baseRevision, setBaseRevision] = useState<string | null>(null);
  const [requesting, setRequesting] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const [saved, setSaved] = useState<GeoSuggestionAcceptResult | null>(null);
  const token = useRef(0);
  const draftFor = useRef<string | null>(null);
  const acceptAttempt = useRef<{ key: string; id: string } | null>(null);
  const generationId = generation?.id ?? '';
  const live = useLiveStore((state) => state.analyticsGeoSuggestionRevisions[`${projectId}:${generationId}`] ?? 0);
  const status = generation?.status ?? null;
  const working = requesting || (status !== null && WORKING.includes(status));

  useEffect(() => () => { token.current += 1; }, []);

  /** Invalidates the draft and every response still on its way. */
  const reset = useCallback((keepSite: boolean) => {
    token.current += 1; draftFor.current = null; acceptAttempt.current = null;
    setGeneration(null); setDraft(null); setConfig(null); setBaseRevision(null); setError(null); setConflict(false); setSaved(null);
    setRequesting(false); setSaving(false);
    if (!keepSite) setSite('');
  }, []);

  const adopt = useCallback((next: GeoSuggestionGeneration) => {
    setGeneration(next);
    if (next.evidence?.sites.length) setSites(next.evidence.sites);
    if (!DRAFTING.includes(next.status) || draftFor.current === next.id) return;
    draftFor.current = next.id;
    setDraft(toDraft(next));
    setBaseRevision(next.result?.configRevision ?? next.configRevision);
    void geoApi.config(projectId).then((value) => { if (draftFor.current === next.id) { setConfig(value); setBaseRevision((old) => old ?? value.revision); } }).catch(() => undefined);
  }, [projectId]);

  const generate = async (chosen = site) => {
    reset(true); setOpen(true);
    const mine = token.current;
    setRequesting(true);
    try {
      const next = await geoApi.generateSuggestions(projectId, crypto.randomUUID(), chosen || undefined);
      if (token.current === mine) adopt(next);
    } catch (failure) { if (token.current === mine) setError(errorText(failure)); }
    finally { if (token.current === mine) setRequesting(false); }
  };

  // Progress: live events and a slow poll while the proposal is collecting or generating.
  useEffect(() => {
    if (!generationId) return undefined;
    const mine = token.current;
    const load = () => geoApi.suggestionGeneration(projectId, generationId)
      .then((next) => { if (token.current === mine) adopt(next); })
      .catch((failure) => { if (token.current === mine) setError(errorText(failure)); });
    void load();
    if (!status || !WORKING.includes(status)) return undefined;
    const timer = window.setInterval(() => { void load(); }, 3000);
    return () => window.clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, generationId, live, status === null || WORKING.includes(status)]);

  const changeSite = (value: string) => { reset(true); setSite(value); };
  const edit = (kind: Kind, id: string, patch: Partial<DraftItem>) => setDraft((old) => old ? { ...old, [kind]: old[kind].map((entry) => entry.id === id ? { ...entry, ...patch } : entry) } : old);
  const setAll = (selected: boolean) => setDraft((old) => old ? { brandTerms: old.brandTerms.map((e) => ({ ...e, selected })), competitors: old.competitors.map((e) => ({ ...e, selected })), prompts: old.prompts.map((e) => ({ ...e, selected })) } : old);

  const chosen = (kind: Kind) => (draft?.[kind] ?? []).filter((entry) => entry.selected && entry.text.trim() && (kind !== 'competitors' || entry.domain.trim()) && !existsIn(kind, entry, config));
  const room = generation?.result?.room;
  const over = (kind: Kind) => room ? chosen(kind).length > room[kind] : false;
  const total = chosen('brandTerms').length + chosen('competitors').length + chosen('prompts').length;
  const overLimit = over('brandTerms') || over('competitors') || over('prompts');

  const save = async () => {
    if (!draft || !baseRevision || !generation) return;
    const mine = token.current;
    const body = {
      baseRevision, generationId: generation.id,
      brandTerms: chosen('brandTerms').map((entry) => entry.text.trim()),
      competitors: chosen('competitors').map((entry) => ({ name: entry.text.trim(), domain: entry.domain.trim() })),
      prompts: chosen('prompts').map((entry) => entry.text.trim()),
    };
    const key = JSON.stringify(body);
    // The same attempt id on a retry of an identical selection keeps the save idempotent.
    if (acceptAttempt.current?.key !== key) acceptAttempt.current = { key, id: crypto.randomUUID() };
    setSaving(true); setError(null);
    try {
      const result = await geoApi.acceptSuggestions(projectId, { id: acceptAttempt.current.id, ...body });
      if (token.current !== mine) return;
      setSaved(result); setConfig(result.config); setDraft(null); setGeneration(null); draftFor.current = null; setConflict(false);
      onSaved();
    } catch (failure) {
      if (token.current !== mine) return;
      if (failure instanceof GeoApiError && failure.status === 409) setConflict(true);
      setError(errorText(failure));
    } finally { if (token.current === mine) setSaving(false); }
  };

  /** After a conflict: load the current configuration; only then can the unchanged draft be saved again. */
  const reviewCurrent = async () => {
    const mine = token.current;
    try {
      const value = await geoApi.config(projectId);
      if (token.current !== mine) return;
      setConfig(value); setBaseRevision(value.revision); setConflict(false); setError(null); onSaved();
    } catch (failure) { if (token.current === mine) setError(errorText(failure)); }
  };

  const copy = status ? STATE_COPY[status] : undefined;
  const reasonText = generation?.reason && copy ? generation.reason : null;

  return <div className="flex flex-col gap-3 rounded-control border border-line bg-surface-2/50 p-3 sm:p-4">
    <div className="flex flex-wrap items-center justify-between gap-3">
      <div className="min-w-0"><h4 className="text-sm font-semibold">Propose with AI</h4>
        <p className="mt-1 text-xs text-ink-subtle">Drafts brand terms, competitors and prompts from the public pages of your production site. A draft is only a proposal: nothing is saved, no run starts and the weekly schedule is untouched until you save a reviewed selection.</p></div>
      <Button variant="secondary" size="sm" onClick={() => void generate()} disabled={working || saving || (sites.length > 1 && !site)}><Sparkles className="size-4" />{generation || draft ? 'Propose again' : 'Propose with AI'}</Button>
    </div>
    {sites.length > 1 ? <label className="flex flex-col gap-1 sm:max-w-md"><span className={caption}>Production site to read</span>
      <select value={site} onChange={(event) => changeSite(event.target.value)} className={field}>
        <option value="">Choose a site…</option>{sites.map((item) => <option key={item.id} value={item.id}>{item.host}</option>)}
      </select>
      <span className="text-xs text-ink-subtle">Your production targets name several sites; choose which one to read. Changing it discards the current draft.</span></label> : null}
    {!open ? null : <div className="flex flex-col gap-3" aria-live="polite">
      {working ? <p role="status" className="flex items-center gap-2 rounded-control bg-brand/10 px-3 py-2 text-sm text-ink"><LoaderCircle className="size-4 animate-spin text-brand" />{status === 'generating' ? 'Asking a linked AI account to draft suggestions from the pages…' : 'Reading the public pages of the production site…'}</p> : null}
      {status === 'selection_required' ? <p role="status" className="rounded-control border border-line px-3 py-2 text-sm text-ink">Your production targets serve several sites. Choose one above, then propose again.</p> : null}
      {copy ? <div role="alert" className="flex gap-2 rounded-control border border-warning/40 bg-warning-soft px-3 py-2 text-sm"><AlertCircle className="mt-0.5 size-4 shrink-0 text-warning" /><div><p className="font-medium text-ink">{copy.title}</p><p className="mt-0.5 text-ink-muted">{copy.hint}</p>{reasonText ? <p className="mt-1 text-xs text-ink-subtle">Detail: {reasonText}</p> : null}</div></div> : null}
      {error ? <div role="alert" className="flex flex-wrap items-center justify-between gap-2 rounded-control border border-danger/30 bg-danger-soft px-3 py-2 text-sm text-danger"><span>{error}</span>
        {conflict ? <Button variant="secondary" size="sm" onClick={() => void reviewCurrent()}>Review current version</Button> : null}</div> : null}
      {conflict ? <p className="text-xs text-ink-muted">Your AI visibility configuration changed (another device or chat) after this draft was read. Your draft is kept; review the current version, then save again.</p> : null}
      {saved ? <p role="status" className="flex items-start gap-2 rounded-control bg-success-soft px-3 py-2 text-sm text-ink"><CheckCircle2 className="mt-0.5 size-4 shrink-0 text-success" /><span>{saved.status === 'replayed' ? 'Already saved. ' : 'Saved. '}Added {saved.added.brandTerms.length} brand terms, {saved.added.competitors.length} competitors and {saved.added.prompts.length} prompts{saved.skipped.length ? `; skipped ${saved.skipped.length} (${saved.skipped.map((entry) => `${entry.value}: ${entry.reason}`).join('; ')})` : ''}. Nothing existing was changed and no run was started.</span></p> : null}
      {generation?.evidence ? <Evidence evidence={generation.evidence} engine={generation.engine} /> : null}
      {draft && generation && DRAFTING.includes(generation.status) ? <>
        {generation.status === 'partial' ? <p className="text-sm text-ink-muted">Partial result: only what the pages support is shown.</p> : null}
        {generation.status === 'empty' ? <p className="text-sm text-ink-muted">The pages did not support any suggestion. Add terms by hand below.</p> : null}
        <Notes generation={generation} />
        {(['brandTerms', 'competitors', 'prompts'] as Kind[]).map((kind) => <DraftGroup key={kind} kind={kind} items={draft[kind]} config={config} room={room?.[kind]} overLimit={over(kind)} empty={kind === 'competitors' ? generation.result?.competitors.length === 0 : false} onEdit={(id, patch) => edit(kind, id, patch)} />)}
        <div className="flex flex-wrap items-center gap-2 border-t border-line pt-3">
          <Button variant="brand" size="sm" onClick={() => void save()} disabled={saving || conflict || !baseRevision || total === 0 || overLimit}>{saving ? <LoaderCircle className="size-4 animate-spin" /> : <CheckCircle2 className="size-4" />}Save {total} selected</Button>
          <Button variant="ghost" size="sm" onClick={() => setAll(true)} disabled={saving}>Select all</Button>
          <Button variant="ghost" size="sm" onClick={() => setAll(false)} disabled={saving}>Clear selection</Button>
          <Button variant="ghost" size="sm" onClick={() => { reset(true); setOpen(false); }} disabled={saving}><X className="size-4" />Reject draft</Button>
          <span className="text-xs text-ink-subtle">{overLimit ? 'You selected more than the configuration has room for.' : 'Saving only adds the selected items to the end of your existing configuration.'}</span>
        </div></> : null}
    </div>}
  </div>;
};

const Evidence = ({ evidence, engine }: { evidence: GeoSuggestionEvidence; engine: GeoSuggestionGeneration['engine'] }) => {
  const coverage = evidence.coverage;
  if (!evidence.collectedAt && !evidence.pages.length) return null;
  return <details className="rounded-control border border-line px-3 py-2 text-xs text-ink-muted">
    <summary className="cursor-pointer text-sm font-medium text-ink">Sources: {evidence.domain ?? evidence.site?.host ?? 'production site'} · {coverage ? `${coverage.pagesCollected} of ${coverage.pagesAttempted} pages read` : 'coverage unknown'} · {dateTime(evidence.collectedAt)}</summary>
    <div className="mt-2 flex flex-col gap-2">
      <p>Public pages only, read on {dateTime(evidence.collectedAt)}{engine ? ` · drafted by ${engine.provider} · ${engine.model}` : ''}. Web content is treated as data, never as instructions.</p>
      {coverage?.limitations.length ? <ul className="list-disc pl-4">{coverage.limitations.map((text) => <li key={text}>{text}</li>)}</ul> : null}
      {evidence.limits ? <p>Limits: {Object.entries(evidence.limits).map(([name, value]) => `${name} ${value}`).join(' · ')}</p> : null}
      <ul className="flex flex-col gap-1">{evidence.pages.map((page) => <li key={`${page.ref}:${page.url}`} className="min-w-0 break-words"><span className="font-medium text-ink">{page.ref ?? '—'}</span> {page.title ?? page.url} <span className="text-ink-subtle">· {page.outcome}{page.httpStatus ? ` ${page.httpStatus}` : ''} · {page.url}</span></li>)}</ul>
    </div>
  </details>;
};

const Notes = ({ generation }: { generation: GeoSuggestionGeneration }) => {
  const result = generation.result;
  if (!result) return null;
  const empty = !result.limitations.length && !result.uncertainties.length && !result.omitted.length;
  if (empty) return null;
  return <div className="flex flex-col gap-1 rounded-control border border-line px-3 py-2 text-xs text-ink-muted">
    <p className={caption}>Limitations and uncertainty</p>
    {result.limitations.map((entry) => <p key={`${entry.source}:${entry.text}`}>{entry.source === 'platform' ? 'Limit' : 'AI note'}: {entry.text}</p>)}
    {result.uncertainties.map((text) => <p key={text}>Uncertain: {text}</p>)}
    {result.omitted.length ? <details><summary className="cursor-pointer">{result.omitted.length} omitted (unsupported, duplicate or already saved)</summary><ul className="mt-1 list-disc pl-4">{result.omitted.map((entry) => <li key={`${entry.kind}:${entry.value}`}>{entry.value} — {entry.reason}</li>)}</ul></details> : null}
  </div>;
};

interface GroupProps { kind: Kind; items: DraftItem[]; config: GeoConfig | null; room: number | undefined; overLimit: boolean; empty: boolean; onEdit: (id: string, patch: Partial<DraftItem>) => void }
const DraftGroup = ({ kind, items, config, room, overLimit, empty, onEdit }: GroupProps) => <section className="flex flex-col gap-2" aria-label={`${KIND_LABEL[kind]} draft`}>
  <div className="flex flex-wrap items-center gap-2"><h5 className={caption}>{KIND_LABEL[kind]} · draft</h5>
    {kind === 'prompts' ? <Badge tone="neutral">Editorial proposals, not measured queries</Badge> : null}
    {room !== undefined ? <span className={`text-xs ${overLimit ? 'text-danger' : 'text-ink-subtle'}`}>room for {room} more</span> : null}</div>
  {!items.length ? <p className="text-sm text-ink-subtle">{empty ? 'No competitor has verifiable support in the public pages (a name, a domain and a competitive relationship stated there), so none is proposed. Add competitors by hand if you know them.' : 'Nothing proposed.'}</p> : null}
  {items.map((entry) => {
    const saved = existsIn(kind, entry, config);
    const label = `${entry.selected ? 'Accepted' : 'Rejected'}: ${entry.text}`;
    return <div key={entry.id} className={`flex flex-col gap-2 rounded-control border border-line p-2 sm:p-3 ${entry.selected && !saved ? '' : 'opacity-70'}`}>
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <input type="checkbox" checked={entry.selected && !saved} disabled={saved} onChange={(event) => onEdit(entry.id, { selected: event.target.checked })} aria-label={label} className="size-4 accent-brand" />
        <input value={entry.text} maxLength={kind === 'prompts' ? 500 : 120} onChange={(event) => onEdit(entry.id, { text: event.target.value })} aria-label={kind === 'competitors' ? 'Competitor name' : KIND_LABEL[kind]} className={`${field} min-w-[10rem] flex-1`} />
        {kind === 'competitors' ? <input value={entry.domain} maxLength={253} onChange={(event) => onEdit(entry.id, { domain: event.target.value })} aria-label="Competitor domain" placeholder="domain.com" className={`${field} w-full sm:w-48`} /> : null}
        {saved ? <Badge tone="neutral">Already saved</Badge> : null}
        {entry.inferred ? <Badge tone="warning">Inferred</Badge> : null}
      </div>
      {entry.relationship ? <p className="text-xs text-ink-muted">Relationship: {entry.relationship}</p> : null}
      {entry.rationale ? <p className="text-xs text-ink-muted">Why: {entry.rationale}</p> : null}
      {entry.references.length ? <details className="text-xs text-ink-muted"><summary className="cursor-pointer">{entry.references.length} source{entry.references.length === 1 ? '' : 's'}</summary>
        <ul className="mt-1 flex flex-col gap-1">{entry.references.map((ref, index) => <li key={index} className="min-w-0 break-words"><span className="font-medium text-ink">{ref.page}</span> · {ref.url}<blockquote className="mt-0.5 border-l-2 border-line pl-2 italic">“{ref.quote}”</blockquote></li>)}</ul></details> : null}
    </div>;
  })}
</section>;
