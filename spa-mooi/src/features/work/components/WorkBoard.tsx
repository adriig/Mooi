import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Background, Controls, MarkerType, MiniMap, ReactFlow, type Edge } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { Network } from 'lucide-react';
import type { AgentConnection } from '@/features/agents/types/AgentConnection';
import { deleteWorkTask, pauseWorkItem, startWorkItem, updateWorkTask } from '@/features/work/api/workApi';
import { TaskEditor } from '@/features/work/components/TaskEditor';
import { TaskNode, type TaskFlowNode } from '@/features/work/components/TaskNode';
import { WorkRunButton } from '@/features/work/components/WorkRunButton';
import { WorkSettingsButton } from '@/features/work/components/WorkSettingsButton';
import { useProjectWork } from '@/features/work/hooks/useProjectWork';
import { layoutTaskGraph } from '@/features/work/lib/layoutTaskGraph';
import { DEFECT_TASK_STYLE, TASK_CATEGORIES, taskStyle } from '@/features/work/lib/taskCategories';
import { ITEM_STATUS } from '@/features/work/lib/workStatus';
import type { WorkTask } from '@/features/work/types/WorkTask';
import { Badge } from '@/shared/components/Badge';
import { Card } from '@/shared/components/Card';
import { EmptyState } from '@/shared/components/EmptyState';
import { Modal } from '@/shared/components/Modal';
import { useThemeStore } from '@/stores/themeStore';

interface WorkBoardProps {
  projectId: string;
  connections: AgentConnection[];
}

const NODE_TYPES = { task: TaskNode };

/**
 * The Board: one feature's whole map, its tasks as a left-to-right dependency graph coloured by
 * category. Nodes change auto-merge, tests and who runs them in place; a click opens the task. The
 * chosen feature lives in `?feature=`, so links from To-dos land on it.
 */
export const WorkBoard = ({ projectId, connections }: WorkBoardProps) => {
  const work = useProjectWork(projectId);
  const { items, run, busy, actionError } = work;
  const theme = useThemeStore((state) => state.theme);
  const [searchParams, setSearchParams] = useSearchParams();
  const [openTaskId, setOpenTaskId] = useState<string | null>(null);
  const features = useMemo(() => (items ?? []).filter((item) => item.kind === 'feature'), [items]);
  const requested = searchParams.get('feature');
  const feature = features.find((item) => item.id === requested)
    ?? features.find((item) => item.status !== 'done') ?? features[0] ?? null;
  const tasks = useMemo(() => feature?.tasks ?? [], [feature]);
  const openTask = tasks.find((task) => task.id === openTaskId) ?? null;

  const { nodes, edges } = useMemo(() => {
    const positions = layoutTaskGraph(tasks);
    const update = (task: WorkTask, change: Parameters<typeof updateWorkTask>[3]) => {
      if (feature) void run(() => updateWorkTask(projectId, feature.id, task.id, change));
    };
    const flowNodes: TaskFlowNode[] = tasks.map((task) => ({
      id: task.id, type: 'task', position: positions.get(task.id) ?? { x: 0, y: 0 }, draggable: false,
      data: { task, tasks, connections, busy, onUpdate: update, onOpen: (target: WorkTask) => setOpenTaskId(target.id) },
    }));
    const byId = new Map(tasks.map((task) => [task.id, task]));
    const flowEdges: Edge[] = tasks.flatMap((task) => task.dependsOn.filter((id) => byId.has(id)).map((id) => {
      const source = byId.get(id)!;
      const color = taskStyle(source).stroke;
      return {
        id: `${id}->${task.id}`, source: id, target: task.id,
        animated: task.status === 'running' || task.status === 'queued',
        style: { stroke: color, strokeWidth: 1.75, strokeDasharray: task.qaOf === id ? '6 4' : undefined, opacity: source.status === 'done' ? 0.55 : 1 },
        markerEnd: { type: MarkerType.ArrowClosed, color, width: 16, height: 16 },
      };
    }));
    return { nodes: flowNodes, edges: flowEdges };
  }, [tasks, connections, busy, feature, projectId, run]);

  if (!items) {
    if (work.loadError) return <EmptyState icon={Network} title="Board unavailable" description={work.loadError} />;
    return <Card className="h-[60vh] animate-pulse-soft" />;
  }
  if (!feature) {
    return (
      <EmptyState icon={Network} title="No features yet"
        description="Describe a feature to the agent of any session: it designs the tasks, and their map shows up here." />
    );
  }

  const status = ITEM_STATUS[feature.status];
  const done = tasks.filter((task) => task.status === 'done').length;

  return (
    <section className="flex flex-col gap-3">
      <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
        <label className="flex min-w-0 flex-1 items-center gap-2">
          <span className="sr-only">Feature</span>
          <select value={feature.id} onChange={(event) => setSearchParams({ tab: 'board', feature: event.target.value }, { replace: true })}
            className="h-10 w-full min-w-0 rounded-control border border-line bg-surface px-3 text-sm font-semibold text-ink outline-none focus:border-brand sm:max-w-md">
            {features.map((item) => <option key={item.id} value={item.id}>{item.title}{item.status === 'done' ? ' (done)' : ''}</option>)}
          </select>
        </label>
        <div className="flex items-center gap-2">
          <Badge tone={status.tone}>{status.label}</Badge>
          <span className="font-mono text-[11px] text-ink-subtle tabular-nums">{done}/{tasks.length} done</span>
          <WorkRunButton item={feature} busy={busy} onStart={() => { void run(() => startWorkItem(projectId, feature.id)); }}
            onPause={() => { void run(() => pauseWorkItem(projectId, feature.id)); }} />
          <WorkSettingsButton projectId={projectId} />
        </div>
      </div>
      {actionError ? <p role="alert" className="rounded-control bg-danger-soft px-3 py-2 text-sm text-danger">{actionError}</p> : null}

      <div className="flex flex-wrap gap-x-3 gap-y-1 text-[11px] text-ink-subtle">
        {Object.entries({ ...TASK_CATEGORIES, defect: DEFECT_TASK_STYLE }).map(([id, category]) => (
          <span key={id} className="inline-flex items-center gap-1.5">
            <span className="size-2.5 rounded-full" style={{ background: category.stroke }} /> {category.label}
          </span>
        ))}
      </div>

      <div className="h-[min(70dvh,44rem)] min-h-[26rem] overflow-hidden rounded-card border border-line bg-surface-2/40">
        <ReactFlow nodes={nodes} edges={edges} nodeTypes={NODE_TYPES} fitView fitViewOptions={{ padding: 0.15 }}
          nodesConnectable={false} nodesDraggable={false} edgesFocusable={false} minZoom={0.2} maxZoom={1.5}
          colorMode={theme === 'dark' ? 'dark' : 'light'} proOptions={{ hideAttribution: true }}>
          <Background gap={18} size={1} />
          <Controls showInteractive={false} />
          <MiniMap pannable zoomable className="!hidden sm:!block"
            nodeColor={(node) => taskStyle((node.data as { task: WorkTask }).task).stroke} />
        </ReactFlow>
      </div>

      {openTask ? (
        <Modal open size="lg" title={openTask.title} description={taskStyle(openTask).label}
          onClose={() => setOpenTaskId(null)}>
          <TaskEditor projectId={projectId} task={openTask} tasks={tasks} connections={connections} busy={busy}
            onSave={(update) => run(() => updateWorkTask(projectId, feature.id, openTask.id, update))}
            onDelete={() => { void run(() => deleteWorkTask(projectId, feature.id, openTask.id)); setOpenTaskId(null); }} />
        </Modal>
      ) : null}
    </section>
  );
};
