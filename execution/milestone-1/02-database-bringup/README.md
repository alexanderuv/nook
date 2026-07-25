# Epic 02 — Database bring-up

**Addresses:** REQ2 (Liquibase changelog applied to Postgres in production and
— per ADR-1 — via embedded PostgreSQL in tests, drift check between
data-access definitions and the changelog).

**Status: done.** Executed 2026-07-24 from [plan.md](./plan.md), which was
built on [discovery.md](./discovery.md)'s mechanism findings under
[ADR-1](../../../architecture/adrs/adr-1.md)'s engine decision.

## What landed

- The **migration bootstrap**: `migrateDatabase()` in `:core-service` runs the
  changelog through Liquibase's in-process `CommandScope("update")` API; the
  changelog is packaged onto the module classpath from `db/changelog` at build
  time, so tests and future service startup load the one committed source
  identically.
- The **embedded-Postgres test fixture**: one Zonky server (2.2.2, PostgreSQL
  17.10 binaries) per test JVM, a freshly created and fully migrated database
  per caller — no Docker, no installed database.
- **Exposed mirrors of all six tables**, schema-only, in `Tables.kt` — the
  base epic 03 builds behavior on.
- The **drift guard as tests**: Exposed's `MigrationUtils` compares the
  mirrors against the migrated database with only computed-default noise
  filtered, plus a negative case proving the guard catches a deliberately
  wrong declaration.
- The discovery's **eleven behavior probes as permanent integration tests**,
  covering the `ready_item` view, every constraint rejection, cascades, and
  per-project slug uniqueness.
- **SQLite removed entirely** — catalog pin, build dependency, and the
  changelog header's stale claim — per ADR-1.
- **Minimal CI**: one GitHub Actions job on Linux, JDK 25, `./gradlew check`.

## Verification

The plan's steps all ticked with their verifies observed: migration applies
all 8 changesets and re-runs as a no-op, the drift guard is empty after
filtering and red on planted drift, all eleven probes pass against embedded
PostgreSQL, and no `org.xerial` coordinate resolves anywhere. The first CI run
went green in 2m21s on `ubuntu-latest` with the embedded-Postgres tests
executed fresh — closing discovery Q6's Linux half; on Apple silicon the
17.10 binary still runs under Rosetta (works, not native). One recorded
divergence: the CI verify waited a session for the repo to gain a GitHub
remote.

## Left open (by design)

- The drift guard stays test-only; promoting it to service startup waits on a
  longer quiet track record for its noise filter (discovery Q7 — the
  full-schema half is answered: at six tables the filter absorbs only
  computed-default shapes).
- Native arm64 embedded binaries — Rosetta suffices; revisit only if test
  startup cost ever matters.
