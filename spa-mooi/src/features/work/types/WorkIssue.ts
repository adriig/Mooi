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
  /** GitHub's closing reason; null while open or reopened, and absent on legacy payloads. */
  stateReason?: 'completed' | 'not_planned' | 'duplicate' | null;
  /** Explicit factory definition state; absent legacy open issues are treated as new. */
  definitionState?: 'NEW' | 'DEFINED';
  /** A definition proposal has been saved, whether or not it has been accepted. */
  hasProposal?: boolean;
}
