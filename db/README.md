# Database

Structure lives here (Postgres and other whitelisted SQL engines). Document
**content** does not — it lives in git behind the `ArtifactStore`; these tables
only hold structure plus a pointer (`path` + `current_version`) to each document.
See [`../ARCHITECTURE.md`](../ARCHITECTURE.md) §2, §7, §8, §13.

## Schema management: Liquibase

The schema is defined as a **Liquibase** changelog, not hand-written per-dialect
SQL, so it targets several SQL databases from one source of truth.

- Master changelog: [`changelog/db.changelog-master.yaml`](./changelog/db.changelog-master.yaml)
- Changes: [`changelog/changes/`](./changelog/changes/) (applied in order; each
  changeSet is immutable once released — evolve the schema by **adding** changeSets).

## Supported databases (the whitelist)

We do **not** flatten the schema to a lowest-common-denominator. Instead we support
only databases that provide the features Nook's integrity rules depend on — chiefly
**partial / filtered UNIQUE indexes** (used for name-uniqueness-per-owner and
"one manifesto per epic / one plan per task").

| Database       | Role                    | Why in / out                         |
| -------------- | ----------------------- | ------------------------------------ |
| **PostgreSQL** | Primary (production)    | Full support; the reference engine.  |
| **SQLite**     | Tests / embedded / dev  | Has partial indexes; zero-setup.     |
| **SQL Server** | Enterprise deployments  | Has filtered indexes.                |
| MySQL / MariaDB | ❌ not supported        | No partial indexes.                  |
| Oracle         | ❌ not supported        | No partial indexes (only hacks).     |

## Portability choices

To stay portable across the whitelist while keeping the strong constraints:

- **Abstract column types.** Liquibase maps `uuid`, `timestamp`, `varchar`, etc. to
  each dialect. **UUIDs are generated in the application**, so there is no
  DB-specific default (`gen_random_uuid()` and friends are avoided).
- **Status/kind domains are `VARCHAR + CHECK`, not native `ENUM`** — SQLite and SQL
  Server have no enum type, and varchar+check is easier to evolve.
- **CHECK constraints, partial UNIQUE indexes, and the `ready_task` view** are
  emitted as ANSI-portable SQL that is valid as-is on all three whitelisted
  engines (`CREATE UNIQUE INDEX … WHERE …` in particular is common to all three).

## Running migrations

```bash
# Example: apply to a local Postgres
liquibase \
  --changelog-file=db/changelog/db.changelog-master.yaml \
  --url=jdbc:postgresql://localhost:5432/nook \
  --username=nook --password=nook \
  update
```

For tests, point the same changelog at an in-memory SQLite/H2 URL. Production
deployments run `update` on startup or via CI before the app boots.
