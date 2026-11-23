import type { AgentConnection } from '@/features/agents/types/AgentConnection';
import type { WorkAssignment } from '@/features/work/types/WorkAssignment';

/**
 * The account a task runs on as the platform will resolve it: the named one, else the provider's
 * default account, else the player's default account. Null when no usable account is linked.
 */
export const assignmentAccount = (assignment: WorkAssignment, connections: AgentConnection[]): AgentConnection | null => {
  const usable = connections.filter((connection) => !connection.stale);
  const named = usable.find((connection) => connection.id === assignment.connectionId);
  if (named) return named;
  const provider = assignment.provider
    ?? (usable.find((connection) => connection.defaultAccount) ?? usable[0])?.provider;
  const accounts = usable.filter((connection) => connection.provider === provider);
  return accounts.find((connection) => connection.defaultAccount) ?? accounts[0] ?? null;
};

export const accountName = (connection: AgentConnection): string =>
  connection.name || connection.accountLabel || connection.label;
