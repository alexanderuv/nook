# Core write path — Spec-1

## Overview & scope

This spec is the requirements contract for every mutation the core service
performs — the single write path that [PRD-1](../prd-1.md) REQ3 mandates. It
pins the testable behavior of seven operations: `create_project`,
`create_item`, `update_item`, `set_item_blocked_by`, `create_release`,
`update_release`, and `assign_epic_to_release`, enforcing the rules settled in
[04 — Structure semantics](../../../docs/04-structure-semantics.md) and the
error model of [01 — Interface contracts](../../../docs/01-interface-contracts.md).
The concurrency requirements are motivated by this epic's
[discovery report](./discovery.md), which showed that unguarded check-then-write
admits real cycles and duplicate slugs under two writers.

In scope: the seven mutations above, invoked directly on the core service —
their inputs, effects, error behavior, and behavior under concurrent callers.

Out of scope:

- **Reads** (`get_item`, `list_items`, `get_ready_items`, `get_project`,
  `list_projects`) — epic 04.
- **The internal RPC exposure of these operations** — epic 05; **MCP and web
  adapters** — epics 06/07. This spec's "caller" is whatever invokes the core
  service in-process.
- **Actor stamping** (`created_by`/`updated_by`, `owner_subject`) — epic 08.
- **`update_project`** — not in the milestone 1 catalog; no goal needs it.
- **Documents and the git pairing of renames** — milestone 2; in this
  milestone a slug change updates the database only (PRD-1 non-goals).
- **Hard deletion, authentication, pagination, free-text search** — deferred
  by the design docs themselves.

## Scenarios

### SCEN1 — An agent builds out a project's structure

**Initiator:** a coding agent (via a later adapter surface; here, a direct
caller of the core service).
**Flow:**
1. `create_project("Search revamp")`.
2. `create_release("v1")`, then `create_item` for an epic, two tasks under the
   epic, and a project-level bug with no parent.
3. `assign_epic_to_release` puts the epic in the release.
4. `set_item_blocked_by` makes the second task wait on the first.
**Outcome:** every entity exists with a derived slug, items start in `todo`
and the release in `planned`, the bug hangs directly off the project, and the
dependency edge is recorded.

### SCEN2 — A caller controls slugs deliberately

**Initiator:** a developer (through a caller).
**Flow:**
1. `create_item` named "Add search" → slug `add-search`.
2. A second item named "Add search" → slug `add-search-2`.
3. `create_item` with explicit slug `search-core` → exactly that slug.
4. `update_item` changes the first item's name to "Add serach [sic] fixed" —
   the slug stays `add-search`.
5. `update_item` sets slug `search-entry` — the item is now referenced by the
   new slug.
**Outcome:** slugs never change unless the caller supplies one; derived
collisions resolve by suffix; explicit slugs are honored exactly.

### SCEN3 — Statuses move freely, and nothing is ever deleted

**Initiator:** a developer reorganizing work.
**Flow:**
1. A `done` task is reopened to `todo`.
2. An epic is set `done` while its leaves are still `in_progress`.
3. An obsolete task is retired by setting `cancelled`.
4. The cancelled task is later reactivated to `todo`.
**Outcome:** every move within the vocabulary succeeds, no status change
touches any other item, and the retired task was never removed from the store.

### SCEN4 — Blocker sets are replaced, and cycles are refused

**Initiator:** an agent recording dependencies.
**Flow:**
1. Task `b` is set blocked by `{a}`; later the whole set is replaced with
   `{a, c}`.
2. Task `c` is set blocked by `{b}`.
3. A call tries to set `a` blocked by `{c}` — closing the loop a→b→c→a.
**Outcome:** the replacement set is exactly what was last supplied; the
cycle-closing call fails with the `cycle` error and no edge from that call is
stored.

### SCEN5 — An item changes type without losing anything silently

**Initiator:** a developer reclassifying work.
**Flow:**
1. A `bug` is reclassified to `chore` — allowed regardless of its edges.
2. A task that still blocks another leaf is converted to `epic` — refused.
3. The caller clears the dependency edge, retries the conversion — it
   succeeds.
**Outcome:** leaf-to-leaf changes are free; a conversion that would invalidate
attachments (parent, dependency edges, release assignment) is refused until
the caller removes them, and nothing is dropped behind the caller's back.

### SCEN6 — Two agents write to the same project at once

