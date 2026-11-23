const openCodeModelNamespace = /^(?:opencode-go|opencode)\//i;

/** Keep OpenCode's canonical model ID for selection while hiding its provider namespace in labels. */
export const sessionModelLabel = (id: string, label: string): string => {
  if (!openCodeModelNamespace.test(id)) return label;

  const displayLabel = label
    .replace(/^(?:opencode-go|opencode)\s*\/\s*/i, '')
    .replace(/^OpenCode(?:\s+Go)?(?=$|[\s/·:—-])(?:\s*[/·:—-]\s*|\s+)?/i, '')
    .trim();

  return displayLabel || id.replace(openCodeModelNamespace, '');
};
