# Database bring-up — Plan

## Analysis

What exists, verified against the repo:

- **The changelog** — `db/changelog/db.changelog-master.yaml` pulls
  `changes/0001-initial-schema.yaml` via `includeAll`
  (`relativeToChangelogFile: true`): 8 changesets covering six tables
  (`project`, `release`, `project_item`, `item_dependency`, `document`,
  `document_sequence`), 22 constraints, query indexes, and the `ready_item`
  view. The [discovery](./discovery.md) executed it end to end on PostgreSQL
  17.5: applies completely, re-runs as a no-op, all eleven behavior probes pass
  (FIND2). It is correct as committed and stays that way.
- **The module skeleton** (epic 01) — `:core-service` already declares
  `exposed-core`, `exposed-jdbc`, `liquibase-core`, and the PostgreSQL driver;
  it also still carries `runtimeOnly(libs.sqlite.jdbc)` and the catalog still
  pins `sqliteJdbc` — both now dead per ADR-1. `kotlin.test` on the JUnit
  Platform is wired by the `nook.kotlin-jvm` convention plugin; no test source
  files exist yet. `Main.kt` is a placeholder. The `nook.persistence-boundary`
  check bans persistence groups (including `org.xerial`) outside
  `:core-service`.
- **The stale header** — the master changelog's file comment still says "Tests
  and embedded use: SQLite"; ADR-1's documentation follow-through (REQ2
  wording, ARCHITECTURE, `db/README`) covered everything except this line.

What this epic realizes is settled upstream — link, don't restate:

- [PRD-1](../prd-1.md) **REQ2** — Liquibase applies the changelog to Postgres
  in production and, via embedded PostgreSQL binaries, in tests; a check
  guards the data-access definitions against drift from the changelog.
- [ADR-1](../../../architecture/adrs/adr-1.md) — PostgreSQL is the sole
  engine; tests get it from Zonky embedded binaries; SQLite is dropped
  entirely. Already adjudicated there, not to be re-investigated: SQLite in
  any form (FIND1/3/4), Docker/Testcontainers, H2.
- [The discovery](./discovery.md) — the mechanisms, all executed: Liquibase's
  in-process `CommandScope("update")` API with correct re-run detection
  (FIND7); Zonky embedded Postgres starting Docker-free from Maven Central
  artifacts (FIND5); the drift check on Exposed's
  `MigrationUtils.statementsRequiredForDatabaseMigration`, which needs
  exact-mirror table declarations and a filter for computed-default noise
  (FIND6). Ruled out there: the Liquibase Gradle plugin (the in-process API is
  the wiring startup needs anyway).

