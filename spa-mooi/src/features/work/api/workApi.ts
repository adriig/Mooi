import { sessionsFetch } from '@/features/sessions/lib/sessionsFetch';
import type { Session } from '@/features/sessions/types/Session';
import type { ProjectDefinition } from '@/features/work/types/ProjectDefinition';
import type { WorkIssue } from '@/features/work/types/WorkIssue';
import type { WorkItem } from '@/features/work/types/WorkItem';
import type { WorkSettings } from '@/features/work/types/WorkSettings';
import type { WorkTaskUpdate } from '@/features/work/types/WorkTaskUpdate';
import { apiErrorMessage } from '@/shared/utils/apiErrorMessage';

const workPath = (projectId: string, path = ''): string => `/projects/${encodeURIComponent(projectId)}/work${path}`;

/** A failed call that keeps its HTTP status, so a view can explain a conflict in its own words. */
export class WorkApiError extends Error {
  readonly status: number;
  constructor(message: string, status: number) {
    super(message);
    this.status = status;
  }
}

const send = async <T>(path: string, method: string, fallback: string, body?: unknown): Promise<T> => {
  const response = await sessionsFetch(path, {
    method,
    ...(body === undefined ? {} : { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  });
  if (!response.ok) throw new WorkApiError(await apiErrorMessage(response, fallback), response.status);
  return (response.status === 204 ? undefined : await response.json()) as T;
};

/**
 * Through mic-sessions, which announces every change live to the player's other views and agents:
 * callers may adopt the answer at once, the live channel brings the same item a moment later.
 */
export const fetchProjectWork = (projectId: string): Promise<{ items: WorkItem[]; newProject?: boolean }> =>
  send(workPath(projectId), 'GET', 'Could not load the project work.');

export const fetchWorkItems = async (projectId: string): Promise<WorkItem[]> =>
  (await fetchProjectWork(projectId)).items;

/** Defects and debt as GitHub has them, read with the player's own grant. */
export const fetchWorkIssues = (projectId: string): Promise<{ issues: WorkIssue[]; items: WorkItem[] }> =>
  send(workPath(projectId, '/issues'), 'GET', 'Could not load the defects and debt.');

export const updateWorkItem = (projectId: string, itemId: string, update: { title?: string; spec?: string; dependsOn?: string }) =>
  send<WorkItem>(workPath(projectId, `/items/${itemId}`), 'PATCH', 'Could not save this work item.', update);

export const deleteWorkItem = (projectId: string, itemId: string) =>
  send<void>(workPath(projectId, `/items/${itemId}`), 'DELETE', 'Could not remove this work item.');

export const updateWorkTask = (projectId: string, itemId: string, taskId: string, update: WorkTaskUpdate) =>
  send<WorkItem>(workPath(projectId, `/items/${itemId}/tasks/${taskId}`), 'PATCH', 'Could not save this task.', update);

export const retryWorkTask = (projectId: string, itemId: string, taskId: string) =>
  send<WorkItem>(workPath(projectId, `/items/${itemId}/tasks/${taskId}/retry`), 'POST',
    'Could not restart this task.');

export const reassignWorkTasks = (projectId: string, itemId: string) =>
  send<{ item: WorkItem; changedCount: number; eligibleCount: number }>(
    workPath(projectId, `/items/${itemId}/reassign`), 'POST', 'Could not recalculate task assignments.');

export const deleteWorkTask = (projectId: string, itemId: string, taskId: string) =>
  send<WorkItem>(workPath(projectId, `/items/${itemId}/tasks/${taskId}`), 'DELETE', 'Could not remove this task.');

/** Queues a change on an ongoing feature; the platform analyzes it at a safe point without pausing it. */
export const requestWorkChange = (projectId: string, itemId: string, request: string) =>
  send<WorkItem>(workPath(projectId, `/items/${itemId}/change-requests`), 'POST',
    'Could not queue this change request.', { request });

/** The user's explicit decision on a proposal awaiting confirmation; acceptance applies it. */
export const decideWorkChange = (projectId: string, itemId: string, changeId: string, decision: 'accept' | 'reject') =>
  send<WorkItem>(workPath(projectId, `/items/${itemId}/change-requests/${changeId}/decision`), 'POST',
    'Could not decide this change request.', { decision });

/** Hands the item to the platform, which runs every task in its own session from now on. */
export const startWorkItem = (projectId: string, itemId: string) =>
  send<WorkItem>(workPath(projectId, `/items/${itemId}/start`), 'POST', 'Could not start this work.');

export const pauseWorkItem = (projectId: string, itemId: string) =>
  send<WorkItem>(workPath(projectId, `/items/${itemId}/pause`), 'POST', 'Could not pause this work.');

export const createWorkIssue = (projectId: string, draft: { kind: 'defect' | 'debt'; title: string; body: string }) =>
  send<{ issue: WorkIssue; item: WorkItem | null }>(workPath(projectId, '/issues'), 'POST', 'Could not open the issue.', draft);

export const updateWorkIssue = (projectId: string, number: number,
  update: { title?: string; body?: string; state?: 'open' | 'closed' }) =>
  send<{ issue: WorkIssue; item: WorkItem | null }>(workPath(projectId, `/issues/${number}`), 'PATCH',
    'Could not update the issue.', update);

export interface IssueDefinitionProposal {
  proposalId: string;
  previousProposalId: string | null;
  title: string;
  description: string;
  valid: boolean;
  acceptance: string;
  kind: 'defect' | 'debt';
  category: 'ux' | 'dev' | 'infra' | 'docs';
  breakdown: { title: string; description: string; acceptance: string; kind: 'defect' | 'debt'; category: 'ux' | 'dev' | 'infra' | 'docs' }[] | null;
  feature: { title: string; spec: string; tasks?: Array<{ title: string; description: string; acceptance: string }> } | null;
}

export interface IssueDefinition {
  state: 'NEW' | 'DEFINED';
  proposal: IssueDefinitionProposal | null;
  accepted: IssueDefinitionProposal | null;
  issueNumbers: number[];
  featureId: string | null;
}

export const fetchIssueDefinition = (projectId: string, number: number) =>
  send<IssueDefinition>(workPath(projectId, `/issues/${number}/definition`), 'GET', 'Could not load the issue proposal.');

export const acceptIssueDefinition = (projectId: string, number: number, proposalId: string) =>
  send<IssueDefinitionResult>(
    workPath(projectId, `/issues/${number}/definition/accept`), 'POST', 'Could not accept the proposal.', { proposalId });

export const reopenIssueDefinition = (projectId: string, number: number, proposalId: string | null) =>
  send<IssueDefinitionResult>(
    workPath(projectId, `/issues/${number}/definition/reopen`), 'POST', 'Could not reopen the definition.', { proposalId });

/** The item that runs an issue, created on first use so its settings can change. */
export const ensureIssueItem = (projectId: string, number: number) =>
  send<WorkItem>(workPath(projectId, `/issues/${number}/item`), 'PUT', 'Could not prepare this issue to run.');

/** Opens or resumes the issue's scoped definition conversation with a server-selected assignment. */
export const defineWorkIssue = (projectId: string, number: number) =>
  send<Session>(workPath(projectId, `/issues/${number}/define`), 'POST',
    'Could not open the definition session.');

export interface IssueDefinitionResult {
  definition: IssueDefinition;
  issues: WorkIssue[];
  items: WorkItem[];
}

export const fetchWorkSettings = (projectId: string) =>
  send<WorkSettings>(workPath(projectId, '/settings'), 'GET', 'Could not load the factory settings.');

export const saveWorkSettings = (projectId: string, settings: WorkSettings) =>
  send<WorkSettings>(workPath(projectId, '/settings'), 'PUT', 'Could not save the factory settings.', settings);

/** The project's definition: current proposal, accepted one, Fundaciones feature and roadmap. */
export const fetchProjectDefinition = (projectId: string) =>
  send<ProjectDefinition>(workPath(projectId, '/definition'), 'GET', 'Could not load the project definition.');

/** Accepts exactly this revision: creates Fundaciones and saves the roadmap. Only the user can do it. */
export const acceptProjectDefinition = (projectId: string, proposalId: string) =>
  send<{ definition: ProjectDefinition; items: WorkItem[] }>(workPath(projectId, '/definition/accept'), 'POST',
    'Could not accept the proposal.', { proposalId });

/** Allows new proposals again, keeping the current one, the acceptance and what it created. */
export const reopenProjectDefinition = (projectId: string, proposalId: string | null) =>
  send<{ definition: ProjectDefinition; items: WorkItem[] }>(workPath(projectId, '/definition/reopen'), 'POST',
    'Could not reopen the definition.', { proposalId });
