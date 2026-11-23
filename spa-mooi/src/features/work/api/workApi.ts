import { sessionsFetch } from '@/features/sessions/lib/sessionsFetch';
import type { WorkIssue } from '@/features/work/types/WorkIssue';
import type { WorkItem } from '@/features/work/types/WorkItem';
import type { WorkSettings } from '@/features/work/types/WorkSettings';
import type { WorkTaskUpdate } from '@/features/work/types/WorkTaskUpdate';
import { apiErrorMessage } from '@/shared/utils/apiErrorMessage';

const workPath = (projectId: string, path = ''): string => `/projects/${encodeURIComponent(projectId)}/work${path}`;

const send = async <T>(path: string, method: string, fallback: string, body?: unknown): Promise<T> => {
  const response = await sessionsFetch(path, {
    method,
    ...(body === undefined ? {} : { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  });
  if (!response.ok) throw new Error(await apiErrorMessage(response, fallback));
  return (response.status === 204 ? undefined : await response.json()) as T;
};

/**
 * Through mic-sessions, which announces every change live to the player's other views and agents:
 * callers may adopt the answer at once, the live channel brings the same item a moment later.
 */
export const fetchWorkItems = async (projectId: string): Promise<WorkItem[]> =>
  (await send<{ items: WorkItem[] }>(workPath(projectId), 'GET', 'Could not load the project work.')).items;

/** Defects and debt as GitHub has them, read with the player's own grant. */
export const fetchWorkIssues = async (projectId: string): Promise<WorkIssue[]> =>
  (await send<{ issues: WorkIssue[] }>(workPath(projectId, '/issues'), 'GET', 'Could not load the defects and debt.')).issues;

export const updateWorkItem = (projectId: string, itemId: string, update: { title?: string; spec?: string; dependsOn?: string }) =>
  send<WorkItem>(workPath(projectId, `/items/${itemId}`), 'PATCH', 'Could not save this work item.', update);

export const deleteWorkItem = (projectId: string, itemId: string) =>
  send<void>(workPath(projectId, `/items/${itemId}`), 'DELETE', 'Could not remove this work item.');

export const updateWorkTask = (projectId: string, itemId: string, taskId: string, update: WorkTaskUpdate) =>
  send<WorkItem>(workPath(projectId, `/items/${itemId}/tasks/${taskId}`), 'PATCH', 'Could not save this task.', update);

export const deleteWorkTask = (projectId: string, itemId: string, taskId: string) =>
  send<WorkItem>(workPath(projectId, `/items/${itemId}/tasks/${taskId}`), 'DELETE', 'Could not remove this task.');

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

/** The item that runs an issue, created on first use so its settings can change. */
export const ensureIssueItem = (projectId: string, number: number) =>
  send<WorkItem>(workPath(projectId, `/issues/${number}/item`), 'PUT', 'Could not prepare this issue to run.');

export const fetchWorkSettings = (projectId: string) =>
  send<WorkSettings>(workPath(projectId, '/settings'), 'GET', 'Could not load the factory settings.');

export const saveWorkSettings = (projectId: string, settings: WorkSettings) =>
  send<WorkSettings>(workPath(projectId, '/settings'), 'PUT', 'Could not save the factory settings.', settings);
