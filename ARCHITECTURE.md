# Nook — Architecture

## About this document

This is the technical reference for Nook: what it is, the concepts it is built
around, how the system is structured, and the principles that hold that structure
together. It is written to be read top to bottom by someone new to the project —
concepts first, mechanics second.

It is a living document and describes the **intended** architecture; the codebase
is still being stood up. Its authority is layered. The **design** — the topology,
data ownership, flows, and principles below — is settled: work doesn't stray from
it, and changing it means changing the owning spec first. The **technology stack**
(§7) is **directional, not mandated**: named libraries and SDKs are the best
current picks, recorded so work has a default, and each is validated — possibly
replaced — by discovery work during execution ([`execution/`](./execution/))
before code commits to it; a reversed pick updates §7 and is recorded in
Appendix A. Where a shape has a concrete counterpart already in the
repo, this document links to it (notably the database schema, which lives as a
Liquibase changelog under [`db/`](./db/)). The **detailed design specs** — one per
area, turning this architecture into buildable requirements, each tracking its own
open decisions — live under [`docs/`](./docs/); see [`docs/README.md`](./docs/README.md)
for the roadmap and build order. A compact record of the decisions behind the design
— including the alternatives that were weighed and rejected — is kept in
[Appendix A](#appendix-a--decisions-and-alternatives-considered) so the body can
stay conceptual.

Status: settled in design. Milestone 1 is under way — the core service's
structure layer (schema, write path, read path) is built; the adapters, the
document layer, and the artifact store are not.

---

## 1. Overview

Nook is an **agent-native project-management and artifact repository**. It is the
place where an AI agent and a human collaborate to carry an idea from a one-line
epic to a fully specified, ready-to-build plan — and where the documents produced
along the way live, versioned, for the life of the product.

Two concerns sit side by side, and keeping them distinct is the spine of the whole
design:

- **Structure** — the project-management index: projects, releases, epics, tasks,
  their statuses and dependencies. This is what you *query* ("what is ready to work
  on?"). It is small, relational, and changes often.
- **Documents** — the payload: manifestos, RFCs, design docs, and per-task
  implementation plans. This is what the work actually *is*. It is prose, it is
  large, it is reviewed and iterated, and its history matters.

Two audiences act on those concerns through two surfaces. **Agents** drive Nook
over the **Model Context Protocol (MCP)**; **humans** drive it through a **web UI**.
Both operate on the same underlying stores, so a plan an agent drafts and a plan a
human edits are the same artifact.

The useful mental anchor is not JIRA or Asana — those model only the index. It is
closer to "a spec-kit constitution and workflow, productized: a queryable task
layer, a versioned document store, governing rules the agent must honor, and a
human UI over all of it."

### Non-goals

Nook sits near a crowd of adjacent tools, and it is defined as much by what it
leaves to them as by what it does. The following are deliberately **out of scope** —
not deferred features, but boundaries of what Nook is:

- **Nook is not a code host or version-control UI for the product's source.** Its
  git repository holds *artifacts* (manifestos, plans, RFCs), versioned
  independently of the code; the product's source lives in its own repository on
  GitHub/GitLab, which Nook references but does not host, branch, or review.
- **Nook is not a CI/CD, build, or test runner.** It never compiles, tests, or
  deploys anything. Tenets such as "use Swift Testing" are guidance the agent
  honors, not checks Nook executes.
- **Nook is not the runtime that does the product work.** It structures the work and
  drafts the documents — and to that end the web app may run an embedded *authoring*
  agent (§6, Appendix A) — but it never carries the plan out: the connected agent (and
  the human) build, test, and write the product's code elsewhere. Nook is the workshop
  and the record, not the builder.
- **Nook is not a general-purpose wiki or knowledge base.** Every document is
  anchored to a project — and optionally to a project item within it; there is no
  free-floating document space competing with Confluence or Notion.
- **Nook is not a people- or resource-management tool.** It has no estimates,
  sprints, burndown, velocity, or workload assignment. It models the *structure* of
  work and its *readiness*, not the coordination of a team.

---

## 2. The domain

### 2.1 Hierarchy

Work is organized in a shallow, deliberate hierarchy. Epics, tasks, bugs, and chores
are one entity — a **project item** — told apart by a `type`: an epic is a container,
and a task, bug, or chore is a leaf.

```
Nook instance             a single deployment
└─ Project                the top-level unit of work; one instance manages many
   ├─ Release             an OPTIONAL grouping of epics — a milestone bucket
   └─ Project item
      ├─ Epic             a container: a body of work; may be assigned to a release
      └─ Task/Bug/Chore   a leaf: an atomic, vertical unit of work (parent epic is OPTIONAL)
```

A **project** is the top level; a single Nook instance manages many of them. A
**project item** is one unit of work carrying a **type** — `epic`, `task`, `bug`, or
`chore`. An **epic** is a coherent body of work that contains leaves. A **task**,
**bug**, or **chore** is an atomic, vertical slice, small enough to finish in one
focused session — which is why containment stops at one level: a leaf never holds
another leaf, so there are no subtasks. A leaf usually sits under an epic, but its
parent is **optional**: it always belongs to a project and may hang directly off it.
The type is the whole classification — a **bug is a project item of type `bug`**,
reusing the same plan, status, and dependency machinery as any other leaf, which is
what lets a bug live outside any epic. A **release** is an optional grouping of epics —
a milestone view, not a rigid parent, so an epic can exist without belonging to any
release.

Leaves may declare that they are **blocked by** other leaves. This is the one
relationship modelled beyond containment, and it exists because "what is ready to
work on?" — a headline query — is meaningless without it. A leaf is *ready* when it
is open and every item blocking it has been resolved.

### 2.2 Documents

Every project item can carry documents. Some are structural and expected — an epic's
**manifesto**, a leaf's **implementation plan** (its analysis, background, high-level
approach, caveats, and test plan). The rest come from a small **catalog of templated
types** — PRDs, specs, RFCs, ADRs, design docs, discovery reports, test plans,
retrospectives, and a per-project **architecture overview** (the living map of how
the system hangs together) — that a project adopts to fit its methodology
([docs/07](./docs/07-document-templates.md)), plus freeform **attachments**: notes,
scratch material, anything. A document is always project-scoped and may additionally attach
to one item; a project's tenets and README are project-level documents with no item.
Documents are prose, they are versioned, and they are the real output of working in
Nook.

Crucially, document *content* is never stored in the project-management database.
It lives in git (see §3). The database holds only a pointer to each document.

### 2.3 Skills

**Skills** are the transforms that move the workflow forward — splitting an epic
into tasks, generating a task's implementation plan, authoring an epic's manifesto.
Nook ships the core skills, but a project can **layer** its own conditions and
refinements on top of them rather than replacing them. A layer only ever *adds*
("…and every task must include a rollback step"), so a project can sharpen a skill
to its needs without forking it and drifting away from later improvements to the
shipped base (the base stays central and composes in, so those improvements keep
flowing).

Skills are **system-level, not project artifacts**: Nook holds and versions them,
and **distributes** them into the agent's environment as a local cache the agent
runs. They are **agent-first** — an agent's own model does the reasoning over the composed
instructions and calls Nook's operations to persist results, whether that agent is an
external one connected over MCP or the agent embedded in the web app's authoring UI
(§6), whose Nook-owned harness comes up with the skill cache preloaded. Nook needs no
model of its own to function, though the design leaves room for optional server-side
inference later (§9). One shipped base skill is the
**operate-Nook** skill: the operating manual for how and when to call the MCP
server, including reading the project's tenets at startup. Because it is itself a
distributed skill, *how the agent uses Nook* is versioned and updatable, not baked
into a client. The concrete storage, distribution, and invocation model is settled
in [`docs/03`](./docs/03-skills-and-tenets.md).

### 2.4 Tenets

**Tenets** are project-level rules the agent is expected to honor — for example,
"never use XCTest, only Swift Testing." They are the always-on layer the agent reads
whenever it acts. Unlike skills, tenets **are** project artifacts: **Nook is their
canonical, versioned source of truth** (project tenets are project-owned documents in
the git artifact store), and each agent keeps an ephemeral local **copy** it reads at
the moment of action. That copy is pulled, never committed and never branched — so the
whole team converges on one canonical tenet set per project rather than tenets varying
by code branch. In v1 they are **advisory**: injected into context rather than
mechanically enforced, exactly as a spec-kit constitution is. They are authored in a
form that a future validation engine could enforce for a checkable subset, but that
enforcement is not built yet. Storage and distribution are settled in
[`docs/03`](./docs/03-skills-and-tenets.md).

### 2.5 The workflow

The concepts above compose into the workflow Nook exists to support:

1. **Create an epic** — a name and a description.
2. **Author its manifesto** — a skill helps the agent and human write the epic's
   guiding document.
3. **Split the epic into tasks** — a skill breaks it into vertical, atomic tasks.
4. **Generate a plan per task** — a skill produces the task's full implementation
   plan, which can be regenerated wholesale or edited by hand.

Throughout, tenets constrain what the agent may propose, and every document
produced is versioned in the artifact store.

---

## 3. Architecture

### 3.1 Two stores, split by data type

The foundational decision is that structure and documents live in **two different
systems of record**, partitioned so that neither ever owns the other's facts:

- A **relational database** is authoritative for *existence and structure* — "task
  123 exists, belongs to epic 7, is in progress" is the database's fact.
- **Git** is authoritative for *content* — what an implementation plan actually says
  is git's fact.

This split is not a compromise between two storage styles; it matches the shape of
the problem. The document-shaped concerns Nook has — iterating a plan, reviewing an
agent's draft as a diff, keeping full history — are precisely what git provides for
free, and re-implementing them inside a relational database would mean rebuilding
git, badly. Conversely, the "list, filter, sort across many items" queries the UI
needs on day one are what a relational database is for, and hand-rolling them over a
tree of files would degrade as the repository grows. Each store does only what it is
genuinely best at.

### 3.2 Documents are versioned, not branched

Documents need *versioning* — history, diffs, rollback — but they explicitly do
**not** ride the code repository's *branches*. These are different git features, and
Nook wants only the first.

The distinction matters because coupling document branches to a global structure
database produces unavoidable inconsistency: a task exists globally the moment it is
created, but a branch-local document is invisible until merged, so the database ends
up pointing at content that isn't there yet. Nook sidesteps this entirely by giving
the artifact store its own **forward-only history**, independent of any code branch.
There is nothing for the global database to fall out of sync with, and the pointer
it stores stays trivial — a path and a version, with no branch coordinates or
draft-versus-merged lifecycle to track.

### 3.3 A core service with thin adapters; the ArtifactStore seam

A Nook instance spans many projects and is delivered as a **core service** with two
**thin adapter apps** in front of it. The **core service** owns everything that
touches state — Postgres, the git document store, and the single write path — and
exposes an **internal RPC API** (localhost in v1). The **web app** (the human surface
— an RPC API and the UI — which also hosts an embedded authoring agent, §6) and the
**MCP server** (the external-agent surface) are separate deployables that **translate
their protocol into core-service calls and hold no store access of their own** — the
web app's embedded agent persists through those same calls, so it is a client of the
core service, not a second store owner. Because one instance serves many projects, "which
project am I acting on?" is answered by **configuration on the connection**, not by
the agent's working directory.

Concentrating all state in the core service is what makes the single-writer
guarantee concrete: exactly one process ever touches a project's git repository, so
there is no cross-process contention to coordinate. The core serializes its own
**git** writes to a given project with a simple **in-process, per-project mutex** —
no distributed lock, no lock files. (An earlier design made the web app and MCP
server peers that both wrote through a shared library; that required a
cross-process lock on the git clone, and centralizing the writer removed it — see
Appendix A.)

Structure writes take their turns in the database instead, by locking a row:
each one opens a transaction and selects its scope's row `FOR UPDATE` before
reading anything it will write against — the project's own row for writes inside
a project, and a dedicated row for the one scope no row represents, the
instance-wide space of project handles. A lock the database holds, rather than
one this process holds, is what keeps the guarantee true of the store rather
than of a particular process: it is released by the transaction ending, it
covers the reads a decision was made on, and a second core process would queue
behind it rather than proceed in parallel. It is also plain standard SQL, which
an engine-specific advisory lock would not have been. See
[docs/05](./docs/05-project-and-ops.md).

Nothing above the store talks to git or the filesystem directly. Everything goes
through an **`ArtifactStore`** interface, which exposes reading, writing, history,
and the granular editing operations described in §4.2. Its implementation is
**git-backed**: it works against a local working clone, and **syncing to a hosted
remote is a configurable behavior** on top of that. In production a remote is
configured, so writes are pushed and pulled to GitHub or GitLab; in development,
tests, and offline use no remote is configured, and the same store operates purely
on a local repository. The underlying git work — commit, read a version, diff,
`git mv` on rename — is identical either way; the remote is a sync detail, not a
separate design. What matters is the interface seam: callers are oblivious to
whether a remote is involved or where the bytes physically are. Below the store, a
project's repository lives in a pluggable **`RepoBackend`** (local filesystem in v1;
object stores such as S3 later), and a new project's repo may be **created fresh or
cloned from an existing source** — details in [`docs/05`](./docs/05-project-and-ops.md).

