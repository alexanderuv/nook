# Structure queries approach

> **Its recommendation was not taken; its measurements stand.** This report
> recommended soft delete, and the epic built it and then reversed it: the
> partial unique index FIND1 shows to be unavoidable is the one engine-specific
> feature the schema refuses ([ADR-1](../../../architecture/adrs/adr-1.md)), and
> that constraint was never weighed here. Deletion now removes the row. FIND1
> through FIND5 remain accurate about what a soft-delete design would have
> cost — which is precisely why it was dropped — and FIND6 through FIND10, on
> the filter, the ordering, the blocker fetch, and the transaction discipline,
> are what the reads were actually built on. See the reversal note in
> [the plan](./plan.md).

## Summary

- **Letting a deleted row give up its handle works, but only as a uniqueness
  rule that applies to some rows and not others** — PostgreSQL refuses to put a
  condition on a plain uniqueness constraint, so the rule has to be rebuilt as a
  conditional unique index. Once it is, a name freed by a delete is immediately
  reusable, the freed name still resolves to the live row alone, and the deleted
  row remains reachable by its identifier.
- **Today's readiness view is wrong in two ways the moment deletion exists**: it
  offers a deleted item as ready work, and it keeps an item blocked by a blocker
  that has been deleted. The amended view answers both correctly. A view also
  freezes its column list when it is created, so the view must be rebuilt in the
  same schema change that adds the deleted mark, or it will never show it.
- **The whole listing filter composes in the data-access library** — several
  values in one part, parts narrowing each other, and the "sitting under no
  epic" case — with one edge to watch: a part supplied with an empty list of
  values quietly matches nothing instead of complaining, so the caller's mistake
  has to be caught in application code.
- **A listing assembled from two queries can show a moment that never existed**:
  at PostgreSQL's normal setting each query sees a fresh snapshot, and the probe
  caught an item reported as not started by the first query while the second
  query already showed the blocker that arrived with its status change. Asking
  for a stricter setting (repeatable read) removed the anomaly, and a read-only
  transaction additionally makes "a read changes nothing" structural rather than
  a matter of care — a write inside one is refused by the database. Of the two
  ways to fetch those blocker sets, one extra query for the whole listing beat
  one query per item by about five times (4.8 ms against 24.5 ms at 510 items),
  for identical results.
- Recommendation in brief: build the deleted mark, the two new actions, and the
  rebuilt view first, inside this epic; state the conditional uniqueness rule in
  the declarations (the library reproduces it exactly) but test it against the
  database directly, because the drift check is blind to the condition; run every
  read in a read-only repeatable-read transaction; and assemble blocker sets with
  a single extra query.

## Questions

- **Q1** — Can a deleted row give up its handle — the short name used in paths —
  so a caller may take that name again, while the deleted row stays reachable?;
  informs: the schema change behind the spec's deleted-rows requirements and its
  rule that a handle names live rows only.
- **Q2** — Can the Exposed declarations state a uniqueness rule that applies to
  live rows only, and does the drift check — the test that compares those
  declarations against the migrated database — actually verify it?; informs: how
  much of the schema change is protected against silently drifting apart.
- **Q3** — Does the readiness view answer the spec's readiness rule once
  amended, and what does today's view answer in a store that has deleted rows?;
  informs: the view amendment, and whether readiness can stay in the database at
  all.
- **Q4** — Can `list_items`' filter — every part optional, each part taking
  several values, plus the newest-first ordering with a tiebreak — be composed in
  the data-access library, and how does it behave at the edges?; informs: the
  listing implementation and which edges application code must police.
- **Q5 (emerged)** — A listing returns items with their blocker sets, which no
  single query produces; can a caller then see two different moments of the
  store, and what is the cheapest way to fetch those sets?; asked once the
  blocker-set fetch turned out to need a second query; informs: the read path's
  transaction discipline and the shape of the listing code.
- **Q6 (emerged)** — Does declaring the readiness view for reading also let code
  write through it?; asked while declaring the view as if it were a table;
  informs: how the spec's "a read changes nothing" rule is enforced.