**Initiator:** two concurrent callers.
**Flow:**
1. Both create an item named "Add search" at the same moment.
2. Each simultaneously adds one half of what would together be a two-edge
   dependency cycle.
**Outcome:** both creates succeed with distinct slugs; of the two edge writes,
exactly one commits and the other fails with `cycle` — the store never holds a
cycle or a duplicate slug.

## Requirements

### Operations and errors

- **REQ1** — The write path MUST provide exactly these mutating operations:
  `create_project`, `create_item`, `update_item`, `set_item_blocked_by`,
  `create_release`, `update_release`, `assign_epic_to_release`, `delete_item`,
  `delete_project`.
- **REQ2** — Deleting MUST remove the rows: no mark, no trash, and no operation
  by which a deleted row could be asked for or brought back. `delete_item` MUST
  also remove an epic's children and the documents attached to any of them;
  `delete_project` MUST remove everything the project contains. There is
  deliberately no operation that deletes a release or a single dependency edge —
  a release is detached with `assign_epic_to_release`, and blocker edges change
  only by whole-set replacement through `set_item_blocked_by`.

  > REQ1 and REQ2 originally named seven operations and forbade hard deletion
  > outright. Deletion was added during milestone 1 and the reversal is recorded
  > in `plan.md`; these two requirements are the amended ones. See
  > [docs/04](../../../docs/04-work-item-model.md) for the deletion rules
  > themselves, and the note there about the git cleanup a delete will owe once
  > documents have content.
- **REQ3** — When a reference string parses as a UUID, the system MUST resolve
  it as an id; otherwise it MUST resolve it as a slug within the target
  project.
- **REQ4** — When a reference resolves to no entity within the target project
  (including entities that exist only in another project), the operation MUST
  fail with `not_found`.
- **REQ5** — When an operation fails, the system MUST return one structured
  error `{code, message, details?}` with `code` one of `validation_failed`,
  `not_found`, `conflict`, `cycle`.
- **REQ6** — When an operation fails, the store MUST be left exactly as it was
  before the call — no partial effect.
- **REQ7** — When an operation succeeds, its effect MUST be applied exactly
  once, and it MUST return the full entity it created or changed. The two
  deletes return nothing: once the call commits, the entity does not exist, so
  there is none left to hand back.

### Projects

- **REQ8** — `create_project(name, slug?, description?)` MUST create a project
  whose slug follows the slug rules (REQ19–REQ24) with uniqueness scoped to
  the whole instance.
- **REQ9** — `create_project` MUST leave `artifactRepoUrl` unset in this
  milestone.

### Item creation and containment

- **REQ10** — `create_item` MUST accept `type` of `epic`, `task`, `bug`, or
  `chore`, and MUST fail with `validation_failed` for any other value.
- **REQ11** — When a leaf is created without `parentRef`, the system MUST
  create it as a project-level leaf.
- **REQ12** — When `parentRef` is supplied for a leaf, it MUST resolve to an
  epic in the same project; a resolved non-epic parent MUST fail with
  `validation_failed`.
- **REQ13** — When `create_item` is called with `type=epic` and a `parentRef`,
  the system MUST fail with `validation_failed` — epics are never parented.
- **REQ14** — When `create_item` is called with a `releaseRef` and a leaf
  `type`, the system MUST fail with `validation_failed` — release assignment
  applies to epics only.
- **REQ15** — A newly created item MUST start in status `todo`; a newly
  created release MUST start in status `planned`.

### Item update

- **REQ16** — `update_item` MUST change only the fields supplied; omitted
  fields MUST keep their values.
- **REQ17** — Changing `name` MUST NOT change the slug; the slug changes only
  when the caller supplies a `slug` value.
- **REQ18** — Setting or clearing `parentRef` MUST reparent a leaf (to a
  same-project epic, or to project level when cleared) while leaving its slug
  and blocker set unchanged.

### Slugs

- **REQ19** — When no explicit slug is supplied, the system MUST derive one
  from the name: lowercased, every run of characters outside `a–z`/`0–9`
  collapsed to a single hyphen, leading and trailing hyphens trimmed.
- **REQ20** — When a derived slug collides with an existing slug in its
  uniqueness scope, the system MUST append the first free numeric suffix
  (`-2`, `-3`, …).
- **REQ21** — When derivation yields an empty slug and no explicit slug was
  supplied, the operation MUST fail with `validation_failed`.