### 3.4 How the pieces fit

```
   Human ─▶  :web-app  (RPC API + UI + agent)  :mcp-server (Java MCP SDK)   ◀─ Agent
                    │                                   │
                    │  internal RPC (localhost)         │  internal RPC
                    └────────────────┬──────────────────┘
                                     ▼
                     ┌───────────────────────────────────────┐
                     │           core service                 │
                     │  sole store owner · single write path  │
                     │  · git: in-process per-project mutex    │
                     │  · structure: locked row per scope      │
                     │  ┌──────────────────┐  ┌────────────┐  │
                     │  │  Structure (SQL) │  │ArtifactStore│ │
                     │  └────────┬─────────┘  └──────┬──────┘  │
                     └───────────┼───────────────────┼─────────┘
                                 ▼                    ▼
                        ┌──────────────────┐  ┌──────────────────┐
                        │    PostgreSQL    │  │   RepoBackend    │
                        │ (Liquibase-      │  │  (git; FS in v1, │
                        │  managed schema) │  │  ± remote sync)  │
                        └──────────────────┘  └──────────────────┘
```

The web app and the MCP server are thin adapters: they reach state **only** by
calling the core service's internal RPC API, and never open the database or the git
repository themselves. The core service is therefore the one and only writer, which
makes the next principle almost trivially true.

