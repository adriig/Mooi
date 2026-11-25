import { ChartLine, DatabaseBackup, FolderGit2, ListTodo, Rocket, ShieldCheck } from 'lucide-react';
import { ROUTES } from '@/app/routes';
import type { AppNavLink } from '@/shared/types/AppNavLink';

export const appNavLinks: AppNavLink[] = [
  { id: 'projects', label: 'Projects', to: ROUTES.projects, icon: FolderGit2 },
  { id: 'todos', label: 'Planification', to: ROUTES.todos, icon: ListTodo },
  { id: 'backups', label: 'Backups', to: ROUTES.backups, icon: DatabaseBackup },
  { id: 'deployments', label: 'Deployments', to: ROUTES.deployments, icon: Rocket },
  { id: 'analytics', label: 'Analytics', to: ROUTES.analytics, icon: ChartLine },
  { id: 'admin', label: 'Admin', to: ROUTES.admin, icon: ShieldCheck, adminOnly: true },
];
