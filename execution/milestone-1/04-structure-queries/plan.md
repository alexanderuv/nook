# Structure queries — Plan

A note on references: this plan leans on the two documents beside it.
[Spec-2](./spec-2.md) numbers the behavior it pins — REQ for requirements, EDGE
for edge cases, AC for acceptance criteria. [The discovery](./discovery.md)
numbers its findings FIND and its open questions Q. When this plan cites one of
those codes it is pointing at a numbered entry in those documents, and it states
the point in plain words alongside the citation — the pointer is a cross-check,
not required reading.

> **Reversal, recorded after the fact.** This plan was executed as written, on a
> soft-delete design: a `deleted_at` mark, the three handle rules rebuilt as
> partial unique indexes, and the readiness view recreated to know about the
> mark. It shipped green and was then reversed, because the partial index broke
> a decision the plan never checked — `ARCHITECTURE.md`, `ADR-1`, and
> `db/README.md` all state that the schema carries **no partial or filtered
> indexes**, and a build plan is not where that gets overturned. Deletion now
> removes the row, which needs no schema change at all: changeset
> `0002-soft-delete.yaml` was deleted rather than reverted, leaving the schema
> exactly as `0001` built it. The text below has been corrected to describe what
> shipped; the steps stay ticked because the work behind them was done, and
> where a step's subject no longer exists it says so. What the reversal costs is
> recorded in [structure semantics](../../../docs/04-structure-semantics.md):
> the git cleanup a delete owes once documents exist is now an open problem
> rather than one the mark quietly deferred.

## Analysis

What already exists, checked against the repository rather than remembered:

- **The write path built by epic 03**, in package `io.nook.core.write` of
  `:core-service`. `WriteService` holds seven public operations and nothing
  else. `WriteTransactions.kt` opens every write in one fresh transaction with
  re-runs disabled, and each operation takes a lock before touching anything —
  a PostgreSQL advisory lock, meaning a lock the application asks for on a
  number of its own choosing, released when the transaction ends. The number is
  the project's identifier folded to one long (`projectLockKey`), or a single
  fixed instance-wide value for creating a project. `References.kt` turns a
  caller's reference into a row: a value shaped like a UUID is looked up as an
  identifier, anything else as a handle, and item and release lookups are
  confined to the project in hand. `RowMappings.kt` turns rows into the shared
  entity classes and reads an item's blocker set (`blockersOf`). `Slugs.kt` and
  `CycleDetection.kt` hold the pure rules — code that takes input and returns
  output without touching a database.

- **The most recent change to that path, which this plan takes as its
  starting point: validation decides, never the store.** `Errors.kt` no longer
  translates the database's own complaints into caller errors; a rejection
  arriving from the store is now reported as a fault in the service, because
  every rule the store enforces is already checked in application code inside
  the lock. The database driver was moved to runtime-only in
  `core-service/build.gradle.kts`, and a build check named
  `checkDriverStaysAtRuntime` fails the build if it ever reaches the compile
  classpath — so no source file can name a driver type. `StoreNeverArbitratesTest`
  walks every rule a caller can actually reach through the service and requires
  a structured error back. This work sits in the working tree unpushed at the
  time of writing; everything below assumes it lands first. Its consequence for
  this epic: each new rule here is checked in application code under the lock,
  nothing leans on the store to report a caller's mistake, and no new code names
  a driver type.

- **Epic 02's foundation.** `core-service/src/main/kotlin/io/nook/core/db/Tables.kt`
  describes every table in Kotlin through Exposed, the library this project uses
  to talk to the database; `DriftGuardTest` fails the build if those
  descriptions and the migrated database ever stop matching; `Migrations.kt`
  applies the change history under `db/changelog/` from inside the running
  process; and `EmbeddedPostgresSupport.freshMigratedDatabase()` starts a real
  PostgreSQL inside the test process and hands each test class its own migrated
  database.

- **What the schema already provides, which turned out to be everything.** The
  handle uniqueness rules `uq_project_slug`, `uq_item_project_slug`, and
  `uq_release_project_slug` are plain constraints covering every row — which is
  exactly right once a delete removes the row, since the name goes with it. The
  readiness view — changeset `0007-create-ready-item-view` inside
  `db/changelog/changes/0001-initial-schema.yaml` — selects leaves that are
  `todo` with no blocker outside `done` and `cancelled`, and needs no clause
  about deletion, because a deleted blocker's edge is gone. `project` cascades
  on delete to releases, items, documents and the sequence table, and an item
  cascades to its blocker edges from either end. The one thing no cascade
  covers is an epic's children: that link is deliberately `NO ACTION`, so the
  write path removes them itself.

