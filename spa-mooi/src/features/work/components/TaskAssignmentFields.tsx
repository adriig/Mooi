import type { AgentConnection } from '@/features/agents/types/AgentConnection';
import { useProviderCatalog } from '@/features/work/hooks/useProviderCatalog';
import { accountName, assignmentAccount } from '@/features/work/lib/assignmentAccount';
import type { WorkAssignment } from '@/features/work/types/WorkAssignment';
import { cn } from '@/shared/utils/cn';

interface TaskAssignmentFieldsProps {
  value: WorkAssignment;
  connections: AgentConnection[];
  disabled?: boolean;
  onChange: (value: WorkAssignment) => void;
  /** Stacked in one column, for narrow popovers. */
  compact?: boolean;
}

const field = 'h-10 w-full min-w-0 rounded-control border border-line bg-surface-2 px-3 text-sm text-ink outline-none focus:border-brand disabled:cursor-not-allowed disabled:opacity-60';

/**
 * Account, model and effort a task runs with. "Default" leaves the choice to the platform when the
 * task launches (the provider's default account and the platform's default model and effort).
 */
export const TaskAssignmentFields = ({ value, connections, disabled = false, onChange, compact = false }: TaskAssignmentFieldsProps) => {
  const usable = connections.filter((connection) => !connection.stale);
  const account = assignmentAccount(value, usable);
  const { catalog, error } = useProviderCatalog(account?.provider ?? null, account?.id ?? null);
  const models = catalog?.models ?? [];
  const efforts = models.find((model) => model.id === value.model)?.efforts
    ?? models.find((model) => model.id === catalog?.defaultModel)?.efforts ?? [];

  if (usable.length === 0) {
    return <p className="text-sm text-ink-muted">Link an agent account to choose who runs this task.</p>;
  }

  return (
    <div className={cn('grid gap-3', compact ? 'grid-cols-1' : 'grid-cols-1 sm:grid-cols-3')}>
      <label className="flex min-w-0 flex-col gap-1.5">
        <span className="text-xs font-medium text-ink-muted">Account</span>
        <select aria-label="Agent account" className={field} disabled={disabled} value={value.connectionId ?? ''}
          onChange={(event) => {
            const next = usable.find((connection) => connection.id === event.target.value);
            onChange(next
              ? { provider: next.provider, connectionId: next.id, model: next.provider === value.provider ? value.model : null, effort: next.provider === value.provider ? value.effort : null }
              : { provider: value.provider, connectionId: null, model: value.model, effort: value.effort });
          }}>
          <option value="">{account ? `Default · ${accountName(account)}` : 'Default account'}</option>
          {usable.map((connection) => (
            <option key={connection.id} value={connection.id}>{accountName(connection)} · {connection.label}</option>
          ))}
        </select>
      </label>
      <label className="flex min-w-0 flex-col gap-1.5">
        <span className="text-xs font-medium text-ink-muted">Model</span>
        <select aria-label="Model" className={field} disabled={disabled || !catalog} value={value.model ?? ''}
          onChange={(event) => onChange({ ...value, provider: account?.provider ?? value.provider,
            model: event.target.value || null, effort: null })}>
          <option value="">{catalog?.defaultModel ? `Default · ${models.find((model) => model.id === catalog.defaultModel)?.label ?? catalog.defaultModel}` : 'Default model'}</option>
          {models.map((model) => <option key={model.id} value={model.id}>{model.label}</option>)}
          {value.model && catalog && !models.some((model) => model.id === value.model)
            ? <option value={value.model}>{value.model} (unavailable)</option> : null}
        </select>
      </label>
      <label className="flex min-w-0 flex-col gap-1.5">
        <span className="text-xs font-medium text-ink-muted">Effort</span>
        <select aria-label="Thinking effort" className={field} disabled={disabled || efforts.length === 0} value={value.effort ?? ''}
          onChange={(event) => onChange({ ...value, provider: account?.provider ?? value.provider, effort: event.target.value || null })}>
          <option value="">Default</option>
          {efforts.map((effort) => <option key={effort} value={effort}>{effort}</option>)}
        </select>
      </label>
      {error ? <p role="alert" className={cn('text-xs text-danger', !compact && 'sm:col-span-3')}>{error}</p> : null}
    </div>
  );
};
