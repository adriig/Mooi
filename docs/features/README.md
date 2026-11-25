# Feature design memory

Each software factory feature has a design folder at
`docs/features/<slug>-<id8>/`. The slug is the feature title lowercased and separated with hyphens,
limited to 48 characters; `id8` is the first eight characters of the item's ID. The ID suffix keeps the
folder stable and unique when a title changes.

```text
docs/features/<slug>-<id8>/
├── FEATURE.md
└── decisions/
    └── <task-key>.md
```

`FEATURE.md` has these fixed sections:

- **Título**: the feature title.
- **Item**: the item ID.
- **Objetivo y spec**: the objective and complete specification.
- **Tareas**: a table of task keys, titles, categories, dependencies and acceptance criteria.

The platform writes or updates `FEATURE.md` on Start and when an applied change request modifies the
specification or tasks. It commits the file on the default branch only when its content changes.

Each development task writes its own `decisions/<task-key>.md` in that task's branch, so the record travels
with the task merge. It has these fixed sections:

- **Decisiones**: decisions made during implementation.
- **Alternativas descartadas**: alternatives considered and why they were rejected.
- **Ficheros clave**: the key files involved.

The platform remains the source of operational state, such as status, sessions and retries. These repository
files preserve readable design memory that can be reviewed or used without Mooi.
