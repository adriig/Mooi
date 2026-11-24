import { useRef, useState } from 'react';
import { Check, ChevronsUpDown, FolderGit2, Layers } from 'lucide-react';
import { useSelectedProject } from '@/features/projects/hooks/useSelectedProject';
import { useOnClickOutside } from '@/shared/hooks/useOnClickOutside';
import { useOnEscape } from '@/shared/hooks/useOnEscape';
import { cn } from '@/shared/utils/cn';
import { useProjectsStore } from '@/stores/projectsStore';

const ALL_PROJECTS = 'All projects';

interface OptionProps {
  label: string;
  selected: boolean;
  icon: typeof Layers;
  onChoose: () => void;
}

const Option = ({ label, selected, icon: Icon, onChoose }: OptionProps) => (
  <button
    type="button"
    role="option"
    aria-selected={selected}
    onClick={onChoose}
    className="flex h-10 w-full items-center gap-2.5 rounded-chip px-2.5 text-left text-[13px] font-medium text-ink transition-colors duration-150 hover:bg-surface-2 focus-visible:bg-surface-2 focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-brand"
  >
    <Icon className="size-4 shrink-0 text-ink-subtle" />
    <span className="min-w-0 flex-1 truncate">{label}</span>
    {selected ? <Check aria-hidden className="size-4 shrink-0 text-brand" /> : null}
  </button>
);

/**
 * The shell's one project switcher: pages read the choice through `useSelectedProject`, so no
 * section needs a selector of its own.
 */
export const ProjectSelector = ({ className }: { className?: string }) => {
  const projects = useProjectsStore((state) => state.projects);
  const { project, select } = useSelectedProject();
  const [open, setOpen] = useState(false);
  const container = useRef<HTMLDivElement>(null);
  const close = () => setOpen(false);

  useOnClickOutside(container, close, open);
  useOnEscape(() => {
    close();
    container.current?.querySelector('button')?.focus();
  }, open);

  const choose = (projectId: string | null) => {
    select(projectId);
    close();
  };
  const label = project?.name ?? ALL_PROJECTS;

  return (
    <div ref={container} className={cn('relative min-w-0', className)}>
      <button
        type="button"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={`Project: ${label}`}
        onClick={() => setOpen((value) => !value)}
        className={cn(
          'flex h-9 max-w-full items-center gap-1.5 rounded-control px-2 text-[13px] font-medium transition-colors duration-150 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand',
          open ? 'bg-surface-2 text-ink' : 'text-ink-muted hover:bg-surface-2 hover:text-ink',
        )}
      >
        <span className="truncate">{label}</span>
        <ChevronsUpDown aria-hidden className="size-3.5 shrink-0 text-ink-subtle" />
      </button>

      {open ? (
        <div
          role="listbox"
          aria-label="Project"
          className="absolute top-full left-0 z-50 mt-2 max-h-[min(20rem,calc(100dvh-5rem))] w-[min(16rem,calc(100vw-2rem))] origin-top-left overflow-y-auto overscroll-contain animate-menu-in rounded-xl border border-line bg-surface p-1 shadow-pop"
        >
          <Option label={ALL_PROJECTS} icon={Layers} selected={!project} onChoose={() => choose(null)} />
          {projects.map((candidate) => (
            <Option
              key={candidate.id}
              label={candidate.name}
              icon={FolderGit2}
              selected={candidate.id === project?.id}
              onChoose={() => choose(candidate.id)}
            />
          ))}
        </div>
      ) : null}
    </div>
  );
};