Bound: one throwaway program on one machine (macOS, Apple silicon, JDK 25), all
library versions taken from the repo's own pins, ten probes, no load or volume
testing, and the trial schema changes applied as loose SQL rather than as
changelog entries. That sufficed because every question is about how a mechanism
behaves, which one honest execution settles, and the code that builds on the
answers lands in this same epic.

## Method

A throwaway Kotlin program (in a scratch directory, with its own build, deleted
after this report) ran against real PostgreSQL: Zonky embedded-postgres 2.2.2
with the 17.10 binaries, the repo's committed changelog applied in-process by
Liquibase 5.0.3, and data access through Exposed 1.3.1 — the same stack the read
path will use. Each probe got its own freshly created database.

Two schemas were compared throughout: the **committed** schema exactly as the
changelog builds it, and a **trial** schema — the committed one plus the
soft-delete changes the read spec assumes, applied as loose SQL: a nullable
`deleted_at` timestamp on `project`, `release`, and `project_item`; the handle
uniqueness rule on `project_item` dropped as a constraint and recreated as a
unique index limited to rows whose mark is unset; and the `ready_item` view
recreated to skip deleted items and to count a deleted blocker as resolved.

Ten probes:

- **Handles**: a duplicate name on the committed schema, to capture what the
  store says; asking PostgreSQL for a uniqueness *constraint* limited to live
  rows; then, on the trial schema, taking a deleted row's name again, taking a
  live row's name again, and resolving that name by handle and by identifier.
- **Declarations and the drift check**: the repo's own table declarations copied
  in with the deleted mark added, the handle rule declared with its live-only
  condition, and the check run against the trial schema — then run again with
  the mark missing, with the condition missing, with an extra uniqueness rule the
  schema does not have, and pointed at the view. Separately, the library was
  asked to build the tables on an empty database so the index it actually
  creates could be read back out of PostgreSQL and compared with the schema's own.
- **The view**: its column list before and after the underlying table gained a
  column with the view left alone, and again with the view rebuilt; an ordered
  read through it with the library's query builder; and a write attempted
  through it.
- **Readiness**: a project holding an epic, a free leaf, a leaf whose three
  blockers are done, deleted, and cancelled, a leaf held by an in-progress
  blocker, a finished leaf, and a deleted todo leaf — read from the amended
  view; then the same question of the committed view, in a store where a
  blocker had been deleted.
- **The filter**: twelve listings over one seeded project — no filter, two
  statuses, type with status, a repeated value, an epic parent, the no-epic
  value, both together, a release, a release with a leaf type, and each of the
  three deleted choices — plus the SQL generated for a part supplied with no
  values.
- **Ordering**: five items created in sequence and two sharing one creation
  instant, listed ten times with the identifier as tiebreak and ten times
  without.
- **Blocker sets**: 510 items carrying 1500 blocker edges, assembled both ways —
  one extra query for all the edges, and one query per item — checked for equal
  results, then timed, 20 runs averaged after a warm-up.
- **Two queries, one answer**: a reader ran the listing query, another caller
  then committed a status change and a new blocker edge together, and the reader
  ran the edge query — first at PostgreSQL's normal setting, then at the stricter
  repeatable-read setting. Then a read-only repeatable-read transaction through
  the library, with a write attempted inside it.
- **A deleted branch**: an epic with four children and an outside leaf blocked by
  one of them, the branch marked deleted in one statement, then read back four
  ways.

Not done: any load or volume testing, more than two simultaneous connections, a
second machine, the trial changes expressed as changelog entries, and any
exercise of delete and restore through the write path — every deleted row in
these probes was marked by hand-written SQL. Probe output is quoted in the
findings; the program itself keeps no authority.

## Findings

### FIND1 — A deleted row can give up its handle, but the rule must be rebuilt as a conditional index

**Confidence:** solid — every case executed on both schemas · answers Q1

