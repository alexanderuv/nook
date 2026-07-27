# Structure queries — Spec-2

## Overview & scope

This spec is the requirements contract for every read the core service
performs — the four ways a caller gets structure back out of the store. It
pins the testable behavior of `get_project`, `list_projects`, `get_item`, and
`list_items`, enforcing the query rules settled in
[04 — Structure semantics](../../../docs/04-structure-semantics.md) and the
reference and error model of
[01 — Interface contracts](../../../docs/01-interface-contracts.md).
[PRD-1](../prd-1.md) makes the readiness question this milestone's payoff: the
loop it is judged on ends with a caller asking what is ready to work on and
getting exactly the right leaves. The write side of the same store is the
subject of [epic 03's spec](../03-core-write-path/spec-1.md), whose rules —
slug derivation, containment by type, free status movement — this one reads
back.

Throughout, a *slug* is an item's handle: the short lowercase name derived
from its title (`Add search` becomes `add-search`) that appears in paths and
can be used anywhere an id can.

In scope: the four reads above, invoked directly on the core service — their
inputs, their results, their ordering, their error behavior, and how they
treat rows that have been deleted.

> This spec originally pinned five reads, the fifth being a `get_ready_items`
> operation. It was folded into `list_items` as a filter part, so that "what is
> ready to work on" is a combination a caller composes — and can narrow to one
> epic — rather than a compound rule the surface carries; the reasoning is
> recorded in [docs/01](../../../docs/01-interface-contracts.md). The
> requirements below keep their original numbers so that citations elsewhere
> still resolve.

Out of scope:

- **The mutations**, including the delete action whose results this spec reads
  — epic 03, and this epic's plan, which carries the delete rules since no
  spec pins them. This spec's requirements about deleted rows describe what
  reads do with them, never how a row comes to be deleted.
- **The internal RPC exposure of these operations** — epic 05; **the agent and
  web adapters** — epics 06 and 07. This spec's "caller" is whatever invokes
  the core service in the same process.
- **Actor fields** (`created_by`, `updated_by`, `owner_subject`) — epic 08.
- **Documents and their reads** — milestone 2; no document exists in this
  milestone for a read to return.
- **Paging, free-text search, sort options, and authentication** — deferred by
  the design docs themselves. A listing returns everything it matches, in one
  ordering, to anyone who asks.

## Scenarios

### SCEN1 — An agent asks what it can work on

**Initiator:** a coding agent, at the start of a session.
**Flow:**
1. The project holds an epic with three tasks, a project-level bug, and a
   release.
2. One task is `done`, one is `todo` but waits on the `done` one, and one is
   `todo` waiting on a task that is still `in_progress`.
3. The agent calls `list_items`, asking for the leaf types, status `todo`, and
   nothing holding them up.
**Outcome:** it gets back the bug and the task whose blocker is finished — the
open leaves nothing is holding up — newest first, and neither the epic nor the
task still waiting. Adding the parent part to the same call would have asked
the same question inside one epic.

### SCEN2 — A developer narrows a long listing

**Initiator:** a developer looking at a busy project.
**Flow:**
1. `list_items` with no filter returns everything in the project.
2. A second call asks for items that are `todo` or `in_progress`.
3. A third call adds a type of `bug`, narrowing to open bugs only.
**Outcome:** each call returns one list in newest-first order; asking for two
statuses widens the result, adding the type narrows it.

### SCEN3 — Finding the work that sits outside any epic

**Initiator:** a developer triaging loose reports.
**Flow:**
1. The project holds two epics with children, plus three bugs filed straight
   against the project.
2. `list_items` is called with the parent filter set to the reserved value
   meaning no epic.
3. The same call is repeated with a type of `bug`.
**Outcome:** the first call returns everything with no epic above it — the three
project-level bugs and both epics, which have no parent either — and none of the
children of either epic. Adding the type narrows that to exactly the three bugs.

### SCEN4 — Reviewing what a release contains

**Initiator:** a developer preparing a release.
**Flow:**
1. Two epics are assigned to release `v1` and one to `v2`.
2. `list_items` is called filtered by release `v1`.
3. The same call is repeated with a type filter of `task`.
**Outcome:** the first call returns the two epics in `v1`; the second returns
nothing, because a task never belongs to a release directly.

### SCEN5 — A deleted item is gone, and asking for it says so

**Initiator:** a developer holding a link to an item someone deleted.
**Flow:**
1. A plain listing no longer shows the item.
2. `get_item` is called with the slug the item used to hold.
3. `get_item` is called again with the item's id, copied from an old note.
**Outcome:** both calls fail with `not_found`. No listing and no fetch can
produce the item, and no argument exists that asks for it — deleted is gone,
whatever the caller knows about the row.

### SCEN6 — A caller finds a project to work in

**Initiator:** a caller that has not yet bound itself to any project.
**Flow:**
1. `list_projects` is called against an instance holding four projects, one of
   which has been deleted.
2. The caller picks one from the result and calls `get_project` with its
   handle.
**Outcome:** the listing shows the three remaining projects, newest first, without
any project having been chosen first; the fetch returns the picked project in
full.

### SCEN7 — A whole branch of work leaves at once

**Initiator:** a developer abandoning an epic.
**Flow:**
1. An epic with four tasks is deleted, which deletes the four tasks with it.
2. A plain listing is taken.
3. The ready combination is asked for, and a task that had been blocked by one
   of the four is checked.
**Outcome:** neither the epic nor any of its four tasks appears in the
listing; none of them comes back as ready; and the task outside the branch does,
because a deleted blocker no longer holds anything up.

## Requirements

### The read surface

- **REQ1** — The read path MUST provide exactly these operations:
  `get_project`, `list_projects`, `get_item`, `list_items`.
- **REQ2** — A read MUST NOT change anything in the store — no row written, no
  timestamp advanced.
- **REQ3** — When a reference string parses as a UUID, the system MUST resolve
  it as an id; otherwise it MUST resolve it as a slug.
- **REQ4** — When a reference resolves to no entity in its scope — the bound
  project for items and releases, the whole instance for projects — the
  operation MUST fail with `not_found`.
- **REQ5** — When a read fails, the system MUST return one structured error
  `{code, message, details?}` whose `code` is `validation_failed` or
  `not_found`; the other two codes of the error model belong to writes and
  MUST NOT arise here.
- **REQ6** — A returned entity MUST be the full entity, carrying the same
  fields the write path returns; for an item this includes its complete blocker
  set. No read result ever reports a row as deleted, because a deleted row is
  not in the store to be returned.
- **REQ7** — A listing operation MUST return an array, and a listing that
  matches nothing MUST succeed with an empty array rather than fail.

### Ordering

- **REQ8** — Every listing MUST be ordered by creation time, newest first.
- **REQ9** — When two rows share a creation instant, the system MUST break the
  tie by id, so that repeating an identical call returns an identical order.

### Listing items

- **REQ10** — `list_items` MUST accept a filter made of these parts, each of
  them optional: type, status, parent, release, and whether anything unfinished
  is holding the item up.
- **REQ11** — When no filter part is supplied, `list_items` MUST return every
  item in the project.
- **REQ12** — Each of the type, status, parent, and release parts MUST accept
  one or more values, and an item MUST match that part when it matches any one
  of the supplied values. The held-up part is the exception: the question has
  two answers rather than a set of values, so it MUST accept exactly one of
  them.
- **REQ13** — When several parts are supplied, an item MUST be returned only
  when it matches every one of them.
- **REQ14** — When a type or status value lies outside its vocabulary
  (items are `epic`, `task`, `bug`, `chore`; statuses are `todo`,
  `in_progress`, `done`, `cancelled`), `list_items` MUST fail with
  `validation_failed`, whether or not the project holds any items at all.
- **REQ15** — The parent part MUST accept an epic reference or the reserved
  value meaning no parent, and MUST match on the item's own direct parent.
- **REQ16** — The release part MUST match on the item's own release
  assignment, so a leaf MUST never match any release value.
- **REQ17** — When a parent value resolves to an item that is not an epic,
  `list_items` MUST fail with `validation_failed` — nothing can sit under a
  leaf, so the question is a caller mistake rather than an empty answer.

### Deleted rows

- **REQ18** — No read operation MUST offer the caller any way to ask for a
  deleted row — no argument, no filter value, no separate operation. Deleting
  removes the row, so there is nothing for such an argument to return.
- **REQ19** — Deleting a branch MUST remove every row in it, so no read can
  return a row whose epic or project has been deleted.
- **REQ20** — `get_item` and `get_project` MUST fail with `not_found` when the
  reference names a row that has been deleted, whether it is an id or a slug.

### Being held up

- **REQ21** — An item MUST count as held up when any of its blockers is neither
  `done` nor `cancelled`, and as not held up when it has no blockers or every
  one of them is `done` or `cancelled`. A deleted blocker takes its edge with
  it, so it holds nothing up.
- **REQ22** — The held-up part MUST apply to any item and MUST NOT imply a type
  or a status of its own: an epic with an unfinished blocker matches it exactly
  as a leaf would.
- **REQ23** — The held-up part MUST narrow alongside the other parts like any
  other, so that asking for the leaf types, status `todo`, and not held up is
  how a caller asks what is ready to work on — and adding the parent part asks
  the same question inside one epic.
- **REQ24** — There MUST be no other way to ask that question: no readiness
  operation, no stored readiness column, and no `ready` value in the status
  vocabulary. Readiness is a combination a caller composes, not a thing the
  system carries.

### Projects

- **REQ25** — `list_projects` MUST return the instance's projects, under the
  same ordering as every other listing.
- **REQ26** — `get_project` MUST resolve its reference across the whole
  instance rather than inside any one project.

## Edge cases

- **EDGE1** — A listing in a project holding no items: an empty array, not an
  error.
- **EDGE2** — A filter part supplied with an empty list of values:
  `validation_failed` — matching any of nothing is a caller mistake, and
  leaving the part out is how a caller says "don't filter on this".
- **EDGE3** — The same value repeated inside one filter part: the same result
  as supplying it once.
- **EDGE4** — A type of `epic` together with the no-parent value: the
  project's epics come back, since an epic never has a parent.
- **EDGE5** — A release value together with a leaf type: an empty array, not
  an error — a leaf never carries a release.
- **EDGE6** — A parent value naming a deleted epic by its id: `not_found`, the
  same as any other reference to a deleted row — and its children are gone with
  it in any case.
- **EDGE7** — `get_item` given a UUID that belongs to an item in another
  project: `not_found`.
- **EDGE8** — `get_item` given the slug a deleted item used to hold, or given
  that item's id: `not_found` either way.
- **EDGE9** — A slug freed by a delete and then taken by a new item: the slug
  resolves to the new item, and the old id resolves to nothing.
- **EDGE10** — A `todo` leaf whose only blocker has been deleted: not held up.
- **EDGE11** — A `todo` leaf that is itself deleted: absent from every listing,
  whatever the filter asks for.
- **EDGE12** — The ready combination in a project whose every leaf is `done`:
  an empty array.
- **EDGE13** — A listing in a project whose every item has been deleted: an
  empty array, indistinguishable from a project that never held anything.
- **EDGE14** — Two items created in the same instant: the same order on every
  call that returns both.
- **EDGE15** — A read running while another caller commits a write: don't care
  which side of that commit the read lands on — but it MUST show the store
  either wholly before or wholly after it, never half-applied.
- **EDGE16** — `list_projects` on an instance holding no projects: an empty
  array.

## Acceptance criteria

- **AC1** (REQ1, REQ2) — The read path's public surface offers exactly the four
  listed operations; and given a project holding items, a release, and blocker
  edges, when every one of the four is called, then every stored row and every
  stored timestamp is exactly what it was beforehand.
- **AC2** (REQ3, REQ4, EDGE7) — Given item `add-search` in project P1, when
  `get_item` is called with its slug, with its id, with the id of an item in
  project P2, and with a UUID belonging to nothing, then the first two return
  that item and the last two fail with `not_found`.
- **AC3** (REQ5) — Given any failing read, when the failure is returned, then
  it is a single structured error whose code is `validation_failed` or
  `not_found`, and no read in the suite ever produces `conflict` or `cycle`.
- **AC4** (REQ6) — Given a leaf under an epic with two blockers, when
  `get_item` returns it, then the result carries its type, name, slug,
  description, status, parent, blocker set of both ids, and timestamps.
- **AC5** (REQ7, EDGE1, EDGE16) — Given a project with no items and an instance
  with no projects, when `list_items` and `list_projects` are called, then both
  succeed and return empty arrays.
- **AC6** (REQ8, REQ9, EDGE14) — Given five items created in sequence and two
  more written with the same creation instant, when `list_items` is called ten
  times, then every call returns them newest-created first, and all ten calls
  return the two same-instant items in the same relative order.
- **AC7** (REQ10, REQ11) — Given a project holding four items of different
  types and statuses, when `list_items` is called with no filter, then all four
  come back.
- **AC8** (REQ12) — Given items across all four statuses, when `list_items` is
  called asking for `todo` or `in_progress`, then exactly the items in those
  two statuses come back.
- **AC9** (REQ13) — Given open and closed bugs and tasks, when `list_items` is
  called asking for type `bug` and status `todo`, then only the open bugs come
  back.
- **AC10** (REQ14, EDGE2) — Given any project, when `list_items` is called with
  type `story`, with status `blocked`, or with an empty list of statuses, then
  each call fails with `validation_failed` — including against a project
  holding no items.
- **AC11** (REQ12, EDGE3) — Given three bugs, when `list_items` is called with
  type `bug` supplied twice, then the three bugs come back once each.
- **AC12** (REQ15, EDGE4) — Given two epics with children and three
  project-level bugs, when `list_items` filters by the first epic, then only
  its children come back; when it filters by the no-parent value, then the
  three bugs and both epics come back; when it filters by the no-parent value
  with type `bug`, then exactly the three bugs come back; and when it filters by
  the no-parent value with type `epic`, then both epics come back.
- **AC13** (REQ16, EDGE5) — Given two epics in release `v1`, one in `v2`, and
  leaves under all three, when `list_items` filters by `v1`, then exactly the
  two epics come back; and when it filters by `v1` with type `task`, then it
  succeeds with an empty array.
- **AC14** (REQ17) — Given leaf `add-search`, when `list_items` filters by
  parent `add-search`, then it fails with `validation_failed`.
- **AC15** (REQ18, EDGE13) — Given a project with three items and two others
  since deleted, when `list_items` is called, then exactly the three come back;
  and the operation offers no argument by which the other two could be asked
  for — a listing of a project whose every item is deleted is an empty array.
- **AC16** (REQ19, REQ20, EDGE6) — Given a deleted epic whose four children
  were deleted with it, when `list_items` filters by that epic's id, then the
  call fails with `not_found`; and when a plain listing is taken, then none of
  the five appears.
- **AC17** (REQ20, EDGE8, EDGE9) — Given a deleted item whose slug `add-search`
  has since been taken by a new live item, when `get_item` is called with
  `add-search`, then it returns the new live item; and when it is called with
  the deleted item's id, then it fails with `not_found`.
- **AC18** (REQ21, REQ23, EDGE10, EDGE12) — Given an epic, a `todo` leaf with
  no blockers, a `todo` leaf whose blockers are one `done` and one since
  deleted, a `todo` leaf blocked by an `in_progress` item, and a `done` leaf,
  when `list_items` asks for the leaf types, status `todo`, and not held up,
  then exactly the first two `todo` leaves come back, newest-created first; when
  every leaf is then set `done`, then the same call returns an empty array; and
  when the parent part naming one epic is added, then only that epic's share of
  the answer comes back.
- **AC19** (REQ22, EDGE11) — Given an epic blocked by an unfinished item and a
  `todo` leaf with no blockers that is then deleted, when `list_items` asks for
  items that are held up with no type part, then the epic comes back; and when
  any listing is taken, then the deleted leaf appears in none of them.
- **AC20** (REQ24) — The read path offers no readiness operation; no entity
  carries a stored readiness field; and `list_items` rejects `ready` as a status
  value with `validation_failed`, since it is not in the vocabulary.
- **AC21** (REQ25, REQ26) — Given three projects and a fourth since deleted on
  the instance, when `list_projects` is called, then it returns exactly the
  three, newest first; when `get_project` is called with one of their slugs,
  then it resolves without a project being bound first; and when it is called
  with the deleted project's id, then it fails with `not_found`.
- **AC22** (EDGE15) — Given a caller repeatedly listing a project while another
  caller repeatedly creates items in it (repeated 100 times), then every
  listing returns only fully committed items — each returned item complete,
  with its blocker set as committed — and no call fails.

## Definitions

- **read path** — the four operations of REQ1, taken together; the only way
  structure leaves the store.
- **deleted row** — a row that has been removed from the store. Nothing of it
  remains: no read returns it, no reference resolves to it, and it is
  indistinguishable from a row that never existed.
- **slug** — an item's handle: a short lowercase name used in paths and
  accepted anywhere an id is. A handle belongs to the row holding it, so a name
  is available again as soon as that row is deleted.
- **reference (ref)** — a string naming an entity: a UUID, or a slug resolved
  within the scope that applies (a project for items and releases, the
  instance for projects).
- **filter part** — one named piece of a listing's filter (type, status,
  parent, release, held up); parts narrow each other and the values inside a
  part widen it.
