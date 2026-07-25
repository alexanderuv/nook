# Epic 04 — Structure queries

**Addresses:** REQ4 (`list_items` filters by type/status/parent/release,
`get_ready_items` from the `ready_item` view, newest-first default).

Documents, in the order they were produced:

- [spec-2.md](./spec-2.md) — the behavior contract for the five reads
  (`get_project`, `list_projects`, `get_item`, `list_items`,
  `get_ready_items`): requirements, edge cases, and acceptance criteria.
- [discovery.md](./discovery.md) — ten probes against real PostgreSQL settling
  how the reads get built: what it takes for a deleted row to give up its
  handle, why the readiness view must be rebuilt, how the listing filter
  composes, and why every read wants a read-only transaction that reads one
  moment. It also settles that the deleted mark and the deletion the spec
  assumes are built here, in this epic. Its recommendation still speaks of a
  restore action alongside delete; that was dropped afterwards — deletion is
  final to every caller, and bringing a row back is deferred with physical
  removal.
- [plan.md](./plan.md) — the build route, steps ticked as execution proceeds.
  No spec pins deletion, so the plan carries its rules itself, taken from the
  structure-semantics document.

## Results

The five reads landed as planned. The schema gained soft delete in one
changeset — `deleted_at` on `project`, `release`, and `project_item`, the three
handle rules rebuilt as unique indexes limited to live rows, and `ready_item`
recreated so a deleted item is never offered and a deleted blocker never holds
anything up. `:core-service` gained two packages: `io.nook.core.store`, holding
reference resolution, the row-to-entity mapping, and the two failures both paths
raise; and `io.nook.core.read`, holding one `ReadService` whose public surface is
exactly the five reads, each running in a read-only transaction that reads one
moment and takes no lock. `WriteService` grew to nine mutations with `deleteItem`
and `deleteProject`. `:contract` gained the listing filter. CI ran green on the
whole of it, migration included — the one part of the epic no probe had
executed.

Two things the plan expected to be hard turned out fine, and one turned out
harder. Liquibase performed the constraint-to-partial-index swap without
trouble, and the declarations reproduce those indexes exactly — condition
included. But making a project's deletion safe against a concurrent writer took
more than the lock the plan named: every write resolves its project before
taking the lock keyed on it, so the project can be deleted in that gap. The race
test caught it, and every write now re-reads its project under the lock.

Deviations from the plan, all folded into its text:

- Deleting a project takes the project's lock as well as the instance-wide one,
  and every write re-reads its project inside the lock. Without the second read
  a caller could add a live item to a project already deleted.
- The live-only rule reaches further into the write path than the handle scans
  it was reached for: cycle detection, an epic's children, and the edges that
  block a type change all consider live rows only. A rule enforced against
  invisible rows refuses work for a reason the caller cannot diagnose or clear.
- Deleting removes no blocker edge, so a live item's blocker set may name a
  deleted row. That is what the readiness rule already assumes, and what
  "nothing is physically removed" means for an edge. Edges still change only the
  way they always have: the whole set replaced at once.
- Neither delete returns an entity, and neither touches `updated_at`: the row is
  out of reach the moment it commits, and `deleted_at` already records when it
  left, so overwriting the last content change would lose history rather than
  record it.
- `validationFailed` and `notFound` moved into the shared package alongside
  resolution, which needs them; `conflict` and `cycle` stayed with the write
  path, the only place they arise. The rule deciding a reference is in UUID form
  moved with resolution too.
- The concurrency step gained a deterministic pair beside the soak. The soak
  never caught the anomaly by luck at either setting, so on its own it proved
  nothing; the pair runs a listing's two statements by hand around a commit and
  requires the anomaly at PostgreSQL's normal setting and its absence at
  repeatable read.
- The readiness view is declared column for column though only two are read: a
  half-declared relation is a trap, and the full declaration is what lets the
  write-refusal test insert something PostgreSQL would otherwise accept.
- STEP1's and STEP3's checks share one test class, since both read the migrated
  schema back out of PostgreSQL for the same reason.

### Rule-to-test mapping

