import { useState } from 'react';
import type { WorkTask } from '@/features/work/types/WorkTask';
import { canRestartTaskClean, cleanRestartWarning } from '@/features/work/lib/cleanRestart';
import { Button } from '@/shared/components/Button';
import { Modal } from '@/shared/components/Modal';

interface RestartCleanControlProps {
  task: WorkTask;
  busy: boolean;
  onRestart: () => Promise<boolean>;
}

/** Confirmed clean restart for tasks whose structured recovery evidence is unusable. */
export const RestartCleanControl = ({ task, busy, onRestart }: RestartCleanControlProps) => {
  const [confirm, setConfirm] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  if (!canRestartTaskClean(task)) return null;
  const spanish = typeof navigator !== 'undefined' && navigator.language.toLowerCase().startsWith('es');
  const restart = async () => {
    const succeeded = await onRestart();
    setConfirm(false);
    setMessage(succeeded ? (spanish ? 'Tarea reiniciada desde cero.' : 'Task restarted from a clean workspace.') : null);
  };

  return (
    <>
      <Button size="sm" variant="danger" disabled={busy} onClick={() => { setMessage(null); setConfirm(true); }}>
        {spanish ? 'Reiniciar desde cero' : 'Restart clean'}
      </Button>
      {message ? <span role="status" className="text-xs text-success">{message}</span> : null}
      <Modal open={confirm} title={spanish ? 'Reiniciar tarea desde cero' : 'Restart task clean'}
        onClose={() => { if (!busy) setConfirm(false); }}
        footer={<>
          <Button variant="secondary" disabled={busy} onClick={() => setConfirm(false)}>{spanish ? 'Cancelar' : 'Cancel'}</Button>
          <Button variant="danger" disabled={busy} onClick={() => { void restart(); }}>
            {spanish ? 'Reiniciar desde cero' : 'Restart clean'}
          </Button>
        </>}>
        <p className="text-sm leading-relaxed text-ink">{cleanRestartWarning}</p>
      </Modal>
    </>
  );
};
