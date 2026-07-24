# Database bring-up approach

## Summary

- The changelog **as committed does not apply on SQLite**: Liquibase 5.0.3
  rejects it at validation with 9 unsupported changes — every post-create
  `addUniqueConstraint`, `addForeignKeyConstraint`, and `addPrimaryKey`.
- On **PostgreSQL it applies cleanly and completely**: all 22 constraints, the
  `ready_item` view, and every probed rule (cross-project foreign keys,
  self-block CHECK, cascades, per-project slug uniqueness) behave exactly as
  specified; a re-run is a clean no-op.
- SQLite is reachable only through a **restructured changelog that gives up
  real constraints**: the three cross-project composite foreign keys and the
  self-block CHECK have no expressible home on SQLite, and violating rows were
  accepted. SQLite also leaves foreign-key enforcement **off by default** on
  every new connection.
- A **real Postgres for tests needs no Docker**: embedded Postgres 17.5
  binaries pulled as a plain Maven dependency started in seconds on this
  Docker-less machine and passed the full probe suite.
- The Exposed **drift check is viable**: `MigrationUtils` caught deliberately
  injected drift on both engines; it needs full-fidelity table declarations and
  a small tolerated-noise filter (computed defaults) to assert "no statements
  required".
- Recommendation in brief: keep the changelog exactly as is, run it in-process
  at startup, test against embedded Postgres, and retire SQLite as the test
  engine — the latter revises a settled assumption, so it goes to an ADR, not
  this document.

## Questions

- **Q1** — Does the existing Liquibase changelog apply cleanly to both
  PostgreSQL and SQLite, producing schemas that enforce the same rules?;
  informs: REQ2's dual-engine commitment — this carries forward the open
  question the [module-skeleton discovery](../01-module-skeleton/discovery.md)
  explicitly deferred to this epic.
- **Q2** — Can a startup/test check detect drift between the Exposed table
  definitions and the Liquibase-managed schema, and at what false-positive
  cost?; informs: REQ2's drift guard.
- **Q3** — Does Liquibase run in-process through its Java API (no CLI, no
  build-tool plugin), including correct re-run detection?; informs: how
  bring-up is wired into the service and the test suite.
- **Q4 (emerged)** — What exactly would keeping SQLite cost, if the changelog
  were restructured to fit it?; asked after Q1's answer came back negative;
  informs: the same engine decision, with the alternative priced.
- **Q5 (emerged)** — Can tests get a real Postgres without Docker?; asked
  because this machine has no Docker and REQ2's test story must run here;
  informs: the test-harness choice and GOAL1's end-to-end loop on developer
  machines.

Bound: one scratch runner on one machine (macOS, Apple silicon, JDK 25), all
versions taken from the repo's own catalog pins; that sufficed because every
question is a yes/no about behavior that any single honest execution settles,
and the code that builds on the answers lands in this same epic.

## Method

A throwaway Kotlin runner (scratch directory, its own Gradle build, deleted
after this report) exercised the real changelog at the repo's pinned versions:
Liquibase 5.0.3, sqlite-jdbc 3.53.2.1, PostgreSQL driver 42.7.13, Exposed
1.3.1 with `exposed-migration-jdbc`. Postgres came from Zonky embedded-postgres
2.1.0 with the 17.5.0 binary artifact — real PostgreSQL binaries fetched from
Maven Central and started from a temp directory, no Docker and no system
install.

What it did, per engine (SQLite file database; embedded Postgres 17.5):

- Applied `db/changelog/db.changelog-master.yaml` through Liquibase's
  in-process `CommandScope("update")` API, then applied it a second time to
  confirm re-run is a no-op.
- Dumped the resulting schema (JDBC metadata, `sqlite_master`, `pg_catalog`)
  for side-by-side comparison.
- Ran eleven behavior probes as plain SQL, identical text on both engines:
  happy-path inserts across all five tables (including the keyword-named
  `release` table), `ready_item` view logic (blocked leaf excluded, freed when
  its blocker is cancelled), timestamp defaults, self-block CHECK, dangling
  and cross-project foreign keys, per-project slug uniqueness both ways, and
  project-delete cascades.
- Prototyped the drift check: two tables mirrored from the changelog in
  Exposed's DSL plus one deliberately wrong copy (an extra column), each run
  through `MigrationUtils.statementsRequiredForDatabaseMigration`, which
  returns the SQL that would reconcile code with database — an empty list
  meaning "no drift".

After Q1 failed on SQLite, a variant changelog was written to price SQLite
support (Q4): composite primary keys moved inline into `createTable`,
composite UNIQUE constraints became unique indexes, and whatever could not be
expressed at all was dropped and the loss recorded.

Not done: the Liquibase Gradle plugin (the in-process API sufficed and is what
startup wiring would use); drift coverage of the remaining four tables and the
view; any concurrency, volume, or performance work; any second machine or OS.
Key runner output is quoted in the findings; the runner itself is disposable
and keeps no authority.