---

## 4. Design principles

### 4.1 One authorized write path, and a consistency model that tolerates the rest

Because there is no transaction that spans a database and git, the two stores are
kept coherent by discipline rather than by a distributed commit: **every mutation
routes through the core service's single write path**, which performs the git write
and the database write in a fixed order (content to git, then the
`(path, version)` pointer to the database). Making the core service the sole store
owner is what makes this literal rather than aspirational — one process is the only
writer, so within it a per-project mutex is all that's needed to serialize git
writes; there is no cross-process lock.

The **database's `current_version` pointer is the authority** for what is current.
The fixed order matters because of that: git is written first, then the pointer is
advanced to the new commit. If the pointer write fails, the pointer still names the
*previous* commit, so the previous version simply remains the truth and the new
commit is an unreferenced object git can garbage-collect — no compensating action,
no "ahead" state to reason about. (Reads therefore resolve content by the DB's
`current_version`, not by git `HEAD`.)

The design does not *rely* on nothing ever writing out of band, because on a
real system it cannot. Instead it is arranged so that drift is survivable:

- With documents reachable only through the core service, the agent has **no door
  to the content except the write path** — the "edit the files directly" hazard is
  structurally absent rather than merely discouraged.
- Even a hypothetical out-of-band change is recoverable, because git — not the
  database — is the source of truth for content. The worst outcome is a **stale
  index**, never lost or corrupted data. This recoverability is the entire payoff
  of putting content in git.
