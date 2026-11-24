# Briefs compactos de la fábrica

Los campos persistidos `spec`, `description` y `acceptance` mantienen su contrato. No hay
migración ni cambios en las dependencias, los límites, las ramas o el arranque de tareas.

## Selección por rol

- Desarrollo recibe título del item, descripción y aceptación completas. Las specs de hasta
  2.000 caracteres se incluyen; las mayores se sustituyen por un índice de secciones, con
  instrucciones de leer objetivo, decisiones compartidas y las secciones pertinentes antes
  de trabajar. El umbral solo decide la presentación: nunca recorta el contenido almacenado.
- QA recibe descripción y aceptación de la tarea probada, además de su propia descripción
  (incluida la preparación) y aceptación. Los bloques exactamente iguales, tras quitar espacios
  exteriores, se representan una vez y se referencian desde el otro bloque. El resto de tareas
  no se incluye. La spec se ofrece por índice, incluso si es pequeña, para seleccionar reglas
  compartidas, comportamiento y preparación pertinentes.
- Si falta la tarea probada en un item antiguo, se conservan descripción y aceptación de QA,
  se indica cómo recuperar la spec y se exige reportar bloqueo si no se puede determinar el
  comportamiento esperado. Si falta la aceptación, se indica qué descripción usar.
- Un cuerpo de issue reproducido exactamente en la descripción se incluye solo como descripción,
  cualquiera que sea su tamaño, también si la descripción añade un encabezado de issue u otro
  texto. No se intenta resumir ni detectar similitud semántica.

El recordatorio genérico de reportar se elimina del mensaje inicial: la instrucción de sesión
ya exige el reporte y contiene endpoint, payload y resultados válidos por rol. Se conserva
la separación existente entre instrucciones de planificación de sesiones normales e instrucciones
de tareas automáticas. Tras revisar las instrucciones comunes se mantienen autenticación,
variables, aislamiento Docker, despliegue, entrega a los chats de producción/backups y reglas
de merge; no se retiran capacidades que una tarea existente pueda necesitar.

## Acceso íntegro sin descargar la lista de trabajo

Las instrucciones de toda tarea publican `GET /sessions/<sessionId>/work/agent/context`.
Usa la autenticación de agente habitual y la autorización del propietario del proyecto;
solo admite sesiones que ejecutan una tarea de fábrica. La respuesta no contiene otros items,
la lista de tareas, configuración de cuentas ni variables.

- Sin parámetros: título, id del item, revisión e índice con números y tamaños de sección.
- `?section=<n>&revision=<revision>`: una sección completa. Una revisión distinta devuelve
  conflicto para que el agente recargue el índice antes de seleccionar números de sección.
- `?full=true`: la spec almacenada íntegra, sin el antiguo corte de 30.000 caracteres.

La revisión es una huella SHA-256 abreviada del texto exacto. Las secciones se separan por
encabezados Markdown ATX fuera de bloques de código; se conserva el preámbulo. Concatenarlas
reproduce exactamente la spec, incluidos espacios y saltos de línea. Specs sin encabezados
(incluidos documentos con encabezados Setext) siguen disponibles como una sección completa.
Si la relevancia no es clara, el agente debe leer la spec completa. Los títulos del índice
son referencias, no resúmenes de decisiones. Las nuevas instrucciones de planificación piden
nombrar secciones pertinentes y decisiones compartidas en las descripciones, sin exigirlo a
items existentes. No se guardan specs, planes ni secretos en Git.

El endpoint lee el estado autorizado de trabajo mediante el cliente existente del backend,
selecciona el item de la ejecución y devuelve solo su contexto; no sincroniza issues ni escribe
estado. No es necesario invocar el endpoint de lista de trabajo desde el agente.

## Comparación reproducible de caracteres

Medición el 2026-11-24 contra `_brief` de `d4c5c06`, usando los mismos payloads actuales
y `len()` de cada mensaje inicial completo. Se ejecutaron las funciones de formato aisladas
mediante AST, sin importar el servicio, abrir sesiones, iniciar items ni ejecutar la app.
El payload Analytics tiene una spec de 8.042 caracteres y 14 tareas (12 desarrollo y 2 QA).
No se copia su contenido a este documento.

| Caso | Antes | Después |
| --- | ---: | ---: |
| Analytics, 14 briefs iniciales | 144.556 | 44.920 |
| Analytics, 12 briefs de desarrollo | 125.979 | 37.515 |
| Analytics, 2 briefs de QA | 18.577 | 7.405 |
| Analytics, `tab-qa` | 9.284 | 3.961 |
| Analytics, `tab-google-qa` | 9.293 | 3.444 |
| Issue #31, cuerpo repetido tras encabezado de GitHub | 5.969 | 3.544 |
| Issue sintético con cuerpo duplicado | 6.326 | 3.249 |

El caso sintético usa `kind=debt`, `issueNumber=999`, título `Duplicated issue body`,
spec y descripción iguales a `"Issue body.\n"` repetido 250 veces, título de tarea `Fix issue`
y aceptación `The issue is resolved.`. Complementa el caso real #31 (spec de 2.348 caracteres
contenida en una descripción de 2.437 caracteres) con el caso de campos completos idénticos.
No representa un issue real creado por esta revisión.

Estas cifras excluyen instrucciones de sistema y lecturas posteriores de la spec. El nuevo
acceso añade instrucciones a cada sesión; leer secciones o la spec completa también consume
entrada. Son tamaños de mensajes, no ahorro de tokens facturados ni atribución de costes.
Una medición de consumo deberá separar entrada, caché, salida y razonamiento por tarea y contar
las lecturas posteriores. El dato previo de 112.588 caracteres corresponde solo a repetir
la spec 14 veces, no al tamaño completo de esos briefs.

## Verificación

- Ruff sobre `factory.py`, compilación Python de `mic-sessions/src` y build de sdist y wheel
  con `uv build`; no hay typecheck configurado para este servicio.
- Medición de tamaños anterior y comprobación de reconstrucción exacta de las specs existentes
  (29.456 caracteres en total en el payload consultado).
- Revisión estática de autorización de sesión/proyecto, conflictos de revisión, secciones
  inexistentes, specs vacías o sin encabezados, bloques de código, criterios duplicados,
  ausencia de tarea probada y conservación de las reglas de preparación/reporte/despliegue.
- Sin archivos o dependencias de tests, ejecución de la app, despliegues ni cambios de límites.
