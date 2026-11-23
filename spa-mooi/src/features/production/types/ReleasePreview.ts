export type ReleaseComparison = 'initial' | 'identical' | 'ahead' | 'behind' | 'diverged';

/** Commit-level comparison returned for the exact release candidate shown before deployment. */
export interface ReleasePreview {
  tag: string | null;
  create: boolean;
  targetSha: string;
  baseTag: string | null;
  baseSha: string | null;
  comparison: ReleaseComparison;
  commits: string[];
  truncated: boolean;
}
