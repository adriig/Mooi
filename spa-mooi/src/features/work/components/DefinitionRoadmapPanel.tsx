import { Link } from 'react-router-dom';
import { CheckCircle2, CircleDashed, Map, Rocket, Sparkles } from 'lucide-react';
import type { DefinitionRoadmapEntry } from '@/features/work/types/ProjectDefinition';
import type { WorkItem } from '@/features/work/types/WorkItem';
import { Badge } from '@/shared/components/Badge';
import { Button } from '@/shared/components/Button';
import { Card } from '@/shared/components/Card';

interface DefinitionRoadmapPanelProps {
  roadmap: DefinitionRoadmapEntry[];
  items: WorkItem[];
  busy: boolean;
  onDesignNext: () => void;
}

/** A roadmap entry's state: pending, designed once linked to a feature, deployed once that feature is in production. */
export const roadmapState = (entry: DefinitionRoadmapEntry, items: WorkItem[]): 'pending' | 'designed' | 'deployed' => {
  const feature = entry.featureId ? items.find((item) => item.id === entry.featureId) : undefined;
  if (feature) return feature.deployment?.state === 'deployed' ? 'deployed' : 'designed';
  return entry.status;
};

/** The accepted roadmap in order, with each entry's state and the way to design the next pending one. */
export const DefinitionRoadmapPanel = ({ roadmap, items, busy, onDesignNext }: DefinitionRoadmapPanelProps) => {
  const states = roadmap.map((entry) => roadmapState(entry, items));
  const hasPending = states.includes('pending');
  return (
    <Card className="flex flex-col gap-3 p-4">
      <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
        <h2 className="flex items-center gap-2 text-sm font-semibold text-ink"><Map className="size-4 text-ink-muted" /> Roadmap</h2>
        <Button variant="brand" size="sm" disabled={busy || !hasPending} onClick={onDesignNext}>
          <Sparkles className="size-4" /> Design next
        </Button>
      </div>
      <ol className="flex flex-col gap-2">
        {roadmap.map((entry, index) => {
          const state = states[index]!;
          const Icon = state === 'deployed' ? Rocket : state === 'designed' ? CheckCircle2 : CircleDashed;
          return (
            <li key={entry.key} className="flex min-w-0 flex-col gap-1.5 rounded-control border border-line px-3 py-2 sm:flex-row sm:items-center sm:gap-3">
              <span className="flex min-w-0 flex-1 items-start gap-2">
                <Icon className="mt-0.5 size-4 shrink-0 text-ink-muted" />
                <span className="min-w-0">
                  <span className="block text-sm font-medium break-words text-ink">{entry.title}</span>
                  <span className="block text-[12.5px] break-words text-ink-muted">{entry.goal}</span>
                </span>
              </span>
              <span className="flex shrink-0 items-center gap-2 pl-6 sm:pl-0">
                <Badge tone={state === 'deployed' ? 'success' : state === 'designed' ? 'info' : 'neutral'}>
                  {state === 'deployed' ? 'Deployed' : state === 'designed' ? 'Designed' : 'Pending'}
                </Badge>
                {entry.featureId ? (
                  <Link to={{ search: `?work=${entry.featureId}` }} className="text-[12.5px] font-medium text-brand hover:underline">
                    Open feature
                  </Link>
                ) : null}
              </span>
            </li>
          );
        })}
      </ol>
    </Card>
  );
};
