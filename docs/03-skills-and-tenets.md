# 03 — Skills & tenets

**Status:** Settled · **Milestone:** 3

Where the product's value lives. The layering mechanism (ARCHITECTURE.md §2.3, §2.4,
§9) plus the storage, distribution, and invocation model are settled here. The
section structure of the generated documents is [07](./07-document-templates.md); the
exact per-skill contracts firm up against [01](./01-interface-contracts.md) and 07.

## Decided

**Two worlds — the operation set for state, a local cache for skills/tenets.**
- Every stateful interaction with structure (epics/tasks/releases) and documents flows
  through Nook's **operation set** — exposed as **MCP tools** to external agents and as
  the mirrored **web RPC** to the UI and its embedded authoring agent (§5,
  [06](./06-web-ui.md)). That is the only way state changes.
- **Skills and tenets are consumed agent-side** — by any agent, an external MCP client
  or the web app's embedded agent — from an **ephemeral local cache** materialized into
  the agent's environment: read locally, **never committed to the code repo, never
  branched.** The cache is a disposable replica; Nook is the source of truth, so
  anything that comes from the server is fetched, not committed.

**Nook is the canonical, versioned source of truth.**
- **Base** tenets and skills are **Nook system-level assets** — shipped with Nook,
  instance-global, one copy serving every project. They are not project artifacts.
  The shipped base tenet set lives at [`artifacts/tenets.md`](../artifacts/tenets.md):
  common conventions applicable to any project, which project tenets layer on top of
  (project wins on collision).
- **Project tenets are project-owned documents** in the git artifact store (document
  kind `tenet`, project-scoped with no item link), reusing document versioning, the
  `(path, current_version)` pointer, the single write path, and the editor-grade API.
  Editing a tenet is a `replace_section`, not a whole-file rewrite. They are markdown,
  so git already gives versioning/diff/history — a separate store would rebuild that.
- **Skills are system-level, not project artifacts** — held and distributed by Nook,
  materialized into the agent's environment. They are **not** stored in the artifact
  repo and have **no** document kind. A project's own skill refinements are expressed
  as append-only layers Nook manages system-side, not as artifact-store documents.

**Composition is agent-side.**
- There is no server-side composition engine in v1. The agent reads its local cache —
  base + tenets (+ any skill layers) — and reasons over it directly.
- A skill **returns instructions the agent executes**; it does not itself write.
  Persistence happens when the agent then calls the MCP operation tools
  (`create_item`, `write_doc`, …). That boundary *is* the two-world split.
- So the three core skills — `split_epic`, `generate_task_plan`, `author_manifesto` —
  are **local skills, not MCP tools.** `split_epic` is a local skill that drives
  repeated `create_item` calls; it is not itself a Nook-served operation.

**The operate-Nook skill.**
- One shipped base skill is the operating manual: how and when to call the MCP server,
  which tool for what, and the startup ritual (read the local tenets before acting).
  The agent's knowledge of *how to use Nook* is itself a layerable, versioned,
  distributed skill — not hardcoded into any client. A project can sharpen even it.

**Distribution — two paths to the same cache.**
- **External agents** (third-party harnesses) receive the cache by **pull**: Nook stamps
  its current tenet/skill version(s) on operation responses (MCP tool results and RPC
  responses alike), and the operate-Nook rule is — if a response reports a version newer
  than the local cache, pull before continuing.
- **The web app's embedded agent** runs in a **Nook-owned harness**, so Nook **preloads**
  it with the cache at startup and refreshes it in-process — no reaching into a foreign
  environment. Same cache contents, same operate-Nook skill; the pull machinery is the
  external-agent path only.
- **Skills are checked once, at load (startup).** They are loaded-once by the harness
  and cannot hot-swap, so checking more often than they can be applied is waste; a new
  skill version takes effect on the **next** load.
- **Tenets are checked at startup and on every response's version stamp.** They are
  read content at the moment of action, so a mid-session pull is immediately usable —
  this is what lets a teammate's new project-wide tenet reach a long-running agent
  before its next action.
- The external reads/pulls are exposed as MCP **resources** (`nook://project/tenets`),
  read-only — not tools.
- Optional: `resources/updated` notifications for external clients that support
  subscriptions, giving a running agent mid-session push. An enhancement, not relied
  upon.

**Team & branching.**
- The whole team converges on Nook's canonical truth; a project-wide tenet reaches
  every member and every branch. **Tenet branch-variance is not supported** — there is
  one canonical tenet set per project.

**Advisory in v1.**
- Tenets are injected into context and honored by the agent, not mechanically enforced
  (§2.4). The update-check is likewise best-effort; the response version-stamp is the
  backstop that keeps re-surfacing staleness. Hard gating — refusing a stale agent, or
  enforcing a checkable tenet subset — is deferred.

## Retired (considered, deliberately not done)

- **Tenets/skills in the code repo, branched with the source.** The team story (one
  canonical set, guaranteed reach) won over per-branch variance.
- **Skills as Nook-served MCP tools/prompts.** Skills are local and agent-run; Nook
  serves the *operations* skills call, plus distribution. (Reverses the earlier §5
  "skills as tools and prompts.")
- **A server-side composition engine.** Composition is agent-side in v1.
- **A dedicated DB-backed tenet store.** Tenets are markdown; the git artifact store
  already gives versioning/diff/history — a second versioning system would contradict
  §3.1.
- **`skill` as a document kind.** Skills are system-level, not project artifacts.

## Open / deferred (intentionally later)

- **Tenet file format** — freeform prose vs a small structured form (id + statement +
  optional machine-checkable rule). Deferred until gating is real; advisory prose
  suffices for v1.
- **Tenet scope beyond project** (release/epic overrides, conflict resolution) —
  deferred; project-level only in v1.
- **The three skills' output structure** — the section sets they fill are a
  development-time concern, not designed here ([07](./07-document-templates.md)).
- **Server-side inference** and a **tenet gating/validation engine** — designed-for,
  deferred (§9).

## Depends on / feeds

- Project tenets are documents under [02](./02-document-layer.md)'s store and API.
- Skills fill the templates, whose section sets are settled in development
  ([07](./07-document-templates.md)).
- Distribution and the resource surface ride [01](./01-interface-contracts.md).
