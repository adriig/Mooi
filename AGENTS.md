# Context

Full product vision of Mooi: what it is and is going to be. Apply it to **every** request: design, name and
structure each change for the whole platform described here. Never build something that blocks or
contradicts a capability below.

The vision runs ahead of the code: many capabilities are not implemented yet, and existing ones may differ
from it. Where they differ, this context wins over the current implementation (see *Source of truth*).

## What Mooi is

- An AI-native **development platform**: take a GitHub project from first prompt to dev preview, production
  deployment and backups, driven by coding agents (Claude Code, Codex).
- Desktop and mobile application (responsive web, mobile shell). Enterprise-grade, startup-modern: awesome
  design, excellent by default in every aspect.
- Multi-user, multi-tenant: users and tenants are fully isolated; every resource is owned and access-checked.
- Engineering bar: efficient, scalable, maintainable, secure, built on proven design patterns.

## What Mooi is not

- Not a single-provider wrapper: providers are pluggable behind one compatible contract.
- Not tied to one hosting target: production and backups work on any platform the user describes.
- Not a public host for dev previews: previews are reachable only through Mooi.
- Not a secret store in Git: development, production and backup variables never reach the repository.
- Not nested virtualization: dev deployments run on the host Docker engine, isolated by network and naming.
- Not two parallel systems for UI and chat: both converge on one mechanism (see *Core principles*).

## Core principles

- **Convergence**: every capability is reachable from UI buttons *and* from the chat; both run the same
  low-level operation. Changes made from one side appear live on the other (deployment setup, variables,
  KPIs, files, status).
- **Real time everywhere**: agent activity, file changes, context/limits KPIs, deployment progress and logs,
  production and backup status stream live to every connected device.
- **Agent writes once, platform reuses**: an LLM turns a free-text description into a deterministic,
  tested script; later runs execute that script without the LLM.
- **Dev ↔ local ↔ production compatibility**: any deployment setup works in Mooi dev sessions, in local
  sessions without Mooi (e.g. Claude Code directly) and in production. Setup prompts enforce it.
- **Isolation by default**: sessions, deployments, tenants and secrets never overlap or leak.

## Identity and projects

- Sign in with Google. Link a GitHub account to list public and private repositories.
- Signed-in devices stay signed in while used: a 30-day sliding refresh token in an `HttpOnly` cookie is
  renewed on every use; 30 days without use requires signing in again.
- Add repositories to Mooi as projects; iterate on them from there.

## Development sessions

- Many sessions per project, technically independent: each has its own branch and its own repository copy
  (worktree/clone), so features progress in parallel without interfering.
- Chat UX like Claude Code / Codex. Supported providers: **Claude**, **Codex**, **Antigravity** and **OpenCode**.
- Live view of what the agent does and of changed files on the session branch.
- **Message queue**: messages sent while the agent is busy (a turn, a compaction, a deployment start) wait
  in the chat's queue, kept by the platform and shown live on every device. Each turn's end delivers the
  next one, in order, until the queue is empty, even with no device connected. Queued messages can be
  edited or removed until they go out; stopping a turn keeps the queue, and a failed session holds it until
  it reconnects. Same in development, production and backup chats.
- **Work planning is decided in the session**: creating a session never plans work. Whatever the user asks
  in a plain session, its agent decides whether it is a single task (done right there), a feature, a defect
  or debt, and records the last three on the project's software factory (see *Software factory*) through
  the same platform API as the To-dos and Board views.
- Provider integration through **adapters** behind one provider-agnostic API: a provider may offer
  several (Claude: **Claude SDK**, the default, and **Claude CLI**, which drives the Claude Code CLI
  natively; Codex: its SDK; Antigravity, which ships no agent SDK: one adapter, **Antigravity CLI**, which
  drives the `agy` CLI natively through its headless stream-json mode; OpenCode: **OpenCode CLI**, driven
  through ACP with an OpenCode Console API key). Each adapter supports every session
  feature (streaming, questions, interrupt, compaction, model/effort, context and limit KPIs) in its own
  way; a session picks one with its model and effort and keeps it.