PostgreSQL refuses a uniqueness constraint with a condition attached: asking for
one failed with the standard code for a syntax error (42601), `syntax error at
or near "WHERE"`. Uniqueness limited to some rows exists only as an index. With
the constraint dropped and the index created in its place, all three behaviors
the spec asks for held:

| Attempt on the trial schema | Result |
| --- | --- |
| take the handle of a deleted row | accepted |
| take the handle of a live row | refused, code 23505, rule `uq_item_project_slug` |
| resolve that handle among live rows | the live row alone |
| fetch the deleted row by its identifier | returned, marked deleted |

Both rows carrying the handle coexist — the probe listed them as one deleted and
one live. On the committed schema, by contrast, the second row is refused
whatever its mark, because the rule covers every row.

### FIND2 — The declarations can state the live-only rule exactly; the drift check cannot see it

**Confidence:** solid — the library's own index read back from PostgreSQL, and four
variants of the check run · answers Q2

Asked to build the tables on an empty database, the library produced

```
CREATE UNIQUE INDEX uq_item_project_slug ON public.project_item
  USING btree (project_id, slug) WHERE (deleted_at IS NULL)
```

which is, word for word, what PostgreSQL reports for the hand-written index in
the trial schema. So the declaration expresses the rule faithfully.

What the drift check notices is narrower than that:

| The declarations say | The check reports |
| --- | --- |
| everything the trial schema has | nothing — they match |
| no deleted mark at all | `ALTER TABLE project_item DROP COLUMN deleted_at` |
| the handle rule with its condition removed | nothing |
| a uniqueness rule the schema does not have | `ALTER TABLE … ADD CONSTRAINT uq_item_name_nobody_declared UNIQUE …` |

A missing column is caught, and a missing rule is caught, but the condition that
limits the rule to live rows is invisible: the check compares which rules exist,
not which rows they cover. Nothing in it would notice the day the index is
rebuilt without its condition.

### FIND3 — The amended view answers readiness correctly; today's view is wrong in two ways once rows can be deleted

**Confidence:** solid — both views read against seeded stores · answers Q3

Against the seeded project, the amended view returned exactly the free leaf and
the leaf whose blockers were done, cancelled, and deleted — and nothing else: not
the epic, not the leaf held by an in-progress blocker, not the finished leaf, and
not the deleted todo leaf.

Today's view, in a store where a blocker had been deleted, got both halves of
that question wrong. It listed the deleted blocker itself as ready work, and it
withheld the leaf whose only blocker was that deleted row — deletion means
nothing to it, so a deleted item both waits to be picked up and holds up
everything behind it.

### FIND4 — A view freezes its column list when it is created

**Confidence:** solid — observed directly · answers Q3

The view is defined as "select everything from the items table", but that is
expanded once, at creation. After the table gained the `deleted_at` column, the
view still reported its original thirteen columns; only recreating it produced
the fourteenth. A schema change that adds a column and leaves the view alone
therefore produces a view that silently lacks it.

### FIND5 — Reading through the view works; the same declaration also writes, and PostgreSQL accepts it

**Confidence:** solid — read and write both executed · answers Q6, Q3

Declared as if it were a table, the view served an ordered read through the
query builder normally, returning the two ready leaves newest-first. It also
accepted an insert: writing into the view succeeded, PostgreSQL passing the row
through into the underlying table, because a view this simple is writable by
default. Nothing about declaring a view for reading confines it to reading.

The drift check must also be kept away from it: pointed at the view, the check
proposed `CREATE TABLE IF NOT EXISTS ready_item (…)` — it does not know a view
when it sees one.

### FIND6 — The whole filter composes; an empty list of values silently matches nothing

**Confidence:** solid — twelve listings plus the generated SQL · answers Q4

Every shape the spec asks for came back right: no filter returned all six live
items; two statuses widened; type with status narrowed to the open bug; a value
repeated changed nothing; an epic parent returned its child; the no-epic value
returned the two epics and the two loose bugs; that value combined with an epic
parent returned both groups; a release returned the epic assigned to it; that
release with a leaf type returned an empty list; and the three deleted choices
returned the six live items, the one deleted item, and all seven.

