# Core write path approach

## Summary

- **Every schema violation the write path must handle arrives through Exposed
  carrying its error code (SQLSTATE) and the name of the exact rule that was
  broken** — so translating database rejections into spec 01's structured
  errors is a simple lookup by rule name, no message parsing.
- **"Check for a cycle, then insert the edge" is correct alone but breaks
  with two writers**: under PostgreSQL's normal settings, each writer's check
  ran before it could see the other's unfinished insert, so both passed and a
  real cycle landed in the table. Two fixes were proven: PostgreSQL's
  strictest transaction mode (SERIALIZABLE, which detects the collision and
  fails one writer), and a per-project advisory lock — Postgres's
  take-a-named-lock feature, where the first writer to ask for a project's
  lock gets it and every other writer waits its turn. The lock closed the
  race with no failures and no retries, and as a side effect also fixed the
  matching race in slug generation.
- **Two Exposed behaviors will bite the write path if unmanaged**: when a
  transaction block fails, Exposed silently runs the whole block again, up to
  three times — dangerous for code that must not run twice; and after any
  constraint rejection PostgreSQL refuses all further commands in that
  transaction, so retrying inside it only works after rolling back to a
  savepoint (a marker set earlier in the transaction that you can rewind to);
  retrying in a brand-new transaction just works.
- Recommendation in brief: make each project's writes take turns via the
  advisory lock at the top of the single write path, detect cycles with an
  in-app walk over the project's edges inside that lock, translate database
  rejections into the structured errors using the constraint name, and set
  Exposed's retry count explicitly instead of accepting the silent default.

## Questions

- **Q1** — Can `set_item_blocked_by`'s cycle rejection be made both correct
  and safe under concurrent writers, and with which detection mechanism
  (recursive SQL query vs in-app graph walk)?; informs: REQ3's
  cycle-rejecting `blocked_by` replacement.
- **Q2** — Does derive-then-insert slug allocation (numeric suffix on
  collision) hold up through Exposed, including when two writers derive the
  same slug simultaneously?; informs: REQ3's slug
  derivation/uniqueness/override rules.
- **Q3** — How do the schema's constraint violations surface through Exposed,
  and is that surface rich enough to map them to spec 01's four structured
  errors (`validation_failed` / `not_found` / `conflict` / `cycle`)?;
  informs: the write path's error-mapping seam.
- **Q4 (emerged)** — What does Exposed's `transaction {}` do when the block
  fails?; asked after one probe printed three attempts for a single insert
  and another probe's expected failure never propagated; informs: transaction
  discipline in the write path.

Bound: a throwaway probe runner on one machine (macOS, Apple silicon, JDK 25),
all versions from the repo's own catalog pins; concurrency exercised as
two-transaction races with a barrier between check and insert. That sufficed
because each question is about mechanism behavior that a single honest
execution settles, and the code building on the answers lands in this same
epic.

## Method

A throwaway Kotlin runner (scratch directory, its own Gradle build, deleted
after this report) ran against real PostgreSQL: Zonky embedded-postgres 2.2.2
with the 17.10 binaries, the repo's committed changelog applied in-process by
Liquibase 5.0.3, and all data access through Exposed 1.3.1 — the same stack
the write path will use. Eleven probes:

- **Slugs**: duplicate-slug insert to capture the exception surface; retry
  after a violation inside the same transaction, with and without a JDBC
  savepoint; two threads deriving the same slug behind a barrier, the loser
  retrying with a suffix in a fresh transaction.
- **Cycles**: correctness of two detectors — a recursive SQL query
  (`WITH RECURSIVE`) and an in-app walk over the loaded edges — on a
  three-item chain, a safe edge, and a diamond shape; then the two-writer
  race: two threads each add one half of a two-edge cycle, and both are held
  at a checkpoint until each has finished its cycle check, so neither can see
  the other's edge before inserting its own. That race was run four ways:
  with PostgreSQL's default settings (READ COMMITTED), in its strictest mode
  (SERIALIZABLE), in strictest mode with Exposed's automatic retry turned
  off, and with the per-project advisory lock (`pg_advisory_xact_lock`).
