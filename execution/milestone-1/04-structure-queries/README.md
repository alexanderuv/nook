# Epic 04 — Structure queries

**Addresses:** REQ4 (`list_items` filters by type/status/parent/release,
`get_ready_items` from the `ready_item` view, newest-first default).

Documents, in the order they were produced:

- [spec-2.md](./spec-2.md) — the behavior contract for the five reads
  (`get_project`, `list_projects`, `get_item`, `list_items`,
  `get_ready_items`): requirements, edge cases, and acceptance criteria.
- [discovery.md](./discovery.md) — ten probes against real PostgreSQL settling
  how the reads get built: how the listing filter composes, why the ordering
  needs a tiebreak, why blocker sets are fetched in one query, and why every
  read wants a read-only transaction that reads one moment. Its soft-delete
  recommendation was not taken — see the note at its head and the reversal note
  in the plan.
- [plan.md](./plan.md) — the build route, steps ticked as execution proceeds.
  No spec pins deletion, so the plan carries its rules itself, taken from the
  structure-semantics document.

## Results

The five reads landed: `:core-service` gained two packages — `io.nook.core.store`,
holding reference resolution, the row-to-entity mapping, and the two failures
both paths raise; and `io.nook.core.read`, holding one `ReadService` whose public
surface is exactly the five reads, each running in a read-only transaction that
reads one moment and takes no lock. `WriteService` grew to nine mutations with
`deleteItem` and `deleteProject`. `:contract` gained the listing filter. Deletion
itself needed no schema change — `0001` already had everything it takes.

That last point is the epic's real story, and it took a wrong turn to reach.
Deletion was built as the plan specified: a `deleted_at` mark, the three handle
rules rebuilt as partial unique indexes so a deleted row would give up its slug,
and the readiness view recreated around the mark. It shipped green, on CI, and
was then reversed — because `ARCHITECTURE.md`, `ADR-1`, and `db/README.md` all
state the schema carries **no partial or filtered indexes**, and the plan had
retired that decision by amending a single changelog comment. A build plan is
not where an architecture decision gets overturned. Changeset
`0002-soft-delete.yaml` was deleted outright rather than reverted — no database
outside a test run had ever held it — so the schema is again exactly what `0001`
builds, and all four statements are true again without an edit.

Removing the row instead of marking it turned out to be the smaller design, not
merely the compliant one. Under the mark, every query on both paths needed the
same live-only clause, and the three that were missed — cycle detection, an
epic's children, the edges blocking a type change — each refused a caller's work
for a reason built out of rows they could not see. Removing the row deletes that
whole class of mistake instead of guarding against it, and the schema's existing
cascades already reach releases, items, blocker edges and document rows.

What it costs, recorded where the decision lives
([`docs/04`](../../../docs/04-structure-semantics.md)): the mark was also how a
delete avoided orphaning git documents, since git is not part of any `ON DELETE
CASCADE`. This milestone has no documents and no operation touching those
tables, so nothing is orphaned — but the document layer now has to settle what a
delete does to git content, rather than inheriting an answer. And with the row
gone, recovering a mistaken delete is a restore-from-backup question, not an
operation this service could grow.

Deviations from the plan, all folded into its text:

- Deleting a project takes the project's lock as well as the instance-wide one,
  and every write re-reads its project inside the lock. Without the second read
  a caller could write into a project already removed, and the store would
  refuse it on a foreign key — reaching the caller as a fault in the service
  rather than the `not_found` it is.
- Neither delete returns an entity: the row does not exist once the call
  commits, so there is nothing left to hand back.
- `validationFailed` and `notFound` moved into the shared package alongside
  resolution, which needs them; `conflict` and `cycle` stayed with the write
  path, the only place they arise. The rule deciding a reference is in UUID form
  moved with resolution too.
- The concurrency step gained a deterministic pair beside the soak. The soak
  never caught the anomaly by luck at either isolation setting, so on its own it
  proved nothing; the pair runs a listing's two statements by hand around a
  commit and requires the anomaly at PostgreSQL's normal setting and its absence
  at repeatable read.
- The readiness view is declared column for column though only two are read: a
  half-declared relation is a trap, and the full declaration is what lets the
  write-refusal test insert something PostgreSQL would otherwise accept.
