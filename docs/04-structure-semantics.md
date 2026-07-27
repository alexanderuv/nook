# 04 — Structure semantics

**Status:** Settled · **Milestone:** 1 (foundation)

The rules governing the structure store beyond what the schema already enforces:
status transitions, slugs, queries, and dependency integrity. The schema and status
*vocabulary* come from ARCHITECTURE.md §6; this spec settles the *behavior*.

Guiding stance for v1: status is single-user, advisory PM state and fully
recoverable, so we impose the *least* policy that is still correct. A wrong rule is
worse than no rule here — rigor is added later when real usage justifies it.

## Decided

**Hierarchy & identity** (from §2.1, §4.3, §6, §7)
- instance → project → (optional release) → project item; epics (containers) and
  task/bug/chore (leaves) are one entity by `type`, and leaves carry a `blocked_by`
  join edge. Identity is a UUID; slugs are **unique within their project** across all
  item types (and releases alike — `(project_id, slug)`), used in paths/URLs.
- A **leaf always belongs to a project; its parent epic is optional.** A project-level
  leaf hangs directly off the project (see *Item type*). A slug is unique **within its
  project**: identity follows the owning project, so an item keeps its slug when moved
  between epics.

**Item type — a discriminator, not a new entity.**
- A project item carries a `type`: `epic` (a container) or `task` (default leaf) /
  `bug` / `chore`. A **bug is a project item of `type=bug`**, reusing the plan, status,
  and `blocked_by` machinery — no separate Bug entity. Bugs are the motivating case for
  **project-level leaves**: a bug reported against the product need not belong to any
  epic.
- Containment follows from type and is enforced in the write path: an **epic** parents
  leaves and is never itself parented; a **leaf** never nests. `type` changes freely
  **within** the leaf categories (task↔bug↔chore); crossing the container/leaf line is
  refused when it would break containment (an epic with children, or a leaf with a
  parent) or silently orphan an attachment (a leaf touching any dependency edge, an
  epic assigned to a release) — the caller clears the attachment first, explicitly.
  `list_items` may filter by type.

**Status transitions — free within the vocabulary.**
- Any status may move to any other *valid* status; the write path validates only that
  the target is in the vocabulary (project items share one set —
  `todo/in_progress/done/cancelled`; releases
  `planned/in_progress/released/cancelled`).
- No transition graph is enforced in v1 — `done` may reopen, `cancelled` may
  reactivate. A state machine is deferred until real policy is known.
- "Blocked" remains derived, never a stored status: it is a question the listing
  answers from the dependency edges, not a value written on the row.

**Transition side effects — none.**
- Status changes are independent. An epic may be `done` while its leaves are still open
  (the UI surfaces this; it is not blocked). Cancelling an epic does **not**
  auto-change its leaves. No cascades, no guards — explicit over surprising.

**Deletion — the row is removed.**
- Deleting removes the row from the store. There is no mark, no trash, and no
  way back: a deleted row is indistinguishable from one that never existed, and
  that holds by construction rather than by a rule every query has to remember.
  No list returns it, no `get_*` returns it, and a reference naming it — by slug
  or by id — is `not_found`. Deletion is therefore a decision, not a gesture.
- **Restoring is not offered**, and with the row gone there is nothing to
  restore from. Recovering a mistaken delete means a backup, not an operation.
- **Delete is an action, not a status.** It is independent of the status
  vocabulary: an item may be `cancelled` and then deleted, or deleted while
  still `todo`. Cancelling retires work that was real and keeps it in sight;
  deleting removes it.
- **Deleting an epic takes its children with it**, together with the documents
  attached to any of them; deleting a project takes everything in it — releases,
  items, blocker edges, document rows. The whole branch goes at once, which is
  what abandoning a branch of work means. The project cascade is the schema's;
  an epic's children and the document rows are removed by the write path,
  because neither link cascades — every cascade in the schema starts at
  `project`, so what a delete reaches is stated rather than traced through a
  graph of foreign keys.
- **A deleted row gives up its slug**, because the row holding it is gone.
  Uniqueness stays a plain whole-table rule, and a name is free the moment the
  thing named stops existing.
- **A deleted item stops blocking**, because its edges go with it. The blocker
  filter needs no clause about deletion: what is gone cannot hold anything up,
  and cannot itself be listed.

