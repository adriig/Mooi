import { KeyRound, Link2, RotateCw, SlidersHorizontal, type LucideIcon } from 'lucide-react';
import { googleErrorState, type GoogleErrorAction } from '@/features/analytics/lib/googleErrors';
import { Button } from '@/shared/components/Button';
import { Card } from '@/shared/components/Card';

interface AnalyticsGoogleErrorStateProps {
  /** The `google_*` reason from the API, or null for an unclassified failure. */
  code: string | null;
  message?: string;
  onAction: (action: GoogleErrorAction) => void;
}

const ICONS: Record<GoogleErrorAction, LucideIcon> = { connect: Link2, relink: KeyRound, pick: SlidersHorizontal, retry: RotateCw };

/** One inline state per Google failure, each with the action that resolves it. */
export const AnalyticsGoogleErrorState = ({ code, message, onAction }: AnalyticsGoogleErrorStateProps) => {
  const state = googleErrorState(code, message);
  const Icon = ICONS[state.action];

  return (
    <Card className="flex flex-wrap items-center justify-between gap-3 p-4 sm:p-5">
      <div role="alert" className="min-w-0 flex-1 basis-64">
        <p className="text-sm font-semibold">{state.title}</p>
        <p className="mt-1 text-[13px] text-ink-muted">{state.description}</p>
      </div>
      <Button variant="secondary" size="sm" onClick={() => onAction(state.action)}><Icon className="size-4" />{state.actionLabel}</Button>
    </Card>
  );
};
