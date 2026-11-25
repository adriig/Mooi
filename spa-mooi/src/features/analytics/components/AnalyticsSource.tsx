/** Visible attribution kept beside the report it describes, including empty and error states. */
export const AnalyticsSource = ({ name }: { name: string }) => (
  <p className="text-xs text-ink-subtle">Source: {name}</p>
);
