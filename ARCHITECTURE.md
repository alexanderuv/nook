# Nook — Architecture & Technical Direction

This document records the architecture and the **reasoning** behind each decision.
Where a choice has a live tension or a rejected alternative, that's captured too —
so a later reader (human or agent) can tell what was decided *and why*, and knows
which stones were already turned over.

Status: settled, pre-implementation.

---

## 1. What Nook is

An **agent-native project-management and artifact repository**. Two concerns sit
side by side:

- **Structure** — the project-management spine: projects, releases, epics, tasks,
  statuses, dependencies. This is the *index*.
- **Documents** — the payload: manifestos, RFCs, design docs, per-task
  implementation plans. This is what the work actually *is*.

The closest mental anchor is not JIRA; it's "spec-kit, productized, with a
queryable task layer and a human UI." JIRA/Asana describe the *index* only.

Agents interact via **MCP**; humans via a **web UI**. Both act on the same stores.

---

## 2. The core decision: two stores, split by data type

**Decision.** Structure lives in a relational database (Postgres). Document
content lives in git. They are two co-equal systems of record, partitioned so
they never own the same fact.

- **Database is authoritative for existence and structure.** "Task 123 exists,
  belongs to epic 7, is In Progress" is the DB's fact.
- **Git is authoritative for content.** What the implementation plan *says* is
  git's fact.

**Why not one store?**