- **REQ22** — An explicit slug MUST be non-empty, consist only of `a–z`,
  `0–9`, and hyphens, and not parse as a UUID; otherwise the operation MUST
  fail with `validation_failed`. The system MUST NOT alter (e.g. lowercase) an
  explicit slug on the caller's behalf.
- **REQ23** — When an explicit slug collides with an existing slug in its
  uniqueness scope, the operation MUST fail with `conflict` — explicit slugs
  are never suffixed.
- **REQ24** — Slug uniqueness scopes: item slugs are unique within their
  project across all item types; release slugs are unique within their
  project across releases; project slugs are unique across the instance.

### Statuses

- **REQ25** — A status change MUST be accepted when the target value is in the
  entity's vocabulary (items: `todo`/`in_progress`/`done`/`cancelled`;
  releases: `planned`/`in_progress`/`released`/`cancelled`), whatever the
  current status, and MUST fail with `validation_failed` otherwise.
- **REQ26** — A status change MUST NOT alter any other entity — no cascades to
  children, blockers, or assigned epics.
- **REQ27** — A `cancelled` entity MUST remain updatable like any other,
  including moving to any status in its vocabulary.

### Type changes

- **REQ28** — A type change between `task`, `bug`, and `chore` MUST be
  accepted regardless of the item's parent, blockers, or edges.
- **REQ29** — A type change from leaf to `epic` MUST fail with
  `validation_failed` while the item has a parent or participates in any
  dependency edge, in either direction.
- **REQ30** — A type change from `epic` to a leaf type MUST fail with
  `validation_failed` while the epic has child items or a release assignment.

### Blockers

- **REQ31** — `set_item_blocked_by(itemRef, blockerRefs[])` MUST replace the
  item's entire blocker set with the supplied set; an empty list MUST clear
  it.
- **REQ32** — The target item and every blocker MUST be leaves in the same
  project, and an item MUST NOT block itself; violations MUST fail with
  `validation_failed`.
- **REQ33** — When the supplied set would create a cycle in the project's
  dependency graph, the operation MUST fail with the `cycle` error.
- **REQ34** — Duplicate references in the supplied list MUST collapse to a
  single edge.

### Releases

- **REQ35** — `create_release(name, slug?, description?, targetDate?)` MUST
  create a release with the supplied fields.
- **REQ36** — `update_release` MUST support changing `name`, `slug`,
  `description`, `status`, and `targetDate`, following the same
  partial-update, slug, and status rules as items (REQ16–REQ17, REQ19–REQ25).
- **REQ37** — `assign_epic_to_release(epicRef, releaseRef?)` MUST assign the
  epic when `releaseRef` is supplied and unassign it when omitted or null; a
  target that is not an epic MUST fail with `validation_failed`.
- **REQ38** — Release assignment and reassignment MUST be accepted whatever
  the status of the release or the epic — `released` locks nothing.

### Concurrency

- **REQ39** — Under concurrent callers, the store MUST NOT come to hold a
  dependency cycle.
- **REQ40** — Under concurrent callers, the store MUST NOT come to hold two
  slugs equal within one uniqueness scope.

## Edge cases

- **EDGE1** — `create_item` named `"???"` (derives to empty) with no explicit
  slug: fails with `validation_failed`; with explicit slug `q3-spike`:
  succeeds.
- **EDGE2** — Derived slug `add-search` taken and `add-search-2` also taken
  (explicitly claimed earlier): the new item gets `add-search-3` — the first
  *free* suffix.
- **EDGE3** — `update_item` supplying the item's own current slug: succeeds
  as a no-op, not a `conflict`.
- **EDGE4** — Explicit slug `Add-Search` (uppercase): `validation_failed` —
  never silently lowercased.
- **EDGE5** — Explicit slug in UUID form (`3f2a…`-style): `validation_failed`
  — it could never be referenced, since references resolve UUID-first.
- **EDGE6** — `create_item`/`create_project`/`create_release` with an empty or
  whitespace-only name: `validation_failed`.
- **EDGE7** — `update_item` with no fields supplied: succeeds as a no-op;
  whether `updatedAt` advances is don't care — no behavior may depend on it.
- **EDGE8** — Setting status, type, or parent to its current value: succeeds
  (any-to-any includes same-to-same).
- **EDGE9** — `set_item_blocked_by` including the item itself:
  `validation_failed` (self-block).
- **EDGE10** — `set_item_blocked_by` re-supplying the existing set: succeeds,
  set unchanged.
