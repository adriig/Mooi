# Bloqueos de tareas de desarrollo y issues de seguimiento

Una tarea de desarrollo de la fábrica que reporta `blocked` deja un traspaso estructurado y
duradero; QA mantiene su propia semántica (`blockerCategory`/`blockerCause` en la entrega QA).

## Reporte del agente

`POST /sessions/<sessionId>/work/agent/task` con
`{"outcome":"blocked","blockerKind":"product|platform","blockerCause","summary","nextStep","files","verifications","preparation","obstacles"}`.

- `product`: trabajo del propio proyecto fuera de la tarea o que necesita una decisión del usuario.
- `platform`: una capacidad que falta en Mooi (p. ej. una operación compartida UI/chat).
- Los reportes antiguos (`blocked` con solo `summary`) siguen aceptándose: la tarea pasa a
  atención con su texto y sin clasificación.

## Persistencia (`work_tasks.blocker_handoff`)

El runner lo envía al liquidar el turno (o, tras un reinicio, reconstruido del snapshot de
recuperación) y mic-mooi lo fusiona en una revisión nueva: los campos vacíos conservan el valor
previo, la revisión anterior pasa a `history` (máx. 10) y nada se borra. `active` indica que es
el bloqueo vigente; un nuevo lanzamiento de la tarea lo marca `false` sin borrarlo y el brief de
la siguiente sesión lo incluye como "Previous blocker". Retry, Resume y Restart clean no lo
eliminan; la memoria de diseño (`docs/features/.../decisions/`) no se toca.

## Issue de seguimiento

`POST /projects/<p>/work/items/<i>/tasks/<t>/follow-up-issue` y su espejo
`POST /sessions/<s>/work/agent/items/<i>/tasks/<t>/follow-up-issue`, con `{"kind":"debt|defect","title"?}`.

- Solo el propietario del proyecto (otro proyecto/propietario: 404). Solo una tarea de desarrollo
  en atención con un bloqueo `product` activo (en otro caso 409).
- Crea un único issue de GitHub etiquetado, con el cuerpo prellenado del traspaso y el marcador
  estable `<!-- mooi-follow-up:<projectId>:<taskId> -->`. La tarea guarda `follow_up_issue`;
  repetir la llamada devuelve el mismo issue, y si una creación anterior perdió su respuesta el
  marcador lo recupera antes de crear otro.
- El issue queda NEW: no se crea item, tarea, asignación, modelo, cuenta ni dato de cuota, y no
  arranca nada. Requiere Define y aceptación.
- Publica `work.issue` (el issue nuevo) y `work.item` (la tarea con `followUpIssue`).
