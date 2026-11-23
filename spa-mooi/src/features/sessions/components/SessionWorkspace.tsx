import { lazy, Suspense, useEffect, useRef, useState, type ReactNode } from 'react';
import { Activity, FolderTree, GitCompareArrows, MessagesSquare, Monitor, PanelRightClose, PanelRightOpen } from 'lucide-react';
import { PreviewPanel } from './preview/PreviewPanel';
import { PreviewSplitHandle } from './preview/PreviewSplitHandle';
import { IconButton } from '@/shared/components/IconButton';
import { useWideLayout } from '@/shared/hooks/useWideLayout';
import { previewSplit } from '@/features/sessions/lib/previewSplit';
import { Tabs } from '@/shared/components/Tabs';
import type { TabItem } from '@/shared/types/TabItem';
import { DeployLogsDrawer } from './deploy/DeployLogsDrawer';
import { deploymentConsoleHidden } from '@/features/sessions/lib/deploymentConsoleHidden';
import { useSessionsStore } from '@/stores/sessionsStore';
import { cn } from '@/shared/utils/cn';

const ChangesPanel = lazy(() => import('./changes/ChangesPanel').then((module) => ({ default: module.ChangesPanel })));
const FilesPanel = lazy(() => import('./files/FilesPanel').then((module) => ({ default: module.FilesPanel })));
const TracePanel = lazy(() => import('@/features/traces/components/TracePanel').then((module) => ({ default: module.TracePanel })));

interface SessionWorkspaceProps {
  sessionId: string;
  /** False for projects that are not web applications: no preview tab, no deployment logs. */
  deployEnabled: boolean;
  children: ReactNode;
}

