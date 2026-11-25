# analytics-analysis-agent

## Decisiones

- El runner consulta y reclama diagnósticos persistentes; conserva solo el identificador de sesión autenticada para renovarla y actuar con cuentas ya vinculadas.
- Cada turno usa las cuentas/modelos que pasan elegibilidad de cuota de factory, prueba alternativas y espera una ranura headless del límite de sesiones.
- La salida JSON solo se guarda si referencias y periodos coinciden con evidencia utilizable guardada; afirmaciones causales, tendencias no comparables y números no respaldados invalidan la salida.
- Fallos, salida inválida y falta de cuenta/cuota se guardan como estados diferenciados; los trabajos en curso abandonados se vuelven a encolar tras reinicios.

## Alternativas descartadas

- Proveedor fijo o credenciales nuevas: contradice las cuentas/adaptadores existentes y el aislamiento de credenciales.
- Diagnóstico síncrono sin estado persistente: no permite progreso, reintentos ni recuperación tras reiniciar el servicio.
- Confiar en referencias o cifras generadas por el modelo: permitiría atribuciones ajenas o métricas inventadas.
- Ejecutar runs GEO o auditorías para completar evidencia: la generación debe ser de solo lectura y usar resultados históricos.

## Ficheros clave

- `mic-sessions/src/mic_sessions/features/analytics.py`: rutas, prompt experto, validación de salida y runner headless.
- `mic-sessions/src/mic_sessions/shared/mooi.py`: claim durable y persistencia de estados.
- `mic-mooi/src/main/java/dev/mooi/mic/features/AnalyticsAnalysisFeature.java`: claim, estados y sesión de recuperación.
- `mic-mooi/src/main/resources/db/changelog/changes/066-analytics-analysis-generation.yaml`: sesión de autenticación recuperable e índice de cola.
- `AGENTS.md`: capacidad y restricciones disponibles para sesiones futuras.