## Findings

### FIND1 — The committed changelog does not apply on SQLite

**Confidence:** solid — executed, deterministic failure at validation · answers Q1

Liquibase 5.0.3 rejects the changelog before touching the database:

```
Validation Failed: 9 changes have validation failures
  addUniqueConstraint is not supported on sqlite   (x4: 0002, 0003)
  addForeignKeyConstraint is not supported on sqlite (x3: 0003, 0005)
  addPrimaryKey is not supported on sqlite          (x2: 0004, 0008)
```

Every rejected change is a post-create constraint addition — the direct
consequence of SQLite's `ALTER TABLE` not supporting `ADD CONSTRAINT`. The
plain-SQL discipline of the schema (no partial indexes, no native enums) is
not what fails; the *changelog idiom* of adding composite constraints after
`createTable` is. This converts the module-skeleton discovery's
documentation-level caution into an executed fact.

### FIND2 — On PostgreSQL the changelog applies completely and every probed rule holds

**Confidence:** solid — executed against real PostgreSQL 17.5, all probes passing · answers Q1

- Both `update` runs behaved: 8 changesets applied, second run "Database is up
  to date, no changesets to execute".
- The schema arrived at full fidelity: native `uuid` columns, 22 constraints
  in `pg_catalog`, `ready_item` view present.
- All eleven behavior probes passed: the blocked leaf stayed out of
  `ready_item` while its blocker was in progress and appeared once the blocker
  was cancelled; self-block was rejected by `ck_dep_no_self_block`;
  cross-project parent and cross-project release inserts were rejected by the
  composite foreign keys; duplicate slug in-project was rejected while the
  same slug in another project was accepted; deleting a project cascaded
  items, releases, and dependencies to zero; the keyword-named `release`
  table needed no quoting anywhere.

### FIND3 — SQLite support is buyable only by restructuring the changelog and giving up constraints

**Confidence:** solid — the variant was executed and the losses observed as accepted violations · answers Q4, Q1

The variant changelog (composite primary keys inline, composite UNIQUEs as
unique indexes) applies cleanly on SQLite, re-runs as a no-op, and the
`ready_item` view, uniqueness, and cascades all work. But two things have no
expressible form at all:

| Lost on SQLite | Probe result |
| --- | --- |
| `fk_item_parent_same_project`, `fk_item_release_same_project`, `fk_doc_item_same_project` (composite same-project FKs) | cross-project parent and release inserts **accepted** |
| `ck_dep_no_self_block` (CHECK via `ALTER TABLE ADD CONSTRAINT`) | self-blocking dependency **accepted** |

SQLite accepts foreign keys only inline in `CREATE TABLE`, and Liquibase has
no abstract syntax for inline *composite* foreign keys — so keeping them would
mean per-engine raw SQL, exactly what the schema's portability stance exists
to avoid. Type mapping also diverges: `uuid` and `timestamp` become `TEXT`
(timestamps at second precision, UTC) versus native `uuid` and microsecond
`timestamp` on Postgres. A test suite on this variant would validate a
measurably weaker schema than production runs.

### FIND4 — SQLite leaves foreign keys unenforced by default on every connection

**Confidence:** solid — pragma read directly off both connection types · answers Q1, Q4

A plain `DriverManager` connection reports `PRAGMA foreign_keys = 0`: even the
simple single-column foreign keys that survived in the variant are ignored
unless every connection opts in (`SQLiteConfig.enforceForeignKeys(true)`
reports `1`, and only then did the dangling-project probe get rejected). Any
SQLite test setup would carry this per-connection footgun on top of FIND3's
structural losses.

### FIND5 — Tests can have a real Postgres without Docker

**Confidence:** solid on this machine — started, migrated, and probed end to end; untested elsewhere · answers Q5

Zonky embedded-postgres 2.1.0 plus the `embedded-postgres-binaries-darwin-arm64v8`
17.5.0 artifact — both plain Maven Central dependencies — started a real
PostgreSQL 17.5 from a temp directory in seconds on this Docker-less machine,
took the unmodified changelog, and passed the full probe suite (FIND2 *is*
this instance). One observed oddity: the server reports itself as an
`x86_64-apple-darwin` build, so it ran under Rosetta translation on this
Apple-silicon machine — it worked, but it is not a native binary.

### FIND6 — The Exposed drift check works, conditioned on declaration fidelity and a noise filter

**Confidence:** solid for the mechanism (drift injected, drift caught, both engines); suggestive for noise-free operation at full schema scale (two tables mirrored, not six) · answers Q2

`MigrationUtils.statementsRequiredForDatabaseMigration` compares Exposed table
declarations against the live (Liquibase-built) schema and returns reconciling
SQL:

