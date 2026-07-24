# Drop SQLite; PostgreSQL is the sole supported engine — ADR-1

## Context

Milestone 1's database bring-up epic (REQ2 of
[PRD-1](../../execution/milestone-1/prd-1.md)) inherited a dual-engine story:
PostgreSQL runs production, SQLite backs the test suite, and the two were
assumed to behave identically under the plain-standard-SQL changelog — an
assumption PRD-1 carried at low severity and
[ARCHITECTURE](../../ARCHITECTURE.md) stated as settled ("SQLite backs tests
and embedded use"). The epic's
[discovery](../../execution/milestone-1/02-database-bringup/discovery.md)
executed that assumption instead of trusting it: Liquibase rejects the
committed changelog on SQLite outright — nine constraint-adding changes are
unsupported (FIND1) — and the only repair, a restructured changelog, cannot
express the three cross-project composite foreign keys or the self-block CHECK
at all, on an engine that additionally ships foreign-key enforcement off by
default per connection (FIND3, FIND4). The same investigation proved a real
PostgreSQL can back tests without Docker: embedded binaries pulled from Maven
Central started in seconds on a Docker-less machine and passed every probe
(FIND2, FIND5). Any acceptable option had to satisfy three drivers: tests
validate the schema production actually runs; developer machines need neither
Docker nor a database install; and the changelog stays single-source, with no
per-engine variants.

## Decision

We will support exactly one database engine: PostgreSQL. Production,
development, and tests all run it; tests obtain theirs as embedded PostgreSQL
binaries (Zonky embedded-postgres, fetched from Maven Central as ordinary
dependencies), migrated by the same unmodified Liquibase changelog as
production. SQLite is dropped entirely — as test engine, dev convenience, and
embedded target. The schema keeps its plain-standard-SQL discipline (no
engine-specific features), but no second engine is promised, listed, or
tested. This satisfies all three drivers at once where every alternative fails
at least one: the test schema *is* the production schema at full fidelity —
all 22 constraints, the `ready_item` view, every discovery probe passing —
the changelog stays exactly as committed, and a bare developer machine runs
the whole loop. Scope: the engine choice is binding project-wide; the harness
(Zonky) is the mechanism serving it — if embedded binaries fail somewhere
(see Revisit when), the harness changes, not the engine.

## Options considered

- **OPT1 — Status quo: SQLite runs the committed changelog** — keep REQ2 as
  written; *ruled out:* not viable at all — Liquibase validation rejects nine
  of the changelog's changes on SQLite before touching the database (FIND1).
- **OPT2 — Restructure the changelog to fit SQLite** — composite keys inlined,
  composite UNIQUEs as unique indexes; *ruled out:* the variant applies, but
  three cross-project composite foreign keys and the self-block CHECK have no
  expressible form — violating rows were accepted — and even surviving foreign
  keys go unenforced unless every connection opts in (FIND3, FIND4); tests
  would validate a measurably weaker schema than production, maintained as a
  second changelog. Genuine strength: instant, zero-dependency, in-process
  test databases.
- **OPT3 — Real Postgres via Docker (Testcontainers)** — the industry default
  for engine-faithful tests; *ruled out:* requires Docker, which the driving
  development machine does not have and which GOAL1's unattended local loop
  must not assume. Genuine strength: mature ecosystem, CI-native.
- **OPT4 — H2 in PostgreSQL-compatibility mode** — the pure-JVM stand-in
  db/README once named beside SQLite; *ruled out:* an emulation of Postgres,
  not Postgres — the same tests-pass-production-differs risk class OPT2
  measures, and never even executed here. Genuine strength: instant and
  dependency-free.

## Consequences

- **gain** — tests exercise the production engine at full fidelity: every
  constraint, the view, and all eleven discovery probes hold under test
  exactly as in production; lands on: the test suite and GOAL3's rule
  coverage.
- **gain** — one changelog, zero variants, no per-engine conditionals; lands
  on: `db/changelog` maintenance.
- **gain** — the end-to-end loop runs on a bare developer machine — no
  Docker, no installed database; lands on: GOAL1 and onboarding.
- **cost** — REQ2's wording, ARCHITECTURE's engine statements, and
  `db/README`'s engine table promised SQLite and must be revised to match
  this record; lands on: this epic's documentation follow-through.
- **cost** — a third-party test dependency: Zonky's ~80 MB of binaries per
  machine and architecture, cached by the build, network needed on first
  fetch; lands on: developer machines and CI.
- **cost** — a test database that starts in seconds rather than SQLite's
  instant in-memory open; lands on: test-suite runtime.
- **cost** — the embedded path is verified on one macOS machine, where the
  binary ran under Rosetta translation; Linux CI and native arm64 are
  unverified; lands on: this epic's bring-up, which must run the discovery's
  probe suite in CI.
- **cost** — no zero-install embedded engine remains for hypothetical
  embedded deployments; embedded now also means embedded Postgres; lands on:
  future deployment stories.

## Revisit when

- **Embedded binaries fail or badly slow on Linux CI or native arm64** —
  reconsider: the test harness (Docker/Testcontainers in CI is the natural
  fallback), not the engine.
- **Zonky stops releasing or its binaries stop tracking PostgreSQL** —
  reconsider: the harness; the engine decision stands on any other
  real-Postgres supply.
- **A concrete demand for a second engine arrives** — reconsider: the
  single-engine scope, via a new ADR; the discovery has the SQLite variant's
  costs already priced.
