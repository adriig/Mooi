import { useEffect, useState } from 'react';
import { ExternalLink, LoaderCircle } from 'lucide-react';
import { startOpenCodeAuthorization, pollOpenCodeAuthorization, cancelOpenCodeAuthorization } from '@/features/agents/api/opencodeApi';
import type { OpenCodeAuthorization } from '@/features/agents/types/OpenCodeAuthorization';

interface OpenCodePairingProps {
  name: string;
  /** Signs this existing account in again instead of adding one. */
  connectionId?: string;
  onConnected: () => void;
}

export const OpenCodePairing = ({ name, connectionId, onConnected }: OpenCodePairingProps) => {
  const [attempt, setAttempt] = useState<OpenCodeAuthorization | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let disposed = false;
    let current: OpenCodeAuthorization | null = null;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const fail = (cause: unknown) => {
      if (!disposed) setError(cause instanceof Error ? cause.message : 'Could not link OpenCode. Try again.');
    };
    const poll = async () => {
      if (!current || disposed) return;
      try {
        const status = await pollOpenCodeAuthorization(current.attemptId);
        if (disposed) return;
        if (status === 'connected') {
          current = null;
          onConnected();
        } else if (['failed', 'expired', 'cancelled'].includes(status)) {
          setError('The login could not be completed. Close this window and try again.');
        } else timer = setTimeout(() => void poll(), 2000);
      } catch (cause) { fail(cause); }
    };
    const startTimer = setTimeout(() => void startOpenCodeAuthorization(name.trim(), connectionId).then((value) => {
      current = value;
      if (disposed) { void cancelOpenCodeAuthorization(value.attemptId).catch(() => {}); return; }
      setAttempt(value);
      void poll();
    }).catch(fail), 0);
    return () => {
      disposed = true;
      clearTimeout(startTimer);
      clearTimeout(timer);
      if (current) void cancelOpenCodeAuthorization(current.attemptId).catch(() => {});
    };
    // One login attempt for the chosen account title.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return error ? <p role="alert" className="text-sm text-danger">{error}</p> : attempt ?
      <div className="flex flex-col gap-4">
        <p className="text-sm text-ink-muted">Open the secure sign-in page and enter this code. This window will update when your account is linked.</p>
        <code className="select-all rounded-xl bg-surface-2 p-4 text-center text-xl font-semibold tracking-widest text-ink">{attempt.userCode}</code>
        <a href={attempt.verificationUrl} target="_blank" rel="noopener noreferrer"
          className="flex items-center justify-center gap-2 rounded-xl bg-brand px-4 py-3 text-sm font-semibold text-white">
          Continue with OpenCode <ExternalLink className="size-4" />
        </a>
        <p className="text-xs text-ink-subtle">Sign in to your OpenCode account (OpenCode Go included) and confirm the code. Mooi stores the sign-in encrypted and never shows it. The code expires after a few minutes.</p>
      </div> : <p role="status" className="flex items-center gap-2 text-sm text-ink-muted"><LoaderCircle className="size-4 animate-spin" /> Preparing secure sign-in…</p>;
};