- A **Files** tab, always visible in a development session: a project view of the session's working tree
  (folders of folders, search, changed and unsaved markers) beside a plain-text editor. Saving writes into
  the session's own workspace, so edits show up live in Changes and travel with the merge; a save names the
  revision it read, so a concurrent agent edit is reported, never overwritten. Desktop shows tree and editor
  side by side, phones show them one after the other.
- KPIs per session and provider account: context size, session limits, weekly limits, other usage metrics.
  The account page shows every provider account's plan limits at a glance, with no session open: what is
  left in each window (5-hour, weekly, per model group) and when it resets, kept live by open sessions;
  providers billed per use say they have no plan limits.
- Model and thinking-effort selection per session.
- Each development session maintains an editable **Suggested commit message** based on what the user
  requested and what the agent actually changed. It is refreshed after productive turns and pre-fills
  the clean merge dialog only while it still matches the working tree. Account preferences enable or
  disable it and choose English or Spanish; they follow the user across devices.
- A **Trace** tab, like Langfuse / LangSmith, shows every internal step of the agent: prompts, turns (one
  agent-loop iteration each: an LLM call with tokens, cache and context size, then the tools it requested),
  nested sub-agents, skills, hooks, injected context, compactions and errors, as a live timeline with an
  inspector and usage insights. **Download log** saves the raw agent session logs the trace was built from
  (Claude transcripts and sub-agent files, Codex rollouts, Antigravity stream and brain transcripts,
  OpenCode ACP logs), kept
  for cleared conversations too. Available in development sessions (including the first dev deployment
  setup), production and backup chats, for every adapter.
- Multiple provider accounts, including several of the same provider; sessions of one project may use the
  same or different accounts.
- Provider accounts link by each provider's own sign-in: Claude like the Claude Code app (OAuth2 + PKCE,
  the code Anthropic shows pasted back; a `claude setup-token` token only when operators turn sign-in
  off, and for existing links), Codex by device code, Antigravity like the Antigravity CLI signs in (Google OAuth2
  performed by `agy` itself, the code Google shows pasted back; no API key), and OpenCode with an encrypted
  OpenCode Console API key. The platform alone renews
  Claude's sign-in tokens, so concurrent sessions of one account never race, and live sessions pick up a
  renewed token on their next message; the exception is Antigravity: `agy` renews its own sign-in inside a
  session and the platform stores the renewed bundle for the next one. The platform renews every Claude
  sign-in in the background as Claude Code does, so an account stays signed in as long as the provider
  keeps the grant; an outage never signs it out, and an account the provider did sign out is flagged on the
  account page and signed in again in place.
- **Agentic merge**: merging an outdated branch with conflicts makes the agent resolve them, preserving
  both sides' features.

## Software factory: To-dos and Board

Each project plans and builds its work as a software factory: agents design it, the platform runs it.

- **Work items** are of three kinds, and nothing else reaches To-dos:
  - **Feature**: work worth several tasks, designed by a session agent acting as orchestrator. It holds a
    spec and its tasks; it lives in the platform. Features are independent of each other: at most a whole
    feature depends on another whole feature, never a task on a task of another feature.
  - **Defect** and **Debt**: a GitHub issue of the project labelled `defect` or `debt` (created when
    missing). GitHub Issues hold only these two kinds, never features; their execution settings live in the
    platform. A single task asked in a session is developed in that session and never becomes a work item.
- **Tasks** belong to one feature and carry a title, a category, a description, acceptance criteria, the
  provider, account, model and effort to run with, **auto-merge** (on or off) and **tests** (on or off),
  all prefilled by the orchestrator and editable. Dependencies are blocking and stay inside the feature.
  Categories have fixed colours: UX/UI green, Development yellow, QA blue, Infra/DevOps purple,
  Documentation grey.
- **QA**: enabling tests on a task adds a separate QA task to its feature, blocked until that task merges,
  with acceptance criteria and a different model preselected. Developing and testing never share a session.
