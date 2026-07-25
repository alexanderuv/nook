# Structure queries — Plan

A note on references: this plan leans on the two documents beside it.
[Spec-2](./spec-2.md) numbers the behavior it pins — REQ for requirements, EDGE
for edge cases, AC for acceptance criteria. [The discovery](./discovery.md)
numbers its findings FIND and its open questions Q. When this plan cites one of
those codes it is pointing at a numbered entry in those documents, and it states
the point in plain words alongside the citation — the pointer is a cross-check,
not required reading.

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

- **What the schema does not have yet.** No table carries a deleted mark. The
  handle uniqueness rules `uq_project_slug`, `uq_item_project_slug`, and
  `uq_release_project_slug` are plain constraints covering every row, so a name
  is locked whether or not its row is still wanted. The readiness view — changeset `0007-create-ready-item-view`
  inside `db/changelog/changes/0001-initial-schema.yaml` — selects leaves that
  are `todo` and have no blocker outside `done` and `cancelled`; it knows
  nothing of deletion. The master changelog carries a comment claiming the
  schema uses no database-specific features.

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
  - A handle can be freed by a delete, but only if its uniqueness rule is
    rebuilt as an index limited to rows with no deleted mark — PostgreSQL
    refuses to put such a condition on a plain constraint (FIND1).
  - The readiness view must be recreated in the same schema change that adds
    the mark, because a view fixes its column list when it is created and would
    otherwise never show the new column (FIND4); the recreated view also has to
    skip deleted items and count a deleted blocker as resolved, since today's
    view offers a deleted item as ready work and lets a deleted blocker hold up
    everything behind it (FIND3).
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
  drift guard cannot see the condition that limits a rule to live rows, and
  pointed at a view it proposes creating a table (FIND2, FIND5); and declaring
  the view for reading does not stop code writing through it, because a view
  this simple is writable by default (FIND5).

- **Deleting has no spec of its own, so this plan is its contract.**
  [Structure semantics](../../../docs/04-structure-semantics.md) settles what it
  does — its deletion section was corrected while this plan was written, and
  spec-2 with it, when deleted rows stopped being something a caller can ask
  for. The rules the build must satisfy, in full:
  - Nothing is ever physically removed. Deleting sets the row's mark and the row
    stays in the store — for history, and so that no git document is left
    pointing at nothing.
  - The mark is for the store, not the caller. No read returns a deleted row,
    and a reference naming one — by handle or by identifier — is `not_found`.
    No argument anywhere asks for deleted rows.
  - Deleting is independent of status. An item may be `cancelled` and then
    deleted, or deleted while still `todo`.
  - Deleting an epic deletes its children with it, and deleting a project
    deletes everything in it. Nothing survives a deletion above it and stays
    visible.
  - A deleted row gives up its handle, so the name can be taken again at once.
  - A deleted item stops blocking, exactly as a `cancelled` one does, and is
    itself never offered as ready work.

  **There is no restore, by decision.** A delete cannot be undone through the
  service: the row survives in the store, but nothing reads it back and no
  operation clears the mark. Bringing a row back is deferred alongside physical
  removal, and until someone specifies it, recovering a mistaken delete means
  hand-written SQL. This is what makes the deletion rules above short — there is
  no returning branch, and no handle to reclaim.

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

- **The schema change.** One new changelog file adds a nullable timestamp
  `deleted_at` to `project`, `release`, and `project_item`, rebuilds all three
  handle uniqueness rules as indexes limited to rows with no mark, and recreates
  the readiness view so that it skips deleted items and counts a deleted blocker
  as resolved. A nullable timestamp rather than a true/false flag: it costs
  nothing extra and answers a question a flag cannot, namely when the row left.
  Every entity kind carries the mark and the live-only handle rule, including
  releases, which no operation deletes yet: the store either supports deletion
  or it does not, and a table left out becomes the exception someone has to
  discover and migrate later. The same change amends the master changelog's
  claim that the schema uses no database-specific features, which the
  conditional index retires — PostgreSQL is the only supported engine anyway.

- **Two new operations on the existing write service**, not a second service:
  deleting an item and deleting a project. Projects need their own because
  spec-2 requires that a deleted project vanish from the listing and that its
  identifier stop resolving (AC21), and the only honest way to produce a deleted
  project is the operation that deletes it. Deleting an item runs under the
  project's lock; deleting a project runs under the instance-wide lock, because
  a project's handle is unique across the whole instance and freeing one must
  not race a creation scanning those handles. Deleting a project marks its
  releases and items in the same transaction — nothing may outlive a deletion
  above it and stay visible. Releases get no delete of their own here — which
  operations exist is the catalog's business, and no read in this epic returns a
  release — but the store is ready for one.

- **A shared home for the pieces both paths need.** Reference resolution, the
  row-to-entity mapping, and the stored-code maps move from the write package
  into a package both paths use. Reads must return exactly the entities writes
  return (REQ6), and resolving a reference means the same thing on both sides;
  two copies of either would drift. The move is mechanical — the code is
  internal to the module, so no visibility changes and no behavior changes.

