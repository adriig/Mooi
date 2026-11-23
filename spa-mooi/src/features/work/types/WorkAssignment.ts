/** Who runs a task; an empty field takes the player's defaults when the task launches. */
export interface WorkAssignment {
  provider: string | null;
  connectionId: string | null;
  model: string | null;
  effort: string | null;
}
