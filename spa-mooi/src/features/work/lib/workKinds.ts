import { Bug, Network, Wrench } from 'lucide-react';
import type { WorkItemKind } from '@/features/work/types/WorkItemKind';

/** How each kind of work item reads: a feature, a defect or debt. */
export const WORK_KINDS: Record<WorkItemKind, { label: string; plural: string; icon: typeof Bug; tone: 'brand' | 'danger' | 'accent' }> = {
  feature: { label: 'Feature', plural: 'Features', icon: Network, tone: 'brand' },
  defect: { label: 'Defect', plural: 'Defects', icon: Bug, tone: 'danger' },
  debt: { label: 'Debt', plural: 'Debt', icon: Wrench, tone: 'accent' },
};
