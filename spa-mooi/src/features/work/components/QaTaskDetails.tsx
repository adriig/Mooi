import { ArrowRight, CircleAlert, Link2 } from 'lucide-react';
import { QA_BLOCKER_LABEL, qaBlocker, qaFindings, qaSummary } from '@/features/work/lib/qaTaskState';
import type { WorkTask } from '@/features/work/types/WorkTask';
import { Badge } from '@/shared/components/Badge';
import { Button } from '@/shared/components/Button';

interface QaTaskDetailsProps {
  task: WorkTask;
  tasks: WorkTask[];
  onOpenTask: (task: WorkTask) => void;
}

const priorityTone = (priority: string): 'danger' | 'warning' | 'neutral' =>
  priority === 'critical' || priority === 'high' ? 'danger' : priority === 'medium' ? 'warning' : 'neutral';

/** The structured QA delivery and its generated fix links, shared by To-dos and Board. */
export const QaTaskDetails = ({ task, tasks, onOpenTask }: QaTaskDetailsProps) => {
  const findings = qaFindings(task);
  const blocker = qaBlocker(task);
  const summary = qaSummary(task);
  const qaOrigin = task.fixOf ? tasks.find((candidate) => candidate.id === task.fixOf) : null;
  const fixes = task.qaOf
    ? tasks.filter((candidate) => candidate.fixOf === task.id)
    : [];
  const linkedFinding = task.findingId
    ? findings.find((finding) => finding.id === task.findingId)
    : null;

  if (!task.qaOf && !task.fixOf) return null;

  return (
    <div className="flex flex-col gap-2">
      {blocker ? (
        <section aria-label="QA blocker" className="rounded-control border border-warning/35 bg-warning-soft px-3 py-2.5">
          <div className="flex flex-wrap items-center gap-2">
            <Badge tone="warning" icon={CircleAlert}>QA blocked</Badge>
            {blocker.category ? <Badge tone="neutral">{QA_BLOCKER_LABEL[blocker.category]}</Badge> : null}
          </div>
          <p className="mt-2 whitespace-pre-line break-words text-[13px] leading-relaxed text-ink">{blocker.cause}</p>
        </section>
      ) : null}

      {summary && task.qaOf && findings.length === 0 && !blocker ? (
        <section aria-label="QA result" className="rounded-control border border-line bg-surface-2 px-3 py-2.5">
          <p className="text-[11px] font-semibold tracking-wide text-ink-subtle uppercase">QA result</p>
          <p className="mt-1 whitespace-pre-line break-words text-[13px] leading-relaxed text-ink">{summary}</p>
        </section>
      ) : null}

      {findings.length > 0 ? (
        <section aria-label="QA findings" className="flex flex-col gap-2">
          <h4 className="text-xs font-semibold text-ink">QA findings ({findings.length})</h4>
          {findings.map((finding) => {
            const fix = fixes.find((candidate) => candidate.findingId === finding.id);
            return (
              <article key={finding.id} className="rounded-control border border-danger/25 bg-danger-soft/40 px-3 py-2.5">
                <div className="flex flex-wrap items-start gap-2">
                  <Badge tone={priorityTone(finding.priority)}>{finding.priority}</Badge>
                  <h5 className="min-w-0 flex-1 break-words text-[13px] font-semibold leading-snug text-ink">
                    {finding.title}
                  </h5>
                  {fix ? (
                    <Button size="sm" variant="ghost" className="min-h-8 shrink-0"
                      ariaLabel={`Open fix for finding: ${finding.title}`}
                      onClick={() => onOpenTask(fix)}>
                      Fix: {fix.title} <ArrowRight className="size-3.5" />
                    </Button>
                  ) : null}
                </div>
                <dl className="mt-2 grid gap-2 text-[12px] leading-relaxed sm:grid-cols-2">
                  <div className="min-w-0">
                    <dt className="font-semibold text-ink-muted">Acceptance criterion</dt>
                    <dd className="mt-0.5 whitespace-pre-line break-words text-ink">{finding.criterion}</dd>
                  </div>
                  <div className="min-w-0">
                    <dt className="font-semibold text-ink-muted">Reproduction</dt>
                    <dd className="mt-0.5 whitespace-pre-line break-words text-ink">{finding.reproduction}</dd>
                  </div>
                  <div className="min-w-0">
                    <dt className="font-semibold text-ink-muted">Expected</dt>
                    <dd className="mt-0.5 whitespace-pre-line break-words text-ink">{finding.expected}</dd>
                  </div>
                  <div className="min-w-0">
                    <dt className="font-semibold text-ink-muted">Observed</dt>
                    <dd className="mt-0.5 whitespace-pre-line break-words text-ink">{finding.observed}</dd>
                  </div>
                </dl>
              </article>
            );
          })}
        </section>
      ) : null}

      {qaOrigin ? (
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-ink-muted">
          <span>This fix addresses{linkedFinding ? ` “${linkedFinding.title}”` : task.findingId ? ` finding ${task.findingId}` : ' a QA finding'}.</span>
          <button type="button" onClick={() => onOpenTask(qaOrigin)}
            className="inline-flex min-h-8 items-center gap-1 font-semibold text-brand underline-offset-4 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand">
            <Link2 className="size-3.5" /> Open originating QA
          </button>
        </div>
      ) : null}
    </div>
  );
};
