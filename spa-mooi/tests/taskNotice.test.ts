import { describe, expect, test } from 'bun:test';
import type { WorkTask } from '../src/features/work/types/WorkTask';
import { taskNotice } from '../src/features/work/lib/taskNotice';
import { TASK_STATUS, taskState } from '../src/features/work/lib/workStatus';

const task = (overrides: Partial<WorkTask> = {}) => ({
  id: 'task', status: 'running', sessionId: 'session', dependsOn: [], contextHandoff: null,
  report: null, ...overrides,
} as WorkTask);

describe('automatic continuation after a turn without a task report', () => {
  test.each([
    task({ contextHandoff: 'continuation', report: 'Continuación automática: el turno terminó sin resultado (1 de 3 sin progreso)' }),
    task({ report: 'Continuación automática: el turno terminó sin resultado (0 de 3 sin progreso)' }),
    task({ contextHandoff: 'continuation', report: 'Turno sin resultado: continuará automáticamente al reanudar' }),
    task({ status: 'queued', sessionId: null, contextHandoff: 'resume', report: 'Continuación automática sin resultado, tramo 2' }),
  ])('is a continuity notice, never "Needs you": %o', (candidate: WorkTask) => {
    const notice = taskNotice(candidate);
    expect(notice?.kind).toBe('continuation');
    expect(notice?.tone).toBe('warning');
    expect(TASK_STATUS[taskState(candidate, [candidate])].label).not.toBe('Needs you');
  });

  test('reaching stallTurns is shown as no progress and needs the user', () => {
    const stalled = task({ status: 'attention', report: 'No progress: sin resultado de tarea tras 3 turnos consecutivos sin progreso' });
    expect(taskNotice(stalled)?.kind).toBe('stalled');
    expect(TASK_STATUS[taskState(stalled, [stalled])].label).toBe('Needs you');
  });
});