export const SessionWorkspace = ({ sessionId, deployEnabled, children }: SessionWorkspaceProps) => {
  const pane = useSessionsStore((state) => state.byId[sessionId]?.pane ?? 'conversation');
  const setPane = useSessionsStore((state) => state.setPane);
  const logOpen = useSessionsStore((state) => state.byId[sessionId]?.deploymentLogOpen ?? false);
  const [visitedOperation, setVisitedOperation] = useState<string | null>(null);
  const [filesVisited, setFilesVisited] = useState(false);
  const [traceVisited, setTraceVisited] = useState(false);
  const changes = useSessionsStore((state) => state.byId[sessionId]?.changes);
  const session = useSessionsStore((state) => state.sessions.find((item) => item.id === sessionId));
  const deployment = session?.deployment;
  const setup = session?.deploymentSetup ?? null;
  const consoleHidden = session ? deploymentConsoleHidden(session) : false;
  const setupStart = useRef<string | null>(null);
  const openedOperation = useSessionsStore((state) => state.byId[sessionId]?.previewOpenedOperationId);
  const operationId = deployment?.operationId;
  const running = deployEnabled && deployment?.state === 'running';
  const hasPreview = deployEnabled && Boolean(operationId);
  // Wide screens may dock the preview right of the other tabs; the choice is remembered on this device.
  const wide = useWideLayout();
  const [split, setSplit] = useState(previewSplit.read);
  const docked = split.docked && wide && hasPreview;
  const rowRef = useRef<HTMLDivElement>(null);

  useEffect(() => previewSplit.write(split), [split]);

  useEffect(() => () => useSessionsStore.getState().setDeploymentLogOpen(sessionId, false), [sessionId]);

  useEffect(() => {
    if (running && operationId && openedOperation !== operationId) {
      useSessionsStore.getState().markPreviewOpened(sessionId, operationId);
      setVisitedOperation(operationId);
      if (!docked) setPane(sessionId, 'preview');
    }
  }, [running, operationId, openedOperation, sessionId, setPane, docked]);

  // A setup brings the chat to the front; its test start shows the preview, the console stays one click away.
  useEffect(() => {
    if (setup !== 'preparing') {
      setupStart.current = null;
      return;
    }
    const store = useSessionsStore.getState();
    if (setupStart.current === null) {
      setupStart.current = '';
      setPane(sessionId, 'conversation');
      store.setDeploymentLogOpen(sessionId, false);
    }
    if (deployment?.state === 'starting' && operationId && setupStart.current !== operationId) {
      setupStart.current = operationId;
      if (!docked) setPane(sessionId, 'preview');
    }
  }, [setup, deployment?.state, operationId, sessionId, setPane, docked]);

  const selectPane = (id: string) => {
    if (id !== 'conversation' && id !== 'changes' && id !== 'files' && id !== 'preview' && id !== 'trace') return;
    setPane(sessionId, id);
    if (id === 'files') setFilesVisited(true);
    if (id === 'trace') setTraceVisited(true);
    if (id === 'preview' && running && operationId) setVisitedOperation(operationId);
  };

  const dock = (next: boolean) => {
    setSplit((current) => ({ ...current, docked: next }));
    if (next && pane === 'preview') setPane(sessionId, 'conversation');
    if (!next) selectPane('preview');
  };

  const items: TabItem[] = [
    { id: 'conversation', label: 'Conversation', icon: MessagesSquare },
    { id: 'changes', label: 'Changes', icon: GitCompareArrows, count: changes?.files.length ?? 0 },
    { id: 'files', label: 'Files', icon: FolderTree },
    ...(hasPreview && !docked ? [{ id: 'preview', label: 'Preview', icon: Monitor, dot: running }] : []),
    { id: 'trace', label: 'Trace', icon: Activity },
  ];
  const visiblePane = pane === 'preview' && (!hasPreview || docked) ? 'conversation' : pane;
  const keepFiles = filesVisited || visiblePane === 'files';
  const keepTrace = traceVisited || visiblePane === 'trace';
  const keepPreview = !docked && (visiblePane === 'preview' || (running && visitedOperation === operationId));

  return (
    <div className="flex min-h-0 flex-1 flex-col bg-canvas">
      <div ref={rowRef} className="flex min-h-0 flex-1">
        <div className="flex min-h-0 min-w-0 flex-1 flex-col">
          <div className="flex shrink-0 items-center justify-between gap-3 border-b border-line bg-surface px-2 composing:max-lg:hidden sm:px-4">
            <Tabs items={items} value={visiblePane} onChange={selectPane} ariaLabel="Session view" />
            <div className="flex shrink-0 items-center gap-1">
              {visiblePane === 'conversation' ? <span className="hidden items-center gap-1.5 rounded-full border border-line px-2 py-0.5 font-mono text-[10px] tracking-[0.08em] text-ink-subtle uppercase sm:flex"><span aria-hidden className="size-1.5 rounded-full bg-success-dot shadow-[0_0_6px_var(--success-dot)]" />Live</span> : null}
              {wide && hasPreview && !docked ? <IconButton icon={PanelRightOpen} label="Dock preview on the right" onClick={() => dock(true)} /> : null}
            </div>
          </div>
          <div className="grid min-h-0 flex-1 grid-cols-1 grid-rows-1">
            <section role="tabpanel" aria-label="Conversation" hidden={visiblePane !== 'conversation'}
              className={cn('min-h-0 min-w-0', visiblePane !== 'conversation' && 'hidden')}>{children}</section>
            {visiblePane === 'changes' ? <section role="tabpanel" id="session-changes" aria-label="Live code changes" className="min-h-0 min-w-0">
              <Suspense fallback={<p className="p-6 text-sm text-ink-muted">Loading changes…</p>}><ChangesPanel sessionId={sessionId} /></Suspense>
            </section> : null}
            {keepFiles ? <section role="tabpanel" id="session-files" aria-label="Session files" hidden={visiblePane !== 'files'}
              className={cn('min-h-0 min-w-0', visiblePane !== 'files' && 'hidden')}>
              <Suspense fallback={<p className="p-6 text-sm text-ink-muted">Loading files…</p>}><FilesPanel sessionId={sessionId} active={visiblePane === 'files'} /></Suspense>
            </section> : null}
            {keepTrace ? <section role="tabpanel" id="session-trace" aria-label="Agent trace" hidden={visiblePane !== 'trace'}
              className={cn('min-h-0 min-w-0', visiblePane !== 'trace' && 'hidden')}>
              <Suspense fallback={<p className="p-6 text-sm text-ink-muted">Loading trace…</p>}><TracePanel sessionId={sessionId} active={visiblePane === 'trace'} /></Suspense>
            </section> : null}
            {keepPreview && deployment ? <section role="tabpanel" aria-label="Application preview" hidden={visiblePane !== 'preview'}
              className={cn('min-h-0 min-w-0', visiblePane !== 'preview' && 'hidden')}>
              <PreviewPanel deployment={deployment} visible={visiblePane === 'preview'} />
            </section> : null}
          </div>
        </div>
        {docked && deployment ? <>
          <PreviewSplitHandle containerRef={rowRef} share={split.share} onChange={(share) => setSplit((current) => ({ ...current, share }))} />
          <section aria-label="Application preview" style={{ flexBasis: `${split.share * 100}%` }} className="flex min-h-0 min-w-0 shrink-0 grow-0 flex-col">
            <div className="flex min-h-11 shrink-0 items-center justify-between gap-2 border-b border-line bg-surface pr-2 pl-4.5">
              <span className="flex items-center gap-2 text-[13px] font-medium text-ink">
                <Monitor className="size-4" />Preview
                {running ? <span aria-hidden className="relative flex size-1.5">
                  <span className="absolute inset-0 animate-ping rounded-full bg-success-dot opacity-60" />
                  <span className="relative size-1.5 rounded-full bg-success-dot" />
                </span> : null}
              </span>
              <IconButton icon={PanelRightClose} label="Show preview as a tab" onClick={() => dock(false)} />
            </div>
            <div className="min-h-0 flex-1"><PreviewPanel deployment={deployment} visible /></div>
          </section>
        </> : null}
      </div>
      {deployEnabled && logOpen && !consoleHidden ? <DeployLogsDrawer sessionId={sessionId} /> : null}
    </div>
  );
};