- **Two pieces of epic 03's test code this epic disturbs.**
  `WriteServiceSurfaceTest` asserts the write service's public methods are
  exactly the seven mutations, and `TestReads.kt` reads state back through the
  tables directly, because until now the service had no reads at all.

What this plan builds to, linked rather than restated:

- **[Spec-2](./spec-2.md)** is the behavior contract for the five reads —
  `get_project`, `list_projects`, `get_item`, `list_items`, `get_ready_items` —
  pinned as 26 requirements, 16 edge cases, and 22 acceptance criteria. The test
  suite below is that contract, executed.

- **[The discovery](./discovery.md)** settled how the reads get built by running
  probes against real PostgreSQL. Adopted here:
  - *Superseded.* A handle can be freed by a soft delete, but only if its
    uniqueness rule is rebuilt as an index limited to rows with no mark —
    PostgreSQL refuses to put such a condition on a plain constraint (FIND1).
    That index is the decision this epic ultimately would not pay for; removing
    the row frees the handle under the plain constraint the schema already has.
  - *Superseded with the mark.* The readiness view would have had to be
    recreated in the same schema change (FIND4, FIND3). Removing the row leaves
    the committed view correct as it stands.
  - Every read runs in one transaction that is both read-only and set to
    repeatable read — the setting under which the whole transaction sees the
    store as of its first statement. Read-only makes "a read changes nothing"
    something the database enforces rather than something the code remembers,
    and repeatable read is what stops a two-query answer from describing a
    moment that never existed (FIND9, FIND5).
  - A listing's blocker sets are fetched with one extra query covering every
    listed identifier, not one query per item — about five times faster at 510
    items for the same result (FIND8).
  - A filter part supplied with an empty list of values must be rejected in
    application code: the query builder quietly turns it into a condition that
    matches nothing and reports success (FIND6).

  The discovery also ruled things out; do not re-investigate them: a uniqueness
  constraint carrying a condition does not exist in PostgreSQL (FIND1); the
  drift guard, pointed at a view, proposes creating a table (FIND5); and
  declaring the view for reading does not stop code writing through it, because
  a view this simple is writable by default (FIND5).

- **Deleting has no spec of its own, so this plan is its contract.**
  [Structure semantics](../../../docs/04-structure-semantics.md) settles what it
  does; that section was rewritten when the mark was dropped. The rules the
  build must satisfy, in full:
  - Deleting removes the row. There is no mark and no trash, so a deleted row is
    indistinguishable from one that never existed — not by a rule each query
    applies, but because there is nothing left to find.
  - No read returns a deleted row, and a reference naming one — by handle or by
    identifier — is `not_found`. No argument anywhere asks for deleted rows.
  - Deleting is independent of status. An item may be `cancelled` and then
    deleted, or deleted while still `todo`.
  - Deleting an epic deletes its children with it, and deleting a project
    deletes everything in it — releases, items, blocker edges, document rows.
    Nothing survives a deletion above it.
  - A deleted row gives up its handle, because the row holding it is gone; the
    name can be taken again at once, under the plain uniqueness rule.
  - A deleted item stops blocking, because its edges go with it, and is itself
    never offered as ready work.

  **There is no restore, by decision, and now no way to offer one.** With the
  row gone there is nothing in the store to bring back: recovering a mistaken
  delete is a restore-from-backup question, not an operation this service could
  grow. That is a sharper trade than the mark made, and it is the trade the
  schema's plain-SQL discipline is worth.

- **[PRD-1](../prd-1.md)** frames the epic: its requirement REQ4 asks for the
  listing filter and the readiness query; its goal GOAL1 makes readiness the
  milestone's payoff; its goal GOAL3 wants every settled rule mapped to a named
  test.

Constraints that bound the change:

- **Epic 03's spec says the write surface is exactly seven mutations, and this
  epic makes it nine.** That spec is finished work and is not being edited, so
  the divergence is recorded here instead: the two operations added below are
  this plan's, built to the rules above, and epic 03's surface test is updated
  to match the code rather than the other way round.
- **The instance-wide operation catalog is not touched.** It lists twelve
  operations and does not include deleting anything; amending it is the business
  of the document that owns it, not of a build plan. The consequence, stated
  plainly: this milestone's agent surface and web API will not offer deletion,
  and the two actions are reachable only from inside the core service.