> **Open — the git side of deletion.** Git is not part of the DB's `ON DELETE
> CASCADE`, so once documents exist, removing a row will leave documents nothing
> points at. Milestone 1 has no documents and no operation touching those tables,
> so nothing is orphaned yet — but the document layer must settle what a delete
> does to git content before it ships ([02](./02-document-layer.md),
> [05](./05-project-and-ops.md)). A soft-delete mark was the earlier answer to
> this; it was dropped because it bought the schema a partial index — the one
> engine-specific feature the schema refuses ([ADR-1](../architecture/adrs/adr-1.md)) —
> to solve a problem this milestone does not have.

**Slugs — auto-generated, overridable.**
- Default slug is derived from the name: lowercased, non-`[a-z0-9-]` collapsed to
  hyphens, trimmed. Per-project uniqueness (across all item types) is ensured by
  appending a numeric suffix (`-2`, `-3`, …) on collision. The caller may supply an
  explicit slug instead — a colliding explicit slug is refused (`conflict`), never
  suffixed.
- Rename is allowed **for items and releases**: it is an **explicit slug change**
  through `update_item` / `update_release` (a name edit alone never re-derives the
  slug — references stay stable), and it updates the slug in the DB **and**
  performs a `git mv` of the document path through the single write path (§4.3),
  so both stores move together.
- **A project's slug is fixed at creation and never changes.** It may be supplied
  or derived when the project is made, and after that no operation alters it — a
  later `update_project` may change the name and description, never the slug. A
  project slug is the outermost thing anything addresses: it names the MCP
  endpoint an agent connects to ([01](./01-interface-contracts.md)), which lives
  in a checked-in client configuration, and it heads every document path in the
  artifact repo. A rename would break addresses written down outside Nook, which
  Nook cannot find or fix. Deleting a project does free its slug, so a later
  project may take it — which is why anything holding a project across time holds
  its id rather than its slug.

**Queries — minimal.**
- `list_items` filters by type, status, parent (and, for epics, release), and
  whether anything unfinished is holding the item up. Default sort is
  newest-first (`created_at` desc). Free-text search is deferred.
- **There is no "ready" operation and no "ready" value.** What used to be
  `get_ready_items` is one `list_items` call composing three ordinary parts: the
  leaf types, status `todo`, and nothing blocking. Readiness is a question a
  caller asks by combining filters, not a concept the system carries — which is
  also what makes "what is ready *in this epic*" askable, by adding the parent
  part to the same call.
- Each filter accepts **several values at once** (`status` of `todo` *or*
  `in_progress` in one call), and naming several filters **narrows** the result
  (that type *and* that status). Asking for open work is the everyday question,
  and one call returns it in one correct ordering.
- The `parent` filter names an epic, or the reserved value for **no epic at
  all** — the way to ask for what sits directly on the project, which omitting
  the filter (meaning "any parent") cannot express. It matches everything with
  no epic above it: the project-level leaves, and the project's epics, which
  never have a parent either. Narrowing it with a type is how either half is
  asked for on its own; excluding the epics here would instead make "the
  top-level epics" unaskable, since parts of a filter narrow each other and can
  never widen.

**`blocked_by` integrity.**
- Blockers are **leaves in the same project** (cross-epic within a project is allowed;
  cross-project is not). Self-block is already barred by the schema `CHECK`.
- The blocker set is **replaced whole** through `update_item`'s `blockedBy` field;
  there is no add-one or remove-one.
- Cycles are prevented at the application level: an update supplying a
  `blockedBy` set that would close a loop is rejected.

**Releases — loose buckets.**
- A release is an optional grouping; epics may be assigned and reassigned freely.
  `released` is informational and locks nothing.

## Deferred (not open — intentionally later)

- A status transition state machine, if usage shows a need.
- The git cleanup a delete owes once documents exist — deferred, but not
  optional; see the open note above and [05](./05-project-and-ops.md).
- Bringing a deleted row back. Nothing in the store survives a delete to bring
  back, so this would be a restore-from-backup feature rather than an
  operation — and it is not missed while deletion means gone.
- Free-text search across structure.

## Depends on / feeds

- These rules are enforced in `:core`'s write path and surfaced through
  [01](./01-interface-contracts.md).