- A reconciliation pass (`fsck`) compares the git repository against the versions the
  database has recorded and re-absorbs any drift. It is deliberately shallow in v1
  but present by design.

### 4.2 Editor-grade document access

Documents are edited through **granular, addressable** operations, never by reading
a whole document and writing a whole document back. Whole-document rewrites waste
tokens on the agent channel and invite lost updates.

The store therefore offers section-level and range-level reads and edits —
replacing a section, inserting before or after an anchor, appending, applying a
patch. Edits are addressed by **stable anchors such as heading paths**, not by raw
line numbers, because line numbers drift the instant anything above them changes
while headings survive. This granularity is a property of the *interface*, not of
storage: git still keeps whole-file snapshots (it deduplicates), so the benefit is
that the agent sends a small patch rather than a full rewrite.

### 4.3 Identity and paths

Every entity has a **UUID** as its stored, permanent identity — it never changes and
survives renames — plus a mutable, human-readable **slug** used in paths, URLs, and
display. Slugs are unique within their scope — a project slug within the instance, and
an **item slug within its project** across all item types — so a slug reference
resolves to exactly one item and paths nest cleanly without a global slug namespace.

Because the artifact repository is meant to be browsed by humans on GitHub or
GitLab, git paths are **slug-based and readable**. Each project is its own repo
(§3.3, [docs/05](docs/05-project-and-ops.md)), so paths are relative to that repo's
root: `epics/<slug>/tasks/<slug>/…` for a leaf under an epic, `tasks/<slug>/…` for a
project-level leaf (leaves of any type live under `tasks/`). The full on-disk layout
is [docs/02](docs/02-document-layer.md)'s job.
The trade-off is that renaming an entity rewrites its path; this is handled as a
`git mv` through the single write path, and git follows the rename so history is
preserved.

---

## 5. Interfaces: the MCP surface

MCP offers capabilities distinguished by *who initiates their use*, and Nook uses
two of them. The surface serves **state and content**, not skills — skills run
agent-side (§2.3) and *call* this surface:

- **Tools** are model-initiated — the agent calls them mid-reasoning. Nook exposes
  structure operations, queries, and the editor-grade document operations as tools.
  Every mutation of state goes through them.
