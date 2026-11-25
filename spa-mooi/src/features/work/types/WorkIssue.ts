/** A defect or debt issue as GitHub has it. */
export interface WorkIssue {
  number: number;
  kind: 'defect' | 'debt';
  title: string;
  body: string;
  state: 'open' | 'closed';
  htmlUrl: string | null;
  createdAt: string | null;
  closedAt: string | null;
  /** Explicit factory definition state; absent legacy open issues are treated as new. */
  definitionState?: 'NEW' | 'DEFINED';
}
