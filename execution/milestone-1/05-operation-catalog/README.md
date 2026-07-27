# Epic 05 — Operation catalog

**Addresses:** REQ5 (the internal RPC API: the operation catalog exposed
once by the core service for both adapters).

Documents, in the order they were produced:

- [spec-3.md](./spec-3.md) — the behavior contract for the connection between
  the core service and its two adapters: the eleven operations it carries,
  what must survive the crossing unchanged, and how a caller tells an answer
  from a refusal from a breakdown.
- [discovery.md](./discovery.md) — seven probe groups against the real services,
  a real server, and a real client, settling how the connection gets built: why
  the catalog goes behind one address with the operation named inside, why the
  three states of a partial update have to be written by hand rather than
  generated, that the waiting and no-resend rules hold as the client comes, that
  a caller giving up mid-write leaves the write alone, and that the loopback
  binding is the whole of the protection.
- [plan.md](./plan.md) — the build route, steps ticked as execution proceeds.

## Results

The connection landed in two halves. `:core-service` gained `io.nook.core.catalog`
— `CoreCatalog`, which is the eleven operations over the write and read services
unchanged, and `CatalogServer`, which answers them on one address. `:contract`
gained the other half: the `OperationCatalog` interface both sides are written
against, the conversions the library could not generate, the request and reply
shapes, one wiring per operation that both directions read, and `CatalogClient`.
`Main.kt` stopped being a placeholder. Neither `WriteService` nor `ReadService`
was touched, which is the result: the operations did not learn they were being
called from somewhere else.

One address carries all eleven, with the operation named inside the request and
the reply naming its own ending — answer, refusal, or fault. An address per
operation is what a web framework invites, and it would have turned a request
naming an operation nobody defined into the server's own empty "no such
address", which is a breakdown where a refusal is required. The same reasoning
put the decoding of the request inside the attempt that runs it: contents that
cannot be read are then a `validation_failed` refusal rather than a fault, which
is the difference between a caller fixing its call and a caller reporting the
core.

The wiring table lives in `:contract` rather than in the core. Each operation
states once what its payload is, what its answer is, and how it is invoked; the
calling library builds a request from that statement and the core runs a request
against it. Split by direction across two modules, the same eleven pairings would
have been written down twice. Whether an operation acts inside a project is the
shape of its wiring rather than a flag on it, so both ways a caller can get the
scope wrong are refused in one place instead of eleven.

The two partial-update conversions are hand-written at the payload level, each
field decided by whether the request mentions it, because a generated one cannot
tell "left alone" from "set to nothing" — and those three states are the whole of
what a partial update means. A structural check asserts the keys written equal
the command's declared fields, so a field added later cannot be quietly dropped
on the way across.

No text-format library sits between the web layer and the shapes. Both halves
take the body as text and hand it to the contract's own converter, which is one
dependency fewer and, more usefully, puts the decoding on a line the handler
chooses rather than one the server owns.

### Rule-to-test mapping

Every acceptance criterion of [spec-3](./spec-3.md), as the named test that
executes it (tests carry no criterion numbers by design — code never cites
planning artifacts):

