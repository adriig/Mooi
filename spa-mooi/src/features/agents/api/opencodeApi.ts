import { sessionsFetch } from '@/features/sessions/lib/sessionsFetch';
import { apiErrorMessage } from '@/shared/utils/apiErrorMessage';
import type { OpenCodeAuthorization } from '@/features/agents/types/OpenCodeAuthorization';

const path = '/agents/opencode/authorization';

/** A `connectionId` signs that existing account in again instead of adding one. */
export const startOpenCodeAuthorization = async (name: string, connectionId?: string): Promise<OpenCodeAuthorization> => {
  const query = new URLSearchParams({ name, ...(connectionId ? { connectionId } : {}) });
  const response = await sessionsFetch(`${path}?${query}`, { method: 'POST' });
  if (!response.ok) throw new Error(await apiErrorMessage(response, 'Could not start the OpenCode sign-in.'));
  return response.json();
};

/** `connected` once the account is saved; the answer never carries a credential. */
export const pollOpenCodeAuthorization = async (attemptId: string): Promise<string> => {
  const response = await sessionsFetch(`${path}/${encodeURIComponent(attemptId)}`);
  if (!response.ok) throw new Error(await apiErrorMessage(response, 'Could not verify the OpenCode sign-in.'));
  return (await response.json()).status;
};

export const cancelOpenCodeAuthorization = async (attemptId: string): Promise<void> => {
  await sessionsFetch(`${path}/${encodeURIComponent(attemptId)}`, { method: 'DELETE' });
};
