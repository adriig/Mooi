import { formatCount } from '@/shared/utils/formatCount';

export const formatMetricCount = (value: number): string => formatCount(Math.round(value));

export const formatPercent = (value: number): string => `${value.toFixed(value >= 10 || value === 0 ? 0 : 1)}%`;

export const formatVisitDuration = (ms: number): string => {
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  return minutes < 60 ? `${minutes}m ${String(seconds % 60).padStart(2, '0')}s` : `${Math.floor(minutes / 60)}h ${String(minutes % 60).padStart(2, '0')}m`;
};

/** Server buckets are wall-clock times in the requested zone; read them as local fields, never shifted. */
export const parseBucket = (bucket: string): Date => new Date(bucket.replace(' ', 'T').replace(/\.\d+$/, ''));

export const formatBucket = (bucket: string, hourly: boolean): string => {
  const date = parseBucket(bucket);
  if (Number.isNaN(date.getTime())) return bucket;
  return hourly
    ? date.toLocaleString('en', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false })
    : date.toLocaleDateString('en', { month: 'short', day: 'numeric', year: 'numeric' });
};

/** A 0..1 ratio as a percentage. */
export const formatRatio = (ratio: number): string => formatPercent(ratio * 100);

/** Average search position, one decimal. */
export const formatPosition = (position: number): string => (position > 0 ? position.toFixed(1) : '–');