- *Pure database (docs as blobs/columns):* throws away three things git gives for
  free and that this product leans on hard — **versioning** (the "regenerate /
  iterate a plan" feature *is* version history), **diffs** (the human reviews an
  agent's document as a diff), and **document storage**. Re-implementing those in
  Postgres is rebuilding git, badly.
- *Pure git (structure as frontmatter):* makes every "list / filter / sort across
  many items" query — which the UI needs on day one — a hand-rolled file scan
  that degrades as the repo grows.

Each store is used only for what it is genuinely best at: the DB for
cross-item queries, git for versioned prose. The split is not a compromise; it
matches the shape of the problem.

---

## 3. Documents are versioned, not branched

**Decision.** The artifact repository has **linear, forward-only history**. It is
*not* coupled to the code repository's branches.

This is the single most important decision in the design, and it was reached by
elimination:

- An earlier direction embedded artifacts in the *code* repo (`.nook/` in-tree) so
  they'd travel with the code through PRs. That coupling, combined with a
  **global** structure DB, produces **guaranteed skew**: a task row exists globally
  the instant it's created, but its document lives on a feature branch, invisible
  on `main` until merged. The DB then points at content that isn't there. This
  isn't a bug you fix — it's designed-in.
- The resolution: documents need **versioning**, not **branching**. Those are
  different git features and Nook only wants one. A separate, forward-only
  artifact repo gives version history, diffs, and rollback *without* branch
  coupling — so there is nothing for the global DB to skew against.

Consequence: the doc pointer stored in the DB is trivial — `(path, version)`.
No branch coordinates, no `draft-on-branch` lifecycle, no "UI must read a specific
ref" machinery. All of that complexity was a symptom of branch coupling and is
deleted along with it.

---

## 4. Nook is a service; the artifact store is a hosted git remote

**Decision.** A single Nook instance is a **service** that manages **many
projects**. The artifact store is a **hosted git remote** (GitHub/GitLab) that
Nook connects to and manages (a working clone it pulls/pushes, or via the host
API). There is **no colocated local repo** in the product path.

**Why no local colocation?** The artifact repo is going to be a real hosted repo
regardless. A colocated `.nook/` working copy inside a project checkout was only
ever a local mirror of that — it earns its keep only in a purely-local tool, and
it drags in a serious hazard (see §5). Since a single instance already spans many
projects, "which project?" is **registration/config**, not the agent's current
working directory.

**The `ArtifactStore` seam.** Nothing above the store talks to git or the
filesystem directly. Everything goes through an `ArtifactStore` interface:

```
readDoc(entityRef, name, version?)            -> content
history(entityRef, name)                       -> [versions]
writeDoc(entityRef, name, content)             -> version   // whole-doc replace
// plus the granular editor-grade ops — see §6
```

Implementations:

- `RemoteGitArtifactStore` — the product path; a managed clone of a hosted remote.
- `LocalGitArtifactStore` — points at any local git repo. **Dev/test/offline
  only**, never the product's real path.

The seam is where cloud/self-hosted/local variation lives. Callers are oblivious
to where the bytes are.

---

## 5. The MCP server is the only sanctioned writer

**Decision.** Every mutation — structure and documents alike — routes through the
MCP server, which performs the DB write and the `ArtifactStore` write as the
single blessed path. Direct writes to the store are out of contract.

**The risk this addresses.** If an agent could reach the document files directly
(as it could with a colocated local repo), it might edit them behind Nook's back,
leaving the DB stale — a lost-update / drift problem across two stores that have
no shared transaction.

**Why it's survivable, in layers:**

1. **No local files in the product path (§4).** With artifacts behind a remote
   `ArtifactStore`, the agent has *no door* to the content except the MCP API. The
   "edit the files directly" hazard becomes structurally impossible, not merely
   discouraged. (In cloud mode the agent has no write access to the server's repo
   at all.)
2. **Drift is staleness, never corruption.** Content's source of truth is git, so
   even a hypothetical out-of-band edit that lands as a commit is still valid,
   versioned, diffable content — the DB is merely behind. Worst case is a stale
   index, never lost data. This is the whole payoff of putting content in git.
3. **Detection + reconciliation.** A `fsck`/reindex compares git `HEAD` against the
   versions the DB has recorded and re-absorbs any drift. Shallow in v1, present
   by design.
4. **Write ordering.** There is no cross-store transaction. The single write path
   commits the document to git, then records `(path, version)` in the DB, in that
   fixed order, with reconciliation as the backstop.

---

## 6. The document API is editor-grade

**Decision.** The `ArtifactStore` (and the MCP tools over it) expose **granular,
addressable** document operations. Read-whole-doc-then-overwrite-whole-doc is an
anti-pattern — it burns tokens and invites lost updates.

Operations:

- **Read** — full document, a **section** (addressed by heading path, e.g.
  `## Implementation Approach`), or a range.
- **Edit** — `replace_section(headingPath, content)`, `insert_before/after(anchor,
  content)`, `append_to_section`, `apply_patch(diff)`, `replace_range`.

**Address by stable anchors (heading paths / block markers), not raw line
numbers.** Line numbers drift the moment anything above them changes; heading
paths survive edits. (This is the same lesson editor tooling has learned
repeatedly.)

**Granularity is a wire concern, not a storage concern.** Storage stays
whole-file snapshots in git (git dedups; one edit or one batch = one commit). The
granularity exists so the *agent↔Nook channel* carries a small patch, not a full
rewrite.

---

## 7. Identity & paths

**Decision.** A **UUID** is the stored, real identity of every entity — it never
changes and survives renames. A human **slug** is a mutable label used for paths,
URLs, and display.

- **Slug uniqueness is per-parent.** Project slug is unique within the instance;
  epic slug unique within its project; task slug unique within its epic. This lets
  paths and URLs nest without a global slug namespace
  (`auth-revamp/oauth-epic/token-refresh`).
- **Git paths are slug-based and readable** —
  `projects/<project-slug>/epics/<epic-slug>/tasks/<task-slug>/...` — because the
  artifact repo is meant to be browsed by humans on GitHub/GitLab. The trade is
  that a **rename rewrites the path**, handled as a `git mv` through the single
  write path (§5); git follows the rename so history is preserved. UUID-based
  (stable but unreadable) and hybrid `<slug>-<shortid>` were rejected in favor of
  clean browsability.
- The DB's doc pointer stays `(path, version)`; `path` is derivable from the slug
  chain, so a rename is one DB update plus one `git mv`.

---

## 8. Hierarchy

```
Nook instance
└─ Project              one instance manages many
   ├─ Release           optional grouping of epics — a milestone bucket
   └─ Epic              belongs to a project; optionally assigned to a release
      └─ Task           atomic, vertical unit of work
```

- **Release is an optional grouping, not a hard level.** An epic can exist with no
  release. This keeps it a milestone *view* rather than a rigid parent.
- **Tasks are atomic and vertical** by intent — which is why there are no
  subtasks. Three-level containment was rejected as contradicting that framing.
- **Task dependencies:** a single **`blocked_by`** edge between tasks. "What's
  ready to work on?" is a headline query and is meaningless without it. Kept
  minimal — no richer dependency graph in v1.

---

## 9. Skills

**Decision.** Skills are the transforms that move the workflow forward
(epic→tasks, task→plan). They are **layered**, not overridden.

- A resolved skill = **Nook-shipped base** + **ordered project layers** +
  **active tenets**. The base always anchors it.
- A project contributes *overlays* — extra conditions, refinements, constraints
  ("...and every task must include a rollback step") — never a full copy. So a
  project sharpens a skill without forking it and drifting from upstream
  improvements.
- **Tenets are the always-on layer** — the same composition mechanism, applied to
  every skill invocation.

**Layer format & merge.**

- **Base skills** ship inside Nook (code resources). **Project layers** live in the
  **artifact repo** under `skills/`, versioned like every other artifact — they
  diff and travel with the project.
- A layer is **markdown + frontmatter**: `skill: <base-id>` (which base it
  refines), `order: <int>`, then the overlay body.
- **Merge is append-only, deterministic, base intact.** The resolved prompt is the
  base template unmodified, then a `## Project refinements` section built from
  layers sorted by `order` (filename as tiebreak), then a `## Tenets (must honor)`
  section from active tenets as the always-on final layer. Layers can only *add* —
  they cannot replace base sections — so a project can never silently diverge from
  upstream base improvements.

**Invocation — both tools and prompts** (see §11 for the MCP mapping).

- **As MCP tools:** the agent can call a skill itself and chain autonomously
  (split epic → generate a plan per task in one run); works in every client. This
  is the workhorse path.
- **As MCP prompts:** the same composition engine surfaced as human-facing
  slash-commands for clients that support prompts. Nicer human UX.

**Who runs the model? Agent-first, both-capable.** In v1 the *connected agent's*
model does the reasoning; Nook ships the skill definitions and the MCP tools they
call, and needs no API keys of its own. The skill interface is designed so that
**server-side inference** (Nook calls an LLM to power a UI "generate" button) can
be added later behind the *same* interface, without reworking skills.

---

## 10. Tenets

**Decision.** Tenets are project-level rules the agent must honor (e.g. "never use
XCTest, only Swift Testing"). **Advisory** in v1: exposed via an MCP resource
(e.g. `get_tenets`) and injected into every agent's context; skills instruct the
agent to honor them.

- Advisory ≠ toothless: it's exactly how a spec-kit constitution works, and it's
  present at every skill invocation via the always-on layer (§8).
- **Structured for later enforcement.** Tenets are authored so a future validation
  engine could *gate* a checkable subset (keyword/regex/rule) — advisory now,
  enforceable later, without rewriting them.

---

## 11. MCP surface

**Decision.** Nook's three concerns map onto MCP's three primitives, which differ
by *who initiates use*: **tools** (model-initiated), **resources**
(application/user-initiated), **prompts** (user-initiated).

- **Tools** — structure CRUD + queries + editor-grade document ops + skills.
- **Resources** — tenets (`nook://project/tenets`) and per-entity documents,
  surfaced read-only for attachment.
- **Prompts** — skills again, as human-facing slash-commands (the same composition
  engine as the skill tools; see §9).

**Project is bound at the session/connection level** (the project-by-config
decision, §4), so tools take epic/task refs *relative to* the bound project — no
`projectId` on every call.

Proposed surface (initial; signatures firmed up with the data model):

- **Structure tools:** `create_epic`, `update_epic`, `create_task`, `update_task`,
  `set_task_blocked_by`, `create_release`, `assign_epic_to_release`, `get_epic`,
  `get_task`, `list_epics`, `list_tasks(filter)`, `get_ready_tasks()` (open ∧ not
  blocked — the "what's ready to work on" query).
- **Document tools:** `read_doc(ref, name, {section?|range?, version?})`,
  `write_doc` (whole replace / regenerate), `replace_section`, `insert`,
  `append_to_section`, `apply_patch`, `doc_history`.
- **Skill tools + prompts:** `split_epic(epicId)`, `generate_task_plan(taskId)`,
  `author_manifesto(epicId)`.
- **Resources:** `nook://project/tenets`, per-entity documents.

---

## 12. Stack

| Layer            | Choice                                   | Note |
| ---------------- | ---------------------------------------- | ---- |
| Backend          | Kotlin + Ktor                            | Plays to the team's strengths; statically typed. |
| Agent interface  | Official Kotlin MCP SDK, network endpoint | Project chosen by config, not cwd. |
| Structure store  | PostgreSQL                               | Chosen over SQLite in anticipation of concurrent multi-project load. |
| Document store   | Git (hosted remote) behind `ArtifactStore` | `LocalGit` impl for dev/test only. |
| Web UI           | React + TypeScript (strict)              | TS-strict is statically typed — not the dynamic-language pain being avoided. Rejected all-Kotlin (Compose-for-Web/Kotlin-JS) as immature and ecosystem-hostile for a browser UI. |

The UI reads **structure** from the DB and document **content/history** through the
`ArtifactStore`.

---

## 13. Auth & multi-user

**Decision.** v1 is **single-user, localhost-bound, no auth**. But every mutation
carries a nominal **`actor`** so multi-user is purely additive later, never a
rewrite. Auth is infrastructure, not one of the product surfaces that must stay
shallow-not-skipped — and there is no v1 workflow value in it — so this is the one
place deferring is genuinely safe.

---

## 14. v1 scope

**Shallow end-to-end.** Every part present, none deep: MCP endpoint + structure
store + document store + the two core skills (epic→tasks, task→plan) + tenets +
UI. The goal is to prove the full workflow feels right before deepening any single
part. Simplify every part rather than skip any.

**Deferred (not rejected):**

- Server-side inference for skills (§9) — interface is ready for it.
- Tenet gating/validation engine (§10) — tenets are authored to accept it.
- `fsck`/reconciliation depth (§5) — present but minimal in v1.
- Richer task dependency graphs beyond `blocked_by` (§8).
- Status-change audit history (git already versions documents; structural history
  is a later nicety).
- Auth / multi-user (§13).

---

## Open questions

- Concrete Postgres schema for structure (projects/releases/epics/tasks/deps) and
  the `(path, version)` doc pointer — **up next.**
- Firm MCP tool/prompt signatures and resource URIs (surface shape settled in §11;
  exact types land with the schema).
- Status vocabulary per entity (the set of allowed statuses and their transitions).
