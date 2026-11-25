/**
 * How a project's factory runs. `quotaLimit`: the quota percent from which an account is spared and a
 * task's alternatives are tried; `maxAttempts`: sessions a task may lose before it waits for the player;
 * `maxQaRounds`: fix-and-check-again rounds after a failed QA (0 hands every failure to the player);
 * `maxResolutions`: agentic merge attempts on conflicts (0 leaves conflicts to the player);
 * `contextHandoffPercent`: the context share at which a task session checkpoints and continues in a
 * fresh session; `stallTurns`: consecutive segments or turns without progress before a task waits for
 * the player.
 */
export interface WorkSettings {
  quotaLimit: number;
  maxAttempts: number;
  maxQaRounds: number;
  maxResolutions: number;
  contextHandoffPercent: number;
  stallTurns: number;
}
