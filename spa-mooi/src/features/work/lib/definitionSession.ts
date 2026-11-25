const key = (projectId: string, purpose: 'define' | 'design') => `mooi.definitionSession.${purpose}.${projectId}`;

/** The session last opened to define a project (or design its next roadmap entry), remembered on this device. */
export const rememberedDefinitionSession = (projectId: string, purpose: 'define' | 'design'): string | null => {
  try { return localStorage.getItem(key(projectId, purpose)); } catch { return null; }
};

export const rememberDefinitionSession = (projectId: string, purpose: 'define' | 'design', sessionId: string): void => {
  try { localStorage.setItem(key(projectId, purpose), sessionId); } catch { /* private mode: reopening just creates a new one */ }
};
