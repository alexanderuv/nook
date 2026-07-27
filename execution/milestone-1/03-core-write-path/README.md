# Epic 03 — Core write path

**Addresses:** REQ3 (the single write path in the core service, enforcing
spec 04's semantics: containment, status vocabulary, slugs,
cancel-not-delete, cycle-rejecting `blocked_by`, releases as loose buckets).

Documents, in the order they were produced:

- [discovery.md](./discovery.md) — the concurrency and error-surface probes:
  proved the per-project advisory lock closes the cycle and slug races, and
  that constraint violations surface with their constraint names.
- [spec-1.md](./spec-1.md) — the behavior contract for the seven mutations:
  requirements, edge cases, and acceptance criteria.
- [plan.md](./plan.md) — the build route, steps ticked as execution proceeds.

## Results

The write path landed as planned: `:contract` holds the shared enums (each
member pinned to its stored integer), the three entity data classes, and the
structured-error surface; `:core-service` gained `io.nook.core.write` — the
slug and cycle rules as pure functions, the reference resolver, the
constraint-name error translator, and one `WriteService` class whose public
surface is exactly the seven mutations. Every write runs in a fresh
transaction with retries disabled and takes the per-project advisory lock (or
the instance-wide lock for project creation) before touching anything. *(Epic 04
replaced both advisory locks with locked rows — `SELECT … FOR UPDATE` — so the
write path holds no engine-specific SQL; the turn-taking is unchanged.)* Both
concurrency stress tests held at 100 repetitions each.

Deviations from the plan, all folded into its text: entity ids are Kotlin's
`Uuid` (the database layer's own type); the PostgreSQL driver became
compile-visible in `:core-service` so the translator can read constraint
names off the driver's exception; the translator-coverage enumeration needs a
live transaction; AC9's tests live with the update tests.

### Rule-to-test mapping

Every acceptance criterion of [spec-1](./spec-1.md), as the named test that
executes it (tests carry no criterion numbers by design — code never cites
planning artifacts):

| Criterion | Test |
| --- | --- |
| AC1 | `WriteServiceSurfaceTest` — the public surface is exactly the seven mutations |
| AC2 | `WriteServiceCreateBehavior` — create_project derives the slug, returns the full entity, and suffixes a name collision; a new release starts planned and a new item starts todo |
| AC3 | `WriteServiceCreateBehavior` — create_project derives the slug, returns the full entity, and suffixes a name collision |
| AC4 | `WriteServiceCreateBehavior` — an unknown item type is rejected |
| AC5 | `WriteServiceCreateBehavior` — a leaf parents under an epic and a parentless leaf sits at project level |
| AC6 | `WriteServiceCreateBehavior` — a leaf cannot parent, an epic cannot be parented, and a leaf cannot join a release |
| AC7 | `WriteServiceCreateBehavior` — derived slugs take suffixes in sequence |
| AC8 | `WriteServiceCreateBehavior` — derivation skips over an explicitly claimed suffix to the first free one |
| AC9 | `WriteServiceUpdateBehavior` — a taken explicit slug conflicts on create, and supplying an item's own slug is a no-op |
| AC10 | `WriteServiceCreateBehavior` — unusable names and slugs are rejected, and an explicit slug saves an unusable name |
| AC11 | `WriteServiceUpdateBehavior` — renaming changes the name but never the slug, by slug or by id |
| AC12 | `WriteServiceUpdateBehavior` — an explicit slug change moves resolution to the new slug and retires the old |
| AC13 | `WriteServiceUpdateBehavior` — statuses move freely in the vocabulary, cascade to nothing, and reject outside values |
| AC14 | `WriteServiceUpdateBehavior` — a done task reopens and its row never ceases to exist |
| AC15 | `WriteServiceUpdateBehavior` — leaf types interchange freely but an edge-holding leaf cannot become an epic until cleared |
| AC16 | `WriteServiceUpdateBehavior` — an epic with children cannot become a leaf until they are reparented; an epic in a release cannot become a leaf until it is unassigned |
| AC17 | `WriteServiceUpdateBehavior` — reparenting keeps the slug and blockers, clearing goes project-level, and same-parent is accepted |
| AC18 | `WriteServiceBlockerBehavior` — the supplied set replaces the whole set, deduplicated, and empty clears it; an update that changes other fields leaves the blocker set alone |
| AC19 | `WriteServiceBlockerBehavior` — blockers must be same-project leaves that exist |
| AC20 | `WriteServiceBlockerBehavior` — a chain-closing set is rejected as a cycle and stores nothing |
| AC21 | `WriteServiceReleaseBehavior` — a past target date is accepted, updates follow the vocabulary, and outside values are rejected |
| AC22 | `WriteServiceReleaseBehavior` — assignment applies to epics only and no status locks it; an update that says nothing about the release leaves the assignment alone |
| AC23 | `WriteServiceCycleRaceBehavior` — of two simultaneous half-loop writers, exactly one succeeds and no loop is ever stored |
| AC24 | `WriteServiceSlugRaceBehavior` — two simultaneous creators of the same name always both succeed with distinct slugs |
| AC25 | `WriteServiceUpdateBehavior` — a failed update is one structured error and changes nothing |

The supporting units: `SlugsTest` (derivation, explicit-slug validation,
suffix allocation and the room it is kept in), `CycleDetectionTest` (the walk on
the chain, diamond, and safe-edge shapes), `StoreNeverArbitratesTest` (every
schema rule a caller can reach is refused by validation first, and a rejection
from the store travels as a fault in the service), `WriteServiceGuardBehavior` (the
rules nothing underneath the service would catch), `WriteServiceInputBehavior`
(caller text the store could not hold) and `WriteServiceLimitsTest` (the widths
those limits are taken from), `EnumCodesTest` in `:contract` (codes
against the changelog map), `WriteLockTest` (the locked row provably
serializes), `TransactionNestingTest` (neither discipline may be entered from
inside another), and `ReferenceResolutionTest` (id-first, slug-second,
project-scoped).

`ConstraintTranslationTest` and `AdvisoryLockTest` are named in earlier drafts
of this table and do not exist. The first was dropped with the translator it
tested — validation decides before the store is asked, so there is nothing to
translate — and the second was renamed `WriteLockTest` when the advisory lock
became a locked row.

What this epic shipped as nine mutations is now seven: `set_item_blocked_by`
and `assign_epic_to_release` became the `blockedBy` and `releaseRef` fields of
`update_item`. The prose above records what was built on the day and is left as
it was; the table's rows are pointers and were re-aimed at the tests that
carry those rules now, which is also where the case a field has and an
operation did not — an update that never mentions the field — is covered. Two
tests exercise the rule the fold introduced, that both fields are read against
the type the item ends up with: `WriteServiceBlockerBehavior` — the target must be
a leaf once the update lands, whatever it is now; and `WriteServiceReleaseBehavior`
— the target must be an epic once the update lands, whatever it is now.

The suites this table names end in `Behavior` rather than `Test` because each
now holds its assertions once, as an abstract suite, beside the two concrete
classes that run them: once against the operations inside the core's own
process, and once across the connection the following epic built. Not one
assertion was edited in the move — that is the point of it, since an assertion
changed while a suite was being made to run twice would hide whether the
connection or the change broke something. `WriteServiceInputBehavior` kept the
checks about caller text and spun off the two that read column widths and call
nothing at all; those became `WriteServiceLimitsTest` and stay in-process,
being a property of the store rather than of an operation.