- **Model assignment**: an assignment expert picks each task's provider, model and effort from the user's
  linked accounts and their remaining quota, with ordered fallbacks, so no account runs out of credits. The
  platform checks quota again when a task launches and falls back to the next alternative when needed.
- **To-dos tab** (per project): features, defects and debt. Opening a feature shows its task breakdown and
  every task's settings; defects and debt offer the same settings. Status filters show their counts in
  compact pills (`1.2K`) that never overflow. A **To-dos** entry in the main menu gathers every project on
  one page, filtered by a project dropdown with checkboxes remembered per user on the current device;
  projects load only as they come into view.
- **Board tab** (per project and feature): pick a feature and see its tasks as a left-to-right dependency
  graph of coloured nodes. Without opening a task, each node shows and changes auto-merge (robot icon),
  provider and model, and tests. Defects detected by QA appear as fix tasks in red, with a defect icon
  and label; their connections and minimap markers use the same red. Finished tasks get a green check
  and stay where they are.
- **Execution**: **Start** applies to a feature, a defect or a debt item, never to a single task. From then
  on the platform runs it automatically, with no agent orchestrating and no device connected:
  - every unblocked task gets its own new session (one task, one session), within the account's session
    limit; the rest wait in line;
  - a development task ends with its merge into the default branch: automatic (agentic merge on
    conflicts) or by the user, as the task says. The merge closes the task and its session and unblocks
    its dependants;
  - a QA task checks the default branch with everything merged through Playwright and ends when its
    acceptance criteria are met. A defect it finds is added to the feature as a fix task that runs
    automatically with no QA of its own, and the failed QA runs again after it;
  - a defect or debt item closes its GitHub issue when its work merges.
- **Factory settings** (per project, from To-dos, the Board or the chat): the quota percent from which an
  account is spared (default 85%), the sessions a task may lose before it waits for the user (3), the
  fix-and-check-again rounds after a failed QA (3; 0 hands every failure to the user) and the agentic merge
  attempts on conflicts (2; 0 leaves conflicts to the user).
- Managed from UI or chat (session agents use the same platform API); changes stream live to every view.

## Development environment variables

- Project-wide, shared by all sessions of the project, propagated live (a variable set from one session is
  instantly available to all others).
- Managed from UI or chat. Sensitive: stored by the platform, never committed.

## Dev deployments (web applications)

- Deploy a session's code to an isolated Docker environment on the host engine (no nested virtualization,
  no performance loss), on an isolated network.
- Many instances at once (one per feature/session): free ports and volumes are detected to avoid overlap.
- Preview embedded in Mooi with mobile and desktop views. Web applications only; other project types can
  still be iterated without previews.
- Exposed only through Mooi's secure reverse proxy; no other access path.
- **First deploy**: a dedicated setup prompt goes to the chat to prepare the project. Resulting setup must be
  compatible with Mooi dev sessions, local runs without Mooi and production deployments. Deploy runs
  directly only once that setup passed its test; a Compose file already in the repository is not enough.
- Deploy, stop, configure through buttons or chat; the chat knows the deployment prompt, so setup from chat
  equals setup from the Deploy button. Build/run logs and progress stream live.

## Production deployments

- A project starts unconfigured. First **Deploy** asks how to deploy, in free text: local, VPS, AWS, GCP,
  any platform, or several targets at once (e.g. a VPS and GCP together).
- This opens a chat with a platform prompt: the agent never deploys with its own tools; it writes a
  deterministic deployment script, tests it with a real deployment and iterates until it works. From then on
  the Deploy button runs that script directly, without an agent; the chat suggests clearing itself.
- **Isolation**: production never collides with any other deployment of the repository or the target (dev
  stack, local tooling, other stacks): own files under `deploy/production/` and production-specific runtime
  names (Compose project, volumes, networks, images, directories, cloud resources).
- **Releases**: create releases from the menu, including from the latest default branch; only releases are
  deployed. A chat test deploys a new release when it just committed repository changes, otherwise the
  deployed release or, before the first deployment, the latest release (created when none exists).
