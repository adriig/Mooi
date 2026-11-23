/** `running`: the platform builds it; `paused`: no new task starts, running ones finish. */
export type WorkItemStatus = 'planned' | 'running' | 'paused' | 'done';