Every acceptance criterion of [spec-2](./spec-2.md), as the named test that
executes it (tests carry no criterion numbers by design — code never cites
planning artifacts):

| Criterion | Test |
| --- | --- |
| AC1 | `ReadServiceSurfaceTest` — the public surface is exactly the five reads; every one of the five reads leaves every stored row and timestamp untouched |
| AC2 | `ReadServiceItemTest` — an item resolves by handle and by id, and never across a project boundary |
| AC3 | `ReadServiceSurfaceTest` — a failing read is always validation_failed or not_found, never a write's verdict |
| AC4 | `ReadServiceItemTest` — a fetched item carries every field, its parent, and its whole blocker set |
| AC5 | `ReadServiceListingTest` — a project holding no items lists an empty array rather than failing; `ReadServiceProjectTest` — an instance holding no projects lists an empty array rather than failing |
| AC6 | `ReadServiceListingTest` — an identical call returns an identical order, same-instant rows included |
| AC7 | `ReadServiceListingTest` — no filter returns every live item in the project |
| AC8 | `ReadServiceListingTest` — several values inside a part widen it |
| AC9 | `ReadServiceListingTest` — several parts narrow each other |
| AC10 | `ReadServiceListingTest` — a value outside its vocabulary is refused, even in a project holding nothing; a part supplied with no values at all is refused rather than answered emptily |
| AC11 | `ReadServiceListingTest` — the same value supplied twice changes nothing |
| AC12 | `ReadServiceListingTest` — the parent part matches an epic, or the absence of one |
| AC13 | `ReadServiceListingTest` — the release part matches an item's own assignment, so no leaf ever matches |
| AC14 | `ReadServiceListingTest` — a parent value naming something that is not an epic is a caller mistake |
| AC15 | `ReadServiceListingTest` — a listing considers live rows only, and a project emptied by deletion looks empty; `ReadServiceSurfaceTest` — no read takes anything but a reference and a filter |
| AC16 | `ReadServiceListingTest` — a deleted branch leaves the listing entirely, and filtering by it is not found |
| AC17 | `ReadServiceItemTest` — a handle taken over by a new item resolves to the live one, and the deleted id to nothing |
| AC18 | `ReadServiceReadinessTest` — exactly the unblocked todo leaves come back, and nothing else; a project whose every leaf is done offers nothing |
| AC19 | `ReadServiceReadinessTest` — a deleted todo leaf is offered nowhere |
| AC20 | `ReadServiceReadinessTest` — ready leaves come back newest created first, carrying their blocker sets; `ReadServiceSurfaceTest` — the readiness question takes no filter at all |
| AC21 | `ReadServiceProjectTest` — the listing shows the live projects newest first, and the deleted one nowhere; a project is fetched by its handle without any project being bound first; a deleted project is not found by its id or by its handle |
| AC22 | `ReadServiceConcurrencyTest` — a listing taken while another caller writes always shows fully committed items |

The deletion rules, which no spec numbers, are executed rule by rule in
`WriteServiceDeleteTest`: the row survives while every reference to it fails; an
epic takes its children and a project takes everything in it; a freed handle is
reusable at once, explicitly and by derivation; status and deletion are
independent in both directions; a second delete is `not_found`; an edge outlives
its blocker and stops constraining live work; and nothing stays live in a
project deleted while four callers write into it.

The supporting checks: `SoftDeleteSchemaTest` (the three handle rules read back
out of PostgreSQL carrying their condition and surviving as no plain
constraint, and the view's stored definition naming the mark in both places),
`DriftGuardTest` (the indexes the declarations build match the migrated
schema's, which is what the guard itself cannot see), `ReadTransactionTest` (a
write inside a read transaction refused by the database, through a table and
through the view alike, and the same write accepted outside one — so the
refusal is the transaction's doing), `ReferenceResolutionTest` (id-first,
slug-second, project-scoped, live-only), and `WriteServiceSurfaceTest` (exactly
nine mutations; no command carrying a field that could ask for a deleted row
back, since a way back would arrive as a parameter long before it arrived as an
operation; and both deletes returning nothing).