| Criterion | Test |
| --- | --- |
| AC1 | `CatalogSurfaceTest` — the catalog offers exactly the eleven operations, under the names they travel by; the calling library offers the same eleven, and one way to let go of the connection; `RequestReplyCrossingTest` — a field no operation defines is refused rather than ignored, wherever it appears |
| AC2 | The fourteen `…AcrossConnectionTest` suites of the write and read paths — every criterion of spec-1 and spec-2, driven through the calling library, reaching the verdict its `…InProcessTest` twin reaches against the same assertions |
| AC3 | `CatalogSurfaceTest` — the seven project-scoped operations take the project they act inside, first; the four instance-level operations offer no place to name a project to act inside; every operation is either project-scoped or instance-level, and the two lists are the eleven |
| AC4 | `CatalogFidelityTest` — the values the core is invoked with are the values that were handed over; `ValueCrossingTest` — text arrives exactly as it was sent |
| AC5 | `PartialUpdateCrossingTest` — every state of every item field comes back as it went out; leaving a field alone and setting it to nothing are never the same request; an update naming no field writes nothing but the item it names |
| AC6 | `ValueCrossingTest` — not filtering on a part and filtering on no values stay different things; `ReadServiceListingAcrossConnectionTest` — no filter returns every item in the project, and a part supplied with no values at all is refused rather than answered emptily |
| AC7 | `PartialUpdateCrossingTest` — every state of every item field comes back as it went out; `WriteServiceBlockerAcrossConnectionTest` — the supplied set replaces the whole set, empty clears it, and an update that changes other fields leaves it alone |
| AC8 | `EntityCrossingTest` — every entity the core produces comes back equal to itself, compared whole rather than field by chosen field; `CatalogFidelityTest` — a listing of five thousand items arrives whole, in the core's order, inside the limit |
| AC9 | `CatalogFidelityTest` — both deletes report success, hand back no entity, and stay apart from a refusal; `CatalogSurfaceTest` — the two deletes hand back nothing, so no caller is given a row that no longer exists |
| AC10 | `CatalogRefusalTest` — each of the four refusals arrives carrying the core's own code and message; a refusal's details cross unchanged |
| AC11 | `CatalogRefusalTest` — a fault planted inside the core arrives as a breakdown carrying no refusal code |
| AC12 | `CatalogReachabilityTest` — a caller outlives the core being absent, arriving, leaving mid-call, and coming back, without ever being rebuilt |
| AC13 | `CatalogRefusalTest` — a request this connection cannot read is refused, and nothing reaches the store; `RequestReplyCrossingTest` — a field no operation defines is refused rather than ignored, wherever it appears |
| AC14 | `CatalogWaitingTest` — a call the core holds without answering gives up between 30 and 31 seconds |
| AC15 | `CatalogWaitingTest` — the core receives a request exactly once, whichever way the call ends |
| AC16 | `CatalogAbandonedWriteTest` — a caller giving up mid-write leaves the item whole with its edges, or untouched |
| AC17 | `CatalogWaitingTest` — a fast call made by another caller while a slow one is in flight is answered first |
| AC18 | `WriteServiceSlugRaceAcrossConnectionTest` — two simultaneous creators of the same name always both succeed with distinct slugs; `WriteServiceCycleRaceAcrossConnectionTest` — of two simultaneous half-loop writers, exactly one succeeds and no loop is ever stored |
| AC19 | `ReadServiceListingDuringWriteAcrossConnectionTest` — a listing taken while another caller writes always shows fully committed items |
| AC20 | `CoreProgramTest` — a core bound to loopback answers there and nowhere else this machine can be reached |
| AC21 | `CoreProgramTest` — told their addresses from outside, the two programs come up and a call passes between them; a program started without a setting stops and names the one it is missing |
| AC22 | `checkPersistenceBoundary`, which is a build task rather than a test — it walks every source set's compile and runtime graph of `:contract`, `:mcp-server` and `:web-app` for banned coordinates, and runs as part of `check` |

AC20's test earns its verdict rather than assuming it. An address that cannot
reach a core listening on every address proves nothing about what the loopback
binding refuses — the network turned the call away, not the binding. So it first
stands up a core bound to everything and establishes which of this machine's
addresses reach it at all; only those are then tried against the loopback-bound
core. On a machine with no such address it stops and says so instead of passing
emptily.

The supporting checks: `ValueCrossingTest` (a vocabulary crosses as the label a
caller writes rather than its Kotlin name, a word outside one is refused naming
the vocabulary, a moment names the same instant to the microsecond, a calendar
date carries no time and no zone, an absent field stays absent rather than
arriving as empty text, and each of the filter's five parts survives),
`RequestReplyCrossingTest` (a request names its operation, the project it acts
inside and its payload; an instance-level one carries no project and one asking
nothing carries no payload; each of the three endings names itself and survives
being written out and read back; and a reply naming no ending cannot be read as
one), `PartialUpdateCrossingTest` (both conversions carry every field their
command declares, so a field added later cannot be dropped in silence), and
`EntityCrossingTest` (a field the core left empty arrives empty, not as text of
no length).

What this epic asked of the two epics before it was that their suites run twice.
They do, as fourteen abstract behavior suites with an in-process and an
across-the-connection class each, and not one assertion was edited in the move —
which is the point, since an assertion changed while a suite was being made to
run twice would hide whether the connection or the change broke something. Two
sets of checks stayed in-process because their subject is the store rather than
an operation: the read path's transaction discipline, and the column widths the
write path's limits are taken from.
