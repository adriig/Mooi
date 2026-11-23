import type { UsageMetric } from '@/features/sessions/types/UsageMetric';

export interface PlanWindow {
  key: string;
  label: string;
  /** Used percent, 0 once the window has reset; null while the provider did not say. */
  percent: number | null;
  resetsAt: number | null;
  updatedAt: number;
}

const CLAUDE_LABELS: Record<string, string> = {
  five_hour: '5-hour limit',
  seven_day: 'Weekly limit',
  seven_day_opus: 'Weekly · Opus',
  seven_day_sonnet: 'Weekly · Sonnet',
  seven_day_oauth_apps: 'Weekly · Apps',
};

/** Codex names its windows `primary`/`secondary` and gives their length as `"300 min"`. */
const minutesLabel = (key: string, window?: string | null) => {
  const minutes = Number.parseInt(window ?? '', 10);
  if (!Number.isFinite(minutes) || minutes <= 0) return key === 'primary' ? '5-hour limit' : 'Weekly limit';
  if (minutes === 10080) return 'Weekly limit';
  if (minutes % 1440 === 0) return `${minutes / 1440}-day limit`;
  if (minutes % 60 === 0) return `${minutes / 60}-hour limit`;
  return `${minutes}-minute limit`;
};

/** Antigravity keys read `Gemini_models_5h`: the model group, then its window. */
const groupLabel = (key: string) => key.replaceAll('_', ' ')
  .replace(/\s5h$/i, ' · 5-hour')
  .replace(/\sweekly$/i, ' · Weekly');

const label = (key: string, metric: UsageMetric) => CLAUDE_LABELS[key]
  ?? (key === 'primary' || key === 'secondary' ? minutesLabel(key, metric.window) : groupLabel(key));

/** Short windows first, then weekly ones, each in the provider's own order. */
const rank = (key: string, metric: UsageMetric) => {
  if (key === 'five_hour' || key === 'primary' || metric.window === '5h') return 0;
  if (key === 'seven_day' || key === 'secondary') return 1;
  return 2;
};

/** The windows of one account, labelled and sorted for display. */
export const planWindows = (windows: Record<string, UsageMetric>, now = Date.now()): PlanWindow[] =>
  Object.entries(windows)
    .map(([key, metric], index) => {
      // An elapsed window has started over empty, whatever the last reading said.
      const elapsed = metric.resetsAt != null && metric.resetsAt * 1000 <= now;
      const percent = typeof metric.percent === 'number' && Number.isFinite(metric.percent)
        ? Math.max(0, Math.min(100, metric.percent)) : null;
      return {
        index, rank: rank(key, metric),
        window: { key, label: label(key, metric), percent: elapsed ? 0 : percent,
          resetsAt: elapsed ? null : metric.resetsAt ?? null, updatedAt: metric.updatedAt },
      };
    })
    .sort((left, right) => left.rank - right.rank || left.index - right.index)
    .map(({ window }) => window);
