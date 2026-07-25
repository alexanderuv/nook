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
- "Blocked" remains derived (the `ready_item` view), never a stored status.

**Transition side effects — none.**
- Status changes are independent. An epic may be `done` while its leaves are still open
  (the UI surfaces this; it is not blocked). Cancelling an epic does **not**
  auto-change its leaves. No cascades, no guards — explicit over surprising.

**Deletion — soft in the store, gone to every caller.**
- Nothing is ever physically removed from the store. Deleting marks the row as
  deleted and it stays. The mark exists for the store's own sake, not the
  caller's: it preserves history and, critically, avoids **orphaning git
  documents**: git is not part of the DB's `ON DELETE CASCADE`, so a physical
  delete would leave documents no row points at. Physical removal with
  coordinated git cleanup stays a deferred, deliberate operation (see
  [05](./05-project-and-ops.md)).
- **A deleted row is unreachable through every operation.** No list returns it,
  no `get_*` returns it, and a reference naming it — by slug or by id — is
  `not_found`. There is no trash view and no way to ask for deleted rows; to a
  caller, a deleted row is simply gone. Deletion is therefore a decision, not a
  gesture: it is not undone by asking.
- **Restoring is not offered.** Bringing a deleted row back is a deliberate
  operation nobody has yet specified, of the same family as physical removal —
  not something a caller can reach.
- **Delete is an action, not a status.** It is independent of the status
  vocabulary: an item may be `cancelled` and then deleted, or deleted while
  still `todo`. Cancelling retires work that was real and keeps it in sight;
  deleting takes it out of sight entirely.
- **Deleting an epic takes its children with it**, and deleting a project takes
  everything in it. The whole branch goes at once, which is what abandoning a
  branch of work means — and nothing may survive a deletion above it and stay
  visible.
- **A deleted row gives up its slug.** Slug uniqueness applies among live rows
  only, so a name freed by a delete can be used again immediately — a caller
  never collides with something they cannot see.
- **A deleted item stops blocking**, exactly as a `cancelled` one does — the
  `ready_item` view treats it as resolved, since work nobody can see must not
  deadlock the work behind it. Deleted items are themselves never ready.

**Slugs — auto-generated, overridable.**
- Default slug is derived from the name: lowercased, non-`[a-z0-9-]` collapsed to
  hyphens, trimmed. Per-project uniqueness (across all item types) is ensured by
  appending a numeric suffix (`-2`, `-3`, …) on collision. The caller may supply an
  explicit slug instead — a colliding explicit slug is refused (`conflict`), never
  suffixed.
- Rename is allowed: it is an **explicit slug change** through `update_item` (a
  name edit alone never re-derives the slug — references stay stable), and it
  updates the slug in the DB **and** performs a `git mv` of the document path
  through the single write path (§4.3), so both stores move together.

**Queries — minimal.**
- `list_items` filters by type, status, and parent (and, for epics, release); the
  `ready` notion is `get_ready_items` (leaves only). Default sort is newest-first
  (`created_at` desc). Free-text search is deferred.
- Each filter accepts **several values at once** (`status` of `todo` *or*
  `in_progress` in one call), and naming several filters **narrows** the result
  (that type *and* that status). Asking for open work is the everyday question,
  and one call returns it in one correct ordering.
- The `parent` filter names an epic, or the reserved value for **no epic at
  all** — the way to ask for the leaves sitting directly on the project, which
  omitting the filter (meaning "any parent") cannot express.

**`blocked_by` integrity.**
- Blockers are **leaves in the same project** (cross-epic within a project is allowed;
  cross-project is not). Self-block is already barred by the schema `CHECK`.
- Cycles are prevented at the application level: `set_item_blocked_by` rejects an
  edge that would create a cycle.

**Releases — loose buckets.**
- A release is an optional grouping; epics may be assigned and reassigned freely.
  `released` is informational and locks nothing.

## Deferred (not open — intentionally later)

- A status transition state machine, if usage shows a need.
- Physical removal of a soft-deleted row, with the coordinated git cleanup it
  needs ([05](./05-project-and-ops.md)).
- Bringing a deleted row back, and any caller-facing sight of deleted rows at
  all — both would need a shape nobody has designed, and neither is missed
  while deletion means gone.
- Free-text search across structure.

## Depends on / feeds

- These rules are enforced in `:core`'s write path and surfaced through
  [01](./01-interface-contracts.md).