- **Resources** are application- or user-attached read-only data. Nook exposes
  tenets (`nook://project/tenets`) and per-entity documents as resources. The tenets
  resource is also the **pull surface** by which an agent refreshes its local tenet
  copy from Nook's canonical version.

Skills are **not** an MCP capability Nook serves. They are distributed into the
agent's environment (§2.3) and executed by the agent's harness; a skill returns
instructions the agent then carries out by calling the tools above. To keep the
agent's caches current, Nook **stamps its tenet/skill version on operation responses**
(MCP tool results, and RPC responses for the web-embedded agent); the operate-Nook
skill's rule is to pull a newer version when it sees one — tenets at the moment of
action, skills at the next load. (The web app's own harness, being Nook-owned, is
preloaded and refreshed directly; §6.) The project is bound at the connection
level, so tools take item and release references relative to the current project
rather than repeating a project id on every call.

The initial surface (signatures firm up against the schema):

- **Structure tools** — `create_item(type, …)`, `update_item` (the one way an item
  changes, its release and its whole blocker set included), `create_release`,
  `update_release`, `get_item`, `list_items(filter)`, and `delete_item`, plus the
  instance-level `create_project` / `get_project` / `list_projects` /
  `delete_project`. "What is ready to work on" is not an operation of its own: it
  is one `list_items` call combining the leaf types, status `todo`, and nothing
  blocking ([docs/01](docs/01-interface-contracts.md)).
- **Document tools** — `read_doc(ref, {section?})`, `doc_outline`, `write_doc`
  (whole replace / regenerate), `replace_section`, `prepend_to_section`,
  `append_to_section`, `apply_patch`, `doc_history`. Full contracts in
  [docs/02](docs/02-document-layer.md).
- **Resources** — `nook://project/tenets` (canonical tenets + pull surface),
  per-entity documents.

The core skills (`split_epic`, `generate_task_plan`, `author_manifesto`) are local
skills, not tools; `split_epic`, for instance, drives repeated `create_item` calls.
See [docs/03](docs/03-skills-and-tenets.md).

---

## 6. Data model

Structure is defined as a **Liquibase changelog** under
[`db/changelog/`](./db/changelog/); see [`db/README.md`](./db/README.md) for the
full rationale. The model reflects the concepts above and encodes several
invariants directly in the schema:

- **Every document is project-scoped** — it always carries `project_id` — and an
  optional `item_id` attaches it to one project item, composite-FK'd to the same
  project. This keeps real foreign-key integrity (something a polymorphic
  `(entity_type, entity_id)` pair cannot) and makes "all documents in a project" a
  direct query. A project's **tenets** (kind `tenet`), its README, its **ADR
  stream**, and its **architecture overview** are project-level documents with
  `item_id` NULL (ADRs and the overview are constrained to project level — one
  decision log and one living map per project, enforced in the write path;
  [docs/02](./docs/02-document-layer.md)); a manifesto or plan carries its item —
  for `plan` that is a rule, not a habit (a plan must attach to an item; same
  write-path enforcement). Skills are
  system-level, not documents (§2.3, [docs/03](./docs/03-skills-and-tenets.md)).
- **Release membership is enforced structurally**: a composite foreign key ties an
  item's `release_id` to its own project, so an epic literally cannot be assigned to
  another project's release.
- **Every item is owned by its project; its parent is optional.** The item carries
  `project_id` directly (so a project-level leaf still has a project) and a nullable
  `parent_id` (a self-reference); a composite foreign key ties the parent to the item's
  own project, same as releases. Every cascade in the schema starts at `project` and
  nowhere else — the parent link is integrity-only, `NO ACTION` — so what a delete
  reaches is read off one table's foreign keys rather than traced through a graph of
  them. An epic's children and the documents attached to them are removed by the
  write path, which states that reach instead of deriving it. The `type`
  (`epic` / `task` / `bug` / `chore`) is the whole
  classification — a bug is an item of type `bug`, not a separate entity — and
  containment (only epics parent; leaves never nest) is enforced by the single-writer
  core service.
- **`item_dependency` is a join table**, allowing a leaf several blockers (including
  cross-epic) while keeping the edge type minimal; blockers are leaves in the same
  project (write path).
- **The document pointer is `(path, current_version)`** — the current git commit
  only. History lives in git and is read through the `ArtifactStore`, never
  duplicated in the database.
- **Readiness is derived, not stored** — and the model carries no notion of it at
  all. "Ready" is what a caller gets by asking a listing for leaves that are
  `todo` with nothing unfinished holding them up, computed from the dependency
  edges when the question is asked, so it can never drift from actual blockers.
  (The first changelog also builds a `ready_item` view, which no operation now
  reads.)
