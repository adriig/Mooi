# analytics-analysis-ui

## Decisiones

- Mantener la revisión dentro de Analytics, ligada al proyecto y al periodo de la URL; cada creación usa la API persistente compartida y el mismo registro sirve para reintentos.
- Mostrar cobertura antes de generar y conservar por fuente su estado, periodo real, consulta, filas, frescura, muestra, errores y límites. Hallazgos exponen sus referencias y evidencia plegable; recomendaciones y sugerencias generales permanecen separadas.
- Recuperar diagnósticos al montar y releer el registro al recibir `analytics.analysis`; un resultado con periodo distinto queda señalado como anterior.
- Los únicos controles mutables generan o reintentan un diagnóstico de solo lectura. La configuración reutiliza el modal existente; no se lanzan runs, auditorías, reconexiones ni cambios del proyecto.

## Alternativas descartadas (y por qué)

- Guardar el estado solo en React: se perdería al recargar y no reflejaría generaciones desde otros dispositivos.
- Inferir ceros para fuentes desconectadas o vacías: ocultaría la cobertura real y sesgaría el diagnóstico.
- Aplicar recomendaciones desde la vista: excedería el contrato de revisión y solo lectura.

## Ficheros clave

- `spa-mooi/src/features/analytics/components/AnalyticsAnalysisPanel.tsx`: cobertura, resultados, evidencia, estados y controles.
- `spa-mooi/src/features/analytics/api/analyticsApi.ts`: contratos tipados y llamadas de lista, lectura, creación y generación.
- `spa-mooi/src/features/analytics/components/AnalyticsPanel.tsx`: acceso junto al selector de periodo y configuración existente.
- `spa-mooi/src/stores/liveStore.ts`: revisión por diagnóstico para releer el estado canónico tras eventos.
