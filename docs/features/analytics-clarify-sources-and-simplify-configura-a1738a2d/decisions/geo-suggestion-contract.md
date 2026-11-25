# Sugerencias GEO: contrato y recopilación pública

## Decisiones
- Operaciones en mic-mooi (`GeoSiteEvidenceFeature`), solo lectura y por propietario/proyecto (404 si no es suyo):
  - `GET /me/projects/{id}/analytics/geo/suggestions/sites`: sitios distintos de los destinos de producción activos
    (`production_unconfigured`, `ready`, `selection_required`). Destinos del mismo host (con o sin `www.`, mismo
    puerto) son un sitio; gana https. El id del sitio es su origen.
  - `POST /me/projects/{id}/analytics/geo/suggestions/evidence` `{site?}`: sin `site` y con varios sitios devuelve
    `selection_required`; un `site` ajeno a los destinos es 400. Una recopilación por proyecto y 4 en todo el
    servidor (429 si no).
  - Espejos de agente en mic-sessions (`/sessions/{id}/analytics/agent/geo/suggestions/...`) y brief del chat: misma
    operación para UI y chat. Las tareas de generación y revisión consumen este contrato.
- Fuentes: solo destinos de producción. Nunca preview, repositorio, secretos, orígenes permitidos ni URLs libres.
- Seguridad de red, en cada salto: URL validada (http/https, sin credenciales), mismo host del sitio (`www.` y
  puertos por defecto o el del sitio), DNS resuelto y fijado solo si todas las direcciones son públicas (reutiliza
  `AuditScanner.publicAddress`), sin proxy, cookies, compresión ni redirecciones automáticas; máximo 3.
- Rastreo: robots.txt primero (RFC 9309: 4xx = sin reglas, 5xx/sin respuesta = no se pide ninguna página) con el
  agente `MooiGeoCollector`; `noindex`/`nofollow` en meta y `X-Robots-Tag`; 401/402/403/407/451 = restringido. Se
  omiten rutas privadas o de estado (login, cuenta, carrito, admin), recursos estáticos y URLs con query.
- Límites expuestos en la respuesta: 6 páginas, 1 MB por página, 4 MB total, 5 s por petición, 25 s total,
  3000 caracteres de texto por página. El análisis HTML aborta al agotar el tiempo (`parse_timeout`).
- Estados: `production_unconfigured`, `selection_required`, `restricted`, `inaccessible` (con causa, p. ej.
  `blocked_address`), `insufficient_evidence` (`empty_content`, < 200 caracteres útiles), `partial` (límites o
  fallos listados en `coverage.limitations`) y `complete`. Cada página lleva URL, URL final, origen, estado HTTP,
  resultado y fecha; `collectedAt`/`finishedAt` fechan la recopilación.
- Contenido no confiable: `contentTrust: "untrusted"`; se eliminan caracteres de control, formato (bidi, ancho
  cero) y uso privado. Es dato para el agente, nunca instrucciones.
- Nada se persiste ni configura: no se crea el sitio de analytics, ni runs GEO, auditorías o programación.

## Alternativas descartadas
- Reutilizar el `fetch` de la auditoría tal cual: su error por dirección privada es un 400 y sus límites (2 MB,
  20 s, sin robots por página) no encajan; se comparten solo las comprobaciones de URL, dirección y robots.
- Persistir la evidencia: no es necesario para el contrato; la generación decidirá qué guardar con sus sugerencias.
- Incluir orígenes permitidos del tracker o URLs arbitrarias: no son destinos de producción autorizados.
- Seguir sitemaps índice o redirecciones a otros hosts: amplía la superficie; se registra como limitación.

## Ficheros clave
- `mic-mooi/src/main/java/dev/mooi/mic/features/GeoSiteEvidenceFeature.java`: contrato, resolución de sitios y
  recopilación.
- `mic-mooi/src/main/java/dev/mooi/mic/features/AnalyticsFeature.java`: `AuditScanner` comparte validación de URL,
  direcciones públicas y robots.
- `mic-sessions/src/mic_sessions/features/analytics.py`, `shared/mooi.py`: espejos de agente, brief y timeout.