- **The project is the tenancy root.** It carries an `owner_subject` (the owning
  subject, distinct from the `created_by` audit actor), single-valued in v1. Every
  other entity is already project-scoped, so per-owner isolation is a filter later, not
  a migration (§8, [docs/08](./docs/08-deployment-and-cloud.md)).

The schema is **plain standard SQL** — tables, plain unique / foreign-key / check
constraints, and a view, with no partial/filtered indexes or other engine-specific
features — kept as discipline, not as a promise of portability. **PostgreSQL** is
the sole supported engine ([ADR-1](./architecture/adrs/adr-1.md)): production,
development, and tests all run it, tests via embedded PostgreSQL binaries rather
than SQLite. Slugs are unique **per project** (`(project_id, slug)` for items and
releases alike), which needs only an ordinary UNIQUE constraint.

**Status vocabulary.** Project items share one status set:
`todo → in_progress → done` (or `cancelled`) — an epic's initial `todo` is what a UI
may show as "Draft." Releases have their own: `planned → in_progress → released` (or
`cancelled`). "Blocked" is intentionally *not* a stored status — it is derived (see
above).

---

## 7. Technology stack

**Directional, not mandated** (see "About this document"): this table records
the current best picks so work has a default, at implementation altitude. A
pick is validated by discovery during execution before code commits to it, and
a reversal updates this table and lands in Appendix A — the MCP SDK row is the
precedent (Kotlin SDK → Java SDK, milestone-1 discovery). The **Modules** row
is the exception: it restates settled design (§3.3), not a library pick.

| Layer            | Choice                                     | Notes |
| ---------------- | ------------------------------------------ | ----- |
| Modules          | core service + `:web-app` + `:mcp-server` (+ shared contract) | The **core service** owns the stores and the single write path and exposes an internal RPC API; the web app and MCP server are **thin adapter apps** that call it and hold no store access. A shared contract library carries the DTOs. |
| Backend          | Kotlin + Ktor                              | Statically typed; plays to the team's strengths. Serves both the core service's internal RPC API and the web app. |
| Data access      | JetBrains **Exposed** (core service only)  | Apache-2.0, Kotlin-native, and needs no code-generation build step; guard schema drift against Liquibase with a startup/test check. (jOOQ was considered but adds a codegen step for little gain at this scale.) |
| Agent interface  | Official **Java** MCP SDK (`:mcp-server`)  | Consumed from Kotlin; chosen over the pre-1.0 Kotlin SDK for its GA, conformance-tested streamable-HTTP server. Project selected by configuration, not working directory. |
| Structure store  | SQL — **PostgreSQL** only                  | Schema managed by **Liquibase** in plain standard SQL; PostgreSQL is the sole supported engine, tests included — the test suite runs embedded PostgreSQL binaries ([ADR-1](./architecture/adrs/adr-1.md)). See [`db/README.md`](./db/README.md). |
| Document store   | Git behind `ArtifactStore`, over a pluggable `RepoBackend` | Git-backed; `RepoBackend` is local filesystem in v1 (S3/others later). Syncing to a hosted remote (GitHub/GitLab) is configurable. |
| Web UI           | React + TypeScript (strict)                | TypeScript in strict mode is statically typed — not the dynamic-language behavior being avoided. An all-Kotlin UI (Compose-for-Web / Kotlin-JS) was judged too immature for a browser UI. |

Only the core service touches the stores; the UI and agents reach structure and
document content/history by calling it.

---

## 8. Security and tenancy

Version 1 is **single-user and localhost-bound, with no authentication**. Every
mutation nonetheless carries a nominal **actor** (recorded as `created_by` /
`updated_by`), so introducing real users and permissions later is additive rather
than a rewrite. That actor is stored as a stable **subject** string, shaped like a
sign-in identity: a configured constant (`system`) on a single-user/localhost
instance, and the subject the edge gate asserts (e.g. an OIDC `sub`) once Nook is
reachable over the internet — so turning on real accounts swaps *where the subject
comes from*, not the columns. There is no users/accounts table in v1.

**Tenancy root.** Each **project** additionally carries an **`owner_subject`** — the
subject that owns it — kept distinct from `created_by` (audit is "who made this row";
ownership is "whose tenancy this is"). It is single-valued in v1 (one owner), and
because every other entity is already project-scoped, it makes per-owner isolation a
`WHERE owner_subject = …` filter later rather than a schema migration. A separate
account/organization entity above `project`, if Nook is productized, is then purely
additive. Authentication is infrastructure rather than one of the product
surfaces that must be present-but-shallow in v1, and there is no v1 workflow value
in it, so deferring it here is safe. The moment Nook is reachable over the internet
this "no authentication" assumption is revisited — the front door is gated at the edge
and the actor is shaped like a real sign-in; see [`docs/08`](./docs/08-deployment-and-cloud.md).

