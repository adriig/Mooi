import { useEffect, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import { useAccountUsage } from '@/features/agents/hooks/useAccountUsage';
import { planWindows, type PlanWindow } from '@/features/agents/lib/planWindows';
import { formatRelativeTime } from '@/shared/utils/formatRelativeTime';
import { cn } from '@/shared/utils/cn';

/** "in 42 min", "in 3 h 05 min", then the day and time once it is a day or more away. */
const resetsIn = (resetsAt: number, now: number) => {
  const minutes = Math.max(1, Math.round((resetsAt * 1000 - now) / 60_000));
  if (minutes < 60) return `in ${minutes} min`;
  if (minutes < 1440) return `in ${Math.floor(minutes / 60)} h ${String(minutes % 60).padStart(2, '0')} min`;
  return new Date(resetsAt * 1000).toLocaleString(undefined, { weekday: 'short', hour: '2-digit', minute: '2-digit' });
};

const tone = (percent: number) => (percent >= 95 ? 'bg-danger' : percent >= 80 ? 'bg-warning' : 'bg-brand');

const WindowMeter = ({ window: plan, now }: { window: PlanWindow; now: number }) => {
  const { percent } = plan;
  return <div className="min-w-0">
    <div className="flex items-baseline justify-between gap-3 text-xs">
      <span className="truncate font-semibold text-ink">{plan.label}</span>
      <span className={cn('shrink-0 font-semibold tabular-nums', percent != null && percent >= 95 ? 'text-danger'
        : percent != null && percent >= 80 ? 'text-warning' : 'text-ink-muted')}>
        {percent == null ? 'Unknown' : `${Math.round(100 - percent)}% left`}
      </span>
    </div>
    <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-surface-2" role="meter" aria-label={`${plan.label} used`}
      aria-valuemin={0} aria-valuemax={100} aria-valuenow={percent ?? undefined}>
      {percent != null && <div className={cn('h-full rounded-full transition-[width] duration-500', tone(percent))}
        style={{ width: `${Math.max(percent, percent > 0 ? 2 : 0)}%` }} />}
    </div>
    <p className="mt-1 text-[11px] text-ink-subtle tabular-nums">
      {percent != null && `${Math.round(percent)}% used`}
      {plan.resetsAt != null && <span title={new Date(plan.resetsAt * 1000).toLocaleString()}>
        {percent != null && ' · '}Resets {resetsIn(plan.resetsAt, now)}
      </span>}
    </p>
  </div>;
};

/** An account's plan limits at a glance: how much of each window is left and when it starts over. */
export const AgentAccountUsage = ({ connectionId }: { connectionId: string }) => {
  const { usage, status, error, refreshing, refresh } = useAccountUsage(connectionId);
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(timer);
  }, []);

  if (status === 'loading') {
    return <div className="mt-3 grid gap-3 animate-pulse-soft sm:grid-cols-2" aria-label="Loading limits">
      {[0, 1].map((index) => <div key={index} className="space-y-1.5">
        <div className="h-3 w-24 rounded bg-surface-2" /><div className="h-1.5 rounded-full bg-surface-2" />
      </div>)}
    </div>;
  }
  if (usage && !usage.limits) {
    return <p className="mt-2 text-xs text-ink-subtle">Billed per use: this account has no plan limits.</p>;
  }

  const windows = usage ? planWindows(usage.windows, now) : [];
  const updatedAt = Math.max(0, ...windows.map((plan) => plan.updatedAt));
  return <div className="mt-3">
    {windows.length > 0
      ? <div className="grid gap-x-6 gap-y-3 sm:grid-cols-2">
        {windows.map((plan) => <WindowMeter key={plan.key} window={plan} now={now} />)}
      </div>
      : <p className={cn('text-xs', status === 'error' ? 'text-danger' : 'text-ink-subtle')}>
        {status === 'error' ? error : 'The provider did not report the limits of this account right now.'}
      </p>}
    <div className="mt-2 flex items-center gap-1.5 text-[11px] text-ink-subtle">
      {updatedAt > 0 && <span>Updated {formatRelativeTime(new Date(updatedAt * 1000).toISOString(), now)}</span>}
      <button type="button" onClick={refresh} disabled={refreshing} title="Refresh limits" aria-label="Refresh limits"
        className="inline-flex size-6 items-center justify-center rounded-md text-ink-subtle transition hover:bg-surface-2 hover:text-ink focus-visible:outline-2 focus-visible:outline-brand disabled:opacity-50">
        <RefreshCw className={cn('size-3', refreshing && 'animate-spin')} />
      </button>
    </div>
  </div>;
};
