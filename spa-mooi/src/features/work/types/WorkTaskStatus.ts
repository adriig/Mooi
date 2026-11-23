/**
 * `planned`: waiting for its dependencies or the item's start; `queued`: unblocked, waiting for a free
 * session or an account with quota; `merging`: its work is being merged; `review`: waiting for the
 * player's merge; `attention`: waiting for the player; `done`: merged, or for QA, its criteria are met.
 */
export type WorkTaskStatus = 'planned' | 'queued' | 'running' | 'merging' | 'review' | 'attention' | 'done';