The one rough edge is a part supplied with no values at all. The library folds it
into the impossible condition `AND (FALSE)`, so the call succeeds and returns
nothing — where the spec wants the caller told they made a mistake. That
rejection has to live in application code; the query builder will not raise it.

### FIND7 — Newest-first with a tiebreak is stable, and this probe did not manage to make it unstable without one

**Confidence:** solid for the ordering with a tiebreak; weak for the tiebreak's
necessity — the instability it guards against did not reproduce · answers Q4

Ordered by creation time with the identifier as tiebreak, ten identical calls
returned one and the same order, the two same-instant rows included. Ordered
without the tiebreak, ten identical calls also returned a single order. So the
tiebreak demonstrably delivers repeatability, but the probe offers no evidence
that dropping it would cost anything: at this size PostgreSQL happened to be
consistent, and nothing promises it stays that way for another data size or
query plan.

### FIND8 — One extra query for blocker sets beat one query per item by about five times

**Confidence:** solid for the direction (identical results, repeated runs);
suggestive for the ratio (one machine, one shape, one size) · answers Q5

At 510 items carrying 1500 blocker edges, both shapes assembled the same 1500
edges. Averaged over 20 runs after a warm-up: one listing query plus one query
fetching every edge for the listed identifiers took 4.8 ms; the same listing with
one edge query per item took 24.5 ms.

### FIND9 — Two queries can report a moment that never existed; a stricter setting prevents it, and read-only makes "reads change nothing" structural

**Confidence:** solid — the anomaly reproduced deliberately, then removed ·
answers Q5

A reader listed an item and saw it as not started. Another caller then committed,
in one transaction, both a status change on that item and a new blocker edge for
it. The reader's second query — the blocker fetch — saw the edge. The assembled
answer described an item that was still todo and already blocked, a combination
that never existed in the store: at PostgreSQL's normal setting every statement
takes a fresh snapshot, so the two halves of one answer can straddle somebody
else's commit.

Repeated at the stricter repeatable-read setting, where the whole transaction
reads as of its first statement, the second query saw no edge and the two halves
agreed. The library requests that setting per transaction (asked what it was
running at, PostgreSQL answered `repeatable read`), and it can mark the
transaction read-only in the same call — in which case an attempted update is
refused by the database itself with code 25006, `cannot execute UPDATE in a
read-only transaction`.

### FIND10 — A deleted branch reads back exactly as the spec describes

**Confidence:** solid — every reading executed · answers Q1, Q3

One statement marked an epic and its four children deleted — five rows. After
that: a live listing returned only the leaf outside the branch; the readiness
view returned that same leaf, now ready because the blocker holding it up had
been deleted; filtering by the deleted epic returned nothing among live rows and
all four children among deleted ones; and a child fetched by its identifier came
back marked deleted.

## Implications & recommendation

- **Build the deleted mark, delete and restore, and the rebuilt view inside this
  epic, before the reads** (FIND1, FIND3, FIND4) — the read spec's requirements
  about deleted rows are untestable until a deleted row can exist, and every
  probe here had to fake one with hand-written SQL. Fold that groundwork in
  rather than reopening the write-path epic: one epic then delivers deletion end
  to end, and the reads are built against a store that already behaves.
- **Carry the mark as a nullable timestamp on `project`, `release`, and
  `project_item`** (opinion) — no finding here compares the shapes; the probes
  simply used a timestamp throughout, and it costs nothing over a true/false
  flag while answering a question a flag cannot: no value means live, a value
  records when the row left.
- **Rebuild the handle rule as a unique index limited to rows with no mark, in
  raw SQL in the changelog** (FIND1) — the conditional form does not exist as a
  constraint, so the changeset must drop the constraint and create the index.
  This also retires a claim the master changelog makes about itself, that the
  schema uses no database-specific features; that comment needs amending in the
  same change, since the sole supported engine is PostgreSQL anyway.
