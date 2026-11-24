import type { QaBlockerCategory, QaFinding, QaFindingPriority, WorkTask } from '@/features/work/types/WorkTask';

const PRIORITY_ORDER: Record<QaFindingPriority, number> = { critical: 0, high: 1, medium: 2, low: 3 };

export const QA_BLOCKER_LABEL: Record<QaBlockerCategory, string> = {
  access: 'Access',
  credentials: 'Credentials',
  infrastructure: 'Infrastructure',
  capacity: 'Capacity',
};

const isFinding = (value: unknown): value is QaFinding => {
  if (!value || typeof value !== 'object') return false;
  const finding = value as Partial<QaFinding>;
  return typeof finding.id === 'string' && typeof finding.title === 'string'
    && typeof finding.criterion === 'string' && typeof finding.reproduction === 'string'
    && typeof finding.expected === 'string' && typeof finding.observed === 'string';
};

/** New structured fields win; legacy single-defect reports remain visible as one finding. */
export const qaFindings = (task: WorkTask): QaFinding[] => {
  const findings = task.qa_findings ?? task.qaFindings ?? task.qaReport?.findings;
  if (Array.isArray(findings) && findings.length > 0) {
    return findings.filter(isFinding).sort((a, b) => PRIORITY_ORDER[a.priority] - PRIORITY_ORDER[b.priority]);
  }

  const legacyReport = task.qaReport;
  const report = task.report?.trim();
  const legacyExhaustedQa = report?.startsWith('QA still fails after') ?? false;
  if (task.qaOf && (legacyReport?.outcome === 'defect' || (report && task.status === 'planned') || legacyExhaustedQa)) {
    const title = legacyReport?.title?.trim();
    const summary = report || legacyReport?.summary || title || 'QA reported a defect.';
    return [{
      id: 'legacy',
      title: title || 'Reported QA defect',
      priority: 'high',
      criterion: task.acceptance || 'Reported against this task’s acceptance criteria.',
      reproduction: summary,
      expected: 'The behaviour should meet the acceptance criteria.',
      observed: summary,
    }];
  }
  return [];
};

export interface QaBlocker {
  category: QaBlockerCategory | null;
  cause: string;
}

const VALID_BLOCKERS = new Set<QaBlockerCategory>(['access', 'credentials', 'infrastructure', 'capacity']);

/** Reads both the structured blocker contract and the historical `QA blocked (category): cause` text. */
export const qaBlocker = (task: WorkTask): QaBlocker | null => {
  if (!task.qaOf) return null;
  const report = task.qaReport;
  if (report?.outcome === 'blocked') {
    return {
      category: report.blockerCategory ?? null,
      cause: report.blockerCause?.trim() || report.summary?.trim() || task.report?.trim() || 'QA could not continue.',
    };
  }

  const text = task.report?.trim() ?? '';
  const legacy = text.match(/^QA blocked(?:\s+\((access|credentials|infrastructure|capacity|unspecified)\))?:\s*([\s\S]*)$/i);
  if (legacy) {
    const category = legacy[1] && VALID_BLOCKERS.has(legacy[1].toLowerCase() as QaBlockerCategory)
      ? legacy[1].toLowerCase() as QaBlockerCategory : null;
    return { category, cause: legacy[2].trim() || 'QA could not continue.' };
  }

  // Earlier blocked deliveries only had a report string, with no outcome/category fields.
  if (task.status === 'attention' && !text.startsWith('QA still fails after') && qaFindings(task).length === 0) {
    return { category: null, cause: text || 'QA is waiting for attention before validation can continue.' };
  }
  return null;
};

export const qaSummary = (task: WorkTask): string | null => {
  if (qaBlocker(task)) return null;
  return task.qaReport?.summary?.trim() || (task.qaOf && task.status === 'done' ? task.report?.trim() || null : null);
};
