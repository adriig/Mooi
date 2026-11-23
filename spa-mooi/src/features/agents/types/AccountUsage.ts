import type { UsageMetric } from '@/features/sessions/types/UsageMetric';

/**
 * An agent account's plan limits, read without a session. `windows` uses the keys each provider
 * streams as session usage (Claude `five_hour`/`seven_day…`, Codex `primary`/`secondary`, Antigravity
 * one per model group and window). `limits` is `false` for a provider billed per use (OpenCode).
 */
export interface AccountUsage {
  provider: string;
  limits: boolean;
  windows: Record<string, UsageMetric>;
}
