import type { WorkAssignment } from '@/features/work/types/WorkAssignment';
import type { WorkTaskCategory } from '@/features/work/types/WorkTaskCategory';
import type { WorkTaskStatus } from '@/features/work/types/WorkTaskStatus';

export type QaFindingPriority = 'critical' | 'high' | 'medium' | 'low';
export type QaBlockerCategory = 'access' | 'credentials' | 'infrastructure' | 'capacity';

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
  attempts: number;
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
  dependsOn: string[];
  startedAt: string | null;
  finishedAt: string | null;
}
