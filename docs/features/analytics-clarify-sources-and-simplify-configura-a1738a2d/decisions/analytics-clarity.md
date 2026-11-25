# Analytics: claridad de fuentes

## Decisiones
- Atribución visible junto a cada informe: tracker Mooi, GA4, Search Console, ejecuciones de prompts o auditoría de producción.
- Explicar Statistics, SEO y GEO al inicio; detallar capacidades, límites y privacidad del tracker dentro del Modal compartido de configuración. Configurar permanece disponible sin eventos y con errores.
- Reutilizar APIs, selección de Google y guardado existentes. Mantener la confirmación de regeneración abierta si falla, con su error visible.
- Consultas: ordenar todos los resultados recuperados antes de mostrar cinco filas; Load more añade cinco, con recuento accesible. Reiniciar al cambiar proyecto, rango, propiedad o dimensión. Pages conserva todas sus filas.

## Alternativas descartadas
- Configuración en línea: ocupa el espacio de lectura de informes y mezcla instalación con resultados.
- Paginación de servidor: el endpoint actual devuelve un conjunto acotado; la ampliación sólo descubre consultas ya recuperadas.
- Unificar datos de Google y Mooi: sus métricas y métodos de recogida son distintos.

## Ficheros clave
- `spa-mooi/src/features/analytics/components/AnalyticsPanel.tsx`: explicaciones y modal.
- `AnalyticsSetupPanel.tsx`: tracker, privacidad, orígenes y confirmación de clave.
- `AnalyticsSource.tsx`, `AnalyticsKpis.tsx`, `AnalyticsBreakdownCard.tsx`, `AnalyticsGa4Section.tsx`, `AnalyticsSearchSection.tsx`, `AnalyticsGeoSection.tsx`: fuentes de los informes.
- `AnalyticsSortableTable.tsx`: ordenación y ampliación local opcional.
