import { DatabaseBackup, FileSearch, GitBranch, Rocket, type LucideIcon } from 'lucide-react';
import type { SessionKind } from '@/features/sessions/types/SessionKind';

const icons: Record<SessionKind, LucideIcon> = {
  session: GitBranch,
  definition: FileSearch,
  production: Rocket,
  backup: DatabaseBackup,
};

export const sessionKindIcon = (kind: SessionKind): LucideIcon => icons[kind];
