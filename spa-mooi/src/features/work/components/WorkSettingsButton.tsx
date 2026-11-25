import { useEffect, useState } from 'react';
import { Settings2 } from 'lucide-react';
import { useWorkSettings } from '@/features/work/hooks/useWorkSettings';
import type { WorkSettings } from '@/features/work/types/WorkSettings';
import { Button } from '@/shared/components/Button';
import { Modal } from '@/shared/components/Modal';

interface WorkSettingsButtonProps {
  projectId: string;
}

const FIELDS: { key: keyof WorkSettings; label: string; hint: string; min: number; max: number; suffix?: string }[] = [
  { key: 'quotaLimit', label: 'Quota limit', min: 10, max: 100, suffix: '%',
    hint: 'From this share of an account\'s session or weekly quota, its tasks move to their alternatives.' },
  { key: 'maxAttempts', label: 'Session attempts', min: 1, max: 10,
    hint: 'Times a task may lose its session (a restart, closed by hand) before it waits for you.' },
  { key: 'maxQaRounds', label: 'QA rounds', min: 0, max: 10,
    hint: 'Fixes run automatically after a failed QA before it waits for you. 0 hands every failure to you.' },
  { key: 'maxResolutions', label: 'Conflict resolutions', min: 0, max: 5,
    hint: 'Agentic merge attempts when a task conflicts with the default branch. 0 leaves conflicts to you.' },
];

/** The project's factory limits, from Work and the Board alike; agents change the same settings. */
export const WorkSettingsButton = ({ projectId }: WorkSettingsButtonProps) => {
  const [open, setOpen] = useState(false);
  const { settings, error, saving, save } = useWorkSettings(projectId, open);
  const [draft, setDraft] = useState<WorkSettings | null>(null);

  useEffect(() => { if (open && settings) setDraft(settings); }, [open, settings]);

  const valid = draft !== null && FIELDS.every(({ key, min, max }) =>
    Number.isInteger(draft[key]) && draft[key] >= min && draft[key] <= max);
  const dirty = draft !== null && settings !== null && FIELDS.some(({ key }) => draft[key] !== settings[key]);

  return (
    <>
      <button type="button" onClick={() => setOpen(true)} aria-label="Factory settings" title="Factory settings"
        className="inline-flex size-8 shrink-0 items-center justify-center rounded-control border border-line text-ink-muted transition hover:bg-surface-2 hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand">
        <Settings2 className="size-3.5" />
      </button>
      <Modal open={open} onClose={() => setOpen(false)} title="Factory settings"
        description="Limits the platform applies while it runs this project's features, defects and improvements."
        footer={(
          <>
            <Button variant="secondary" onClick={() => setOpen(false)}>Cancel</Button>
            <Button disabled={!valid || !dirty || saving} onClick={() => {
              if (draft) void save(draft).then((saved) => { if (saved) setOpen(false); });
            }}>Save</Button>
          </>
        )}>
        {!draft ? (
          error ? <p role="alert" className="text-sm text-danger">{error}</p>
            : <div className="h-40 animate-pulse-soft rounded-card bg-surface-2" />
        ) : (
          <div className="flex flex-col gap-4">
            {FIELDS.map(({ key, label, hint, min, max, suffix }) => (
              <label key={key} className="flex flex-col gap-1.5">
                <span className="flex items-baseline justify-between gap-3">
                  <span className="text-sm font-semibold text-ink">{label}</span>
                  <span className="font-mono text-[11px] text-ink-subtle">{min}–{max}{suffix ?? ''}</span>
                </span>
                <span className="flex items-center gap-2">
                  <input type="number" inputMode="numeric" min={min} max={max} step={1} value={Number.isNaN(draft[key]) ? '' : draft[key]}
                    onChange={(event) => setDraft({ ...draft, [key]: event.target.valueAsNumber })}
                    className="h-10 w-28 rounded-control border border-line bg-surface-2 px-3 text-sm text-ink tabular-nums outline-none focus:border-brand" />
                  {suffix ? <span className="text-sm text-ink-muted">{suffix}</span> : null}
                </span>
                <span className="text-[12.5px] leading-relaxed text-ink-muted">{hint}</span>
              </label>
            ))}
            {error ? <p role="alert" className="text-sm text-danger">{error}</p> : null}
          </div>
        )}
      </Modal>
    </>
  );
};
