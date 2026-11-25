# Decisiones

- El runner lee `recoveryDisposition.restartPending` antes de consultar recuperaciones: omite reconciliación del intento descartado y lanza sin contexto de restauración. Usa un intento mayor que `discardedAttempt`, incluso al guardar esperas por capacidad o cuota.
- El lanzamiento conserva la selección de asignación y alternativas; abre una sesión nueva con rama aleatoria nueva desde la rama por defecto integrada. El mensaje inicial visible en sesión/Trace explica el descarte explícito del usuario y el inicio limpio.
- `WorkService.progress` consume la disposición en la misma transacción que acepta `running` con sesión. Esperas, errores y rechazo por pausa conservan la intención. La marca permanente `discardedAttempt` y la metadata histórica se mantienen.
- La eliminación física ocurre después de leer la invalidación durable; es repetible y registra fallos sin rehabilitar evidencia. No se elimina metadata de auditoría. QA, Retry, cuota y contexto conservan sus rutas de recuperación.

# Alternativas descartadas

- Inferir el descarte de un snapshot ausente/inválido: perdería trabajo sin decisión del usuario.
- Consumir la marca al crear la sesión o antes de esperar cuota: un fallo del proceso perdería la intención antes de que la API acepte el lanzamiento.
- Depender de memoria del runner o del éxito del borrado físico: no sobrevive reinicios y confunde limpieza con elegibilidad.

# Ficheros clave

- `mic-sessions/src/mic_sessions/features/factory.py`
- `mic-mooi/src/main/java/dev/mooi/mic/features/ProjectWorkFeature.java`
- `mic-sessions/tests/test_clean_restart_runner.py`
- `mic-sessions/tests/test_task_recovery.py`
- `mic-mooi/src/test/java/dev/mooi/mic/features/WorkCleanRestartTest.java`