- **The reads as their own service** in a new package, with exactly five public
  operations. Every one of them opens a transaction that is read-only and set to
  repeatable read, and takes no lock: locking is a write discipline, and a
  read-only transaction reading one moment needs nothing else. Validation runs
  the same way as on the write side — the vocabulary check, the empty-value
  check, and the rule that a parent filter naming a leaf is a caller mistake are
  all decided in application code before the store is asked anything.

- **Live rows only, in one place rather than five.** Reference resolution
  refuses a marked row, and every listing query carries the same condition, so
  the rule is written once per shape and not repeated per operation. There is no
  argument, filter value, or operation by which a caller could ask for a deleted
  row — the absence is the design, and a test asserts the surface offers no such
  door. The entity classes therefore never carry a deleted mark: the column is
  the store's business, and an entity that could report itself deleted would
  imply a caller could hold one.

- **The readiness view is declared for reading only.** It is described to Exposed
  as though it were a table, kept out of the set the drift guard compares — the
  guard does not recognise a view and would propose creating a table — and the
  read-only transaction is what stops anything writing through it, since the
  declaration itself would happily accept a write.

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

- [ ] **STEP1** — Write `db/changelog/changes/0002-soft-delete.yaml`: add the
  nullable `deleted_at` column to `project`, `release`, and `project_item`; drop
  the constraints `uq_project_slug`, `uq_release_project_slug`, and
  `uq_item_project_slug` and create in their place unique indexes of the same
  names limited to rows whose mark is unset, as raw SQL since a conditional rule
  cannot be a constraint; and recreate the `ready_item` view so that it skips
  items carrying a mark and treats a marked blocker as resolved alongside `done`
  and `cancelled`. Amend the master changelog's comment that the schema uses no
  database-specific features; verify: the existing migration test applies the
  whole changelog to a fresh database and passes, and a new test reads all three
  index definitions back out of PostgreSQL and asserts each carries the
  live-only condition. This step is first because it is the only part of the
  epic no probe has executed.

- [ ] **STEP2** — Mirror the change in `Tables.kt`: the new column on all three
  tables, and the three handle rules re-declared with their live-only condition;
  verify: the drift guard passes, and a test builds the tables from the
  declarations on an empty database, reads the indexes PostgreSQL actually
  created, and asserts each matches the migrated schema's — the drift guard is
  blind to the condition (FIND2), so this is what guards it.

- [ ] **STEP3** — Add a test asserting the readiness view's own stored
  definition names the deleted mark in both places it must: excluding a marked
  item, and counting a marked blocker as resolved; verify: the test passes
  against the migrated database, and fails if pointed at the previous view
  definition.

- [ ] **STEP4** — Move reference resolution, the row-to-entity mapping, and the
  stored-code maps out of `io.nook.core.write` into a package both paths use,
  and make resolution refuse a marked row — a reference naming one is
  `not_found`, by handle or by identifier, on both paths; verify: every existing
  write-path test passes unchanged apart from imports, `./gradlew check` stays
  green — a move that changes anything else has gone wrong — and a new resolver
  test covers the marked row under both reference forms.

- [ ] **STEP5** — Implement deleting an item on the write service: under the
  project's lock, set the mark, and mark an epic's children in the same
  transaction; deleting something already deleted is `not_found`, since a
  deleted row is not addressable at all; verify: named tests cover each rule of
  the delete contract above — the row survives in the store while every read
  loses sight of it, deleting an epic takes its four children, the freed handle
  is immediately reusable by a new item, the delete leaves the status untouched,
  and a second delete of the same item comes back `not_found`.

- [ ] **STEP6** — Implement deleting a project under the instance-wide lock,
  marking the project together with its releases and its items in one
  transaction, so nothing inside it stays visible; update
  `WriteServiceSurfaceTest` to the nine operations the service now offers;
  verify: named tests show a deleted project gone from the listing of projects
  with its handle immediately reusable, every item it held gone from that
  project's listing, and the surface test passing with the two new names and no
  others.

- [ ] **STEP7** — Build the read scaffold: the transaction helper that opens one
  read-only, repeatable-read transaction with re-runs disabled; the readiness
  view declared for reading and kept out of the drift guard's set; and the
  listing filter types in `:contract` — each part optional and multi-valued, and
  the parent part whose values are each either an epic reference or the reserved
  "no epic at all"; verify: a test attempts a write inside a read transaction
  and the database itself refuses it, and another attempts a write through the
  view declaration inside such a transaction and is refused the same way.

- [ ] **STEP8** — Implement `get_project` and `list_projects` on the new read
  service: resolution across the whole instance rather than inside a project,
  live rows only, and newest-first ordering with the identifier as tiebreak;
  verify: the named tests for AC21 pass, and a test shows the same call repeated
  ten times returning one and the same order.