---

## 9. Scope and roadmap

**Version 1 is a shallow end-to-end slice.** Every part is present — the MCP
endpoint, the structure store, the document store, the two core skills
(epic→tasks, task→plan), tenets, and the UI — and none is deep. The goal is to
prove the whole workflow feels right before deepening any single part; the guiding
rule is to simplify every part rather than skip any.

**Deferred, but designed for:**

- Server-side inference for skills — the skill interface is ready for it (§2.3, §9
  rationale in Appendix A).
- A tenet gating / validation engine — tenets are authored to accept it (§2.4).
- Deeper `fsck` / reconciliation (§4.1).
- Richer task-dependency graphs beyond `blocked_by`.
- Structural audit history (git already versions documents).
- Authentication and multi-user (§8).

**Settled since — resolved in the area specs:**

- **Status transitions** are free within the vocabulary in v1 — no enforced state
  machine, so `done` may reopen and `cancelled` may reactivate; a transition graph is
  deferred until real policy is known ([docs/04](./docs/04-structure-semantics.md)).
- **Cycle prevention for `blocked_by`** *is* enforced in v1: an `update_item`
  supplying a blocker set that would close a loop is rejected
  ([docs/04](./docs/04-structure-semantics.md)).

**Open — at implementation, not design:**

- Firm MCP tool signatures and resource URIs — the surface shape is settled (§5);
  exact types land against the schema at build time.

---

## Appendix A — Decisions and alternatives considered

A compact record of the load-bearing decisions and the options weighed against
them, for traceability. The body above explains the resulting design; this is the
"why not otherwise."

