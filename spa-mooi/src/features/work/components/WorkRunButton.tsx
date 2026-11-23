import { Pause, Play, RotateCcw, Sparkles } from 'lucide-react';
import type { WorkItem } from '@/features/work/types/WorkItem';
import { Button } from '@/shared/components/Button';
import type { ButtonSize } from '@/shared/types/ButtonSize';

interface WorkRunButtonProps {
  item: WorkItem | null;
  busy: boolean;
  size?: ButtonSize;
  onStart: () => void;
  onPause: () => void;
}

/**
 * The one control that runs work: Start on a feature, a defect or debt, never on a single task.
 * A running item pauses; one waiting for the player (a task that needs them) starts again to retry.
 */
export const WorkRunButton = ({ item, busy, size = 'sm', onStart, onPause }: WorkRunButtonProps) => {
  if (item?.status === 'done') return null;
  if (item?.status === 'running') {
    const stuck = item.tasks.some((task) => task.status === 'attention' && !task.sessionId);
    return stuck ? (
      <Button size={size} variant="secondary" disabled={busy} onClick={onStart}>
        <RotateCcw className="size-4" /> Retry
      </Button>
    ) : (
      <Button size={size} variant="secondary" disabled={busy} onClick={onPause}>
        <Pause className="size-4" /> Pause
      </Button>
    );
  }
  const started = item?.status === 'paused';
  return (
    <Button size={size} variant="brand" disabled={busy || (item !== null && item.tasks.length === 0)} onClick={onStart}>
      {started ? <Play className="size-4" /> : <Sparkles className="size-4" />}
      {started ? 'Resume' : 'Start'}
    </Button>
  );
};
