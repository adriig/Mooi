import { afterEach, beforeAll, describe, expect, test, mock } from 'bun:test';
import { Window } from 'happy-dom';

const browser = new Window({ url: 'http://localhost' });
Object.assign(globalThis, {
  window: browser,
  document: browser.document,
  navigator: browser.navigator,
  HTMLElement: browser.HTMLElement,
  HTMLDialogElement: browser.HTMLDialogElement,
  IS_REACT_ACT_ENVIRONMENT: true,
});

const React = await import('react');
const { act, cleanup, fireEvent, render, screen } = await import('@testing-library/react');
const { canRestartTaskClean, cleanRestartWarning } = await import('../src/features/work/lib/cleanRestart');
mock.module('@/features/work/lib/cleanRestart', () => ({ canRestartTaskClean, cleanRestartWarning }));
mock.module('@/shared/components/Button', () => ({
  Button: ({ children, onClick, disabled }: { children: unknown; onClick: () => void; disabled: boolean }) =>
    React.createElement('button', { type: 'button', onClick, disabled }, children),
}));
mock.module('@/shared/components/Modal', () => ({
  Modal: ({ open, children, footer }: { open: boolean; children: unknown; footer: unknown }) => open
    ? React.createElement('section', { role: 'dialog' }, children, footer) : null,
}));
const { RestartCleanControl } = await import('../src/features/work/components/RestartCleanControl');
const eligibleTask = {
  id: 'task-1', status: 'attention', sessionId: null, recoveryDisposition: { state: 'missing' },
} as never;

afterEach(() => cleanup());

describe('RestartCleanControl', () => {
  test('asks for confirmation, cancellation sends no request, and confirmation invokes the clean action', async () => {
    const onRestart = mock(async () => true);
    render(React.createElement(RestartCleanControl, { task: eligibleTask, busy: false, onRestart }));

    await act(async () => { fireEvent.click(screen.getAllByRole('button', { name: 'Restart clean' })[0]!); });
    expect(screen.getByText('El estado guardado de esta tarea no puede recuperarse. Reiniciarla desde cero descartará cualquier cambio no integrado de la sesión anterior. Esta acción no puede recuperar ese trabajo.')).toBeTruthy();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Cancel' })); });
    expect(onRestart).not.toHaveBeenCalled();

    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Restart clean' })); });
    await act(async () => { fireEvent.click(screen.getAllByRole('button', { name: 'Restart clean' })[1]!); });
    expect(onRestart).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('status').textContent).toContain('Task restarted');
  });

  test('does not render for recoverable evidence', () => {
    render(React.createElement(RestartCleanControl, {
      task: { ...eligibleTask, recoveryDisposition: { state: 'recoverable' } }, busy: false, onRestart: mock(async () => true),
    }));
    expect(screen.queryByRole('button', { name: 'Restart clean' })).toBeNull();
  });
});