- **EDGE11** — Unassigning an epic that is in no release: succeeds as a no-op.
- **EDGE12** — `targetDate` in the past on create or update: accepted —
  advisory PM state carries no date policy.
- **EDGE13** — A cycle closed through a longer chain (a→b→c→a), not just a
  two-node loop: `cycle`, store unchanged.
- **EDGE14** — Two concurrent callers deriving the same slug: both succeed
  with distinct slugs, one suffixed.
- **EDGE15** — Two concurrent callers each writing one half of a two-edge
  cycle: exactly one commits; the other fails with `cycle`.

## Acceptance criteria

- **AC1** (REQ1, REQ2) — The write path's public surface offers exactly the
  nine listed mutations, and no operation that removes a release or a single
  dependency edge from the store.
- **AC2** (REQ7, REQ8, REQ9, REQ15, REQ19) — Given a running core service,
  when `create_project("Search Revamp!")` is called, then it returns the full
  project entity with slug `search-revamp`, `artifactRepoUrl` unset, and a
  second call for a release and an item under that project yields status
  `planned` and `todo` respectively.
- **AC3** (REQ20, REQ24) — Given a project whose instance already
  holds a project `search-revamp`, when a second project named "Search
  Revamp" is created, then it succeeds with slug `search-revamp-2`.
- **AC4** (REQ10, REQ5) — Given a project, when `create_item` is called with
  `type="story"`, then it fails with a structured error whose code is
  `validation_failed`.
- **AC5** (REQ11, REQ12) — Given an epic `search-core`, when a task is created
  with `parentRef="search-core"` and a bug is created with no `parentRef`,
  then the task's `parentId` is the epic's id and the bug's `parentId` is
  null.
- **AC6** (REQ12, REQ13, REQ14) — Given a leaf `add-search`, when a task is
  created with `parentRef="add-search"`, an epic is created with any
  `parentRef`, or a task is created with a `releaseRef`, then each call fails
  with `validation_failed`.
- **AC7** (REQ19, REQ20) — Given an empty project, when three items named
  "Add search" are created in sequence, then their slugs are `add-search`,
  `add-search-2`, `add-search-3`.
- **AC8** (EDGE2) — Given items with slugs `add-search` and `add-search-2`,
  when an item named "Add search" is created, then its slug is
  `add-search-3`.
- **AC9** (REQ22, REQ23, EDGE3) — Given an item with explicit slug
  `search-core`, when a second item is created with explicit slug
  `search-core`, then it fails with `conflict`; and when the first item's
  `update_item` supplies slug `search-core` (its own), then it succeeds
  unchanged.
- **AC10** (REQ21, REQ22, EDGE1, EDGE4, EDGE5, EDGE6) — Given a project, when
  items are created named `"???"` with no slug, named `""`, with slug
  `Add-Search`, or with a slug in UUID form, then every call fails with
  `validation_failed`.
- **AC11** (REQ3, REQ16, REQ17) — Given item `add-search`, when
  `update_item("add-search", {name: "Improve search"})` is called, then the
  returned entity has the new name, the same slug `add-search`, and all other
  fields unchanged — and the same call addressed by the item's UUID behaves
  identically.
- **AC12** (REQ17, REQ23) — Given item `add-search`, when `update_item`
  supplies slug `search-entry`, then subsequent operations resolve
  `search-entry` to the item and `add-search` to nothing (`not_found`).
- **AC13** (REQ25, REQ26, REQ27) — Given an epic `done` whose two leaves are
  `in_progress`, when the epic was set `done`, then the call succeeded and
  both leaves remained `in_progress`; when a leaf is set to `blocked`, then
  it fails with `validation_failed`; and when a `cancelled` leaf is set to
  `todo` and its description edited, then both calls succeed.
- **AC14** (REQ2, REQ25) — Given a `done` task, when its status is set to
  `todo`, then the call succeeds; and when it is deleted, its row ceases to
  exist and every later reference to it — by slug or by id — is `not_found`,
  with no operation by which it could be asked for again.
- **AC15** (REQ28, REQ29) — Given a bug that blocks another task, when its
  type is set to `chore`, then it succeeds; when its type is set to `epic`,
  then it fails with `validation_failed`; and after its dependency edges are
  cleared and it is parentless, the same conversion succeeds.
- **AC16** (REQ30) — Given an epic with one child and a release assignment,
  when its type is set to `task`, then it fails with `validation_failed`; and
  after the child is reparented away and the epic unassigned, the conversion
  succeeds.
