import type { SessionDisplayStatus } from '@/features/sessions/types/SessionDisplayStatus';
import type { SessionStatus } from '@/features/sessions/types/SessionStatus';
import type { WorkTaskStatus } from '@/features/work/types/WorkTaskStatus';
import type { Tone } from '@/shared/types/Tone';

const tones: Record<SessionDisplayStatus, Tone> = {
  provisioning: 'neutral',
  ready: 'success',
  working: 'info',
  waiting: 'warning',
  compacting: 'info',
  deploying: 'accent',
  deployed: 'accent',
  blocked: 'danger',
  failed: 'danger',
  closed: 'neutral',
};

const labels: Record<SessionDisplayStatus, string> = {
  provisioning: 'Provisioning',
  ready: 'Ready',
  working: 'Working',
  waiting: 'Waiting for you',
  compacting: 'Compacting',
  deploying: 'Deploying',
  deployed: 'Deployed',
  blocked: 'Blocked',
  failed: 'Failed',
  closed: 'Closed',
};

const pulsing: Record<SessionDisplayStatus, boolean> = {
  provisioning: true,
  ready: false,
  working: true,
  waiting: true,
  compacting: true,
  deploying: true,
  deployed: false,
  blocked: false,
  failed: false,
  closed: false,
};

export const sessionStatusTone = (status: SessionDisplayStatus): Tone => tones[status];

export const sessionStatusLabel = (status: SessionDisplayStatus): string => labels[status];

export const sessionStatusPulses = (status: SessionDisplayStatus): boolean => pulsing[status];

export const recentSessionDisplayStatus = (status: SessionStatus, taskStatus?: WorkTaskStatus): SessionDisplayStatus => (
  status === 'waiting' || taskStatus === 'attention' || taskStatus === 'review' ? 'blocked' : status
);
