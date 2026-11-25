# Definición de proyecto y Feature 0

Un proyecto nuevo define primero el proyecto entero con el usuario y construye una **Feature 0:
Fundaciones**; después, cada entrada del roadmap se diseña como una feature normal de la factoría.
Es opcional en proyectos existentes, que también ofrecen **Definir proyecto** en su menú de
Planificación. La plataforma calcula la detección, no el agente: `GET /projects/{projectId}/work`
expone `newProject: true` cuando no hay items de tipo feature y hay como mucho 10 ficheros
versionados fuera de `docs/`, `.github/` y los ficheros de configuración raíz (README, LICENSE,
.gitignore, AGENTS.md, CLAUDE.md). En un proyecto nuevo, el agente de sesión que recibe la primera
petición de trabajo abre la definición del proyecto en lugar de registrar una feature normal;
Planificación muestra un estado vacío con **Definir proyecto** en los proyectos sin items.

**Definir proyecto** crea o reabre una sesión asociada a la definición del proyecto, con un modelo
fuerte porque es trabajo de arquitectura. El agente conversa con el usuario y guarda una propuesta
revisionada; proponer no cambia el repositorio ni crea trabajo ejecutable. Solo la aceptación del
usuario crea la feature Fundaciones con las validaciones normales y guarda el roadmap; reabrir
vuelve a permitir nuevas propuestas sin tocar lo ya creado.

Las rutas de usuario comparten el prefijo `/projects/{projectId}/work/definition`:

- `GET`: propuesta actual, última propuesta aceptada y resultados (`featureId` y roadmap con el
  estado y el enlace de cada entrada).
- `PUT /proposal`: guarda una propuesta revisionada, sin escribir en el repositorio ni crear trabajo
  ejecutable.
- `POST /accept` con `{"proposalId": "<uuid>"}`: acepta exactamente esa revisión.
- `POST /reopen` con `{"proposalId": "<uuid actual>"}`: vuelve a permitir propuestas conservando
  propuesta, aceptación y resultados anteriores. Para una definición sin propuesta se envía
  `{"proposalId": null}`.

Las mutaciones responden `{definition, items}` y publican `work.definition.project` con
`{projectId, definition}` para sincronizar el panel; aceptar publica además el evento existente
`work.item` de la feature creada. mic-mooi ofrece las mismas rutas bajo `/me/projects`, autenticadas
mediante el servicio y limitadas al propietario del proyecto. Los agentes tienen `GET` y
`PUT /proposal` bajo `/sessions/{sessionId}/work/agent/definition` y
`PUT /sessions/{sessionId}/work/agent/definition/roadmap/{key}` con `{"featureId": "<uuid>"}` para
enlazar una entrada del roadmap a su feature diseñada; la aceptación se reserva a la API autenticada
del usuario y no tiene una ruta de agente.

Ejemplo de propuesta:

```json
{
  "proposalId": "6f1c2a9e-4b7d-4e8a-9c31-2d5f8b0a7e42",
  "previousProposalId": null,
  "vision": "Gestor de reservas para clínicas pequeñas. Usuarios: recepción y pacientes. Alcance: agenda, reservas y recordatorios. Fuera de alcance: facturación.",
  "architecture": "API Java y SPA React sobre PostgreSQL, despliegue Docker en un VPS. Integraciones: correo transaccional.",
  "decisions": [
    {
      "decision": "Monolito modular con una sola base de datos",
      "reason": "Equipo pequeño y dominio acotado",
      "affects": "Estructura del repositorio y despliegue"
    },
    {
      "decision": "Sin tests programáticos; verificación por tipos, linters y builds",
      "reason": "Política de tests del proyecto",
      "affects": "Todas las tareas de desarrollo y QA"
    }
  ],
  "roadmap": [
    {
      "key": "agenda",
      "title": "Agenda de la clínica",
      "goal": "Ver y editar la disponibilidad semanal",
      "dependsOn": []
    },
    {
      "key": "reservas",
      "title": "Reservas de pacientes",
      "goal": "Reservar y cancelar citas desde la web",
      "dependsOn": ["agenda"]
    }
  ],
  "foundations": {
    "title": "Feature 0: Fundaciones",
    "spec": "Base técnica, documentos de diseño y preview de desarrollo del proyecto.",
    "dependsOn": null,
    "tasks": [
      {
        "key": "docs",
        "title": "Documentos de diseño",
        "category": "docs",
        "description": "Escribir docs/VISION.md, docs/ARCHITECTURE.md, docs/DECISIONS.md y docs/ROADMAP.md a partir de la propuesta aceptada.",
        "acceptance": "Los cuatro documentos existen con sus secciones fijas y reflejan la propuesta aceptada.",
        "autoMerge": true,
        "tests": false,
        "dependsOn": []
      },
      {
        "key": "base",
        "title": "Base técnica",
        "category": "dev",
        "description": "Esqueleto, estructura, dependencias y convenciones según la arquitectura aceptada.",
        "acceptance": "El esqueleto compila y pasa los checks del proyecto.",
        "autoMerge": true,
        "tests": false,
        "dependsOn": ["docs"]
      },
      {
        "key": "dev-deploy",
        "title": "Despliegue de desarrollo",
        "category": "infra",
        "description": "Preparar el despliegue de desarrollo siguiendo exactamente el brief del botón Deploy (GET /sessions/{sessionId}/deployment/agent/brief).",
        "acceptance": "El setup del brief pasa su test y la preview de desarrollo queda lista.",
        "autoMerge": true,
        "tests": true,
        "dependsOn": ["base"]
      }
    ]
  }
}
```