- **Error surfaces**: deliberately violating each constraint class — unique
  slug, cross-project parent (composite foreign key), self-block (CHECK),
  dangling blocker (plain foreign key), duplicate dependency edge (primary
  key) — and recording exception chain, SQLSTATE, and constraint name.
- **Cost**: order-of-magnitude timing of both detectors on a 500-edge chain,
  worst case (full-length walk), 20 runs averaged.

Not done: more than two concurrent writers, any load or volume testing, any
second machine, and no probing of the advisory lock's hash keyspace
(collisions only over-serialize; see Limitations). Key runner output is
quoted in the findings; the runner keeps no authority.

## Findings

### FIND1 — Every violated constraint surfaces with its SQLSTATE and constraint name

**Confidence:** solid — each constraint class violated deliberately, surface captured · answers Q3

Every violation arrives as `ExposedSQLException` wrapping `PSQLException`,
with the SQLSTATE on the exception and the constraint name on
`serverErrorMessage.constraint`:

| Violation | SQLSTATE | Constraint name surfaced |
| --- | --- | --- |
| duplicate slug in project | 23505 | `uq_item_project_slug` |
| cross-project parent | 23503 | `fk_item_parent_same_project` |
| self-block | 23514 | `ck_dep_no_self_block` |
| dangling blocker | 23503 | `fk_dep_blocker` |
| duplicate dependency edge | 23505 | `pk_item_dependency` |

The constraint name pinpoints the rule, not just the class — enough to map
`uq_item_project_slug` to `conflict` and `fk_item_parent_same_project` to
`validation_failed` without parsing message text.

### FIND2 — After a violation the transaction is dead; retry needs a savepoint or a fresh transaction

**Confidence:** solid — all three retry shapes executed · answers Q2

PostgreSQL aborts the transaction on any constraint violation. Retrying the
insert with a suffixed slug inside the same transaction failed; the same
retry after rolling back to a JDBC savepoint set before the first attempt
succeeded; and in the two-writer race, the loser of the slug collision
(23505) retried in a fresh transaction and correctly got `add-search-2` while
the winner kept `add-search`. So "derive the slug, try the insert, let the
database's uniqueness rule catch the rare collision, retry with a suffix"
works in either shape; the savepoint route required reaching through Exposed
to the raw JDBC connection underneath it.

### FIND3 — Exposed silently re-executes a failed transaction block, three attempts by default

**Confidence:** solid — observed directly in two independent probes · answers Q4

A single failing insert printed its "attempting" line three times: Exposed's
`transaction {}` re-runs the whole block on a database error until
`maxAttempts` (default 3) is exhausted. In the SERIALIZABLE race probe this
hid the real failure entirely — PostgreSQL had cancelled one transaction with
error 40001 (its "these transactions collided, one must retry" signal), but
Exposed silently re-ran the block, the re-run's check saw the other writer's
now-committed edge, and the block "succeeded" by rejecting. Only with
`maxAttempts = 1` did the 40001 surface at all. Left at the default, any
write-path code that must not run twice — allocating the next document
number, say — re-executes invisibly whenever a passing failure triggers the
retry.

### FIND4 — Both cycle detectors are correct; the in-app walk measured faster

**Confidence:** solid for correctness (both agreed on every shape); suggestive for the timing (one shape, transaction overhead included) · answers Q1

On a chain `a←b←c`, both the recursive SQL query and the in-app depth-first
walk detected the closing edge as a cycle, accepted a safe edge, and were not
confused by a diamond. On a 500-edge chain checked end to end (worst case,
average of 20 runs, each in its own transaction): recursive query ~12 ms, in-app
walk including a full edge reload ~2.5 ms. One quirk: raw `WITH RECURSIVE`
SQL through Exposed's `exec` is misclassified as an update and fails unless
`explicitStatementType = StatementType.SELECT` is passed.

### FIND5 — Check-then-insert admits a real cycle under concurrent writers at default isolation

**Confidence:** solid — race reproduced deterministically with a barrier · answers Q1

Two transactions at PostgreSQL's default setting (READ COMMITTED) each
checked one half of a two-edge cycle. Both checks passed — each transaction
reads its own snapshot of the database and is blind to work the other hasn't
committed yet — so both inserted, both committed, and the cycle was in the
table. A check that is correct inside one transaction is therefore not
enough; the application-level check spec 04 mandates needs something that
stops two writers from checking at the same time.