Constraints that bound the change: no Docker anywhere in the loop (ADR-1
driver, GOAL1); persistence stays inside `:core-service` (GOAL4); the
changelog's changesets are immutable — schema evolution is additive; the
embedded path is proven only on this one macOS machine, where the 17.5 binary
ran under Rosetta — Linux CI and native arm64 are exactly what this epic must
verify (discovery Q6, PRD-1's assumption risk).

## Approach

Build what the discovery recommends, under ADR-1's decision, in `:core-service`:

- A **migration bootstrap** — a small function running the changelog through
  Liquibase's in-process API against a given JDBC target. The changelog is
  packaged onto the module's classpath from `db/changelog` at build time
  (single source, no checked-in duplicate), so tests and the future service
  startup load it identically. This
  epic delivers the callable; wiring it into a running server is epic 03+.
- An **embedded-Postgres test fixture** — Zonky starts one real PostgreSQL per
  test run; each test class gets a freshly migrated database through the same
  bootstrap.
- **Exposed table definitions for all six tables**, schema-only, mirroring the
  changelog column-for-column — the data-access definitions REQ2's drift
  guard protects, and the base epic 03 builds behavior on.
- The **drift guard as a test**: `MigrationUtils` across all six tables,
  known-benign computed-default statements filtered, remainder asserted
  empty — plus a negative case proving the guard still catches real drift.
  Tests only for now; it moves to startup once the noise filter has proven
  quiet at full scale (discovery Q7).
- The discovery's **eleven behavior probes ported as permanent tests** — they
  are the executable statement of what the schema must enforce, and they cover
  the `ready_item` view the table-comparing drift guard cannot see.
- **SQLite removed** from the catalog, the build, and the master changelog's
  header comment.
- A **minimal CI workflow** — one GitHub Actions job running `./gradlew check`
  on Linux — because PRD-1 and ADR-1 both assign the beyond-one-machine proof
  of the embedded path to this epic, and the whole test suite grows on it.

Why this way over the obvious alternative: there isn't a live one — the
discovery executed the mechanisms and ADR-1 ratified the engine; the remaining
choices (in-process API over the Gradle plugin, tests-only drift guard) follow
the discovery's own recommendation.

Unverified assumptions, named: current Zonky (2.2.x) and latest PG-17-line
binaries behave as the discovery's 2.1.0/17.5 did, ideally natively on Apple
silicon; the classpath-based resource loading handles `includeAll`; the whole
path holds on Linux. The first integration test (STEP3) and the CI run (STEP8)
prove these earliest.

Blast radius: `:core-service` (main and test sources, build script),
`gradle/libs.versions.toml`, the master changelog's header comment, and a new
`.github/workflows/` file. Must leave untouched: every changeset in
`db/changelog/changes/`, `:contract`, `:mcp-server`, `:web-app`, the
convention plugins' existing behavior, `docs/`.

## Steps

- [x] **STEP1** — Add to `gradle/libs.versions.toml`: Zonky
  `embedded-postgres` (2.2.2), the `embedded-postgres-binaries-bom` pinned to
  the latest PostgreSQL 17.x (17.10.0), the `darwin-arm64v8` binary artifact
  (`linux-amd64` for CI needs no entry — it is in Zonky's default transitive
  set, version-aligned by the BOM), `exposed-migration-jdbc` and
  `exposed-java-time` at the existing `exposed` version (the latter for the
  timestamp/date column types, main-scope), and the PostgreSQL driver as a
  test dependency; declare the rest test-scope in `:core-service`; verify:
  `./gradlew :core-service:dependencies --configuration testRuntimeClasspath`
  resolves every new coordinate at the pinned versions.
- [x] **STEP2** — Wire `db/changelog/` onto `:core-service`'s classpath in its
  build script: a `processResources` copy-spec reading the existing directory
  at build time (no checked-in duplicate; `db/changelog` stays the single
  source); verify: `./gradlew :core-service:processResources` output contains
  `db.changelog-master.yaml` and `changes/0001-initial-schema.yaml`.
- [x] **STEP3** — Write the migration bootstrap (`io.nook.core.db`): a function
  taking a JDBC url/credentials and running Liquibase `CommandScope("update")`
  with a classpath resource accessor; write the embedded-Postgres test fixture
  (one Zonky server per test JVM, a freshly migrated database per caller);
  write the first integration test — migrate, assert all 8 changesets applied,
  migrate again, assert no changesets execute; verify: the test passes
  locally; record in the epic whether the PG binary now runs natively on
  arm64 or still under Rosetta (either passes; the answer prices the harness).
  Observed: PostgreSQL 17.10 still reports `x86_64-apple-darwin` — Rosetta,
  even with the `darwin-arm64v8` binary artifact on the classpath.
- [x] **STEP4** — Declare the six Exposed table objects in `:core-service`
  main sources, mirroring the changelog exactly — every column type,
  nullability, default, unique constraint, index, and the CHECK — with no
  behavior on top; verify: compiles, and a smoke test (its own test class, on
  the STEP3 fixture) confirms Exposed can `SELECT` from each declared table on
  the migrated database.
- [x] **STEP5** — Write the drift-guard test:
  `MigrationUtils.statementsRequiredForDatabaseMigration` over all six tables
  against the migrated database, filter only the known-benign
  computed-default `SET DEFAULT` shapes, assert the remainder empty; add the
  negative case — a deliberately wrong table object (extra column) defined in
  test code must produce reconciling statements; verify: both tests pass, and
  the filter's absorbed shapes are exactly the computed-default ones (anything
  else failing means a STEP4 mirroring bug — fix the mirror, not the filter).
- [x] **STEP6** — Port the discovery's eleven behavior probes as integration
  tests: happy-path inserts across all five writable tables, `ready_item`
  exclusion and release-on-cancel, timestamp defaults, self-block CHECK
  rejection, dangling and cross-project FK rejections, per-project slug
  uniqueness both ways, project-delete cascades; verify: all pass against the
  embedded database.
