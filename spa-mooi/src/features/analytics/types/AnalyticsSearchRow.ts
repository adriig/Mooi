/** `ctr` is a 0..1 ratio; `position` an impression-weighted average where lower is better. */
export interface AnalyticsSearchRow {
  key: string;
  clicks: number;
  impressions: number;
  ctr: number;
  position: number;
}