| Area | Decision | Alternatives rejected, and why |
| ---- | -------- | ------------------------------ |
| Storage split | Structure in a relational DB; document content in git (§3.1). | *Pure DB* — would re-implement versioning, diffs, and document storage that git gives for free. *Pure git* — would make every cross-item query a hand-rolled file scan. |
| Doc history | Versioned, forward-only artifact repo, not coupled to code branches (§3.2). | *Docs embedded in the code repo, branching with it* — with a global structure DB this guarantees skew (a task exists globally while its document is branch-local). |
| Topology | Nook is a service; the git-backed `ArtifactStore` is server-managed and synced to a hosted remote (§3.3). | *Colocated `.nook/` working copy embedded in each project checkout* — only justified for a purely local tool, and it reopens the direct-file-edit hazard. |
| App topology | A **core service** owns the stores and the single write path; the web app and MCP server are thin adapter apps calling its internal RPC API. The core is the sole git writer, serialized by an in-process per-project mutex; structure writes serialize on a locked database row instead (§3.3). | *Peer apps over a shared `:core` library* — first chosen, then reversed: two writer processes forced a cross-process lock on the git clone; centralizing the writer removed it. *One combined app* — couples two very different client surfaces. *MCP proxies the web backend* — makes web "primary" and MCP secondary. |
| Data access | JetBrains Exposed — Kotlin-native, no codegen build step (§7). | *jOOQ* — its codegen keeps the schema as the single source of truth, but adds a generation step to the build for little gain at this scale. *Raw JDBC* — untyped, more room for query errors. |
| Consistency | Single authorized write path + git-recoverable drift + `fsck` (§4.1). | *Rely on preventing out-of-band writes* — impossible to guarantee; the design tolerates drift instead. |
| Document API | Granular, anchor-addressed edits (§4.2). | *Read-whole / write-whole* — token-wasteful and lost-update-prone. *Line-number addressing* — drifts on any edit above. |
| Identity | UUID identity + per-project slug; slug-based readable git paths, rename = `git mv` (§4.3). | *UUID-only paths* — unreadable, defeats browsability. *Slug-as-identity* — breaks on rename. |
| Hierarchy | Instance → Project → (Release) → Project item, with `blocked_by` between leaves (§2.1). | *Epic→task only* — leaves "what's ready" unanswerable. *Adding subtasks* — contradicts atomic-task framing. |
| Item model | Epic, task, bug, and chore are one typed `project_item` (single `type` axis; containment in the write path). A document is project-scoped with an optional `item_id` link, not an exclusive project/epic/task arc (§2.1, §6). | *Separate epic and task tables* — duplicate operations and a three-way document owner arc. *Two axes (level + category)* — the type already carries container-vs-leaf; a second column is redundant. *Dynamic / EAV properties (stored property definitions + data types)* — discards the typed columns, FK integrity, and cheap queries §3.1 is built on, to make a fixed, known type set "flexible" it doesn't need to be. |
| Bugs & parent-less leaves | A bug is a project item of `type=bug`; a leaf's parent epic is optional, so a bug can hang off the project directly (§2.1, §6). | *A separate Bug entity* — duplicates the leaf's plan/status/blocked-by machinery. *Forcing every bug under an epic* — needs a catch-all "Bugs" epic, an artificial parent. |
| Catalog shape | Eleven operations: one `update_item` carries every change to an item, including its release and its whole blocker set, and readiness is a combination of listing filters ([docs/01](./docs/01-interface-contracts.md)). | *A dedicated operation per relationship* (`assign_epic_to_release`, `set_item_blocked_by`) — first chosen for "one operation named for the one thing that changes it", then reversed: `create_item` already took a release and a parent as plain fields, so the catalog said a release was a field at creation and an operation at update. *A `get_ready_items` operation* — reversed with them: readiness as its own call bakes a compound rule (leaf, `todo`, unblocked) into the surface, cannot be narrowed to one epic, and re-states as an operation what filters can compose. |
| Skills | System-level, Nook-canonical and versioned; distributed into the agent's environment as a local cache, layered append-only (shipped base + project overlays), agent-first (§2.3, [docs/03](./docs/03-skills-and-tenets.md)). | *Override/replace base* — lets projects drift from upstream. *Skills as project artifacts in the artifact repo* — skills are general operating instructions, not per-project content. *Server-side-only inference* — makes Nook own model keys and become an inference product prematurely. |
| Skill invocation | Local skills the agent runs; a skill returns instructions the agent executes by calling Nook's operation tools (§5, [docs/03](./docs/03-skills-and-tenets.md)). | *Skills as Nook-served MCP tools/prompts* — first chosen, then reversed: a skill whose definition is distributed and agent-run can't also be a server-served tool without Nook reading it back at runtime; the operation tools it calls carry the state. *Server-side composition engine* — unnecessary once composition is agent-side. |
| Web agent surface | The web app hosts an embedded authoring agent whose Nook-owned harness is preloaded with the skill/tenet cache; it runs skills and persists through the web RPC operations, so skills are triggerable from the UI without a second store owner (§3.3, §5, [docs/06](./docs/06-web-ui.md)). | *Web app is human-only, skills agent-only in v1* — would bar authoring-agent flows (author manifesto, split epic) from the human surface. *Web agent as its own MCP client* — a needless hop for an agent co-located with the web app that can call the core directly. |
| Tenets storage & distribution | Nook-canonical, versioned; project tenets are project-owned `tenet` documents in the git artifact store; agents read an ephemeral local copy, pulled (never committed, never branched) or preloaded for the web-embedded agent, refreshed via a version stamp on operation responses (§2.4, §6, [docs/03](./docs/03-skills-and-tenets.md)). | *Tenets in the code repo, branched with source* — reversed for one canonical set per project and guaranteed team reach over per-branch variance. *Dedicated DB-backed tenet store* — rebuilds git's versioning for markdown. |
| Tenets | Advisory in v1, structured for later enforcement (§2.4). | *Gating engine now* — needs a checkable tenet DSL; too much for v1. |
| Database support | **PostgreSQL only** ([ADR-1](./architecture/adrs/adr-1.md)), the schema staying plain standard SQL as discipline — structural rules (per-project slug uniqueness, same-project parent/release/document links via composite FKs) are ordinary UNIQUE/FK, and enum domains (type/status/kind) are SMALLINT codes validated by the application enums (§6). | *SQLite for tests/embedded* — first chosen, then reversed (ADR-1): the changelog cannot even apply on SQLite, and buying it back forfeits the composite FKs and the self-block CHECK (epic 02 discovery). *Whitelisting engines by capability (partial/filtered indexes)* — an earlier choice, dropped once the rules no longer needed partial indexes. *Native `ENUM` types* — not portable and awkward to evolve. |
| Schema tooling | Liquibase changelog (§6, [`db/README.md`](./db/README.md)). | *Per-dialect hand-written SQL* — a variant to maintain per database. |
| MCP SDK | Official **Java** MCP SDK, consumed from Kotlin in `:mcp-server` (§7). | *Kotlin MCP SDK* — first chosen, then reversed (milestone-1 discovery): still pre-1.0 with breaking minors and open streamable-HTTP conformance bugs, while the Java SDK is GA against the current spec with conformance tests in CI. |
| Auth | None in v1, nominal actor carried (§8). | *Full multi-user now* — heavy, no v1 workflow value. |
| Stack | Ktor + Postgres + React/TS (§7). | *All-Kotlin UI (Compose-for-Web / Kotlin-JS)* — too immature for a browser UI. |
