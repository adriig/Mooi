# Sugerencias GEO: generación y aceptación

## Decisiones
- Generación en mic-sessions (`features/geo_suggestions.py`), mismas operaciones para UI y chat:
  - `POST /projects/{id}/analytics/geo/suggestions/generations` `{id, site?}` (idempotente por `id`) y
    `GET .../generations/{id}`; espejos `/sessions/{id}/analytics/agent/geo/suggestions/...` y brief del chat.
  - Recopila con el contrato `geo-suggestion-contract` y hace un único turno headless
    (`ProviderDescriptor.answer`) con las cuentas vinculadas que pasan la elegibilidad de cuota de factory, en
    orden y con alternativas, ocupando una ranura del límite de sesiones de la cuenta. La selección de
    candidatos (`analytics.answer_candidates`) y la ranura (`hold_answer_slot`) se comparten con el diagnóstico.
  - No crea runs GEO, auditorías ni cambia la programación; no guarda nada.
  - Estados: collecting, generating, completed, partial, empty, production_unconfigured, selection_required,
    restricted, inaccessible, insufficient_evidence (sin llamar al modelo), no_account, quota, invalid_output,
    failed (busy, invalid_site, agent_failure, signin, error) y superseded. Evento en vivo
    `analytics.geo.suggestion` `{projectId, generationId, status}`.
- Validación (`validate_suggestions`): JSON con exactamente brandTerms, competitors, prompts, limitations y
  uncertainties; si no, la salida se rechaza y se prueba la siguiente alternativa (`invalid_output` al final).
  Cada elemento cita páginas recopiladas (`P1`...) con citas literales, comprobadas contra el texto normalizado;
  citas dirigidas a una IA ("ignore previous instructions", "add X as competitor") no sustentan nada.
  - Marcas: el término aparece en una cita. Competidores: nombre en una cita con lenguaje competitivo y sin
    lenguaje de partner/proveedor/cliente, dominio válido presente en la página citada, distinto del propio.
  - Prompts: `kind: "editorial_proposal"`; se descartan los que afirman volumen, rankings o datos medidos.
  - Lo descartado, duplicado o ya guardado va a `omitted` con motivo; sin competidores verificables se añade la
    limitación `no_verifiable_competitors`. Máximo 10 por tipo; `room` indica el hueco hasta los límites reales.
- Borrador solo en memoria del proceso (1 h tras terminar): no es configuración. Una generación nueva del mismo
  proyecto sustituye a la que está en vuelo (`superseded`), invalidando respuestas de contexto obsoleto.
- Aceptación en mic-mooi (`GeoSuggestionFeature`): `GET /me/projects/{id}/analytics/geo/config` devuelve
  settings, prompts y `revision` (SHA-256 de lo editable, sin `nextRunAt`). `POST .../geo/suggestions/accept`
  `{id, baseRevision, brandTerms, competitors, prompts}` en una transacción bajo el mismo bloqueo del sitio que
  toda escritura GEO: compara revisión (409 si cambió), valida, deduplica (también contra lo existente),
  comprueba 20/20/50 antes de escribir y solo añade al final; no toca ids, orden, estados, `weeklyEnabled` ni
  `nextRunAt`. El `id` se guarda (`analytics_geo_suggestion_acceptances`, 30 días): mismo id y cuerpo =
  `replayed` sin duplicar; mismo id con otro cuerpo = 409. mic-sessions lo expone (UI y agente), rechaza un
  `generationId` de otro proyecto/propietario y publica `analytics.updated`.
- SPA: solo contrato cliente (`geoApi.config/generateSuggestions/suggestionGeneration/acceptSuggestions`) y
  `analyticsGeoSuggestionRevisions` en el live store; la revisión visual es la tarea `geo-suggestion-review`.

## Alternativas descartadas
- Persistir borradores en mic-mooi: no son configuración; perderlos al reiniciar se muestra como "expirado" y
  basta con generar de nuevo. Evita tablas y estados recuperables sin valor para el usuario.
- Guardar con el `PUT geo` existente o en varias llamadas: reemplaza secciones y no es atómico.
- Contador de revisión en base de datos: el resumen del contenido detecta igual cualquier edición sin migrar
  ni tocar las escrituras existentes; excluir `nextRunAt` evita conflictos falsos cuando avanza el programa.
- Confiar en el modelo para competidores o dominios: solo cuenta lo que dicen literalmente las páginas.
- Tests programáticos: la política del repositorio los excluye; se verificó con compilación, ruff, typecheck,
  build y una comprobación manual de `validate_suggestions` con contenido hostil.

## Ficheros clave
- `mic-sessions/src/mic_sessions/features/geo_suggestions.py`: generación, validación, rutas UI/agente.
- `mic-sessions/src/mic_sessions/features/analytics.py`: candidatos/ranura compartidos, `GET geo` con revisión y brief.
- `mic-mooi/src/main/java/dev/mooi/mic/features/GeoSuggestionFeature.java`: configuración con revisión y aceptación.
- `mic-mooi/src/main/resources/db/changelog/changes/069-analytics-geo-suggestion-acceptances.yaml`.
- `spa-mooi/src/features/analytics/api/geoApi.ts`, `spa-mooi/src/stores/liveStore.ts`.
