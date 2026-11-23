export type ReleaseComparison = 'initial' | 'identical' | 'ahead' | 'behind' | 'diverged';

export interface ReleaseCommit {
  sha: string;
  title: string;
}

/** Commit-level comparison returned for the exact release candidate shown before deployment. */
export interface ReleasePreview {
  tag: string | null;
  create: boolean;
  targetSha: string;
  baseTag: string | null;
  baseSha: string | null;
  comparison: ReleaseComparison;
  commits: ReleaseCommit[];
  truncated: boolean;
}
