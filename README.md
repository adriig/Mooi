<div align="center">

<img src="spa-mooi/public/icons/icon-512.png" alt="Mooi logo" width="112" />

# Mooi

**Ship from a chat.**

The AI-native development platform: from GitHub repository to live preview, production and backups,
driven by coding agents, on desktop and mobile.

![Claude Code](https://img.shields.io/badge/Claude_Code-supported-d97757?style=flat-square)
![Codex](https://img.shields.io/badge/Codex-supported-10a37f?style=flat-square)
![Antigravity](https://img.shields.io/badge/Antigravity-supported-4285f4?style=flat-square)
![OpenCode](https://img.shields.io/badge/OpenCode-supported-111827?style=flat-square)
![Docker](https://img.shields.io/badge/Docker-isolated_previews-2496ed?style=flat-square&logo=docker&logoColor=white)
![Multi-tenant](https://img.shields.io/badge/multi--tenant-secure-111111?style=flat-square)
![Responsive](https://img.shields.io/badge/desktop_%26_mobile-ready-276ef1?style=flat-square)

[What is Mooi](#-what-is-mooi) · [Features](#-features) · [How it works](#-how-it-works) · [Deployments](#-deployments)

</div>

---

## ✨ What is Mooi

Mooi turns your GitHub repositories into a workspace of AI coding agents. Sign in with Google, link GitHub,
add a project and start building: run many agent sessions side by side, watch the code change live, preview
every feature in its own isolated environment and ship to production on any infrastructure, all without
leaving the chat.

Every action is available from the interface **and** from the chat. Both run the same mechanism and stay in
sync in real time.

## 🚀 Features

| | Feature | Description |
| :---: | --- | --- |
| 💬 | **Parallel agent sessions** | Independent chats per project with Claude Code, Codex, Antigravity or OpenCode, each on its own branch and repository copy. |
| 👀 | **Live changes** | Follow what the agent does and every file it touches, as it happens. |
| 📊 | **Usage insights** | Context size, session and weekly limits and more, per session and per account. |
| 🎛️ | **Models & accounts** | Pick model and thinking effort; connect several provider accounts, even of the same provider. |
| 🧩 | **Live previews** | One click deploys a session to an isolated container, embedded in mobile and desktop views, reachable only through Mooi. Run as many as you need. |
| 🔀 | **Agentic merge** | Conflicts with the main branch are resolved by the agent, keeping both sides. |
| 🏭 | **Software factory** | Agents design features into tasks with dependencies; Planification lists features, defects and debt (GitHub issues), the Board maps each feature, and Start runs every task in its own session, merged and QA-checked automatically. |
| 🔐 | **Shared environments** | Project-wide variables, live across every session, never committed. |
| 🌍 | **Deploy anywhere** | Describe your target once (VPS, AWS, anything); the agent builds and tests a repeatable script reused for every release. |
| 💾 | **Backups & restore** | The same describe-once flow, versioned per release, restorable to production or a sandbox. |
| 🧑‍🍳 | **Recipes** | Reusable implementation recipes applied straight from the chat. |
| 🎙️ | **Dictation** | Private, local push-to-talk in every chat. |
| 🏢 | **Teams & admin** | Multi-tenant isolation, roles and an admin console with platform insights and default models. |

## 🧭 How it works

1. **Connect**: sign in with Google and link GitHub, Claude, Codex, Antigravity and OpenCode accounts. OpenCode supports two separate connection modes: an API key from OpenCode Console, which offers the models that key can use, or an OpenCode account sign-in, including OpenCode Go, which offers the models enabled by that account or subscription. Session creation and chat let you choose from the connected account's available models; OpenCode's provider namespace is hidden in model names, while the canonical model ID is retained when starting a session or changing models.
2. **Add a project**: any public or private repository you can access.
3. **Build**: open sessions, chat with agents, watch changes and preview each feature live.
4. **Merge**: bring finished work into the main branch, conflicts handled by the agent.
5. **Ship**: create a release and deploy it to production; back it up and restore whenever needed.

### 🏭 Software factory: independent QA and task continuity

Starting a feature runs its unblocked tasks autonomously through the deterministic platform runner, including
when no device is connected. Each development task and its QA task has a separate session, workspace, provider
trace and selected model. QA starts
from the integrated default branch and uses its own isolated preview with the project's existing development
deployment setup. It validates acceptance criteria independently; the development summary and reported checks
help locate the change but do not establish that it works.

Development reports a compact handoff with the result, next step, changed files, reported verifications,
preparation for QA and obstacles. When a session is interrupted, a new session can restore the latest valid,
saved task snapshot and handoff. The platform saves eligible local commits and workspace edits without pushing,
in revisioned recovery bundles outside the repository and scoped to the user, project, work item, task and
attempt. Snapshots exclude ignored files, Git configuration, credentials, secret-bearing content and unsafe
paths; symlinks are rejected. A sudden failure can recover only through the last snapshot actually saved.
Missing, invalid, incomplete or mismatched evidence sends the task to attention with the reason instead of
restarting on a clean checkout or claiming unsaved work was recovered. Recovery restores changes into a clean
workspace at the recorded branch base; it never resumes another provider's runtime. Finalized recovery is
retired, abandoned snapshots have bounded retention, and snapshots in active use are protected from cleanup.

QA reports either a pass, a separately classified access/credentials/capacity/infrastructure block, or one
bounded delivery containing prioritized, reproducible in-scope product findings. A block waits for attention,
creates no fix and uses no correction round. Each finding creates an independent red fix task without its own
QA; QA waits for every fix to merge before it repeats. Stable delivery and finding IDs make retries idempotent,
and one grouped delivery uses one correction round. Legacy single-defect and generic blocked reports remain
supported; when a legacy development record lacks structured handoff, QA is told which evidence is unavailable.

This task-specific continuity complements debt #31 for compacting general briefs and removing their duplicated
content. It neither repeats that work nor depends on its merge order.

## 📦 Deployments

Mooi itself ships three ways. Each has its own files; never mix them.

### 🏭 Production

For Mooi's production environment only, managed through Mooi's production deployment feature. The
production files live under `deploy/production/` and `tools/`. Never use them for development or local runs.

### 🧪 Dev session

For previews inside Mooi: the session's **Deploy** button runs the root `compose.yml` in an isolated
environment, fully separate from production.

### 💻 Local

For running Mooi on your machine without the platform, for example from Claude Code or a terminal. The
`Makefile` reuses the dev session setup; it needs Docker and `make`.

```bash
make up
```

| Command | Action |
| --- | --- |
| `make up` | Build, start and print the local address |
| `make url` | Print the local address |
| `make logs` | Follow logs |
| `make ps` | Show services |
| `make down` | Stop, keeping data |
| `make clean` | Remove everything, data included |

Sign in with **Continue as developer**. Local secrets are generated once in `.env.local`, which is never committed.

### 📊 Analytics configuration

Configure these environment variables when enabling project Analytics:

| Variable | Purpose |
| --- | --- |
| `GOOGLE_CLIENT_SECRET` | OAuth client secret used to link Google accounts for Analytics. |
| `GOOGLE_OAUTH_REDIRECT_URI` | OAuth callback URL registered for the Google Analytics connection flow. |
| `ANALYTICS_PUBLIC_BASE_URL` | Public base URL used to serve the Mooi analytics tracker and collect endpoint. |

The tracker requires the public ingest path `/api/mooi/public/analytics/` to be reachable from production
websites. Mooi production is otherwise only reachable through Tailscale, so operators must explicitly
expose this path publicly for the tracker to work.

---

<div align="center">

Made with 🖤 by the Mooi team

</div>