- **Platform files** (managed by Mooi, never committed, since they reveal the real infrastructure):
  `deploy.sh`, `status.sh` (live status of every target) and `DEPLOYMENT.md`, the natural-language manifest
  agents read to change the deployment later; all kept consistent with every change.
- **Repository files** (committed only when the target needs them): e.g. a production Compose file reading
  env variables, holding variable references, never values.
- **Production variables**: values stored only in the platform; set manually in their menu or by the chat;
  both views stay in sync. Stored values that neither the deployment scripts nor the backup scripts read
  are flagged as unused and preselected for removal.
- An open production chat can be reused to change or fix the deployment.

## Backups

- Same flow as production: describe the backup in free text, the agent writes and tests a script, the
  platform reuses it.
- Each backup carries a timestamp and the release it belongs to.
- Restore into production and into sandbox (disposable, isolated) environments.
- Buttons and chat converge; changes appear live in both.

## Accounts and administration

- Account area: profile, linked GitHub, provider accounts and other personal settings; secure storage.
- Roles. **Admin** gets a tabbed console with rich, live insights and controls:
  - **Users**: every registered user with their last connection, who is online right now (and on how many
    devices), their live sessions and what each agent is doing, projects, provider accounts and devices,
    all updated in real time. Admins close a user's sessions, sign them out everywhere, and ban (sign out,
    close sessions, block sign-in) or unban them. Live data costs nothing while no admin watches.
  - **Agents**: minimal per-provider session defaults (adapter, model, thinking effort) preselected for
    every user; saved on change.
  - **Account session limits**: administrators configure a positive maximum of open agent sessions per
    user account (default: four), shared across projects, providers and development, production and
    backup chats. Changes apply to new creations immediately; lowering a limit keeps existing sessions
    open. The host capacity limit still applies independently.
  - Other platform settings as they come.

## Other capabilities

- **Recipes**: reusable implementation recipes from GitHub marketplaces, applied from the session chat.
- **Dictation**: local push-to-talk speech-to-text in every agent chat composer.

# Guidelines

## Source of truth

- Priority: **1.** the user's new request → **2.** *Context* → **3.** current implementation.
- When a request conflicts with *Context*, follow the request and update *Context* in the same change, so it
  always states the latest vision.
- When the implementation conflicts with *Context*, align new work with *Context*, never the other way round.

## Testing

- Do not write programmatic tests: no unit, integration or end-to-end test files, test suites, test runners
  or test dependencies (pytest, vitest, `node:test`...) when adding or changing features. The repository
  keeps none.
- By default, verify without running the application: keep type checks, linters and builds passing, review
  the change for regressions and report what was checked. Type checks, linters and builds are not
  programmatic tests.
- Do not deploy the preview, start the stack, call the running services or drive the app in a browser
  (Playwright or otherwise) unless the user explicitly asks you to test the application: by default the user
  deploys and tests manually. When asked, test with those tools and report what was checked.

## Deployment channels

Three channels, each owned by one Mooi feature. Edit a channel's files only when the request comes from its
owner (its Mooi platform prompt); otherwise treat them as read-only.

| Channel | Files | Owner (only editor) | Used by |
| --- | --- | --- | --- |
| Production | `deploy/production/`, `tools/deploy-production.sh` | Mooi **production deployment** feature | Production only |
| Dev session | `compose.yml` | Mooi dev **Deploy** feature (deployment setup) | Mooi dev sessions; reused by local |
| Local | `Makefile` | Wraps the dev session channel; no logic of its own | Local runs outside Mooi |

- Never edit production or dev session files from local work, plain sessions or tools outside Mooi.
- Local deployment always goes through `make`, which only drives `compose.yml`; never run production files
  locally and never add a separate local stack.
- Files shared by channels (e.g. service `Dockerfile`s) must keep every channel working.
- Secrets live in the platform or git-ignored files (`.env`, `.env.local`), never in the repository.
- Respect isolation: keep the assigned Compose project, let Docker name resources and pick host ports, and
  manage only resources the current session owns. No automatic restarts or deploys after code changes.
