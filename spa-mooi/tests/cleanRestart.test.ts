import { describe, expect, test } from 'bun:test';
import type { WorkTask } from '../src/features/work/types/WorkTask';
import { canRestartTaskClean, cleanRestartWarning } from '../src/features/work/lib/cleanRestart';

const task = (overrides: Partial<WorkTask> = {}) => ({
  status: 'attention', sessionId: null, report: 'Recovery unavailable',
  recoveryDisposition: { state: 'missing' },
  ...overrides,
} as WorkTask);

describe('clean restart eligibility', () => {
  test.each(['missing', 'invalid', 'incomplete', 'unrestorable'])('allows structured state %s', (state: string) => {
    expect(canRestartTaskClean(task({ recoveryDisposition: { state } }))).toBe(true);
  });

  test.each([
    task({ status: 'planned' }),
    task({ sessionId: 'live-session' }),
    task({ recoveryDisposition: { state: 'recoverable' } }),
    task({ recoveryDisposition: { state: 'normal' } }),
    task({ recoveryDisposition: null }),
    task({ report: 'Recovery unavailable', recoveryDisposition: undefined }),
  ])('does not infer eligibility from report text or other task state: %o', (candidate: WorkTask) => {
    expect(canRestartTaskClean(candidate)).toBe(false);
  });
});

test('confirmation warning is the required exact text', () => {
  expect(cleanRestartWarning).toBe('El estado guardado de esta tarea no puede recuperarse. Reiniciarla desde cero descartará cualquier cambio no integrado de la sesión anterior. Esta acción no puede recuperar ese trabajo.');
});
