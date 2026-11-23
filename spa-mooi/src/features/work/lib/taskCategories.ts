import { BookOpen, Bug, Code2, FlaskConical, Palette, Server, type LucideIcon } from 'lucide-react';
import type { WorkTask } from '@/features/work/types/WorkTask';
import type { WorkTaskCategory } from '@/features/work/types/WorkTaskCategory';

export interface TaskCategoryStyle {
  label: string;
  icon: LucideIcon;
  /** Card edge and tint on the Board. */
  node: string;
  /** A small chip naming the category. */
  chip: string;
  /** The colour of edges and the minimap. */
  stroke: string;
}

/** One colour per category, the same on every view: UX/UI green, development yellow, QA blue, infra purple, docs grey. */
export const TASK_CATEGORIES: Record<WorkTaskCategory, TaskCategoryStyle> = {
  ux: {
    label: 'UX/UI', icon: Palette, stroke: '#10b981',
    node: 'border-emerald-500/45 bg-emerald-500/[0.07]',
    chip: 'border-emerald-500/30 bg-emerald-500/12 text-emerald-700 dark:text-emerald-300',
  },
  dev: {
    label: 'Development', icon: Code2, stroke: '#f59e0b',
    node: 'border-amber-500/45 bg-amber-500/[0.07]',
    chip: 'border-amber-500/30 bg-amber-500/12 text-amber-700 dark:text-amber-300',
  },
  qa: {
    label: 'QA', icon: FlaskConical, stroke: '#0ea5e9',
    node: 'border-sky-500/45 bg-sky-500/[0.07]',
    chip: 'border-sky-500/30 bg-sky-500/12 text-sky-700 dark:text-sky-300',
  },
  infra: {
    label: 'Infra/DevOps', icon: Server, stroke: '#8b5cf6',
    node: 'border-violet-500/45 bg-violet-500/[0.07]',
    chip: 'border-violet-500/30 bg-violet-500/12 text-violet-700 dark:text-violet-300',
  },
  docs: {
    label: 'Documentation', icon: BookOpen, stroke: '#64748b',
    node: 'border-slate-500/45 bg-slate-500/[0.07]',
    chip: 'border-slate-500/30 bg-slate-500/12 text-slate-700 dark:text-slate-300',
  },
};

/** QA findings keep their execution category and have a distinct visual identity. */
export const DEFECT_TASK_STYLE: TaskCategoryStyle = {
  label: 'Defect', icon: Bug, stroke: '#ef4444',
  node: 'border-red-500/45 bg-red-500/[0.07]',
  chip: 'border-red-500/30 bg-red-500/12 text-red-700 dark:text-red-300',
};

export const taskStyle = (task: WorkTask): TaskCategoryStyle =>
  task.fixOf ? DEFECT_TASK_STYLE : TASK_CATEGORIES[task.category];

/** What a person may pick: QA tasks come from turning tests on, never from a category change. */
export const EDITABLE_CATEGORIES: WorkTaskCategory[] = ['ux', 'dev', 'infra', 'docs'];