### FIND6 — SERIALIZABLE closes the race, at the cost of retry handling

**Confidence:** solid — run both with and without automatic retry · answers Q1

The same race under SERIALIZABLE never produced a cycle. With Exposed's
default retry, one transaction was cancelled and silently re-run, and its
re-run check rejected the edge — a correct outcome, but one that depends on
the block being harmless to run twice (FIND3). With retry disabled, the loser
failed with error 40001, which the write path would have to catch and re-run
itself.

### FIND7 — A per-project advisory lock closes the race with no failures and no retries

**Confidence:** solid — race run under the lock; correct rejection observed · answers Q1, Q2

With each transaction taking `pg_advisory_xact_lock` keyed on the project id
before checking, the second writer simply waited its turn, saw the first
writer's committed edge once it got the lock, and rejected its own — one edge
in the table, no exceptions, no retry logic. And because the lock makes all
of a project's writes take turns, two writers can no longer derive the same
slug at the same moment either (FIND2's fresh-transaction retry becomes a
backstop, not the mechanism).

## Implications & recommendation

- **Make each project's writes take turns: take the per-project advisory lock
  at the top of the single write path** (FIND5, FIND6, FIND7) — the plain
  check-then-insert is provably unsafe, and of the two safe fixes the lock is
  the only one with no failure to catch and no retry to write; it closes the
  cycle race and the slug race with one mechanism. The schema's constraints
  stay on as the backstop. Nook's core service is already the single writer
  by design, so writes taking turns within a project changes no promised
  behavior.
- **Map database rejections to spec 01's structured errors by constraint
  name** (FIND1) — the name arrives reliably on the exception; a small
  constraint-name → error-code table covers `conflict`, `validation_failed`,
  and leaves `cycle` to the application check. Which name maps to which code
  is design work for this epic's plan, not a discovery matter.
- **Set `maxAttempts` explicitly in every write-path transaction, and default
  it to 1** (FIND3) — silently running twice a block that must not run twice
  is a correctness hazard; any retry should be a deliberate, visible
  decision.
- **Detect cycles with an in-app walk over the project's edges, inside the
  locked transaction** (FIND4, FIND7) — under the lock both detectors are
  safe, so the choice is on merits: the walk measured faster at probe scale,
  avoids the raw-SQL quirk in Exposed, and can be unit-tested without a
  database. The recursive query stays a viable fallback if dependency graphs
  ever grow big enough that the walk should move into the database.
- **Derive the slug, then insert, all under the lock, keeping the
  duplicate-key catch as backstop** (FIND2, FIND7) — under the lock two
  writers cannot derive the same slug simultaneously; the backstop turns an
  impossible-in-practice collision into a clean `conflict` instead of a
  crash.

## Limitations

- **Races were two transactions on one machine over tiny data** — at risk:
  the advisory lock's throughput under real multi-agent write load is
  unmeasured, and the recommendation makes it the universal discipline; would
  raise confidence: a load probe against the real write path once it exists
  (Q5).
- **Timings are order-of-magnitude, one graph shape, each check paying its
  own transaction overhead** — at risk: the ~5× detector gap may not hold on
  real graph shapes or when checks share an open transaction; would raise
  confidence: re-measuring inside a single transaction at realistic sizes if
  detector cost ever matters.
- **The savepoint probe reached the raw JDBC connection through Exposed
  internals** — at risk: breakage on Exposed upgrades if the write path
  adopts savepoint retries; moot under the recommended lock-plus-fresh-transaction
  shape; would raise confidence: a pinned test on the unwrap, only if
  savepoints are adopted.
- **The advisory lock keys on a 64-bit hash of the project id; collisions
  were not probed** — at risk: nothing structural — a hash collision would
  make two unrelated projects take turns with each other unnecessarily, but
  it cannot corrupt anything; would raise confidence: nothing needed — the
  failure mode is harmless by construction.

## Open questions

**Follow-ups:**

- **Q5** — Does per-project write serialization hold up under real
  multi-agent load?; matters because: the advisory lock is recommended as the
  universal write discipline, and its cost was only shown correct, not
  cheap, at two writers; would take: a load probe against the implemented
  write path when multi-agent usage exists to measure.
