import { shortCommit } from '@/shared/utils/shortCommit';

interface CommitLinkProps {
  /** `owner/name` of the repository on GitHub; without it the commit reads as plain text. */
  repository: string | null | undefined;
  sha: string;
}

const base = 'rounded bg-surface-2 px-1.5 py-0.5 font-mono text-[11px] text-ink-muted';

/** A merge commit as its short SHA, linking to GitHub. */
export const CommitLink = ({ repository, sha }: CommitLinkProps) => repository ? (
  <a href={`https://github.com/${repository}/commit/${sha}`} target="_blank" rel="noreferrer" title={sha}
    className={`${base} transition hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand`}>
    {shortCommit(sha)}
  </a>
) : <span title={sha} className={base}>{shortCommit(sha)}</span>;
