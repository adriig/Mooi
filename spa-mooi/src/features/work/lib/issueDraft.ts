export interface IssueDraft {
  kind: 'defect' | 'debt';
  title: string;
}

const storageKey = (playerId: string, projectId: string) =>
  `mooi.todos.issue-draft:${playerId}:${projectId}`;

/** Issue drafts are local to this player and project; unavailable storage should not block composing. */
export const issueDraft = {
  read: (playerId: string | undefined, projectId: string): IssueDraft => {
    if (!playerId) return { kind: 'defect', title: '' };
    try {
      const value: unknown = JSON.parse(window.localStorage.getItem(storageKey(playerId, projectId)) ?? 'null');
      if (typeof value !== 'object' || value === null) return { kind: 'defect', title: '' };
      const draft = value as Partial<IssueDraft>;
      return {
        kind: draft.kind === 'debt' ? 'debt' : 'defect',
        title: typeof draft.title === 'string' ? draft.title : '',
      };
    } catch {
      return { kind: 'defect', title: '' };
    }
  },
  write: (playerId: string | undefined, projectId: string, draft: IssueDraft): void => {
    if (!playerId) return;
    try {
      const key = storageKey(playerId, projectId);
      if (draft.title || draft.kind !== 'defect') window.localStorage.setItem(key, JSON.stringify(draft));
      else window.localStorage.removeItem(key);
    } catch {
      // Draft persistence is best effort when browser storage is unavailable.
    }
  },
};