- [x] **STEP7** — Remove SQLite: delete the `sqliteJdbc` pin and
  `sqlite-jdbc` library from the catalog, drop
  `runtimeOnly(libs.sqlite.jdbc)` from `:core-service`, and fix the master
  changelog's header comment (PostgreSQL only, per ADR-1); leave `org.xerial`
  in the boundary check's banned set; verify: `./gradlew check` green and
  `:core-service:dependencies` shows no `org.xerial` coordinate.
- [ ] **STEP8** — Add `.github/workflows/ci.yaml`: one job, Linux runner,
  JDK 25, `./gradlew check`; verify: the workflow runs green on GitHub with
  the embedded-Postgres tests visibly executed, not skipped — this closes
  discovery Q6. Divergence: the workflow is written and YAML-validated, but
  the repo has no GitHub remote yet, so the green run (and Q6) waits on the
  repo being pushed.

## Caveats & rabbit holes

- **no-go: editing changesets** — the changelog is fully correct on Postgres
  (FIND2) and changesets are immutable once committed; the header comment fix
  (STEP7) is the only sanctioned touch; instead: any schema itch becomes a new
  changeset in the epic that needs it.
- **rabbit-hole: building the write path** — six declared tables invite DAOs,
  insert helpers, "just one" business rule; instead: schema declarations only —
  behavior is epic 03's, and every rule this epic checks is exercised through
  plain SQL probes or the drift guard.
- **caveat: the noise filter is a tripwire, not a wastebasket** — if drift
  output beyond the computed-default shapes tempts the filter wider, that's
  either a mirroring bug (fix the table object) or discovery Q7 materializing
  (stop and reassess the guard's design); instead: the filter's accepted
  shapes stay enumerable in one place and each addition needs a reason.
- **rabbit-hole: connection pooling** — no server runs yet, so Hikari or any
  pool is dead weight; instead: plain JDBC connections in tests; pooling
  arrives with the service that needs it.
- **caveat: Main.kt stays a placeholder** — "migration at startup" does not
  mean building startup; instead: the bootstrap is a function later epics
  call.
- **rabbit-hole: CI beyond one job** — matrices, caching strategies, release
  pipelines; instead: one Linux job running `check`; anything more is its own
  chore when something demands it.
- **caveat: PostgreSQL 18 exists** — the binaries track upstream past 17;
  instead: stay on the 17 line this epic proves; a major bump is a deliberate
  later pin change, not a side effect of bring-up.

## Test plan

- **TEST1** — integration: migration bootstrap applies all 8 changesets to a
  fresh embedded PostgreSQL, and an immediate second run applies zero.
- **TEST2** — integration: the drift guard, run over all six mirrored tables
  on the migrated database, reports nothing after filtering only
  computed-default noise.
- **TEST3** — integration: the drift guard's negative case — a deliberately
  wrong table declaration yields reconciling statements (the guard provably
  catches drift).
- **TEST4** — integration: all eleven ported behavior probes pass, including
  `ready_item`'s blocked/freed logic and every constraint rejection the
  discovery observed on Postgres.
- **TEST5** — build: `./gradlew check` green on a clean checkout;
  `checkPersistenceBoundary` still passes on the three non-core modules; no
  `org.xerial` coordinate resolves anywhere.
- **TEST6** — CI: the GitHub Actions job runs `./gradlew check` green on
  Linux, with the embedded-Postgres integration tests in the executed set.
- **Standing check, comment hygiene** — grep the final diff for
  STEP/REQ/GOAL/FIND/PRD/ADR/epic tokens and `.md` paths in code and code
  comments; expect zero hits.
- **Standing check, conformance sweep** — re-read this plan against the final
  diff: every step ticked with its verify observed, blast radius respected,
  caveats honored, divergences folded back into the plan text.
- Run both standing checks through a separate agent handed only this plan and
  the final diff.

Done when: a clean checkout runs `./gradlew check` green locally and on the
Linux CI job, with the unmodified changelog migrating an embedded PostgreSQL,
all six Exposed table mirrors passing the drift guard, the eleven behavior
probes passing, and SQLite absent from the catalog, the build, and the
changelog's prose.
