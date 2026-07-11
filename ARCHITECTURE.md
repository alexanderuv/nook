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

## 7. Hierarchy

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

## 8. Skills

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

**Who runs the model? Agent-first, both-capable.** In v1 the *connected agent's*
model does the reasoning; Nook ships the skill definitions and the MCP tools they
call, and needs no API keys of its own. The skill interface is designed so that
**server-side inference** (Nook calls an LLM to power a UI "generate" button) can
be added later behind the *same* interface, without reworking skills.

---

## 9. Tenets

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

## 10. Stack

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

## 11. v1 scope

**Shallow end-to-end.** Every part present, none deep: MCP endpoint + structure
store + document store + the two core skills (epic→tasks, task→plan) + tenets +
UI. The goal is to prove the full workflow feels right before deepening any single
part. Simplify every part rather than skip any.

**Deferred (not rejected):**

- Server-side inference for skills (§8) — interface is ready for it.
- Tenet gating/validation engine (§9) — tenets are authored to accept it.
- `fsck`/reconciliation depth (§5) — present but minimal in v1.
- Richer task dependency graphs beyond `blocked_by` (§7).
- Status-change audit history (git already versions documents; structural history
  is a later nicety).

---

## Open questions

- Concrete Postgres schema for structure (projects/releases/epics/tasks/deps) and
  the `(path, version)` doc pointer.
- Identity: **UUID stored** as the real identity, with a mutable human **slug** for
  paths/URLs and display. (Decided; schema TBD.)
- Exact MCP tool surface and resource list.
- Skill layer file format and deterministic merge order.
- Auth / multi-user (single-user assumed for v1; the service shape doesn't
  preclude it).
