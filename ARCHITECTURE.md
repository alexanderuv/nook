# Nook — Architecture

## About this document

This is the technical reference for Nook: what it is, the concepts it is built
around, how the system is structured, and the principles that hold that structure
together. It is written to be read top to bottom by someone new to the project —
concepts first, mechanics second.

It is a living document and describes the **intended** architecture; the codebase
is still being stood up. Where a shape has a concrete counterpart already in the
repo, this document links to it (notably the database schema, which lives as a
Liquibase changelog under [`db/`](./db/)). The **detailed design specs** — one per
area, turning this architecture into buildable requirements, each tracking its own
open decisions — live under [`docs/`](./docs/); see [`docs/README.md`](./docs/README.md)
for the roadmap and build order. A compact record of the decisions behind the design
— including the alternatives that were weighed and rejected — is kept in
[Appendix A](#appendix-a--decisions-and-alternatives-considered) so the body can
stay conceptual.

Status: settled in design, pre-implementation.

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
- **Nook is not an agent runtime, and it does not do the work.** It structures the
  work and drafts the documents; the connected agent (and the human) carry the plan
  out and write the product's code. Nook is the workshop and the record, not the
  builder.
- **Nook is not a general-purpose wiki or knowledge base.** Every document is
  anchored to a project, epic, or task in the hierarchy; there is no free-floating
  document space competing with Confluence or Notion.
- **Nook is not a people- or resource-management tool.** It has no estimates,
  sprints, burndown, velocity, or workload assignment. It models the *structure* of
  work and its *readiness*, not the coordination of a team.

---

## 2. The domain

### 2.1 Hierarchy

Work is organized in a shallow, deliberate hierarchy:

```
Nook instance          a single deployment
└─ Project             the top-level unit of work; one instance manages many
   ├─ Release          an OPTIONAL grouping of epics — a milestone bucket
   └─ Epic             a body of work in a project; may be assigned to a release
      └─ Task          an atomic, vertical unit of work (epic is OPTIONAL)
```

A **project** is the top level; a single Nook instance manages many of them. An
**epic** is a coherent body of work within a project. A **release** is an optional
grouping of epics — a milestone view, not a rigid parent, so an epic can exist
without belonging to any release. A **task** is an atomic, vertical slice of work;
tasks are intended to be small enough to finish in one focused session, which is why
the hierarchy stops here and there are no subtasks. A task usually belongs to an
epic, but its epic is **optional**: it always belongs to a project and may hang
directly off it. A task also carries a **type** (`feature` / `bug` / `chore`) — a
**bug is just a task with `type=bug`**, which is the case epic-less tasks exist for
(a bug reported against the product need not sit inside any epic).

Tasks may declare that they are **blocked by** other tasks. This is the one
relationship modelled beyond containment, and it exists because "what is ready to
work on?" — a headline query — is meaningless without it. A task is *ready* when it
is open and every task blocking it has been resolved.

### 2.2 Documents

Every epic and task can carry documents. Some are structural and expected — an
epic's **manifesto**, a task's **implementation plan** (its analysis, background,
high-level approach, caveats, and test plan). Others are **attachments** of any
kind: RFCs, design docs, notes. Documents are prose, they are versioned, and they
are the real output of working in Nook.

Crucially, document *content* is never stored in the project-management database.
It lives in git (see §3). The database holds only a pointer to each document.

### 2.3 Skills

**Skills** are the transforms that move the workflow forward — splitting an epic
into tasks, generating a task's implementation plan, authoring an epic's manifesto.
Nook ships the core skills, but a project can **layer** its own conditions and
refinements on top of them rather than replacing them. A layer only ever *adds*
("…and every task must include a rollback step"), so a project can sharpen a skill
to its needs without forking it and drifting away from later improvements to the
shipped base.

Skills are **agent-first**: the connected agent's own model does the reasoning, and
Nook supplies the composed instructions and the tools they call. Nook needs no
model of its own to function, though the design leaves room for optional
server-side inference later (§9).

### 2.4 Tenets

**Tenets** are project-level rules the agent is expected to honor — for example,
"never use XCTest, only Swift Testing." They are the always-on layer that composes
into every skill invocation, so the agent sees them whenever it acts. In v1 they
are **advisory**: injected into context rather than mechanically enforced, exactly
as a spec-kit constitution is. They are authored in a form that a future validation
engine could enforce for a checkable subset, but that enforcement is not built yet.

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
exposes an **internal RPC API** (localhost in v1). The **web app** (human surface:
an RPC API and the UI) and the **MCP server** (agent surface) are separate
deployables that **translate their protocol into core-service calls and hold no
store access of their own**. Because one instance serves many projects, "which
project am I acting on?" is answered by **configuration on the connection**, not by
the agent's working directory.

Concentrating all state in the core service is what makes the single-writer
guarantee concrete: exactly one process ever touches a project's git repository, so
there is no cross-process contention to coordinate. The core serializes its own
writes to a given project with a simple **in-process, per-project mutex** — no
distributed lock, no lock files. Postgres tolerates the core's concurrent
connections natively. (An earlier design made the web app and MCP server peers that
both wrote through a shared library; that required a cross-process lock on the git
clone, and centralizing the writer removed it — see Appendix A.)

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
   Human ─▶  :web-app  (RPC API + UI)          :mcp-server (Kotlin MCP SDK)  ◀─ Agent
                    │                                   │
                    │  internal RPC (localhost)         │  internal RPC
                    └────────────────┬──────────────────┘
                                     ▼
                     ┌───────────────────────────────────────┐
                     │           core service                 │
                     │  sole store owner · single write path  │
                     │  · in-process per-project write mutex   │
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
display. Slugs are unique within their parent (a project slug within the instance,
an epic slug within its project, a task slug within its epic — or within its project
when the task is epic-less), which lets paths nest cleanly without a global slug
namespace.

Because the artifact repository is meant to be browsed by humans on GitHub or
GitLab, git paths are **slug-based and readable**. Each project is its own repo
(§3.3, [docs/05](docs/05-project-and-ops.md)), so paths are relative to that repo's
root: `epics/<slug>/tasks/<slug>/…` for a task under an epic, `tasks/<slug>/…` for an
epic-less one. The full on-disk layout is [docs/02](docs/02-document-layer.md)'s job.
The trade-off is that renaming an entity rewrites its path; this is handled as a
`git mv` through the single write path, and git follows the rename so history is
preserved.

---

## 5. Interfaces: the MCP surface

MCP offers three kinds of capability, distinguished by *who initiates their use*,
and Nook's three concerns map onto them almost exactly:

- **Tools** are model-initiated — the agent calls them mid-reasoning. Nook exposes
  structure operations, queries, the editor-grade document operations, and skills
  as tools.
- **Resources** are application- or user-attached read-only data. Nook exposes
  tenets (`nook://project/tenets`) and per-entity documents as resources.
- **Prompts** are user-initiated templates. Nook also exposes skills as prompts, so
  a human can trigger "split this epic" as a first-class command in clients that
  support them.

Skills are offered as **both tools and prompts** over the same composition engine:
as tools they can be chained autonomously by an agent (split an epic, then plan each
task in one run) and work in every client; as prompts they give humans a cleaner
trigger. The project is bound at the connection level, so tools take epic and task
references relative to the current project rather than repeating a project id on
every call.

The initial surface (signatures firm up against the schema):

- **Structure tools** — `create_epic`, `update_epic`, `create_task`, `update_task`,
  `set_task_blocked_by`, `create_release`, `assign_epic_to_release`, `get_epic`,
  `get_task`, `list_epics`, `list_tasks(filter)`, and `get_ready_tasks()` (open and
  unblocked — the "what is ready to work on" query).
- **Document tools** — `read_doc(ref, {section?})`, `doc_outline`, `write_doc`
  (whole replace / regenerate), `replace_section`, `prepend_to_section`,
  `append_to_section`, `apply_patch`, `doc_history`. Full contracts in
  [docs/02](docs/02-document-layer.md).
- **Skills** (as tools and prompts) — `split_epic(epicId)`,
  `generate_task_plan(taskId)`, `author_manifesto(epicId)`.
- **Resources** — `nook://project/tenets`, per-entity documents.

---

## 6. Data model

Structure is defined as a **Liquibase changelog** under
[`db/changelog/`](./db/changelog/); see [`db/README.md`](./db/README.md) for the
full rationale. The model reflects the concepts above and encodes several
invariants directly in the schema:

- **Documents use an exclusive-arc owner** (exactly one of project / epic / task),
  which preserves real foreign-key integrity — something a polymorphic
  `(entity_type, entity_id)` pair cannot give.
- **Release membership is enforced structurally**: a composite foreign key ties an
  epic's release to the epic's own project, so an epic literally cannot be assigned
  to another project's release.
- **A task is owned by its project; its epic is optional.** The task carries
  `project_id` directly (so an epic-less task still has a project) and a nullable
  `epic_id`; a composite foreign key ties the epic to the task's own project, same as
  releases. The `project → task` cascade is the single delete path (the `task → epic`
  link is integrity-only), which keeps the schema portable to SQL Server. A task's
  `type` (`feature` / `bug` / `chore`) distinguishes a bug without a separate entity.
- **`blocked_by` is a join table**, allowing a task several blockers (including
  cross-epic) while keeping the edge type minimal.
- **The document pointer is `(path, current_version)`** — the current git commit
  only. History lives in git and is read through the `ArtifactStore`, never
  duplicated in the database.
- **Readiness is derived, not stored.** A `ready_task` view computes it from
  dependencies, so a task's readiness can never drift from its actual blockers.

Because the integrity rules depend on **partial / filtered unique indexes** (for
"one manifesto per epic," "one plan per task," and name-uniqueness per owner), Nook
supports only databases that provide them — **PostgreSQL** (primary), **SQLite**
(tests and embedded use), and **SQL Server** — rather than flattening the schema to
a lowest common denominator. MySQL, MariaDB, and Oracle are out of scope for that
reason.

**Status vocabulary.** Epics are `draft → in_progress → done` (or `cancelled`);
tasks are `todo → in_progress → done` (or `cancelled`); releases are
`planned → in_progress → released` (or `cancelled`). "Blocked" is intentionally
*not* a stored task status — it is derived (see above).

---

## 7. Technology stack

| Layer            | Choice                                     | Notes |
| ---------------- | ------------------------------------------ | ----- |
| Modules          | core service + `:web-app` + `:mcp-server` (+ shared contract) | The **core service** owns the stores and the single write path and exposes an internal RPC API; the web app and MCP server are **thin adapter apps** that call it and hold no store access. A shared contract library carries the DTOs. |
| Backend          | Kotlin + Ktor                              | Statically typed; plays to the team's strengths. Serves both the core service's internal RPC API and the web app. |
| Data access      | JetBrains **Exposed** (core service only)  | Apache-2.0, covers the whole DB whitelist incl. SQL Server; guard schema drift against Liquibase with a startup/test check. (jOOQ was rejected: its free edition excludes SQL Server.) |
| Agent interface  | Official Kotlin MCP SDK (`:mcp-server`)    | Project selected by configuration, not working directory. |
| Structure store  | SQL — **PostgreSQL** primary               | Schema managed by **Liquibase**; supported engines whitelisted by capability (Postgres / SQLite / SQL Server). See [`db/README.md`](./db/README.md). |
| Document store   | Git behind `ArtifactStore`, over a pluggable `RepoBackend` | Git-backed; `RepoBackend` is local filesystem in v1 (S3/others later). Syncing to a hosted remote (GitHub/GitLab) is configurable. |
| Web UI           | React + TypeScript (strict)                | TypeScript in strict mode is statically typed — not the dynamic-language behavior being avoided. An all-Kotlin UI (Compose-for-Web / Kotlin-JS) was judged too immature for a browser UI. |

Only the core service touches the stores; the UI and agents reach structure and
document content/history by calling it.

---

## 8. Security and tenancy

Version 1 is **single-user and localhost-bound, with no authentication**. Every
mutation nonetheless carries a nominal **actor** (recorded as `created_by` /
`updated_by`), so introducing real users and permissions later is additive rather
than a rewrite. Authentication is infrastructure rather than one of the product
surfaces that must be present-but-shallow in v1, and there is no v1 workflow value
in it, so deferring it here is safe.

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

**Open questions:**

- Firm MCP tool and prompt signatures and resource URIs — the surface shape is
  settled (§5); exact types land against the schema.
- Legal status *transitions* — the vocabulary is fixed (§6), but which transitions
  are permitted is not yet defined.
- Cycle prevention for `blocked_by` (application-level; deferred).

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
| App topology | A **core service** owns the stores and the single write path; the web app and MCP server are thin adapter apps calling its internal RPC API. The core is the sole git writer, serialized by an in-process per-project mutex (§3.3). | *Peer apps over a shared `:core` library* — first chosen, then reversed: two writer processes forced a cross-process lock on the git clone; centralizing the writer removed it. *One combined app* — couples two very different client surfaces. *MCP proxies the web backend* — makes web "primary" and MCP secondary. |
| Data access | JetBrains Exposed (§7). | *jOOQ* — codegen keeps migrations as single source of truth, but its free edition excludes SQL Server, which is on our whitelist. *Raw JDBC* — untyped, more room for query errors. |
| Consistency | Single authorized write path + git-recoverable drift + `fsck` (§4.1). | *Rely on preventing out-of-band writes* — impossible to guarantee; the design tolerates drift instead. |
| Document API | Granular, anchor-addressed edits (§4.2). | *Read-whole / write-whole* — token-wasteful and lost-update-prone. *Line-number addressing* — drifts on any edit above. |
| Identity | UUID identity + per-parent slug; slug-based readable git paths, rename = `git mv` (§4.3). | *UUID-only paths* — unreadable, defeats browsability. *Slug-as-identity* — breaks on rename. |
| Hierarchy | Instance → Project → (Release) → Epic → Task, plus `blocked_by` (§2.1). | *Epic→task only* — leaves "what's ready" unanswerable. *Adding subtasks* — contradicts atomic-task framing. |
| Bugs & epic-less tasks | A bug is a task with `type` (`feature`/`bug`/`chore`); a task's epic is optional, so a bug can hang off the project directly (§2.1, §6). | *A separate Bug entity* — duplicates the task's plan/status/blocked-by machinery. *Forcing every bug under an epic* — needs a catch-all "Bugs" epic, an artificial parent. |
| Skills | Layered (shipped base + project overlays + tenets), append-only; agent-first, both-capable (§2.3). | *Override/replace base* — lets projects drift from upstream. *Server-side-only inference* — makes Nook own model keys and become an inference product prematurely. |
| Skill invocation | Both MCP tools and prompts (§5). | *Prompts only* — an autonomous agent can't chain a prompt as a reasoning step. *Tools only* — loses the clean human trigger. |
| Tenets | Advisory in v1, structured for later enforcement (§2.4). | *Gating engine now* — needs a checkable tenet DSL; too much for v1. |
| Database support | Whitelist engines with partial/filtered unique indexes (§6). | *Lowest-common-denominator schema* — would push integrity rules out of the schema into app code. |
| Schema tooling | Liquibase changelog (§6, [`db/README.md`](./db/README.md)). | *Per-dialect hand-written SQL* — a variant to maintain per database. |
| Auth | None in v1, nominal actor carried (§8). | *Full multi-user now* — heavy, no v1 workflow value. |
| Stack | Ktor + Postgres + React/TS (§7). | *All-Kotlin UI (Compose-for-Web / Kotlin-JS)* — too immature for a browser UI. |