- **Actor fields stay as they are** — `created_by`, `updated_by`, and
  `owner_subject` keep their `system` defaults, since plumbing a real actor
  through is epic 08.
- **Paging, free-text search, sort options, and authentication are out**,
  deferred by the design documents themselves. A listing returns everything it
  matches, in one ordering, to anyone who asks.
- **The document tables belong to milestone 2** and no operation here touches
  them.

## Approach

Build the deletion groundwork first, then the five reads on top of it — the
order the discovery recommends, because every read requirement about deleted
rows is untestable until a deleted row can be produced through the service
rather than faked with hand-written SQL.

- **No schema change at all.** This is where the plan was reversed. It was
  built as a changelog file adding a `deleted_at` mark, rebuilding the three
  handle rules as partial unique indexes, and recreating the readiness view —
  and that changeset was then deleted, because the partial index is the one
  engine-specific feature the schema refuses. Removing the row instead needs
  nothing the schema does not already have: the plain uniqueness rules free a
  handle when its row goes, the cascades reach releases, items, edges and
  document rows, and the committed readiness view is already correct, since a
  deleted blocker has no edge left to hold anything up.

- **Two new operations on the existing write service**, not a second service:
  deleting an item and deleting a project. Projects need their own because
  spec-2 requires that a deleted project vanish from the listing and that its
  identifier stop resolving (AC21), and the only honest way to produce a deleted
  project is the operation that deletes it. Deleting an item runs under the
  project's lock. Deleting a project takes two locks, in a fixed order: the
  instance-wide one, because a project's handle is unique across the whole
  instance and freeing one must not race a creation scanning those handles; then
  the project's own, because a writer already inside it would otherwise leave a
  row behind in a project that is gone. No other operation holds both, so the pair
  cannot deadlock. That is not sufficient on its own — every write resolves its
  project *before* taking the lock keyed on it, so the project can be deleted in
  the gap; each write therefore re-reads the project under the lock, which is the
  only place the answer holds still. Deleting a project marks its releases and
  items in the same transaction — nothing may outlive a deletion above it and
  stay visible. Neither delete returns an entity: the row is out of every
  caller's reach the moment it commits, so there is nothing left to hand back.
  Releases get no delete of their own here — which operations exist is the
  catalog's business, and no read in this epic returns a release — but the store
  is ready for one.

- **A shared home for the pieces both paths need.** Reference resolution, the
  row-to-entity mapping, and the stored-code maps move from the write package
  into a package both paths use — `io.nook.core.store`, sitting above the schema
  declarations in `io.nook.core.db` and below both services. Reads must return
  exactly the entities writes return (REQ6), and resolving a reference means the
  same thing on both sides; two copies of either would drift. Two smaller pieces
  follow them because resolution needs them: the rule deciding that a reference
  is written in UUID form, and the two failures both paths raise — the other
  two, conflict and cycle, stay in the write package, where they are the only
  ones that can arise. The move is otherwise mechanical: the code is internal to
  the module, so no visibility changes and no behavior changes at all.

- **The reads as their own service** in a new package, with exactly five public
  operations. Every one of them opens a transaction that is read-only and set to
  repeatable read, and takes no lock: locking is a write discipline, and a
  read-only transaction reading one moment needs nothing else. Validation runs
  the same way as on the write side — the vocabulary check, the empty-value
  check, and the rule that a parent filter naming a leaf is a caller mistake are
  all decided in application code before the store is asked anything.

- **Nothing to filter out, in any of the five.** Because a delete removes the
  row, no read carries a clause about deletion and no operation can forget one.
  There is no argument, filter value, or operation by which a caller could ask
  for a deleted row — the absence is the design, and a test asserts the surface
  offers no such door. The entity classes carry no deleted mark for the same
  reason there is no column: the state does not exist.

  This is what the reversal bought back. Under the mark, every query on both
  paths needed the same live-only clause, and the ones that were missed —
  cycle detection, an epic's children, the edges blocking a type change — each
  refused a caller's work for a reason built out of rows they could not see.
  Removing the row deletes that whole class of mistake rather than guarding
  against it.

Why this way rather than the obvious alternative: computing readiness in
application code instead of reading the view would put the same rule in two
places, and the spec explicitly hangs readiness on the view (ASM3). The one
genuine alternative the discovery weighed — fetching each item's blockers with
its own query instead of one query for the whole listing — lost on measurement.

