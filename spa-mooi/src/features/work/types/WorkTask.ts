import type { WorkAssignment } from '@/features/work/types/WorkAssignment';
import type { WorkTaskCategory } from '@/features/work/types/WorkTaskCategory';
import type { WorkTaskStatus } from '@/features/work/types/WorkTaskStatus';

export type QaFindingPriority = 'critical' | 'high' | 'medium' | 'low';
export type QaBlockerCategory = 'access' | 'credentials' | 'infrastructure' | 'capacity';

export interface RecoveryDisposition {
  state: 'normal' | 'missing' | 'recoverable' | 'invalid' | 'incomplete' | 'unrestorable' | 'discarded' | string;
  restartPending?: boolean;
  discardedAttempt?: number | null;
}

/** Structured, bounded evidence from one independent QA delivery. */
export interface QaFinding {
  id: string;
  title: string;
  priority: QaFindingPriority;
  criterion: string;
  reproduction: string;
  expected: string;
  observed: string;
  evidence?: string;
}

/** Additive QA report. Older tasks may only have `report` populated. */
export interface QaTaskReport {
  outcome?: 'passed' | 'defect' | 'blocked';
  summary?: string;
  title?: string | null;
  deliveryId?: string | null;
  findings?: QaFinding[];
  blockerCategory?: QaBlockerCategory | null;
  blockerCause?: string | null;
}

/** Compact development handoff for its independent QA task; all evidence remains agent-reported. */
export interface QaTaskHandoff {
  summary: string;
  files: string[];
  verifications: string[];
  preparation: string;
}

/** `product`: project work outside the task or needing a decision; `platform`: a capability Mooi lacks. */
export type DevBlockerKind = 'product' | 'platform';

/** One revision of a development task's blocker; earlier revisions stay in `history`. */
export interface DevBlockerRevision {
  revision: number;
  kind: DevBlockerKind | null;
  cause: string;
  summary: string;
  nextStep: string;
  preparation: string;
  files: string[];
  verifications: string[];
  obstacles: string[];
  /** False once the task was launched again: kept for the record, no longer what holds it. */
  active: boolean;
  attempt?: number;
  segment?: number;
  reportedAt?: string;
}

export interface DevBlockerHandoff extends DevBlockerRevision {
  history?: DevBlockerRevision[];
}

/** The NEW issue opened once from this task's product blocker. */
export interface FollowUpIssue {
  number: number;
  kind: 'defect' | 'debt';
  title: string;
  htmlUrl: string | null;
  blockerRevision?: number;
  createdAt?: string;
}

/** One task of a work item, run in its own session; `dependsOn` names tasks of the same item. */
export interface WorkTask {
  id: string;
  key: string;
  title: string;
  category: WorkTaskCategory;
  description: string;
  acceptance: string;
  assignment: WorkAssignment;
  /** Tried in order when the assigned account runs low on quota. */
  alternatives: WorkAssignment[];
  autoMerge: boolean;
  tests: boolean;
  /** The task this QA task checks. */
  qaOf: string | null;
  /** The QA task whose defect this task fixes. */
  fixOf: string | null;
  status: WorkTaskStatus;
  sessionId: string | null;
  /** Authoritative session registry check supplied by mic-sessions for session links. */
  sessionAvailable?: boolean;
  /** Monotonic across Retry and Start: recovery snapshots are keyed by it. */
  attempts: number;
  /** `attempts` at the latest Retry or Start; the session limit counts attempts beyond it. */
  attemptsBase: number;
  /** Context continuations completed without consuming a session attempt. */
  segments?: number;
  stalledSegments?: number;
  lastSnapshotRevision?: string | null;
  /** Structured platform recovery state. Never infer restart eligibility from report text. */
  recoveryDisposition?: RecoveryDisposition | null;
  /** `continuation`: a turn ended without a task report and the runner continues it on its own. */
  contextHandoff?: 'requested' | 'checkpoint' | 'forced' | 'handoff' | 'resume' | 'continuation' | null;
  report: string | null;
  /** Structured QA delivery (camelCase API contract). */
  qaReport?: QaTaskReport | null;
  /** Additive findings field used by newer work payloads; accepts either wire spelling. */
  qaFindings?: QaFinding[] | null;
  qa_findings?: QaFinding[] | null;
  /** Finding id that this generated fix addresses. */
  findingId?: string | null;
  /** The commit that merged this task into the default branch, when available. */
  mergeSha?: string | null;
  /** The first production release containing the merge commit; null until deployed. */
  deployedVersion?: string | null;
  deployedAt?: string | null;
  /** Task-scoped handoff; absent on legacy work items. */
  qaHandoff?: QaTaskHandoff | null;
  /** Structured development blocker; absent on legacy blocked reports, which only have `report`. */
  blockerHandoff?: DevBlockerHandoff | null;
  followUpIssue?: FollowUpIssue | null;
  dependsOn: string[];
  startedAt: string | null;
  finishedAt: string | null;
}
