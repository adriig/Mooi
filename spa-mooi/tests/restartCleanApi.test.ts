import { expect, mock, test } from 'bun:test';

const fetchMock = mock(async (_path: string, _options: RequestInit) => new Response(JSON.stringify({ id: 'item-1' }), { status: 200 }));
mock.module('@/features/sessions/lib/sessionsFetch', () => ({ sessionsFetch: fetchMock }));
mock.module('@/shared/utils/apiErrorMessage', () => ({ apiErrorMessage: async () => 'request failed' }));
const { restartWorkTaskClean } = await import('../src/features/work/api/workApi');

test('clean restart uses its dedicated endpoint and POST method', async () => {
  await restartWorkTaskClean('project /1', 'item-2', 'task-3');
  expect(fetchMock).toHaveBeenCalledWith('/projects/project%20%2F1/work/items/item-2/tasks/task-3/restart-clean', { method: 'POST' });
});