Unverified assumption, named: nothing has yet performed this schema change
through Liquibase. The discovery applied its trial schema as loose SQL, so
dropping a uniqueness constraint and creating a conditional index in its place,
as a migration, is unproven. That is why it is the first step, before any code
depends on it.

Blast radius — what this change touches: `db/changelog/` (one new file, and the
master changelog's comment), `Tables.kt`, `:contract` main sources (the listing
filter types; the entity classes are untouched), the write package (two new
operations and the move of the shared pieces), a new read package, and both
modules' tests. What it must leave untouched:
`db/changelog/changes/0001-initial-schema.yaml` — already applied everywhere, so
it is history, not a document to edit — the instance-wide interface document and
the milestone's requirements document, epic 03's spec, `gradle/libs.versions.toml`
(no new dependencies), `:mcp-server`, `:web-app`, `build-logic/`, `Main.kt`, and
the document tables.

## Steps

- [x] **STEP1** — *Reversed.* Written as
  `db/changelog/changes/0002-soft-delete.yaml`: the `deleted_at` column on all
  three tables, the three handle rules dropped as constraints and recreated as
  partial unique indexes, and the readiness view rebuilt around the mark. It
  applied cleanly and its tests passed. The changeset was then deleted outright
  — no database outside a test run had ever held it — so the schema is once
  again exactly what `0001` builds, and the master changelog's claim that the
  schema uses no engine-specific features is true again without an edit.

- [x] **STEP2** — *Partly reversed.* `Tables.kt` gained the column and the
  live-only handle rules, and both went with the changeset. What survives is the
  test the step existed for: `DriftGuardTest` now builds the schema from the
  declarations onto an empty database and compares every index PostgreSQL
  actually created against the migrated schema's, definitions and all. The drift
  guard alone compares which rules exist, not what they cover, and that gap is
  worth closing whether or not any rule is conditional.

- [x] **STEP3** — *Reversed with the view.* A test asserted the rebuilt view's
  stored definition named the mark in both places it had to. The view is
  unchanged from `0001`, so the test went with the changeset; readiness is
  covered behaviourally by the readiness tests instead.

- [x] **STEP4** — Move reference resolution, the row-to-entity mapping, and the
  stored-code maps out of `io.nook.core.write` into a package both paths use;
  verify: every existing write-path test passes with nothing changed but its
  imports, and `./gradlew check` stays green — a move that changes anything else
  has gone wrong. A resolver test covers a row that has been removed, under both
  reference forms. The resolver's own tests move with it, the reference-form
  test among them: it belongs beside the rule it exercises, not beside the slug
  rules it was filed with.

- [x] **STEP5** — Implement deleting an item on the write service: under the
  project's lock, remove the row, and an epic's children in the same
  transaction; deleting something already deleted is `not_found`, like any other
  reference to nothing; verify: named tests cover each rule of the delete
  contract above — the row is gone from the store rather than marked in it,
  deleting an epic takes its four children, an item takes its blocker edges from
  both ends, the freed handle is immediately reusable by a new item, the delete
  neither reads nor writes a status, and a second delete comes back
  `not_found`.

- [x] **STEP6** — Implement deleting a project under the instance-wide lock and
  then the project's own, removing the project and letting the schema's cascade
  take its releases, items, edges and document rows; make every write
  re-read its project under the lock, since the resolution that produced the lock
  key ran before the lock was held; update `WriteServiceSurfaceTest` to the nine
  operations the service now offers; verify: named tests show a deleted project
  gone from the listing of projects with its handle immediately reusable, every
  item it held gone from that project's listing, four callers creating items
  throughout the deletion leaving nothing live behind them, and the surface test
  passing with the two new names and no others.

- [x] **STEP7** — Build the read scaffold: the transaction helper that opens one
  read-only, repeatable-read transaction with re-runs disabled; the readiness
  view declared for reading and kept out of the drift guard's set; and the
  listing filter types in `:contract` — each part optional and multi-valued, and
  the parent part whose values are each either an epic reference or the reserved
  "no epic at all"; verify: a test attempts a write inside a read transaction
  and the database itself refuses it, and another attempts a write through the
  view declaration inside such a transaction and is refused the same way.

- [x] **STEP8** — Implement `get_project` and `list_projects` on the new read
  service: resolution across the whole instance rather than inside a project,
  and newest-first ordering with the identifier as tiebreak;
  verify: the named tests for AC21 pass, and a test shows the same call repeated
  ten times returning one and the same order.

