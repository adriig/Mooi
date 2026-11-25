import type { WorkTask } from '@/features/work/types/WorkTask';

const unusableRecoveryStates = new Set(['missing', 'invalid', 'incomplete', 'unrestorable']);

/** Eligibility comes only from the task's structured recovery disposition. */
export const canRestartTaskClean = (task: WorkTask): boolean =>
  task.status === 'attention'
  && !!task.recoveryDisposition && unusableRecoveryStates.has(task.recoveryDisposition.state);

export const cleanRestartWarning = 'El estado guardado de esta tarea no puede recuperarse. Reiniciarla desde cero descartará cualquier cambio no integrado de la sesión anterior. Esta acción no puede recuperar ese trabajo.';