- **leaf** — an item of type `task`, `bug`, or `chore`; **epic** — an item of
  type `epic`, which parents leaves and is never itself parented.
- **project-level leaf** — a leaf with no parent epic, hanging directly off the
  project; the no-parent filter value is how a caller asks for these.
- **blocker set** — the items an item is blocked by; a blocker counts as
  resolved when it is `done` or `cancelled`. Deleting a blocker removes the edge
  along with it, so it stops appearing in the set at all.
- **held up** — having at least one blocker that is neither `done` nor
  `cancelled`. **Ready** is not a thing this contract defines: it is the name
  people give one combination of filter parts — the leaf types, status `todo`,
  and not held up — computed when asked, never stored and never a status.
- **structured error** — the `{code, message, details?}` failure payload; on
  reads, only `validation_failed` and `not_found` occur.

## Assumptions

- **ASM1** — The write path gains the delete action, and deleting removes the
  row rather than marking it; if false: REQ18 through REQ20 and REQ23 are
  untestable, since nothing could produce a deleted row.
- **ASM2** — Deleting a branch reaches every row under it — an epic's children,
  and everything inside a project — so no row is left pointing at something
  gone; if false: REQ19 fails and a listing can return an orphan.
- **ASM3** — The held-up part is answered from the dependency edges when the
  question is asked, not from the `ready_item` view the changelog builds; if
  false: that view's own idea of leaf-and-`todo` rides along invisibly with
  every use of the part, and combining it with the other parts stops meaning
  what it says.
- **ASM4** — Reads run against the same database as writes, with no cache in
  between; if false: REQ2's no-change guarantee still holds, but the ordering
  and committed-state guarantees of REQ8, REQ9, and EDGE15 no longer follow
  from the store's own behavior.
- **ASM5** — Projects in this milestone hold few enough items that returning
  every match in one unpaginated array is acceptable; if false: this spec sets
  no size or speed limit to fall back on, and paging — deferred by the design
  docs — becomes the fix rather than a tuning exercise.