- [x] **STEP9** — Implement `get_item`: identifier or handle, an item from
  another project reported as not found, and a deleted item not found under
  either reference form; verify: the named tests
  for AC2, AC4, and AC17 pass.

- [x] **STEP10** — Implement `list_items`: every filter part optional, several
  values inside a part widening it and several parts narrowing each other, the
  reserved no-parent value, the ordering, and the blocker sets
  fetched with one extra query for the whole listing. Rejected in application
  code: a value outside its vocabulary, a part supplied with no values, and a
  parent value naming something that is not an epic; verify: the named tests for
  AC5 through AC16 pass.

- [x] **STEP11** — Stress the listing against concurrent writes (AC22): one
  caller lists a project repeatedly while another creates items in it, 100
  repetitions; verify: every listing returns only fully committed items, each
  with its blocker set as committed, and no call fails. This runs here rather
  than at the end because it is the first proof that the two-query listing
  really describes one moment (FIND9).

  A soak alone does not prove that, though — it never caught the anomaly by
  luck, in either direction, so on its own it would pass just as happily
  without the discipline. Pair it with the shape the discovery used: run a
  listing's two statements by hand with a writer committing between them, once
  at each setting, and require the anomaly to appear at PostgreSQL's normal one
  and to be gone at repeatable read. The soak then says the discipline holds
  under load; the pair says it is what is holding.

- [x] **STEP12** — Implement `get_ready_items`: read the view, take no filter,
  order as every other listing does, and carry blocker sets like any other item
  result; verify: the named tests for AC18, AC19, and AC20 pass — including a
  leaf whose only blocker was deleted coming back ready, and a deleted leaf
  coming back nowhere.

- [x] **STEP13** — Close the surface: a test asserts the read service offers
  exactly the five operations, and that no operation and no filter type carries
  any way to ask for deleted rows; a test calls all five against a seeded
  project and asserts every stored row and every stored timestamp is unchanged
  afterwards (AC1); and a test asserts no read in the suite ever produces
  `conflict` or `cycle` (AC3); then run `./gradlew check` on a clean checkout
  and push for the CI run; verify: check green locally and in CI with the new
  tests visibly executed.

## Caveats & rabbit holes

- **no-go: editing the operation catalog or the milestone's requirements
  document** — adding deletion there would decide, from a build plan, what later
  epics expose; instead: leave both as they stand, and raise the exposure
  question with the document that owns the catalog if it matters.

- **no-go: a way back for a deleted row** — no restore operation, no "undelete"
  option on an update, no flag that turns them back on for debugging; each looks
  like a kindness and each reopens the decision that deletion is final; instead:
  if recovery turns out to be needed, it is a restore-from-backup question for
  the document that owns deletion, since the row itself is gone.

- **no-go: editing epic 03's spec to match the new surface** — it is finished
  work, and rewriting a settled contract to agree with newer code hides the
  change; instead: the divergence is recorded in this plan's Analysis, and only
  the surface test moves.

- **no-go: a deleted row that half-survives** — a mark, a trash listing, an
  archived flag; each reintroduces the two-state store this design exists to
  avoid, and with it the live-only clause every query then has to remember;
  instead: the row goes, and the absence of any such clause is the proof.

- **no-go: naming a database driver type** — the build check keeps the driver
  off the compile classpath, so reading a rejection's constraint name off a
  driver exception cannot compile; instead: check the rule in application code
  under the lock, which is where every caller-facing decision is made.

- **caveat: the drift guard compares which rules exist, not what they cover**
  (FIND2) — nothing in it would notice an index quietly rebuilt with a different
  reach; instead: the index-definition comparison added in STEP2 reads both
  schemas back out of PostgreSQL and is the real guard.

- **no-go: letting the drift guard see the readiness view** — pointed at a view
  it proposes creating a table (FIND5); instead: the view's declaration stays
  out of the set the guard compares, and the view is checked by its own
  definition test.

- **caveat: a view fixes its column list when it is created** (FIND4) — any
  future changeset adding a column to `project_item` leaves `ready_item`
  silently without it; instead: recreate the view in the same changeset as any
  such column, never in a later one.

- **rabbit-hole: one blocker query per listed item** — it reads more naturally
  and is about five times slower at 510 items, for an identical result (FIND8);
  instead: one extra query for the whole listing, which also keeps the answer
  inside the two statements the transaction discipline makes consistent.

