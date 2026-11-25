# Reinicio limpio explícito de tareas con recuperación irrecuperable

Design memory of a Mooi software factory feature, kept by the platform. Its live state (task statuses,
sessions, assignments) lives in Mooi; each task's technical decisions are in `decisions/`.

## Item

`1bd5a7f9-3e0c-48e6-9cf3-d3d98384b40a`

## Goal and spec

## Goal
Let a user explicitly discard an unusable persisted recovery and restart one factory task from a clean workspace. Retry remains the recovery-preserving operation.

## User-facing behaviour
- Show “Restart clean” / “Reiniciar desde cero” only for a task in attention, without a live session, whose structured recovery state says missing, invalid, incomplete, or unrestorable. Keep ordinary Retry for recoverable evidence and preserve its current restoration behaviour.
- Before the operation, require confirmation with this exact warning: “El estado guardado de esta tarea no puede recuperarse. Reiniciarla desde cero descartará cualquier cambio no integrado de la sesión anterior. Esta acción no puede recuperar ese trabajo.”
- Offer it in Planification cards and the work item/task detail, and through both the authenticated user API and session-agent mirror. Both routes call one shared low-level operation. Successful changes stream through work.item.
- The next session says that it starts from zero at the user's explicit decision and must not claim old work was recovered.

## Technical decisions
- Persist an explicit recovery disposition on work_tasks, e.g. recovery_disposition with values that distinguish normal recovery from an explicit clean restart and identify the discarded attempt. Add an idempotent Liquibase migration. Keep old metadata for audit; once the disposition is durable, old evidence is never eligible for restore. Physical bundle deletion is best-effort idempotent cleanup after invalidation and cannot undo the disposition.
- The command validates owner, project, item and task, requires attention and no session, rejects done/non-attention tasks, then clears sessionId, context handoff, last snapshot revision, stalled segment counters and incompatible transient result fields; reset the attempt budget with attemptsBase and set planned. Repeating the same decision is idempotent or returns a stable conflict.
- Runner checks the durable disposition before looking up prior recovery, creates a new task workspace/branch from the integrated base, opens a new session with existing assignment/fallbacks, and only clears the restart marker after the launch is accepted. A runner restart between decision and launch must preserve the intent.
- Keep Retry and valid snapshots, context handoffs, quota resumes, durable QA results, legacy tasks and startup behavior unchanged. Do not delete old recovery on startup or automatically reset legacy attention tasks.
- Tests cover API ownership/eligibility/idempotency/eventing, runner restart/clean launch/brief, valid recovery, quota and handoff regression, cleanup failure, UI confirmation/live update, and trace/session wording. No production action or deployment is part of this feature.

## Tasks

| Key | Title | Category | Depends on | Acceptance criteria |
| --- | --- | --- | --- | --- |
| api | Persist and expose explicit clean restart operation | Development | - | 1) Migration adds the durable disposition without rewriting existing tasks or recovery metadata. <br> 2) User API verifies owner/project/item/task and only accepts attention tasks with no live session; done/non-attention and session-bound tasks receive stable conflicts. <br> 3) Repeating the command is idempotent or returns a stable, clear conflict and never re-enables old evidence. <br> 4) The session-agent mirror resolves the scoped session project and invokes the same operation as the user route. <br> 5) Success persists explicit discard intent, resets attemptsBase and incompatible transient fields, leaves status planned, publishes work.item and wakes a running item. <br> 6) Tests cover owner/tenant isolation, eligibility, retry remaining distinct, idempotency and event payload. |
| runner | Launch clean attempts without consulting discarded recovery | Development | api | 1) A clean-restart task never fetches or restores the discarded attempt's recovery, including after runner restart before launch. <br> 2) A fresh workspace and task branch are created from the integrated base, and a new session uses the saved assignment/fallbacks. <br> 3) The durable marker remains until launch acceptance; accepted launch consumes it exactly once. <br> 4) Failed physical bundle cleanup is logged and cannot make discarded evidence eligible again. <br> 5) New session prompt/Trace clearly says clean start by explicit user choice and makes no recovery claim. <br> 6) Tests prove invalid recovery stays attention absent a user command, normal Retry preserves invalid evidence, clean launch, runner restart durability, cleanup failure safety, valid recovery restoration, QA result replay, quota resume and handoff snapshot continuity. |
| ui | Add confirmed Restart clean actions in work views | UX/UI | runner, api | 1) Planification and item/task detail show the action only for attention tasks without a session and with structured unusable recovery state. <br> 2) Confirmation displays the exact required warning before any API request; cancel performs no operation. <br> 3) The action invokes the clean-restart endpoint, while Retry still invokes its existing endpoint. <br> 4) work.item updates both views live to planned and the action disappears; conflicts/errors are visible and do not imply success. <br> 5) UI tests cover eligibility, confirmation, cancellation, request routing and live update in both views. |
