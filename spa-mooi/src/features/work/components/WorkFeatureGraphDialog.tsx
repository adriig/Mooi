import { Background, Controls, MarkerType, ReactFlow, type Edge, type Node } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { useMemo } from 'react';
import { layoutTaskGraph, NODE_HEIGHT, NODE_WIDTH } from '@/features/work/lib/layoutTaskGraph';
import { taskStyle } from '@/features/work/lib/taskCategories';
import type { WorkItem } from '@/features/work/types/WorkItem';
import type { WorkTask } from '@/features/work/types/WorkTask';
import { Modal } from '@/shared/components/Modal';
import { useThemeStore } from '@/stores/themeStore';

const FeatureTaskNode = ({ data }: { data: { task: WorkTask } }) => {
  const style = taskStyle(data.task);
  const Icon = style.icon;
  return (
    <div style={{ width: NODE_WIDTH, minHeight: NODE_HEIGHT }}
      className={`flex flex-col justify-center gap-2 rounded-card border-2 bg-surface p-3 shadow-card ${style.node}`}>
      <span className={`inline-flex w-fit items-center gap-1 rounded-full border px-1.5 py-0.5 text-[10px] font-semibold ${style.chip}`}>
        <Icon className="size-3" /> {style.label}
      </span>
      <span className="line-clamp-2 text-[13px] font-semibold text-ink">{data.task.title}</span>
    </div>
  );
};

const NODE_TYPES = { featureTask: FeatureTaskNode };

/** A feature's task map opened directly from Work, in the same expandable dialog used by Board. */
export const WorkFeatureGraphDialog = ({ item, onClose }: { item: WorkItem; onClose: () => void }) => {
  const theme = useThemeStore((state) => state.theme);
  const { nodes, edges } = useMemo(() => {
    const positions = layoutTaskGraph(item.tasks);
    const nodes: Node[] = item.tasks.map((task) => ({
      id: task.id, type: 'featureTask', position: positions.get(task.id) ?? { x: 0, y: 0 }, data: { task },
      draggable: false,
    }));
    const taskIds = new Set(item.tasks.map((task) => task.id));
    const edges: Edge[] = item.tasks.flatMap((task) => task.dependsOn.filter((id) => taskIds.has(id)).map((id) => ({
      id: `${id}->${task.id}`, source: id, target: task.id,
      markerEnd: { type: MarkerType.ArrowClosed },
    })));
    return { nodes, edges };
  }, [item]);

  return (
    <Modal open size="full" title={item.title} description="Task dependency board" onClose={onClose}>
      <div className="h-full min-h-0 overflow-hidden rounded-card border border-line bg-surface-2/40">
        <ReactFlow nodes={nodes} edges={edges} nodeTypes={NODE_TYPES} fitView fitViewOptions={{ padding: 0.15 }}
          nodesConnectable={false} nodesDraggable={false} edgesFocusable={false} minZoom={0.2} maxZoom={1.5}
          colorMode={theme === 'dark' ? 'dark' : 'light'} proOptions={{ hideAttribution: true }}>
          <Background gap={18} size={1} />
          <Controls showInteractive={false} />
        </ReactFlow>
      </div>
    </Modal>
  );
};
