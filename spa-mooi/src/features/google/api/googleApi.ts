import { authenticatedFetch } from '@/features/auth/lib/authenticatedFetch';
import type { GoogleConnection } from '@/features/google/types/GoogleConnection';
import { apiErrorMessage } from '@/shared/utils/apiErrorMessage';

const path = '/me/google/connections';

const request = async (suffix: string, options?: RequestInit): Promise<Response> => {
  const response = await authenticatedFetch(`${path}${suffix}`, options);
  if (!response.ok) {
    throw new Error(await apiErrorMessage(response, 'Could not update your Google connections. Please try again.'));
  }
  return response;
};

export const fetchGoogleConnections = async (): Promise<GoogleConnection[]> =>
  (await request('')).json() as Promise<GoogleConnection[]>;

export const startGoogleAuthorization = async (connectionId?: string): Promise<string> => {
  const response = await request('/authorize', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ connectionId }),
  });
  const result = (await response.json()) as { authorizeUrl: string };
  return result.authorizeUrl;
};

export const completeGoogleAuthorization = async (code: string, state: string): Promise<GoogleConnection> =>
  (await request('/callback', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code, state }),
  })).json() as Promise<GoogleConnection>;

export const disconnectGoogle = async (id: string): Promise<void> => {
  await request(`/${encodeURIComponent(id)}`, { method: 'DELETE' });
};
