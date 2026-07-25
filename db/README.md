# Database

Structure lives here (PostgreSQL). Document
**content** does not — it lives in git behind the `ArtifactStore`; these tables
only hold structure plus a pointer (`path` + `current_version`) to each document.
See [`../ARCHITECTURE.md`](../ARCHITECTURE.md) §2, §7, §8, §13.

## Schema management: Liquibase

The schema is defined as a **Liquibase** changelog — one ordered source of
truth, applied identically in production and tests.

- Master changelog: [`changelog/db.changelog-master.yaml`](./changelog/db.changelog-master.yaml)
- Changes: [`changelog/changes/`](./changelog/changes/) (applied in order; each
  changeSet is immutable once released — evolve the schema by **adding** changeSets).

## Supported database

**PostgreSQL only** ([ADR-1](../architecture/adrs/adr-1.md)): production,
development, and tests all run it — the test suite starts real PostgreSQL from
embedded binaries, so no Docker or local install is needed. No other engine is
promised or tested; the committed changelog does not even validate on SQLite
(its post-create constraint additions are unsupported there).

## Plain-SQL choices

The schema sticks to plain standard SQL — tables, plain UNIQUE / PK / FK
constraints, CHECK constraints, and a view, with no partial/filtered indexes or
other engine-specific capabilities. That is discipline, not a promise of
portability; it keeps the DDL simple and the constraints strong:

- **Abstract column types.** Liquibase maps `uuid`, `timestamp`, `varchar`, etc. to
  each dialect. **UUIDs are generated in the application**, so there is no
  DB-specific default (`gen_random_uuid()` and friends are avoided).
- **Enum domains (`status`, `kind`, `type`) are `SMALLINT` codes**, not native
  `ENUM` and not `VARCHAR + CHECK`. The meaning lives in the application enums, each
  member carrying an **explicit, stable integer** (never an ordinal, so reordering an
  enum can't remap existing rows). The single writer (core service) is the sole
  inserter, so domain membership is enforced there; the code table is in the schema
  header comment.
- **Uniqueness is plain, not partial.** Every UNIQUE is a whole-table constraint
  (e.g. task/epic/release slugs are unique per project via `(project_id, slug)`),
  so there is no `WHERE`-filtered index to depend on. This is also why deleting
  removes the row rather than marking it: a mark would need uniqueness limited to
  unmarked rows, which exists only as a partial index (see
  [`docs/04`](../docs/04-structure-semantics.md)).
- **`instance_lock` is a table that exists to be locked.** Writers take turns by
  locking a row `FOR UPDATE` — plain standard SQL, where a named lock primitive
  would not be. A writer inside one project locks that project's own row; the two
  writes contending over the instance-wide space of project handles (creating and
  deleting a project) have no such row, so they lock a dedicated one here. It
  holds no data and nothing reads its contents; one row per scope, so a second
  scope would not queue behind the first. The row is seeded by the changelog, and
  the write path treats its absence as a broken schema rather than as "no lock
  needed".
- **The CHECK constraint and the `ready_item` view** are emitted as ANSI-portable SQL.

## Running migrations

```bash
# Example: apply to a local Postgres
liquibase \
  --changelog-file=db/changelog/db.changelog-master.yaml \
  --url=jdbc:postgresql://localhost:5432/nook \
  --username=nook --password=nook \
  update
```

Tests apply the same changelog to an embedded PostgreSQL through Liquibase's
in-process Java API — no CLI involved. Production deployments run `update` on
startup or via CI before the app boots.
