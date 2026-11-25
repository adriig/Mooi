import type { LucideIcon } from 'lucide-react';
import { AlertCircle, Clock, RefreshCw, ShieldAlert, Split } from 'lucide-react';
import type { WorkTask } from '@/features/work/types/WorkTask';

export type TaskNoticeKind = 'quota' | 'stalled' | 'rejected' | 'continuation' | 'handoff' | 'attention';

export interface TaskNotice {
  kind: TaskNoticeKind;
  text: string;
  icon: LucideIcon;
  /** Attention tone for waits and handoffs, danger for results the user must look at. */
  tone: 'warning' | 'danger';
}

/** The runner's own wording for each state; its reports are the only payload that carries them. */
const QUOTA = /^Esperando cuota|^Waiting for quota/i;
const STALLED = /^Sin progreso|^No progress/i;
const REJECTED = /no changes relative to its base|sin cambios/i;
const CONTINUING = /^Continuación automática|^Turno sin resultado/i;

/** What a task's `report` means to the player, when it is one of the factory's continuity states. */
export const taskNotice = (task: WorkTask): TaskNotice | null => {
  const text = task.report?.trim();
  if (!text) return null;
  if (task.status === 'queued' && QUOTA.test(text)) return { kind: 'quota', text, icon: Clock, tone: 'warning' };
  if (STALLED.test(text)) return { kind: 'stalled', text, icon: AlertCircle, tone: 'danger' };
  if (task.status === 'attention' && REJECTED.test(text)) return { kind: 'rejected', text, icon: ShieldAlert, tone: 'danger' };
  // A turn ended without a task report: the runner continues it by itself, nothing waits for the player.
  if (task.status !== 'attention' && (task.contextHandoff === 'continuation' || CONTINUING.test(text))) {
    return { kind: 'continuation', text, icon: RefreshCw, tone: 'warning' };
  }
  if (task.contextHandoff) return { kind: 'handoff', text, icon: Split, tone: 'warning' };
  if (task.status === 'attention') return { kind: 'attention', text, icon: AlertCircle, tone: 'danger' };
  return null;
};

/** 1-based current segment; shown only once a context handoff happened. */
export const segmentLabel = (task: WorkTask): string | null =>
  (task.segments ?? 0) > 0 ? `Segment ${(task.segments ?? 0) + 1}` : null;