- **caveat: an empty list of filter values is not an empty filter** — the query
  builder folds it into a condition matching nothing and reports success, which
  is exactly the silent wrong answer the spec replaces with a complaint (FIND6,
  EDGE2); instead: reject it in application code, and leave the part out
  entirely to mean "don't filter on this".

- **rabbit-hole: rewriting the write path's test helper to use the new reads** —
  `TestReads.kt` exists because the service had no reads, and now it does;
  instead: leave epic 03's tests reading the tables directly. Their job is to
  prove the write path, and routing them through another service under
  construction would blur which side failed.

- **rabbit-hole: a query abstraction between the read service and the
  database** — five operations do not justify one, exactly as seven mutations
  did not; instead: use the data-access library directly, and let a later epic
  revisit if a third caller ever wants the same queries.

- **rabbit-hole: measuring how the read discipline behaves under load** — how a
  read-only repeatable-read transaction per read holds up under real
  multi-agent load, and at what size an unpaginated listing stops being
  acceptable, are the discovery's open questions Q8 and Q7; instead: leave both
  open on the discovery — this epic proves the reads correct, not cheap.

- **caveat: deletion is not a status** — the temptation is to fold it into the
  status vocabulary or to make deleting imply `cancelled`; instead: the two are
  independent, and a test says so.

## Test plan

- **TEST1** — *reversed with the changeset.* It checked that the migration
  applied and that all three handle indexes carried their live-only condition.
  What remains of it is the unchanged migration test: the whole changelog
  applies to a fresh database, and a second run is a no-op.

- **TEST2** — integration: the drift guard stays green, and every index the
  declarations produce on an empty database matches the migrated schema's,
  definition for definition.

- **TEST3** — integration: the delete contract, rule by rule, through the
  service — the row is gone from the store rather than marked in it; an epic
  takes its children, an item takes its blocker edges from both ends, and a
  project takes everything in it; a freed handle is immediately reusable; a
  reference to a deleted row is `not_found` by handle and by identifier alike;
  and deleting neither reads nor writes a status.

- **TEST4** — integration: all 22 acceptance criteria of spec-2 as named tests
  against the embedded PostgreSQL, each named after the behavior it executes —
  the rule-to-test mapping PRD-1's GOAL3 asks for, delivered for this epic.

- **TEST5** — integration: reads change nothing — every stored row and
  timestamp identical after all five operations run against a seeded project
  (AC1) — and a write attempted inside a read transaction, and through the view
  declaration, is refused by the database itself.

- **TEST6** — integration: the listing under concurrent writes at 100
  repetitions (AC22), green on every run.

- **TEST7** — unit: the read service's public surface is exactly the five
  operations and offers no way to ask for a deleted row, and the write service's
  surface is exactly the nine mutations, with nothing that clears a mark.

- **TEST8** — build: `./gradlew check` green on a clean checkout, with the
  driver still absent from the compile classpath and `:contract` still free of
  any database dependency.

- **Standing check, comment hygiene** — search the final diff for artifact
  tokens (STEP, REQ, GOAL, FIND, AC, EDGE, PRD, epic) and `.md` paths in code
  and code comments; expect zero hits — those citations belong in documents like
  this one, never in code.

- **Standing check, conformance sweep** — re-read this plan against the final
  diff: every step ticked with its verification observed, the blast radius
  respected, every caveat honored, and any mid-build divergence already folded
  back into this text.

- Run both standing checks through a separate agent handed only this plan and
  the final diff — a fresh reader sees only what is there, while the builder
  reads its own intent into the diff.

Done when: a clean checkout runs `./gradlew check` green locally and on CI; all
22 acceptance criteria of spec-2 pass as named tests; the delete contract above
passes rule by rule as named tests; the listing stress holds at 100 repetitions;
the read service's surface is exactly the five reads and the write service's
exactly the nine mutations; no operation anywhere offers a way to see or revive
a deleted row; and no read in the suite writes a row, advances a timestamp, or
produces `conflict` or `cycle`.

## Rollback

There is no schema change to reverse, which is most of what this section used
to be about. Reverting the code is enough, and it leaves a database built by
`0001` exactly as it was.

What a revert cannot undo is the deletes themselves: rows removed are gone, and
only a backup brings them back. That is the standing cost of the design, not a
property of the revert — and it is the reason the git side of deletion
([structure semantics](../../../docs/04-structure-semantics.md)) has to be
settled before documents exist rather than after.
