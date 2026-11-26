# Sugerencias GEO: revisión y guardado en la UI

## Decisiones
- `GeoSuggestionPanel` vive dentro de Manage AI visibility, encima de los campos manuales (que siguen intactos,
  con sus controles de runs/programación). Botón visible «Propose with AI»; la edición manual nunca se bloquea.
- Selector de sitio solo cuando el backend devuelve varios sitios (`selection_required`); cambiarlo descarta el
  borrador. El componente se monta con `key={projectId}`, así cambiar de proyecto lo desmonta; un contador
  `token` invalida respuestas en vuelo al cambiar de sitio, rechazar, regenerar o desmontar.
- Progreso: evento en vivo `analytics.geo.suggestion` + sondeo de 3 s mientras `collecting`/`generating`.
- Cada estado (producción sin configurar, restringido, inaccesible, evidencia insuficiente, sin cuenta, cuota,
  salida inválida, fallo, sustituido) tiene título y pista; los errores de red conservan el borrador.
- Borrador separado del guardado: cada elemento es editable y se acepta/rechaza con casilla; muestra fundamento,
  «Inferred», referencias con cita literal, fuentes (páginas, fecha, cobertura, límites), limitaciones,
  incertidumbres y omitidos. Prompts marcados como propuestas editoriales; sin competidores verificables se
  explica en vez de dejar un hueco mudo.
- Guardar solo con el botón «Save N selected»: envía `{id, baseRevision, generationId, selección}`. El `id` se
  reutiliza si el reintento es idéntico (idempotencia). Elementos ya existentes se marcan «Already saved» y no se
  envían; se bloquea si la selección supera el hueco (`room`).
- 409 → `GeoApiError.status`: aviso, borrador intacto, guardado deshabilitado hasta «Review current version»
  (relee `geo/config`, actualiza `baseRevision` y refresca la sección).
- Tras guardar se recarga la sección; no se lanza ningún run, auditoría ni cambio de `weeklyEnabled`.

## Alternativas descartadas
- Modal para la revisión: peor en móvil con listas largas y pierde contexto con los campos manuales; se usa un
  bloque en línea con `aria-live`.
- Reintentar automáticamente tras 409 con la revisión nueva: saltaría la revisión humana que exige la spec.
- Guardar al perder foco o al cerrar: prohibido por la spec; «Reject draft» solo descarta.
- Ocultar prompts/competidores vacíos: se prefiere explicar la limitación.

## Ficheros clave
- `spa-mooi/src/features/analytics/components/GeoSuggestionPanel.tsx`: revisión, edición, selección y guardado.
- `spa-mooi/src/features/analytics/components/AnalyticsGeoSection.tsx`: monta el panel y recarga al guardar.
- `spa-mooi/src/features/analytics/api/geoApi.ts`: `GeoApiError` con estado HTTP.
