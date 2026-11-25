# Decisiones

- Ambas rutas POST terminan en `WorkService.restartCleanTask`: `/projects/{projectId}/work/items/{itemId}/tasks/{taskId}/restart-clean` y `/sessions/{sessionId}/work/agent/items/{itemId}/tasks/{taskId}/restart-clean`. El espejo resuelve el proyecto desde la sesión autenticada y mantiene la restricción de analistas.
- La transacción valida propietario/proyecto/item/tarea y exige `attention` sin `sessionId`. Repeticiones tras el éxito devuelven 409 estable. No exige interpretar mensajes de error para autorizar el descarte.
- Columnas anulables `recovery_disposition=clean_restart` y `discarded_attempt=attempts`: migración aditiva idempotente, sin modificar filas antiguas. `discarded_attempt` es una marca permanente; ninguna lectura/escritura de recuperación ni progreso atrasado puede reutilizar ese intento o anteriores. Se conserva la metadata histórica.
- Payload `recoveryDisposition: {state, restartPending, discardedAttempt}`. Estados: `normal` (sin intentos), `missing`, `recoverable`, `invalid` (metadata no válida, incluidos fallos de restauración), `discarded`. `restartPending` refleja la marca durable independientemente del estado de la evidencia actual. Una consulta por item obtiene la metadata actual de sus tareas.
- Reiniciar limpia segmentos, estancamiento, revisión, context handoff, report y fechas; fija `attemptsBase=attempts` y `planned`. Conserva resultados QA, rondas, asignaciones y datos de integración. Retry conserva su contrato.
- mic-sessions publica `work.item` tras el commit y despierta únicamente items running. La limpieza física se delega al runner después de la invalidación. El trabajo runner debe consultar `restartPending` antes de recuperación, iniciar un intento mayor que `discardedAttempt` y consumir únicamente `recovery_disposition` tras aceptar el lanzamiento; nunca borrar la marca permanente.

# Alternativas descartadas

- Borrar bundles o metadata en la petición: perdería auditoría y permitiría una carrera antes del commit.
- Reutilizar Retry o deducir el descarte del texto del report: confundiría recuperación conservadora con una decisión destructiva explícita.
- Poner attempts a cero: reutilizaría claves antiguas de recuperación; se reinicia el presupuesto con attemptsBase.

# Ficheros clave

- `mic-mooi/src/main/java/dev/mooi/mic/features/ProjectWorkFeature.java`
- `mic-mooi/src/main/resources/db/changelog/changes/064-work-clean-restart.yaml`
- `mic-sessions/src/mic_sessions/features/factory.py`
- `mic-mooi/src/test/java/dev/mooi/mic/features/WorkCleanRestartTest.java`
- `mic-mooi/src/test/java/dev/mooi/mic/features/WorkCleanRestartMigrationTest.java` (Liquibase dos veces sobre H2 en modo PostgreSQL; preservación de filas antiguas).
- `mic-sessions/tests/test_clean_restart_api.py`
