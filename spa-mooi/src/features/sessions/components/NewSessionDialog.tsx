import { useEffect, useRef, useState, type ReactNode } from 'react';
import { GitBranch } from 'lucide-react';
import type { AgentConnection } from '@/features/agents/types/AgentConnection';
import type { Project } from '@/features/projects/types/Project';
import { AgentModelFields } from '@/features/sessions/components/AgentModelFields';
import { useAgentModelChoice } from '@/features/sessions/hooks/useAgentModelChoice';
import type { CreateSessionRequest } from '@/features/sessions/types/CreateSessionRequest';
import { Button } from '@/shared/components/Button';
import { Modal } from '@/shared/components/Modal';

interface NewSessionDialogProps {
  open: boolean;
  /** The project the session starts on; null until the picker has one. */
  project: Project | null;
  /** Shown first when the project is picked here (the Sessions overview) rather than given. */
  projectPicker?: ReactNode;
  connections: AgentConnection[];
  busy: boolean;
  actionError: string | null;
  onRefreshConnections: () => Promise<void>;
  onClose: () => void;
  onCreate: (request: CreateSessionRequest) => Promise<boolean>;
  initialMessage?: string;
  /** Preselects the strongest model of the chosen account (architecture or design work); still changeable. */
  preferStrongModel?: boolean;
  title?: string;
  description?: string;
  /** Prefix of the proposed branch, `feat` by default. */
  branchPrefix?: string;
}

const slugify = (value: string): string => {
  const slug = value
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/(^-+|-+$)/g, '');

  return slug.length > 0 ? slug : 'session';
};

const MAX_BRANCH_LENGTH = 120;

const randomSuffix = (): string => Math.random().toString(36).slice(2, 8);

/**
 * Suggest a fresh branch per opening; users can also reuse an existing remote branch.
 * Each session has its own clone, so matching branch names can run independently.
 */
const proposeBranchName = (projectName: string, prefix: string): string => {
  const suffix = randomSuffix();
  const slugBudget = MAX_BRANCH_LENGTH - prefix.length - 1 - suffix.length - 1;
  const slug = slugify(projectName).slice(0, Math.max(slugBudget, 1));

  return `${prefix}/${slug}-${suffix}`;
};

const WEAK_MODEL = /mini|haiku|flash|lite|spark|nano/i;
const STRONG_MODEL = /opus|fable|ultra|pro\b|max/i;
const modelScore = (id: string): number => (WEAK_MODEL.test(id) ? 0 : STRONG_MODEL.test(id) ? 3 : /sonnet/i.test(id) ? 2 : 1);

export const NewSessionDialog = ({
  open,
  project,
  projectPicker,
  connections,
  busy,
  actionError,
  onRefreshConnections,
  onClose,
  onCreate,
  initialMessage,
  preferStrongModel = false,
  title = 'New session',
  description,
  branchPrefix = 'feat',
}: NewSessionDialogProps) => {
  const [branch, setBranch] = useState('');
  const [refreshingConnections, setRefreshingConnections] = useState(false);
  const choice = useAgentModelChoice(open, connections);
  const wasOpenRef = useRef(false);
  const branchEditedRef = useRef(false);
  const projectName = project?.name ?? null;

  /**
   * Opening seeds the branch, and so does picking another project until the user edits it. Nothing
   * else does: a `connections` refresh (e.g. the account page linking a provider in another tab)
   * must not wipe what the user is typing.
   */
  useEffect(() => {
    if (open && !wasOpenRef.current) {
      branchEditedRef.current = false;
      setRefreshingConnections(true);
      void onRefreshConnections().finally(() => setRefreshingConnections(false));
    }
    if (open && !branchEditedRef.current) setBranch(projectName ? proposeBranchName(projectName, branchPrefix) : '');
    wasOpenRef.current = open;
  }, [open, projectName, branchPrefix, onRefreshConnections]);

  const catalog = choice.selectedProvider;
  const { selectModel, setEffort } = choice;
  useEffect(() => {
    if (!open || !preferStrongModel || !catalog || catalog.unavailable) return;
    const best = catalog.models.reduce<(typeof catalog.models)[number] | null>(
      (top, entry) => (!top || modelScore(entry.id) > modelScore(top.id) ? entry : top), null);
    if (!best || modelScore(best.id) <= modelScore(catalog.defaultModel)) return;
    selectModel(best.id);
    if (best.efforts.includes('high')) setEffort('high');
  // Only a new catalog re-picks: the user's later choice must stick.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, preferStrongModel, catalog]);

  const canSubmit = !refreshingConnections && Boolean(project) && choice.ready && branch.trim().length > 0;

  const handleSubmit = () => {
    if (!project || !canSubmit) {
      return;
    }

    void onCreate({
      projectId: project.id,
      provider: choice.provider,
      adapter: choice.adapter || undefined,
      connectionId: choice.connectionId,
      model: choice.model,
      effort: choice.effort || null,
      branch: branch.trim(),
      initialMessage,
    }).then((succeeded) => {
      if (succeeded) {
        onClose();
      }
    });
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={title}
      description={description ?? (project && !projectPicker ? `Start a live agent session on ${project.fullName}.`
        : 'Pick a project and start a live agent session on it.')}
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button variant="brand" onClick={handleSubmit} disabled={busy || !canSubmit}>
            {busy ? 'Creating…' : 'Create session'}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-5">
        {projectPicker ? <div className="flex flex-col gap-2">
          <span className="text-xs font-medium text-ink-muted">Project</span>
          {projectPicker}
        </div> : null}

        <AgentModelFields choice={choice} onLeave={onClose} />

        {connections.length > 0 ? <label className="flex flex-col gap-2">
          <span className="text-xs font-medium text-ink-muted">Branch</span>
          <span className="flex h-12 w-full items-center gap-2 rounded-control border border-line bg-surface-2 px-3 transition focus-within:border-brand/50">
            <GitBranch className="size-4 shrink-0 text-ink-subtle" />
            <input
              value={branch}
              onChange={(event) => {
                branchEditedRef.current = true;
                setBranch(event.target.value);
              }}
              placeholder="feat/my-change"
              autoComplete="off"
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
              aria-label="Branch name"
              maxLength={MAX_BRANCH_LENGTH}
              className="h-full w-full min-w-0 bg-transparent font-mono text-sm text-ink placeholder:text-ink-subtle focus:outline-none"
            />
          </span>
          <span className="text-xs text-ink-subtle">
            Created from {project?.defaultBranch ?? 'the default branch'}. Reusing this branch replaces its previous live session safely; the remote branch is never deleted.
          </span>
        </label> : null}
      </div>

      {actionError ? (
        <p className="mt-4 rounded-control border border-danger/30 bg-danger-soft px-4 py-2.5 text-sm text-danger">
          {actionError}
        </p>
      ) : null}
    </Modal>
  );
};