- **AC17** (REQ18, EDGE8) — Given a leaf with slug `add-search` and one
  blocker under epic A, when its `parentRef` is set to epic B, then its slug
  and blocker set are unchanged; when `parentRef` is cleared, then it becomes
  project-level; and when it is reparented to its current parent, the call
  succeeds.
- **AC18** (REQ31, REQ34, EDGE7, EDGE9, EDGE10) — Given leaf `b` blocked by
  `{a}`, when `set_item_blocked_by(b, [c, c, d])` is called, then `b`'s
  blockers are exactly `{c, d}`; when called with `[]`, then the set is
  empty; when called with `[b]`, then it fails with `validation_failed`; when
  called re-supplying the current set, then it succeeds; and
  `update_item(b, {})` succeeds without changing any field.
- **AC19** (REQ4, REQ32) — Given projects P1 and P2 each holding leaves and an
  epic, when `set_item_blocked_by` in P1 names an epic, a P2 leaf's slug, or
  a random UUID, then the epic case fails `validation_failed` and both
  unresolvable cases fail `not_found`.
- **AC20** (REQ6, REQ33, EDGE13) — Given the chain where `b` is blocked by
  `a` and `c` is blocked by `b`, when `set_item_blocked_by(a, [c])` is
  called, then it fails with the `cycle` error and `a`'s blocker set is still
  empty.
- **AC21** (REQ35, REQ36, REQ25, EDGE12) — Given a release created with a past
  `targetDate`, when `update_release` sets status `released` and a new name,
  then it succeeds; and when status is set to `shipped`, then it fails with
  `validation_failed`.
- **AC22** (REQ37, REQ38, EDGE11) — Given a `released` release R1 and release
  R2, when an epic is assigned to R1, reassigned to R2, and unassigned via a
  null `releaseRef`, then all three calls succeed; when the same calls target
  a task, then each fails with `validation_failed`; and when an unassigned
  epic is unassigned again, then it succeeds.
- **AC23** (REQ39, EDGE15) — Given leaves `x` and `y` and two concurrent
  callers where one sets `x` blocked by `{y}` and the other sets `y` blocked
  by `{x}`, when both run to completion (repeated 100 times), then in every
  run exactly one call succeeds, the other fails with `cycle`, and the store
  holds no cycle.
- **AC24** (REQ40, EDGE14) — Given an empty project and two concurrent callers
  each creating an item named "Add search" (repeated 100 times), then in
  every run both calls succeed and the two slugs differ.
- **AC25** (REQ5, REQ6) — Given item `add-search` in status `todo`, when
  `update_item` supplies both a new name and status `finished`, then the call
  fails with one structured error (`validation_failed`) and the item's name
  and status are unchanged.

## Definitions

- **project item** — one row of work; attributes: `type`, `name`, `slug`,
  `description`, `status`, optional parent, optional release (epics),
  blockers (leaves); relates to: project, epic, release.
- **epic** — a project item of `type=epic`; a container that parents leaves,
  is never parented, and may be assigned to a release.
- **leaf** — a project item of `type` `task`, `bug`, or `chore`; never nests,
  may carry blocker edges.
- **project-level leaf** — a leaf with no parent epic, hanging directly off
  the project.
- **blocker set** — the set of leaves an item is blocked by, stored as
  dependency edges; replaced whole by `set_item_blocked_by`, never
  incrementally edited.
- **derived slug** — the identifier the system computes from a name (REQ19);
  **explicit slug** — one the caller supplies verbatim.
- **status vocabulary** — the closed set of statuses an entity kind allows;
  items and releases have distinct vocabularies (REQ25).
- **structured error** — the `{code, message, details?}` failure payload; the
  four codes are the whole error surface.
- **reference (ref)** — a string identifying an entity: a UUID, or a slug
  resolved within the target project.

## Assumptions

- **ASM1** — The deployed schema is exactly the committed Liquibase changelog
  (unique `(project_id, slug)`, self-block `CHECK`, the foreign keys); if
  false: the backstops behind REQ23, REQ32, REQ39, and REQ40 are gone and
  those guarantees rest on application code alone.
- **ASM2** — The core service is the only writer to the structure tables — no
  out-of-band SQL; if false: REQ39 and REQ40 cannot hold, since the write
  path cannot serialize writers it never sees.
- **ASM3** — No documents exist in this milestone, so a slug change moves no
  files; if false: REQ17's slug changes would orphan document paths — the
  paired git move arrives with the document layer in milestone 2.