- **Recreate the readiness view in the same changeset that adds the mark**
  (FIND4) — a view built before the column will never show it, and the amended
  readiness rule needs it in both places: skip deleted items, and count a
  deleted blocker as resolved alongside done and cancelled.
- **Test the handle rule and the view against the database, not through the
  drift check** (FIND2, FIND5) — the check verifies that a rule with that name
  exists and says nothing about the rows it covers, and pointed at a view it
  proposes creating a table. Two small tests close the gap: one reading the
  index definition back out of PostgreSQL and asserting the live-only condition
  is in it, one asserting the view's own definition; and the view's declaration
  stays out of the set the check compares.
- **Run every read in one read-only, repeatable-read transaction** (FIND5,
  FIND9) — read-only turns "a read changes nothing" into something the database
  enforces, which matters more than usual here because the view declaration
  would otherwise accept writes; repeatable read is what makes a two-query
  listing describe one moment instead of two. Both are one call in the
  data-access library.
- **Assemble blocker sets with a single extra query keyed on the listed
  identifiers** (FIND8) — five times faster at 510 items than a query per item,
  for the same result, and it keeps the whole listing inside the two statements
  the transaction discipline above makes consistent.
- **Reject an empty list of filter values in application code** (FIND6) — the
  query builder turns it into a condition matching nothing and reports success,
  which is precisely the silent-wrong-answer the spec wants replaced by a
  complaint.
- **Keep the identifier as the ordering tiebreak** (FIND7) — the spec requires
  that an identical call return an identical order, and the tiebreak is what
  makes that true by construction rather than by luck of the query plan. This
  one is a judgment call, not a finding: the probe never caught the ordering
  wobbling without it.

## Limitations

- **The trial schema was applied as loose SQL, not as changelog entries** — at
  risk: nothing here shows Liquibase performing the same change, in particular
  dropping a uniqueness constraint and creating a conditional index in its
  place, and the migration is where that work actually lands; would raise
  confidence: writing the changeset and running the existing migration test
  against it, which is the first step of the work this report recommends.
- **No deleted row in these probes was produced by the write path** — at risk:
  every finding about deleted rows describes what reads do with a mark that was
  set by hand; how the two new actions set it, how deleting an epic reaches its
  children, and how restore behaves when a handle has been taken meanwhile are
  all unexamined; would raise confidence: exercising the same readings through
  the delete and restore actions once they exist.
- **Timings are one machine, one shape, one size** — at risk: the roughly
  fivefold gap between the two blocker-set shapes may narrow or widen at other
  sizes, and 4.8 ms says nothing about a project ten times larger; would raise
  confidence: repeating the measurement at several sizes if listing cost ever
  matters.
- **Concurrency was two connections doing one thing each** — at risk: the cost
  of repeatable read under real multi-agent read load is unmeasured, and it is
  recommended as the discipline for every read; would raise confidence: a load
  probe against the implemented read path (Q8).
- **The ordering probe never reproduced the instability the tiebreak guards
  against** — at risk: the recommendation to keep the tiebreak rests on the
  spec's requirement and on how databases are known to behave, not on evidence
  from this run; would raise confidence: nothing worth spending — the tiebreak
  is cheap and the alternative is unprovable in the safe direction.

## Open questions

**Follow-ups:**

- **Q7** — At what project size does returning every match in one unpaginated
  list stop being acceptable?; matters because: the read spec assumes projects
  stay small enough that this is fine and offers no fallback if they do not,
  while paging is deferred by the design documents; would take: measuring
  listing cost against progressively larger seeded projects once real usage
  suggests a size worth testing.
- **Q8** — Does a read-only repeatable-read transaction per read hold up under
  real multi-agent load?; matters because: it is recommended as the universal
  read discipline on the strength of correctness alone, with its cost only shown
  at two connections; would take: a load probe against the implemented read
  path when there is multi-agent usage to imitate.
