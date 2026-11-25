import type { ProjectDefinition } from '@/features/work/types/ProjectDefinition';

/** First message of a project-definition session: the agent reads the contract and converses with the user. */
export const defineProjectMessage = (projectName: string, definition: ProjectDefinition | null): string => [
  `Define the project ${projectName} as a whole before any feature is designed, following docs/project-definitions.md.`,
  '',
  'Work as the definition agent:',
  '1. Read the contract in docs/project-definitions.md and the repository as it is (README, AGENTS.md, existing code and docs).',
  '2. Converse with me: problem, users, closed scope and what is out of scope, stack, components, data, integrations, global decisions (including the tests policy) and an ordered roadmap of features.',
  '3. Save each revision with PUT /sessions/{sessionId}/work/agent/definition/proposal (vision, architecture, decisions, roadmap and a complete foundations FeatureDraft for Feature 0: Fundaciones). Read the current one first with GET /sessions/{sessionId}/work/agent/definition and send its proposalId as previousProposalId.',
  '4. Do not register features, change the repository or accept the proposal: only I accept it, from Planification. Fundaciones must hold the docs task, the technical base tasks and, for web applications, the infra task that follows the Deploy brief with tests on.',
  ...(definition?.state === 'DEFINED'
    ? ['', 'The project already has an accepted definition: propose changes to it, keeping what was already created.'] : []),
].join('\n');

/** First message of an orchestrator session that designs the next pending roadmap entry as a normal feature. */
export const designNextMessage = (
  projectName: string, entry: { key: string; title: string; goal: string; dependsOn: string[] },
  previous: { title: string; featureId: string | null } | null, foundationsId: string | null,
): string => [
  `Design the next roadmap entry of ${projectName} as a normal factory feature: "${entry.title}" (key: ${entry.key}).`,
  `Goal: ${entry.goal}`,
  '',
  'Work as the orchestrator:',
  '1. Read docs/VISION.md, docs/ARCHITECTURE.md, docs/DECISIONS.md and docs/ROADMAP.md when they exist, and the real code. Respect their decisions or propose changing them explicitly.',
  '2. Read the project definition with GET /sessions/{sessionId}/work/agent/definition.',
  `3. Register the feature with POST /sessions/{sessionId}/work/agent/features (spec and tasks with category, acceptance criteria, auto-merge and tests), depending on ${previous?.featureId
    ? `the feature of the previous entry "${previous.title}" (${previous.featureId})` : `Fundaciones${foundationsId ? ` (${foundationsId})` : ''}`}.`,
  `4. Link it to the roadmap entry with PUT /sessions/{sessionId}/work/agent/definition/roadmap/${entry.key} {"featureId": "<the new feature id>"}.`,
  '5. Do not start it: I review and Start it from Planification.',
].join('\n');
