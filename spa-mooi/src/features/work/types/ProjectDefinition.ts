import type { WorkTaskCategory } from '@/features/work/types/WorkTaskCategory';

export interface DefinitionDecision {
  decision: string;
  reason: string;
  affects: string;
}

export interface DefinitionRoadmapDraft {
  key: string;
  title: string;
  goal: string;
  dependsOn: string[];
}

export interface DefinitionFoundationTask {
  key: string;
  title: string;
  category: WorkTaskCategory;
  description: string;
  acceptance: string;
  autoMerge?: boolean;
  tests?: boolean;
  dependsOn: string[];
}

/** One immutable, revisioned proposal saved by the definition agent. */
export interface ProjectDefinitionProposal {
  proposalId: string;
  previousProposalId: string | null;
  vision: string;
  architecture: string;
  decisions: DefinitionDecision[];
  roadmap: DefinitionRoadmapDraft[];
  foundations: { title: string; spec: string; dependsOn: string | null; tasks: DefinitionFoundationTask[] };
  createdAt?: string | null;
}

/** A roadmap entry as the platform keeps it once a proposal is accepted. */
export interface DefinitionRoadmapEntry extends DefinitionRoadmapDraft {
  featureId: string | null;
  status: 'pending' | 'designed' | 'deployed';
}

/** The project's definition: NEW until the user accepts a proposal; reopening returns it to NEW. */
export interface ProjectDefinition {
  state: 'NEW' | 'DEFINED';
  proposal: ProjectDefinitionProposal | null;
  accepted: ProjectDefinitionProposal | null;
  /** The Fundaciones feature created by the first acceptance. */
  featureId: string | null;
  roadmap: DefinitionRoadmapEntry[];
}
