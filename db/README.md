# Database

Structure lives here (Postgres and other standard-SQL engines). Document
**content** does not — it lives in git behind the `ArtifactStore`; these tables
only hold structure plus a pointer (`path` + `current_version`) to each document.
See [`../ARCHITECTURE.md`](../ARCHITECTURE.md) §2, §7, §8, §13.

## Schema management: Liquibase

The schema is defined as a **Liquibase** changelog, not hand-written per-dialect
SQL, so it targets several SQL databases from one source of truth.

- Master changelog: [`changelog/db.changelog-master.yaml`](./changelog/db.changelog-master.yaml)
- Changes: [`changelog/changes/`](./changelog/changes/) (applied in order; each
  changeSet is immutable once released — evolve the schema by **adding** changeSets).

## Supported databases

The schema is **plain standard SQL** — tables, plain UNIQUE / PK / FK constraints,
CHECK constraints, and a view. It does **not** rely on partial/filtered indexes or
any other engine-specific capability, so it is not tied to a particular database.

| Database       | Role                    | Notes                                |
| -------------- | ----------------------- | ------------------------------------ |
| **PostgreSQL** | Primary (production)    | The reference engine.                |
| **SQLite**     | Tests / embedded / dev  | Zero-setup; used by the test suite.  |

Only these two are actively exercised. Because the DDL is standard SQL, other
engines (SQL Server, MySQL 8+, MariaDB, Oracle) are not excluded by design — they
are simply not part of the tested matrix today.

## Portability choices

To stay engine-agnostic while keeping the strong constraints:

- **Abstract column types.** Liquibase maps `uuid`, `timestamp`, `varchar`, etc. to
  each dialect. **UUIDs are generated in the application**, so there is no
  DB-specific default (`gen_random_uuid()` and friends are avoided).
- **Status/kind domains are `VARCHAR + CHECK`, not native `ENUM`** — not every
  engine has an enum type, and varchar+check is easier to evolve.
- **Uniqueness is plain, not partial.** Every UNIQUE is a whole-table constraint
  (e.g. task/epic/release slugs are unique per project via `(project_id, slug)`),
  so there is no `WHERE`-filtered index to depend on.
- **CHECK constraints and the `ready_task` view** are emitted as ANSI-portable SQL.

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