- [ ] **STEP9** — Implement `get_item`: identifier or handle, both resolving to
  live rows only, an item from another project reported as not found, and a
  deleted item not found under either reference form; verify: the named tests
  for AC2, AC4, and AC17 pass.

- [ ] **STEP10** — Implement `list_items`: every filter part optional, several
  values inside a part widening it and several parts narrowing each other, the
  reserved no-parent value, the ordering, live rows only, and the blocker sets
  fetched with one extra query for the whole listing. Rejected in application
  code: a value outside its vocabulary, a part supplied with no values, and a
  parent value naming something that is not an epic; verify: the named tests for
  AC5 through AC16 pass.

- [ ] **STEP11** — Stress the listing against concurrent writes (AC22): one
  caller lists a project repeatedly while another creates items in it, 100
  repetitions; verify: every listing returns only fully committed items, each
  with its blocker set as committed, and no call fails. This runs here rather
  than at the end because it is the first proof that the two-query listing
  really describes one moment (FIND9).

- [ ] **STEP12** — Implement `get_ready_items`: read the view, take no filter,
  order as every other listing does, and carry blocker sets like any other item
  result; verify: the named tests for AC18, AC19, and AC20 pass — including a
  leaf whose only blocker was deleted coming back ready, and a deleted leaf
  coming back nowhere.

- [ ] **STEP13** — Close the surface: a test asserts the read service offers
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
  option on an update, no listing that shows deleted rows, no flag that turns
  them back on for debugging; each looks like a kindness and each reopens the
  decision that deletion is final; instead: if recovery turns out to be needed,
  it is designed as its own operation in the document that owns deletion, not
  smuggled in as a parameter.

- **no-go: editing epic 03's spec to match the new surface** — it is finished
  work, and rewriting a settled contract to agree with newer code hides the
  change; instead: the divergence is recorded in this plan's Analysis, and only
  the surface test moves.

- **no-go: physically removing a row** — deleting means marking, always; the
  document layer will point at rows that must still exist; instead: if
  something appears to need real removal, that is the deferred operation the
  design documents already name, not a quiet addition here.

- **no-go: naming a database driver type** — the build check keeps the driver
  off the compile classpath, so reading a rejection's constraint name off a
  driver exception cannot compile; instead: check the rule in application code
  under the lock, which is where every caller-facing decision is made.

- **caveat: the drift guard cannot see the condition on a handle rule** — it
  compares which rules exist, not which rows they cover, so it would stay green
  the day the index is rebuilt without its condition (FIND2); instead: the
  direct index test of STEP1 and STEP2 is the real guard — never treat a green
  drift guard as proof the condition survived.

- **no-go: letting the drift guard see the readiness view** — pointed at a view
  it proposes creating a table (FIND5); instead: the view's declaration stays
  out of the set the guard compares, and the view is checked by its own
  definition test.

- **caveat: the view must be recreated in the same changeset that adds the
  column** — a view fixes its column list when it is created, so one built
  earlier will never show the mark (FIND4); instead: keep both changes in
  `0002-soft-delete.yaml`, never split them across changesets.

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

- **TEST1** — integration: the whole changelog applies to a fresh database, and
  all three handle indexes read back out of PostgreSQL carry their live-only
  condition; the readiness view's stored definition names the deleted mark in
  both places it must.

- **TEST2** — integration: the drift guard stays green with the new column and
  the re-declared rules, and the index the declarations produce on an empty
  database matches the migrated schema's.

- **TEST3** — integration: the delete contract, rule by rule, through the
  service — the row survives in the store while every read loses sight of it; an
  epic takes its children and a project takes everything in it; a freed handle
  is immediately reusable; a reference to a deleted row is `not_found` by handle
  and by identifier alike; and deleting neither reads nor writes a status.

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

The code is trivially revertible, but the schema change is not, so back out in
this order:

- **Revert the code first**, then the schema. The reverted code runs against the
  new schema without trouble — an unread column and a uniqueness rule that
  covers fewer rows change nothing for it — while new code against the old
  schema fails immediately.
- **Reversing the schema change means a new changeset**, never editing
  `0002-soft-delete.yaml` once it has been applied anywhere: drop the three
  conditional indexes and restore the plain constraints, recreate the readiness
  view in its original form, and drop the columns last.
- **What a revert cannot undo**: rows already marked deleted. Dropping the
  column discards every mark at once, which makes rows visible again that
  callers were told were gone; and restoring a plain uniqueness rule fails
  outright while two rows share a handle — one live, one deleted. So before
  dropping anything, decide row by row what happens to the marked ones. There is
  no service operation for this and deliberately none: it is a human decision
  taken against the database, not a script.
- **Where the door closes**: once a caller has taken a handle freed by a delete,
  the old rule can no longer be restored without renaming one of the two rows.
  That is the first moment reversal stops being mechanical.