- **It catches real drift.** The deliberately wrong table (one extra column
  declared in code) produced `ALTER TABLE project ADD color VARCHAR(20) NULL`
  plus a `DROP COLUMN` per undeclared column, on both engines — a missing or
  extra column cannot slip through.
- **It also catches sloppy mirroring.** One "noise" line was the check working:
  the prototype declared `description` as non-null where the changelog leaves
  it nullable, and the check flagged exactly that. The core-service table
  definitions must mirror the changelog *exactly* — every column's nullability,
  every index, the CHECK constraint — or the guard cries wolf.
- **Some noise is irreducible.** Computed defaults (`CURRENT_TIMESTAMP`) are
  re-emitted as `SET DEFAULT` statements even when the database already has
  them — the comparison cannot see expression equality. On Postgres the
  matching tables produced 6 such lines, SQLite 2. A startup/test guard should
  therefore normalize or filter these known-benign statement shapes and assert
  the remainder is empty, rather than assert the raw list is empty.

### FIND7 — Liquibase runs in-process through its Java API, with correct re-run detection

**Confidence:** solid — this is how every migration in this investigation was executed · answers Q3

`CommandScope("update")` with a `DirectoryResourceAccessor` rooted at the repo
applied the YAML changelog against both engines from inside a JVM process —
no CLI, no Gradle plugin — and its bookkeeping tables correctly reported
"nothing to do" on every second run. This is directly the shape a service
startup hook or test fixture needs; nothing about the API resisted it.

## Implications & recommendation

- **Test against real Postgres via embedded binaries and retire SQLite as the
  test engine** (FIND1, FIND3, FIND4, FIND5) — the committed changelog cannot
  run on SQLite; buying SQLite back costs a restructured changelog, three lost
  cross-project foreign keys, a lost CHECK, and per-connection enforcement
  care, after which tests would still validate a weaker schema than
  production. The embedded route removes the entire dual-engine risk class
  while keeping tests Docker-free. This reverses REQ2's "SQLite backs tests"
  and the architecture's dual-engine assumption — a discovery cannot close
  that: this epic should open an ADR ratifying (or rejecting) the engine
  switch.
- **Keep the changelog exactly as committed** (FIND2) — on Postgres it is
  fully correct as-is; no restructuring is warranted once SQLite is out of the
  picture.
- **Wire migration at service startup through the in-process API, and reuse
  the same call in the test fixture** (FIND7) — proven on both engines,
  idempotent on re-run, no extra tooling.
- **Build the drift guard on `MigrationUtils`: exact-mirror table definitions,
  a normalize-and-filter step for computed-default noise, assert empty** 
  (FIND6) — wire it into the test suite (and optionally startup) so a
  changelog/code mismatch fails before any query does.
- **If the ADR nonetheless keeps SQLite**, budget FIND3/FIND4's measured
  costs explicitly — variant changelog maintenance, weakened constraints
  under test, and connection-level enforcement discipline (FIND3, FIND4).

## Limitations

- **One machine, one OS; the embedded Postgres ran an x86_64 binary under
  Rosetta** — at risk: the recommended test path failing or slowing on Linux
  CI or native-arm setups; would raise confidence: running this same probe
  suite in the target CI environment during this epic.
- **Drift check exercised on two of six tables, without the view** — at risk:
  the noise filter growing unwieldy at full scale, eroding trust in the guard;
  would raise confidence: extending the mirror to the full schema as the real
  table definitions are written.
- **Zonky embedded-postgres is actively maintained** — Maven Central shows
  core 2.2.2 (March 2026, steady cadence of dependency and security upkeep
  since the repo's pinned 2.1.0) and binary artifacts tracking PostgreSQL
  through 18.4; an earlier in-session lookup suggesting a November 2024
  standstill traced to a stale package-search index. At risk: little on
  maintenance itself; the repo's 2.1.0 pin lags upstream, and whether current
  binaries run natively on Apple silicon is unverified (folds into Q6).
- **The Liquibase Gradle plugin path was never tried** — at risk: little; the
  in-process API is the recommended wiring and is proven; the plugin only
  matters if ops later wants migrations outside the service process.
- **Timestamp semantics were only surfaced, not chased** (TEXT/UTC/seconds on
  SQLite vs native/local/microseconds on Postgres) — at risk: nothing if
  SQLite is retired; becomes a real correctness question if the ADR keeps it.

## Open questions

**Follow-ups:**

- **Q6** — Does the embedded-Postgres test path hold on Linux CI and native
  arm64 (no Rosetta)?; matters because: the recommendation makes it the
  foundation of the whole test suite; would take: running the probe suite in
  CI as part of this epic's bring-up.
- **Q7** — Is the drift guard's "empty after filtering" assertion sustainable
  across the full six-table schema, or does the noise filter grow into its own
  maintenance burden?; matters because: a noisy guard gets ignored, which is
  worse than no guard; would take: extending the drift mirror to the full
  schema while implementing REQ2 and reviewing what the filter had to absorb.