- `DriftGuardTest` gained an index-definition comparison — built from the
  declarations onto an empty database, read back out of PostgreSQL, matched
  against the migrated schema's. It was added to guard the partial index's
  condition and kept afterwards: the drift guard compares which rules exist, not
  what they cover, and that gap is worth closing regardless.
- **The advisory locks went too.** Removing the partial index left
  `pg_advisory_xact_lock` as the last engine-specific SQL in the service, which
  no recorded decision forbade but which made "plain standard SQL" true only of
  the schema. Writers now take a turn by locking a row `FOR UPDATE`. A writer
  inside a project locks that project's own row, which is strictly better than a
  named lock: locking the row and checking the project is still there became one
  statement, deleting the double-read the delete race had needed. The two writes
  contending over the instance-wide space of project handles have no row to
  lock, so changeset `0002-instance-lock` adds one — a table holding nothing,
  one row per scope, seeded by the changelog. Its absence is treated as a broken
  schema, never as a licence to skip the turn.

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
| AC7 | `ReadServiceListingTest` — no filter returns every item in the project |
| AC8 | `ReadServiceListingTest` — several values inside a part widen it |
| AC9 | `ReadServiceListingTest` — several parts narrow each other |
| AC10 | `ReadServiceListingTest` — a value outside its vocabulary is refused, even in a project holding nothing; a part supplied with no values at all is refused rather than answered emptily |
| AC11 | `ReadServiceListingTest` — the same value supplied twice changes nothing |
| AC12 | `ReadServiceListingTest` — the parent part matches an epic, or the absence of one |
| AC13 | `ReadServiceListingTest` — the release part matches an item's own assignment, so no leaf ever matches |
| AC14 | `ReadServiceListingTest` — a parent value naming something that is not an epic is a caller mistake |
| AC15 | `ReadServiceListingTest` — deleted items are absent, and a project emptied by deletion looks like one that never held anything; `ReadServiceSurfaceTest` — no read takes anything but a reference and a filter |
| AC16 | `ReadServiceListingTest` — a deleted branch leaves the listing entirely, and filtering by it is not found |
| AC17 | `ReadServiceItemTest` — a handle taken over by a new item resolves to it, and the old id to nothing |
| AC18 | `ReadServiceReadinessTest` — exactly the unblocked todo leaves come back, and nothing else; a project whose every leaf is done offers nothing |
| AC19 | `ReadServiceReadinessTest` — a deleted todo leaf is offered nowhere |
| AC20 | `ReadServiceReadinessTest` — ready leaves come back newest created first, carrying their blocker sets; `ReadServiceSurfaceTest` — the readiness question takes no filter at all |
| AC21 | `ReadServiceProjectTest` — the listing shows the live projects newest first, and the deleted one nowhere; a project is fetched by its handle without any project being bound first; a deleted project is not found by its id or by its handle |
| AC22 | `ReadServiceConcurrencyTest` — a listing taken while another caller writes always shows fully committed items |

The deletion rules, which no spec numbers, are executed rule by rule in
`WriteServiceDeleteTest`: the row is gone from the store rather than marked in
it; an epic takes its children, an item takes its blocker edges from both ends,
and a project takes everything in it; a freed handle is reusable at once,
explicitly and by derivation; status and deletion are independent in both
directions; a second delete is `not_found`; and nothing survives a project
deleted while four callers write into it.

The supporting checks: `DriftGuardTest` (the declarations match the migrated
schema, index definitions included), `ReadTransactionTest` (a write inside a
read transaction refused by the database, through a table and through the view
alike, and the same write accepted outside one — so the refusal is the
transaction's doing), `ReferenceResolutionTest` (id-first, slug-second,
project-scoped, and nothing found once the row is removed), and
`WriteServiceSurfaceTest` (exactly nine mutations; no command carrying a field
that could ask for a deleted row back, since a way back would arrive as a
parameter long before it arrived as an operation; and both deletes returning
nothing), and `WriteLockTest` (a second writer provably waits on both the
project row and the instance lock row, a project that is not there is
`not_found` rather than an unlocked pass, and a missing lock row is reported as
a broken schema instead of silently forfeiting the turn).
