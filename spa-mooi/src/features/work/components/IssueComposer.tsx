import { useState, type FormEvent } from 'react';
import { Plus } from 'lucide-react';
import { WORK_KINDS } from '@/features/work/lib/workKinds';
import { Button } from '@/shared/components/Button';
import { cn } from '@/shared/utils/cn';

interface IssueComposerProps {
  disabled: boolean;
  onCreate: (draft: { kind: 'defect' | 'debt'; title: string; body: string }) => Promise<unknown>;
}

/**
 * Opens a defect or debt issue on GitHub straight from To-dos. Features are not created here: they
 * are designed by a session agent, which breaks them into tasks.
 */
export const IssueComposer = ({ disabled, onCreate }: IssueComposerProps) => {
  const [kind, setKind] = useState<'defect' | 'debt'>('defect');
  const [title, setTitle] = useState('');
  const [sending, setSending] = useState(false);
  const text = title.trim().replace(/\s+/g, ' ');

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!text || disabled || sending) return;
    setSending(true);
    const created = await onCreate({ kind, title: text, body: '' });
    setSending(false);
    if (created) setTitle('');
  };

  return (
    <form onSubmit={(event) => { void submit(event); }} className="flex flex-col gap-2 sm:flex-row sm:items-center">
      <div role="group" aria-label="Kind" className="inline-flex shrink-0 gap-0.5 rounded-control border border-line bg-surface-2 p-0.5">
        {(['defect', 'debt'] as const).map((id) => {
          const { label, icon: Icon } = WORK_KINDS[id];
          return (
            <button key={id} type="button" aria-pressed={kind === id} onClick={() => setKind(id)}
              className={cn('inline-flex min-h-9 flex-1 items-center justify-center gap-1.5 rounded-[7px] px-3 text-[13px] font-medium transition',
                kind === id ? 'bg-surface text-ink shadow-raised ring-1 ring-line' : 'text-ink-muted hover:text-ink')}>
              <Icon className="size-3.5" /> {label}
            </button>
          );
        })}
      </div>
      <label className="flex min-h-11 min-w-0 flex-1 items-center gap-2.5 rounded-control border border-line bg-surface px-3 shadow-card transition focus-within:border-brand/60 focus-within:ring-4 focus-within:ring-brand/12">
        <Plus className="size-4 shrink-0 text-ink-subtle" />
        <input value={title} onChange={(event) => setTitle(event.target.value)} disabled={disabled || sending} maxLength={300}
          placeholder={kind === 'defect' ? 'Describe what does not work…' : 'Describe the debt to pay back…'}
          aria-label={kind === 'defect' ? 'New defect' : 'New debt'}
          className="h-10 w-full min-w-0 bg-transparent text-sm font-medium text-ink placeholder:text-ink-subtle focus:outline-none" />
      </label>
      <Button type="submit" disabled={disabled || sending || !text} className="h-11">Open issue</Button>
    </form>
  );
};
