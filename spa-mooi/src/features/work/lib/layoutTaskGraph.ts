import type { WorkTask } from '@/features/work/types/WorkTask';

export const NODE_WIDTH = 248;
export const NODE_HEIGHT = 168;
const COLUMN_GAP = 96;
const ROW_GAP = 28;

/**
 * Left to right by dependency depth: a task sits one column after the deepest task it waits for, so
 * every edge points right and tasks that can run together share a column. Within a column the
 * breakdown's own order holds, and a QA task follows its task. Cycles cannot reach here: the server
 * refuses them.
 */
export const layoutTaskGraph = (tasks: WorkTask[]): Map<string, { x: number; y: number }> => {
  const byId = new Map(tasks.map((task) => [task.id, task]));
  const depth = new Map<string, number>();
  const visit = (task: WorkTask, trail: Set<string>): number => {
    const known = depth.get(task.id);
    if (known !== undefined) return known;
    if (trail.has(task.id)) return 0;
    trail.add(task.id);
    const level = task.dependsOn.reduce((deepest, id) => {
      const dependency = byId.get(id);
      return dependency ? Math.max(deepest, visit(dependency, trail) + 1) : deepest;
    }, 0);
    trail.delete(task.id);
    depth.set(task.id, level);
    return level;
  };
  tasks.forEach((task) => visit(task, new Set()));

  const columns = new Map<number, WorkTask[]>();
  tasks.forEach((task) => {
    const level = depth.get(task.id) ?? 0;
    columns.set(level, [...(columns.get(level) ?? []), task]);
  });
  const tallest = Math.max(0, ...[...columns.values()].map((column) => column.length));
  const positions = new Map<string, { x: number; y: number }>();
  columns.forEach((column, level) => {
    // Centred against the tallest column, so short columns sit in the middle of the flow.
    const offset = ((tallest - column.length) * (NODE_HEIGHT + ROW_GAP)) / 2;
    column.forEach((task, row) => positions.set(task.id, {
      x: level * (NODE_WIDTH + COLUMN_GAP),
      y: offset + row * (NODE_HEIGHT + ROW_GAP),
    }));
  });
  return positions;
};
