# Definición de Defects e Improvements

`debt` sigue siendo el identificador compatible de Improvement. Issues e items exponen
`definitionState: "NEW" | "DEFINED"`; las features siempre son DEFINED. La migración conserva
como DEFINED los items que ya se ejecutaron y los que tienen tareas de desarrollo con descripción
y criterios de aceptación completos. La asignación puede usar los valores por defecto del contrato
actual. Un issue sin metadatos de definición es NEW; configurar sus tareas no lo define.

Las rutas de usuario comparten el prefijo `/projects/{projectId}/work/issues/{number}/definition`:

- `GET`: estado, propuesta actual, última propuesta aceptada y resultados (`issueNumbers`, `featureId`).
- `PUT /proposal`: guarda una propuesta, sin escribir en GitHub ni crear/modificar items o tareas.
- `POST /accept` con `{"proposalId": "<uuid>"}`: acepta exactamente esa revisión.
- `POST /reopen` con `{"proposalId": "<uuid actual>"}`: vuelve a NEW conservando propuesta,
  aceptación y resultados anteriores. Para un issue sin propuesta se envía `{"proposalId": null}`.

Las mutaciones responden `{definition, issues, items}` y publican los eventos existentes `work.issue`
y `work.item`. `work.definition` añade `{projectId, number, definition}` para sincronizar el diálogo.
mic-mooi ofrece las mismas rutas bajo `/me/projects`, autenticadas mediante el servicio y limitadas
al propietario del proyecto. Los agentes tienen `GET` y `PUT /proposal` bajo
`/sessions/{sessionId}/work/agent/issues/{number}/definition`; la aceptación se reserva a la API
autenticada del usuario y no tiene una ruta de agente. Una sesión Define también puede actualizar
una tarea existente del mismo issue NEW con
`PATCH /sessions/{sessionId}/work/agent/issues/{number}/tasks/{taskId}`. El repositorio sigue siendo
de solo lectura; la actualización de una tarea no acepta la definición ni vuelve ejecutable el issue.

### Seguridad de ejecución

Los adaptadores de Define no activan los sandboxes basados en `bwrap`: en Linux requieren namespaces de
usuario sin privilegios, que algunos kernels y contenedores deshabilitan. Define aplica en su lugar una
lista de comandos de lectura y permite Python únicamente para una petición `urllib` a las rutas GET y
PUT de propuesta de esa misma sesión (y PATCH de una tarea existente del mismo issue). No se permite
composición del shell, escritura de archivos, otros destinos HTTP, comandos destructivos, despliegues,
aceptación de propuestas ni operaciones de GitHub Issues. Las herramientas de escritura de repositorio
se rechazan por separado. Un rechazo identifica los comandos permitidos para que el agente pueda
corregir la operación; no se solicita elevación de privilegios.

Los cinco adaptadores reenvían el checkout de su sesión al evaluar comandos; los dos adaptadores
Claude también inicializan el prefijo API desde las instrucciones de Define. Si falta el checkout,
las lecturas se rechazan sin recurrir al directorio del servicio; si falta el prefijo, las peticiones
API se rechazan. El mensaje común enumera los comandos y operaciones permitidos e indica reabrir
Define cuando falta ese contexto.

Ejemplo de propuesta para un issue individual:

```json
{
  "proposalId": "928949fe-b98c-4a80-8dad-164edebcb038",
  "previousProposalId": null,
  "title": "Corregir la búsqueda sin resultados",
  "description": "Al buscar un término inexistente debe aparecer un estado vacío.",
  "valid": true,
  "acceptance": "Una búsqueda sin coincidencias muestra el estado vacío y permite otra búsqueda.",
  "kind": "defect",
  "category": "dev",
  "breakdown": [],
  "feature": null
}
```

`kind` admite `defect` o `debt`; `category` admite `ux`, `dev`, `infra` o `docs`.
`valid: false` permite documentar un reporte inválido; al aceptar se cierra el issue sin crear trabajo.
Una definición válida exige descripción y aceptación no vacías. Aceptar un issue individual actualiza
título, cuerpo, etiqueta, categoría y criterios de su tarea, conservando sus ajustes de ejecución.
No permite ejecutar NEW, ni mediante Start ni mediante las operaciones internas del runner.

Para dividir, `breakdown` contiene hasta 20 objetos `{title, description, acceptance, kind, category}`.
Aceptar crea un issue y un item DEFINED por objeto y cierra el original. Para promover, `feature`
contiene el `FeatureDraft` actual (`title`, `spec`, `dependsOn`, `tasks`); se crea esa feature con las
validaciones normales y se cierra el issue original. División, promoción e invalidez son resoluciones
excluyentes. No se generan subtareas ni features al proponer.

Cada propuesta tiene un UUID estable y contenido inmutable. `previousProposalId` debe identificar
la revisión actual al guardar otra; una edición concurrente devuelve 409. Repetir una propuesta o
aceptación conserva el resultado, incluso después de revisiones posteriores. Repetir una aceptación
tras reabrir nunca vuelve a definir el issue. Reabrir con una revisión obsoleta devuelve 409.
No se redefine un item mientras tenga ejecución o sesiones activas.

Las transiciones se serializan por proyecto y mantienen recibos persistentes por revisión. GitHub
queda fuera de la transacción de base de datos: los issues de una división llevan un marcador estable
que se busca con paginación completa al reintentar una aceptación interrumpida. Así se recuperan
creaciones parciales sin depender del listado acotado del tablero. Ante un fallo externo, se reintenta
la misma revisión; no se cambia el UUID para recuperar la operación.
