# Decisiones

- La elegibilidad vive en `canRestartTaskClean` y usa solo `status`, `sessionId` y `recoveryDisposition.state`; Planificación y detalle comparten la misma regla y diálogo.
- El diálogo muestra la advertencia española exacta. Confirmar llama `restart-clean`; cancelar no llama ninguna API. Retry conserva su ruta actual.
- Las respuestas exitosas se adoptan por el hook de trabajo y los cambios `work.item` actualizan el store común; los errores se muestran sin mensaje de éxito.

# Alternativas descartadas

- Inferir irrecuperabilidad desde `report`: texto histórico o variable no autoriza descartar evidencia.
- Reutilizar Retry: mantiene recuperación y no expresa la decisión de iniciar desde cero.
- Duplicar confirmación/eligibilidad en cada vista: podría producir reglas o advertencias diferentes.

# Ficheros clave

- `spa-mooi/src/features/work/lib/cleanRestart.ts`
- `spa-mooi/src/features/work/components/RestartCleanControl.tsx`
- `spa-mooi/src/features/work/components/WorkBoardCard.tsx`
- `spa-mooi/src/features/work/components/WorkItemDialog.tsx`
- `spa-mooi/src/features/work/api/workApi.ts`