`vision` y `architecture` son texto Markdown: la visión cubre problema, usuarios, alcance cerrado y
fuera de alcance; la arquitectura, stack, componentes, datos e integraciones. `decisions` es una lista
de `{decision, reason, affects}` e incluye la política de tests. `roadmap` es una lista ordenada de
`{key, title, goal, dependsOn}`; `dependsOn` nombra otras entradas por key y una entrada sin
dependencias depende de Fundaciones. `foundations` contiene el `FeatureDraft` actual (`title`, `spec`,
`dependsOn`, `tasks`) de la Feature 0, con el contrato de tareas habitual.

Fundaciones contiene siempre una tarea `docs` que escribe `docs/VISION.md`, `docs/ARCHITECTURE.md`,
`docs/DECISIONS.md` y `docs/ROADMAP.md` a partir de la propuesta aceptada, las tareas de base técnica
(esqueleto, estructura, dependencias, convenciones) y, para aplicaciones web, una tarea `infra` que
prepara el despliegue de desarrollo siguiendo exactamente el brief del botón Deploy
(`GET /sessions/{sessionId}/deployment/agent/brief`), que la autoriza a editar `compose.yml` como
dueña del canal, con tests activados para que la QA compruebe la preview. Producción y backups quedan
fuera de Fundaciones: siguen en sus propios chats. La plataforma nunca escribe los documentos en el
repositorio: lo hace la tarea `docs`, y viajan con su merge.

Cada propuesta tiene un UUID estable y contenido inmutable. `previousProposalId` debe identificar la
revisión actual al guardar otra; una edición concurrente devuelve 409. Repetir una propuesta o una
aceptación conserva el resultado, incluso después de revisiones posteriores: aceptar nunca crea una
segunda feature Fundaciones ni duplica el roadmap. Repetir una aceptación tras reabrir no vuelve a
crear trabajo. Reabrir con una revisión obsoleta devuelve 409.

El panel del roadmap muestra las entradas en orden con su estado, derivado de la feature enlazada:
pendiente (sin feature), diseñada (con enlace a su feature) o desplegada. **Diseñar siguiente** abre
una sesión de orquestador que diseña la siguiente entrada pendiente como una feature normal,
dependiendo de la entrada anterior o de Fundaciones, leyendo el código real y los documentos de
diseño, y la enlaza a la entrada mediante la ruta de agente del roadmap. Toda feature posterior, del
roadmap o no, lee antes `docs/VISION.md`, `docs/ARCHITECTURE.md` y `docs/DECISIONS.md` si existen, y
respeta sus decisiones o propone cambiarlas explícitamente.

Los documentos de diseño tienen secciones fijas:

- `docs/VISION.md`: **Problema**, **Usuarios**, **Alcance** y **Fuera de alcance**.
- `docs/ARCHITECTURE.md`: **Stack**, **Componentes**, **Datos** e **Integraciones**.
- `docs/DECISIONS.md`: una entrada por decisión con **Decisión**, **Motivo** y **Afecta**; incluye la
  política de tests.
- `docs/ROADMAP.md`: las entradas en orden, cada una con **Key**, **Título**, **Objetivo** y
  **Dependencias**.

Las transiciones se serializan por proyecto y mantienen recibos persistentes por revisión; ante un
fallo se reintenta la misma revisión, sin cambiar el UUID para recuperar la operación. La plataforma
sigue siendo la fuente del estado operativo (estados del roadmap, enlaces y versiones); estos
ficheros son memoria de diseño legible que funciona sin Mooi.
