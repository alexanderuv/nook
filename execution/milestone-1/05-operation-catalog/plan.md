# Operation catalog — Plan

A note on references: this plan leans on the two documents beside it.
[Spec-3](./spec-3.md) numbers what it pins — REQ for requirements, EDGE for edge
cases, AC for acceptance criteria. [The discovery](./discovery.md) numbers its
findings FIND and its questions Q. When this plan cites one of those codes it is
pointing at a numbered entry in those documents, and it states the point in plain
words alongside, so the pointer is a cross-check and never required reading. None
of those codes belongs in code or in a code comment.

Three phrases recur below. **The core** is the program that owns the database —
the one epics 03 and 04 built. **The connection** is the link the core offers on
the machine it runs on, and the only way the two front-door programs reach
anything it owns. **The calling library** is the single piece of code, shared by
both front doors, that makes calls over that connection and reads the replies;
building it is half of this epic, and it is what stops the two front doors from
reading the same reply differently.

## Analysis

### What already exists, checked against the repository rather than remembered

- **The eleven operations, working, inside `:core-service`.** `WriteService`
  (`core-service/src/main/kotlin/io/nook/core/write/WriteService.kt`) offers
  exactly seven: `createProject`, `createItem`, `updateItem`, `createRelease`,
  `updateRelease`, `deleteItem`, `deleteProject`. `ReadService`
  (`.../io/nook/core/read/ReadService.kt`) offers exactly four: `getProject`,
  `listProjects`, `getItem`, `listItems`. Every write opens one fresh
  transaction and locks a row before touching anything; every read opens one
  transaction that is read-only and sees the store as of its first statement.
  The pieces both paths share — turning a caller's reference into a row, turning
  rows into the shared entity classes, and the two ways an operation refuses a
  caller — sit in `io.nook.core.store`.

- **`:contract`, six files of plain data classes and nothing else.**
  `Commands.kt` holds the five write commands; `Entities.kt` holds `Project`,
  `ProjectItem`, and `Release`; `Enums.kt` holds the three vocabularies, each
  member carrying both the number it is stored as and the label a caller writes
  (`task`, `in_progress`); `Errors.kt` holds the four refusal codes, the
  `{code, message, details}` value they travel in, and the exception that carries
  it; `FieldChange.kt` holds the three-state field of a partial update — leave
  alone, or set to this value, where the value may itself be nothing;
  `Queries.kt` holds the listing filter's five parts. The module declares every
  public name explicitly, and its build file records, in a comment, that it
  carries no code for turning values into text and back — because until now there
  was no wire for anything to travel over.

- **The shared build files, in `build-logic/`.** Three of them, applied by the
  modules instead of each repeating the same settings. `nook.kotlin-jvm` sets the
  Kotlin version and toolchain, makes every compiler warning a failure, and bounds
  every test at two minutes and every test task at twenty. `nook.application`
  adds the ability to run a module as a program. `nook.persistence-boundary`
  fails the build if a database library appears anywhere in a module's compile or
  runtime graph, in any source set including tests; it is applied to `:contract`,
  `:mcp-server`, and `:web-app` — every module except the core.

- **What the pinned-version list already names, and nobody uses.**
  `gradle/libs.versions.toml` pins the library that turns values into text and
  back (kotlinx.serialization 1.11.0) and names its build-time helper, and
  neither is referenced anywhere in the repository. Ktor 3.5.1 is pinned, but
  only `ktor-server-core` is depended on — which is the part that describes a
  server, not the part that listens on a port, and there is no client half at
  all. So this epic adds the engine that listens, the client, and the piece that
  joins either of them to a text format.

- **The test foundation.** One real PostgreSQL starts inside the test process for
  the whole test run, and each test class gets its own freshly created, fully
  migrated database from it (`EmbeddedPostgresSupport`). There are 158 tests
  today. The behavior suites reach the operations by constructing
  `WriteService(db)` and `ReadService(db)` directly — some in a companion object,
  some as a field on the test class — and read state back through the tables
  (`TestReads.kt`) rather than through the reads.

- **All three programs do nothing when started.** `io/nook/core/Main.kt`,
  `io/nook/mcp/Main.kt`, and `io/nook/web/Main.kt` are each a `main` whose body
  is empty, waiting for their epics.

### The framing documents, linked rather than restated

- **[Spec-3](./spec-3.md)** is the behavior contract for the connection: 30
  requirements, 20 edge cases, and 22 acceptance criteria. It pins what may
  cross, what must survive unchanged, the three ways a call can end, what happens
  when the core is slow or absent or sent something it cannot read, several
  callers at once, and where the core listens. The test plan below is that
  contract, executed.

- **[The discovery](./discovery.md)** settled how the connection gets built, by
  driving the real services behind a real server with a real client. Adopted here:
  - The catalog goes behind **one address, with the operation named inside the
    request**. It is the only layout in which a request naming an operation
    nobody defined comes back as a refusal instead of the web server's own empty
    "no such address" answer (FIND4).
  - The reply **names its own ending** rather than leaving it to the number every
    web reply carries — otherwise an item that is not there and an address that
    is not there both answer 404, and the number that is supposed to carry the
    ending cannot decide it (FIND4).
  - Reading the request **sits inside the same attempt that runs it**, or
    contents that cannot be read arrive as a fault where a refusal was required.
    Nothing about the framework forces either shape; it is which line of the
    handler the reading sits on, and the wrong one is the arrangement a handler
    falls into by default (FIND5).
  - The three states of a partial-update field are **decided by whether the
    request mentions the field at all**, in code written by hand. The code the
    library generates has one slot per field and no way to record that the field
    was there, so "leave this alone" and "clear this" arrive identical in both
    directions (FIND1). Annotating the contract's own three-state field instead
    does not rescue it: that field is declared once for every carried type, and
    the generated code keeps only the "there is a value" half — it throws when
    asked to write a cleared field, and refuses to read one somebody else wrote
    (FIND2).
  - Everything else the contract carries **crosses whole**: entities compared as
    whole values, text with emoji and line breaks, a moment named to the
    microsecond, a blocker list still carrying its duplicate, and a 5,000-item
    listing at 38.6 milliseconds against the 30-second limit (FIND3).
  - The code that turns values into text and back **can live in `:contract`**,
    provided the build-time helper is applied from the shared build file rather
    than declared by the module. The module's recorded obstacle — the Kotlin
    plugin loaded twice, which the build tool warns "may break the build" — was
    caused by declaring it in the module, and reproduces exactly that way and
    only that way (FIND11).
  - The wait limit and the no-resend rule **hold as the client comes**, with
    nothing added: the limit fired at 30,055 milliseconds, and the core's own
    count of what actually reached it was 1 after a call that ran past the limit,
    after each of the four refusals, and after a fault (FIND6). The four ways a
    call can fail arrive as four distinguishable shapes, and a caller recovers on
    its own once the core is back, without being rebuilt (FIND7).
  - **Nothing needs building to protect a write from a caller that gives up.**
    Across 400 runs on two web-server engines, every write ran to its own end and
    every item landed whole; deliberately shielding the write changed nothing,
    because store work that blocks cannot be interrupted by the caller leaving
    (FIND8).
  - The store's work goes to **threads that are allowed to sit and wait**, kept
    apart from the small pool the web server answers on — the arrangement every
    concurrency result was taken under (FIND9).
  - The core binds to the **loopback address** — the address a machine uses to
    reach itself, which nothing outside it can route to — and a control run
    attributes the refusal to the binding rather than to the network: the one
    address of this machine that demonstrably reaches a core listening everywhere
    is turned away by a core bound to loopback (FIND10).

  It also ruled things out; do not re-investigate them. Letting the library
  generate the partial-update commands cannot work at all (FIND1, FIND2). A
  hand-written conversion that is missing a field compiles perfectly and drops
  that field in silence, with no complaint anywhere (FIND3). And **kotlinx-rpc**
  — JetBrains' library for calling Kotlin functions across a process boundary,
  which would in principle deliver both halves of the connection from one
  interface — is not adopted: every one of its 21 published versions carries a
  number below 1, the convention by which a library says its interfaces are still
  moving, and its release notes describe recent versions as previews that change
  without notice. Epic 01 rejected the Kotlin MCP library on the same ground
  (FIND12).

- **[PRD-1](../prd-1.md)** frames the epic: its requirement REQ5 asks for the
  operation catalog to be exposed once by the core and reached by both front
  doors; its goal GOAL2 wants one contract that holds wherever it could drift,
  which one shared calling library makes structural rather than hopeful on this
  connection; its goal GOAL4 wants no database dependency outside the core.

### Three decisions taken before this plan, because a plan is not where they belong

- **The calling library lives in `:contract`, beside the shapes.** Where a module
  lives is settled by `ARCHITECTURE.md`'s Modules row, which the stack table
  flags as restating settled design rather than as a library pick — so it was
  amended there first, and this plan follows it. `:contract` therefore gains a
  web client, and the core, which depends on `:contract`, carries that client on
  its own classpath without using it. The alternative considered and not taken was
  a fifth module holding the calling library alone.

- **Spec-3 was amended where it said "two adapter processes".** Three of its
  requirements are about two callers acting at the same instant; they now say
  "callers" rather than "processes", because the core cannot tell whether two
  callers sit in one program or two, so keeping them in separate programs is not a
  property the connection has to get right. What genuinely differs between the two
  front doors is how each turns its own protocol into a call, and that is tested
  where each is built. The discovery's follow-up Q6, which existed only because of
  the old wording, is closed on the same reasoning, and its first limitation no
  longer names a gap.

- **The shape this epic defines is also the public one.**
  [01 — Interface contracts](../../../docs/01-interface-contracts.md) settled that
  the web app serves the core's own request and reply shape outward rather than
  inventing a second one: one address, the operation and the project named inside
  the request, the reply naming its own ending, and one numeric status on every
  reply. The web app adds the access gate, HTTPS, and the UI in front of it, not a
  translation. No step below changes because of this — but the bar on naming does:
  a field named here is a field the UI and every later web caller is written
  against, so it is not a private detail to be tidied afterwards.

### Constraints that bound the change

- **The connection applies no rule of its own** (REQ16). Every acceptance and
  every refusal of a request the connection can read is the core's verdict.
  `WriteService` and `ReadService` keep their behavior exactly as epics 03 and 04
  left it; nothing in this epic changes what an operation does.
- **Both halves ship together from one source tree** (ASM2). That is what makes a
  field the operation does not define a defect to surface rather than a
  difference to absorb, and it is why the request reader refuses one.
- **No credential anywhere** (REQ27). The loopback binding is the whole of the
  protection, which is why this plan fixes the host in code rather than taking it
  from a setting — see Approach.
- **Neither front door is touched.** `:mcp-server` and `:web-app` keep their
  empty programs; their protocol surfaces, and the startup that will build a
  calling library from a setting, arrive with epics 06 and 07.
- **No actor fields, no documents, no paging or search or sort options** —
  deferred to epic 08 and to milestone 2, and by the design documents themselves.
- **The database schema is not touched.** No changelog file, no `Tables.kt`
  change; this epic adds a way to reach the store, not a change to it.

## Approach

Build the shapes first, then the two halves of the connection over them, then
prove that crossing it changes nothing. The order below is risk-first: the two
things that could invalidate the whole approach — that the build tolerates the
text-conversion helper at all, and that the three states of a partial update
survive — come before anything is built on top of them.

**One interface names the eleven operations, and two things implement it.**
`io.nook.contract.OperationCatalog` declares all eleven under the names spec-3
gives them, taking the same commands and filter and returning the same entities.
`:core-service` gets `CoreCatalog`, which holds a `WriteService` and a
`ReadService` and delegates. `:contract` gets `CatalogClient`, the calling
library, which sends a request and reads the reply. The two services keep their
own surfaces and their own surface tests untouched — the interface is a third
thing, not a change to either.

This is what makes the epic's hardest requirement testable without putting a
single test hook into production code. "The same operation called inside the
core's own process reaches the same verdict" (REQ4, AC2) becomes: run epic 03's
and epic 04's behavior suites twice, once against `CoreCatalog` and once against
`CatalogClient`. "A fault deliberately planted inside the core" (AC11) becomes: a
server started over an implementation that throws. "A core made to hold a call
without answering" (AC14, AC17) becomes: one that sleeps. "The core received that
request exactly once" (AC15) becomes: one that counts. Every misbehavior the spec
requires the connection to survive is a few lines in a test source, and the
production path never learns it can misbehave.

**One address, with the operation named inside; the reply names its own ending.**
A request is `{operation, project?, payload}` — the operation by name, the
project for the seven operations that act inside one, absent for the four that
act on the whole instance, and a payload shaped by the named operation. Reading
it is two steps: read the operation, then decode the payload with that
operation's own converter — which is exactly what turns an operation nobody
defined into a refusal instead of a silence. A reply says which of three endings
it is and carries the matching value: an answer with its entity or list or
nothing, a refusal with the core's own code and message and details, or a fault.
Every reply the core produces comes back under the same numeric status, because
the ending is in the reply and a number that has to serve two purposes decides
neither (FIND4).

The library turns the third ending, and every failure of the connection itself,
into a breakdown — and the two are distinguishable, because the spec requires a
caller to tell "the core is broken" from "I could not reach the core" (REQ15).

**Reading and running sit inside one attempt.** The handler's failure mapping
wraps the decoding as well as the call, so contents that cannot be read come back
as a refusal rather than as the web server's default (FIND5). This is the one
place in the epic where nothing looks wrong when it is wrong, so it gets a test of
its own.

**Every field of a partial update is decided by whether the request mentions it.**
`UpdateItem` and `UpdateRelease` get conversions written by hand: a field the
request never names becomes "leave alone", a field named with a value becomes
"set to that", and a field named with nothing becomes "set to nothing". The
contract's own three-state field is left exactly as the write path already uses
it. Everything else — the entities, the three vocabularies, the listing filter —
is annotated so the library writes its conversion, with two exceptions the
library does not know: a moment in time and a calendar date, both written by hand
(FIND3). The vocabularies cross as the labels they already carry (`task`,
`in_progress`), not as the names their members happen to have in Kotlin.

**The store's work goes to threads that may sit and wait**, one call inside the
handler, keeping it off the small pool the web server answers on (FIND9).

**The core binds to loopback in code, and takes only its port from a setting.**
Spec-3 asks for the address to be settable from outside (REQ28) and for the
loopback binding to be the entire protection against a caller elsewhere (REQ26,
REQ27). Those pull against each other: a host taken from a setting means one
typo removes the whole protection. So the port is the settable part and the host
is not — which honors what REQ28 is for (no port hard-coded, no default nobody
chose, REQ29) while leaving REQ26 structural rather than a deployment
convention. A caller takes the core's whole address from a setting, since it may
legitimately be told to call anywhere.

**The wait limit is a value the library is built with, defaulting to 30 seconds.**
The spec fixes the limit at 30 seconds (REQ18), and a fixed one would make the
criterion about a caller that gives up while the core is writing (AC16)
untestable — there is no way to make a real `create_item` take half a minute.
Built with a value, the same code proves both: 30 seconds against a core that
never answers, and a few milliseconds against a core doing ordinary work.

Why this way rather than the obvious alternative: the obvious alternative is one
address per operation, which is what a web framework's routing invites, and it
costs the unknown-operation case outright while gaining nothing (FIND4). The
other genuine alternative — kotlinx-rpc, which would generate both halves from
one interface — is ruled out on maturity, on the precedent epic 01 set (FIND12);
what it would have saved is small, since the calling library is one class over a
web client and this epic builds both halves anyway.

**Unverified assumptions, named, and made the first steps below.** The
discovery's finding that the text-conversion helper can be applied from the
shared build file was established on a *reproduction* of this repository's
structure, not on this repository (FIND11) — so applying it here for real is
STEP1, before anything depends on it. And `:contract` is compiled with every
warning treated as a failure and every public name declared explicitly; the
generated conversion code has never been compiled under those settings in this
build.

**Blast radius — what this change touches:** `build-logic` (the shared Kotlin
build file gains the text-conversion helper, and `build-logic`'s own build file
puts it on the classpath), `gradle/libs.versions.toml` (the engine that listens,
the client, the piece joining either to a text format), `:contract` (the operation
interface, the request and reply shapes, the conversions, the calling library, its
build file, and the comment recording why it had none), `:core-service` (a new
package holding the in-process implementation and the answering side, a real
`Main.kt`, and its build file), and both modules' tests. Epic 03's and epic 04's
behavior suites are restructured so each runs twice — their assertions are not
edited.

**What it must leave untouched:** `WriteService` and `ReadService` and everything
in `io.nook.core.store` and `io.nook.core.db`; `db/changelog/` and `Tables.kt`;
the five command classes, three entity classes, three vocabularies, error types,
three-state field, and listing filter already in `:contract` — annotated, never
reshaped; `:mcp-server` and `:web-app`; epic 03's and epic 04's specs; and the
surface tests asserting the two services offer exactly seven and exactly four
operations.

## Steps

- [ ] **STEP1** — Move the text-conversion helper onto the shared Kotlin build
  file (`build-logic/src/main/kotlin/nook.kotlin-jvm.gradle.kts`), adding its
  artifact to `build-logic/build.gradle.kts`; annotate one entity in `:contract`
  and delete the build-file comment that records why the module carried none,
  replacing it with what is true now; verify: `./gradlew build --warning-mode all`
  is green and its output contains no line about the Kotlin plugin being loaded
  multiple times — the obstacle the module recorded, checked rather than trusted
  (FIND11).

- [ ] **STEP2** — Write the conversions for `UpdateItem` and `UpdateRelease` by
  hand, deciding each field by whether the request mentions it, and leave
  `FieldChange` itself untouched; verify: a test writes out and reads back every
  state of every field — nothing at all, a name alone, a description cleared, a
  description set, a release cleared, a blocker list emptied, a blocker list still
  carrying its duplicate, and every field at once — and requires each to come back
  equal to what went in, with "leave alone" and "set to nothing" never equal
  (REQ7, EDGE2, EDGE3, EDGE4).

- [ ] **STEP3** — Annotate the entities, the three vocabularies, and the listing
  filter, writing by hand only the two conversions the library does not know: a
  moment in time and a calendar date; make the vocabularies cross as their labels;
  verify: a test takes a project, an item named with emoji and non-Latin script,
  an item carrying two blockers, and a release with a target date straight from
  the core, writes each out, reads it back, and compares **the whole entity**
  rather than a named subset — so a field added later is covered without the test
  being edited — and separately checks that a moment names the same instant
  afterwards, that an absent field stays absent, and that each of the filter's
  five parts survives, including one supplied with no values (REQ8, REQ9, EDGE1,
  EDGE5, AC8's first half). The whole-entity comparison is the guard against the
  one mistake nothing else catches: a hand-written conversion missing a field
  compiles and drops it in silence (FIND3).

- [ ] **STEP4** — Declare `io.nook.contract.OperationCatalog` naming all eleven
  operations, and implement it in `:core-service` as `CoreCatalog` over the
  existing `WriteService` and `ReadService`; verify: a test reads the operations
  off the interface itself and requires exactly the eleven of REQ1 and no
  twelfth, the seven project-scoped ones each taking a project and the four
  instance-level ones offering no place to name one (AC1's first part, AC3), and
  `./gradlew check` stays green with both service surface tests untouched.

- [ ] **STEP5** — Add the request and reply shapes: a request naming its
  operation, carrying a project for the seven that need one, and a payload
  decoded by the named operation; a reply naming its own ending and carrying an
  answer, a refusal, or a fault; verify: a test requires a request carrying a
  field its operation does not define to be rejected rather than ignored, and each
  of the three endings to survive being written out and read back (EDGE14).

- [ ] **STEP6** — Build the answering side in `:core-service`: one address, the
  request read **inside** the attempt that runs it, the store's work handed to
  threads that may sit and wait, the core's refusals passed through with their own
  code and message and details, and anything else reported as a fault; verify:
  named tests drive each of the four refusals and require the core's own code,
  message, and details to arrive unchanged; a planted fault to arrive as a fault
  carrying none of the four codes; and an unknown operation, contents that cannot
  be read at all, a project-scoped call naming no project, an instance-level call
  naming one, and an unknown field each to be refused as a failed validation with
  nothing reaching the store and the next good call answered normally (AC10,
  AC11, AC13, EDGE11–EDGE14).

- [ ] **STEP7** — Build the calling library in `:contract`: the eleven operations
  under the same names, one web client reused across calls, the wait limit taken
  at construction and defaulting to 30 seconds, nothing installed that resends,
  and a breakdown that says whether the core broke or could not be reached;
  verify: named tests require the library's public surface to be exactly the
  eleven (AC1); a call to a core that is not running to fail as a breakdown, the
  same library to succeed once the core is started, to break when the core is
  stopped mid-call, and to succeed again once it is restarted, never rebuilt
  (AC12); a call the core holds without answering to fail between 30 and 31
  seconds (AC14); the core's own count of what reached it to be exactly one after
  a dropped connection, after the limit, and after each of the four refusals
  (AC15); and a fast call made while a slow one is in flight to be answered first
  (AC17).

- [ ] **STEP8** — Restructure epic 03's and epic 04's operation-behavior suites so
  each runs twice, once against `CoreCatalog` and once against a `CatalogClient`
  pointed at a server started in the test — the assertions untouched, only how the
  suite reaches the operations changing; verify: every one of them green under
  both, which is REQ4 executed (AC2), and which also delivers the two callers
  acting at the same instant: the two same-name creators, the two halves of a
  dependency loop, and the listing taken during a write, each already written and
  each now run across the connection (AC18, AC19). The suites that examine the
  services' own internals rather than the operations — the slug and cycle rules,
  reference resolution, the locking, the transaction discipline, the schema
  guards, and the two service surface tests — stay in-process only, and this step
  names them so the split is deliberate rather than accidental.

- [ ] **STEP9** — Add the checks that exist only across the connection: that the
  values the core's own operation is invoked with equal, one for one, what was
  handed to the library — a name with emoji, a description with line breaks and
  quotation marks, a blocker list holding the same reference twice, and a
  reference that is nearly but not quite a well-formed identifier (AC4, REQ6,
  EDGE7); that both deletes report success, return no entity, and stay
  distinguishable from a refusal (AC9); that a 5,000-item listing arrives whole,
  in the core's order, inside the limit (AC8's second half, EDGE6); and that a
  caller giving up while the core writes an item and its blocker edges leaves,
  in all 100 runs, either the item whole with its edges or no item at all (AC16,
  EDGE10). Nothing is built for that last one — it is a test of the transaction
  the write path already opens (FIND8).

- [ ] **STEP10** — Give `:core-service` a real `Main.kt`: the port and the
  database setting taken from outside the program, the host fixed to loopback, and
  a start refused with a message naming the missing setting rather than a default
  nobody chose; add, in test sources, a small program that builds the calling
  library from a setting and makes one call; verify: launching each program with
  its setting unset ends it with a message naming that setting, launching both
  with settings set passes a call end to end (AC21, EDGE20), and a core bound to
  loopback refuses a call on every other address this machine has while answering
  on loopback (AC20, EDGE18, FIND10).

- [ ] **STEP11** — Close the epic: confirm the database boundary check still
  passes for `:contract` now that it carries a web client, and that neither front
  door nor the calling library resolves a database dependency (AC22, REQ30); run
  `./gradlew check` on a clean checkout; push for the continuous-integration run;
  verify: green locally and in that run, with the new tests visibly executed.

## Caveats & rabbit holes

- **no-go: one address per operation** — it is what a web framework's routing
  invites, and it turns a request naming an operation nobody defined into the web
  server's own empty "no such address" answer, which is a breakdown where the spec
  requires a refusal (FIND4, EDGE11); instead: one address, the operation named
  inside the request, decoded in two steps.

- **no-go: letting the numeric status carry which of the three endings a reply
  is** — an item that is not there and an address that is not there both answer
  404, so the number cannot decide the thing it was chosen to carry (FIND4);
  instead: the reply names its own ending, and each front door maps that onto its
  own protocol in its own epic.

- **caveat: reading the request outside the attempt that runs it** — the
  arrangement a handler falls into by default, and nothing about the code looks
  wrong when it is wrong: contents that cannot be read come back as a fault where
  a refusal was required (FIND5, EDGE12); instead: the failure mapping wraps the
  decoding too, with a test of its own that sends unreadable contents.

- **no-go: letting the library generate the conversions for the two partial-update
  commands** — it has one slot per field and cannot record that a field was
  mentioned, so "leave this alone" and "clear this" become the same request in
  both directions (FIND1); instead: written by hand, decided by whether the
  request names the field.

- **no-go: annotating the contract's own three-state field so the library
  generates it after all** — it compiles, and then throws when asked to write a
  cleared field and refuses to read one somebody else wrote, because that field is
  declared once for every carried type and the generated code keeps only the half
  that has a value (FIND2); instead: leave `FieldChange` exactly as the write path
  uses it, and reshaping it to suit the generator is not on the table — it is a
  larger change for a worse result.

- **caveat: a hand-written conversion missing a field compiles and drops it in
  silence** — nothing is wrong with the code; it simply says less than it used to,
  and no compiler, no test naming its own fields, and no warning notices (FIND3);
  instead: compare whole entities, never a named subset, so a field added later is
  covered without the check being edited.

- **no-go: declaring the text-conversion helper in `:contract`'s own build file** —
  that is what gave the module a build set-up of its own and loaded the Kotlin
  plugin twice, which the build tool warns may break the build; it reproduces
  exactly that way and only that way (FIND11); instead: the shared build file
  carries it and the module declares neither half.

- **no-go: adopting kotlinx-rpc to generate both halves from one interface** —
  every one of its 21 published versions numbers below 1, the convention by which
  a library says its interfaces are still moving, and epic 01 rejected the Kotlin
  MCP library on that same ground (FIND12); instead: build the two halves, which
  is one class over a web client on the calling side.

- **no-go: building anything to protect a write from a caller that gives up** —
  400 runs say the write finishes on its own and lands whole, and deliberately
  shielding it changed nothing, because store work that blocks cannot be
  interrupted by the caller leaving (FIND8); instead: what guarantees it is the
  transaction the write path already opens, and the corresponding criterion is a
  test to write, not a mechanism to build.

- **no-go: installing anything that resends a call** — not after the limit, not
  after a dropped connection, not after a refusal; a write cannot be repeated
  safely and no rule that repeats only some calls is worth the risk of getting the
  set wrong (REQ19); instead: the client as it comes installs no such thing, and
  the core's own count of what reached it is what proves nothing anywhere else
  does either (FIND6).

- **no-go: taking the host the core binds to from a setting** — the loopback
  binding is the entire protection against a caller elsewhere, so a host from a
  setting means one typo removes all of it (REQ26, REQ27); instead: the port is
  settable, the host is not, and the plan says so out loud because it reads
  against a literal reading of REQ28.

- **no-go: touching what an operation does** — the connection applies no rule of
  its own, and every acceptance and refusal of a readable request is the core's
  verdict (REQ16); instead: if an operation looks wrong from across the
  connection, it is wrong in-process too and belongs to epic 03's or epic 04's
  contract, not to this one.

- **no-go: giving either front door anything** — the temptation is a startup, or a
  first route, "since the library is right there"; instead: leave both empty
  programs, and let epics 06 and 07 add a startup that builds the library from a
  setting.

- **rabbit-hole: rewriting epic 03's and epic 04's assertions while restructuring
  them to run twice** — the change is how a suite reaches the operations, and any
  edit to what it asserts hides whether the connection or the rewrite broke
  something; instead: change the reaching, leave every assertion character for
  character, and expect the in-process run to stay green throughout.

- **caveat: the call "from another machine" is made from this machine** — the
  check proves that a core bound to loopback turns away a caller on this machine's
  other addresses, which is what the binding does; a genuinely remote caller is
  untested, and a network arrangement that forwards traffic onto loopback would
  defeat it (the discovery records the same limitation); instead: run the check
  against every non-loopback address this machine has, and where a machine has
  none, say so in the test's own message rather than passing silently.

- **caveat: seeding 5,000 items through the write path may not fit the two-minute
  per-test bound** — each create opens its own transaction and takes a lock;
  instead: seed those rows directly, since the criterion is about the listing
  crossing whole and in order, not about how the items got there — and keep every
  other test going through the operations.

- **caveat: the wait-limit check takes over thirty seconds of wall clock** — it is
  the one test that must, since the criterion is the window itself (AC14);
  instead: leave it in the ordinary suite rather than hiding it behind a flag, and
  remember the per-test bound is two minutes.

- **caveat: `:contract` now carries a web client, and the core inherits it** — the
  core is the server; it will hold a client of itself on its own classpath and
  never use it; instead: accept it as the cost of the module decision recorded in
  `ARCHITECTURE.md`, and do not "tidy" it by moving the library later without
  amending that document first.

- **rabbit-hole: measuring the connection under load, or finding the project size
  at which a listing stops arriving in time** — the discovery's Q7 and Q8, both
  waiting on a real workload worth imitating rather than an invented one;
  instead: leave both open on the discovery — this epic proves the connection
  correct, not cheap.

## Test plan

- **TEST1** — build: `./gradlew build --warning-mode all` green with the
  text-conversion helper applied from the shared build file, and no line anywhere
  in its output about the Kotlin plugin being loaded multiple times.

- **TEST2** — unit: every state of every field of both partial-update commands
  survives being written out and read back, and "leave alone" never equals "set to
  nothing" in either direction.

- **TEST3** — unit: entities compared as whole values after crossing — a project,
  an item named with emoji and non-Latin script, an item with two blockers, a
  release with a target date — plus a moment naming the same instant afterwards,
  an absent field staying absent, a blocker list keeping its duplicate, and each
  of the filter's five parts including one supplied with no values.

- **TEST4** — unit: the operation interface names exactly the eleven of REQ1 and
  no twelfth; the seven project-scoped ones take a project and the four
  instance-level ones offer no place for one; the calling library's public surface
  is the same eleven under the same names.

- **TEST5** — integration: each of the four refusals arrives carrying the core's
  own code, message, and details; a planted fault arrives as a fault carrying none
  of those codes; and an unknown operation, unreadable contents, a project-scoped
  call naming no project, an instance-level call naming one, and an unknown field
  are each refused as a failed validation with nothing reaching the store and the
  next good call answered normally.

- **TEST6** — integration: a core that is not running, then started, then stopped
  mid-call, then restarted, against one calling library never rebuilt — a
  breakdown, a success, a breakdown, a success, with every breakdown
  distinguishable from a fault inside the core.

- **TEST7** — integration: a call the core holds without answering fails between
  30 and 31 seconds; the core's own count of what reached it is exactly one after
  the limit, after a dropped connection, and after each of the four refusals; and
  a fast call made while a slow one is in flight is answered first.

- **TEST8** — integration: every acceptance criterion of
  [spec-1](../03-core-write-path/spec-1.md) and
  [spec-2](../04-structure-queries/spec-2.md) reaches the same verdict driven
  across the connection as driven in the core's own process — the same suites,
  run twice, assertions unchanged. The two same-name creators, the two halves of a
  dependency loop, and the listing taken during a write are among them, at 100
  repetitions each, which is where the several-callers criteria are met.

- **TEST9** — integration: the values the core's operation is invoked with equal,
  one for one, what was handed to the calling library; both deletes report success
  and return no entity while staying distinguishable from a refusal; a 5,000-item
  listing arrives whole, in order, inside the limit; and across 100 runs a caller
  that gives up while the core writes an item and its blocker edges leaves either
  the item whole with its edges or no item at all.

- **TEST10** — integration: each program started with its setting unset ends with
  a message naming that setting; started with settings set, a call passes end to
  end; and a core bound to loopback answers there and refuses on every other
  address this machine has.

- **TEST11** — build: `./gradlew check` green on a clean checkout, with the
  database boundary check still passing for `:contract`, `:mcp-server`, and
  `:web-app` now that `:contract` carries a web client, and the driver still
  absent from the core's compile classpath.

- **Standing check, comment hygiene** — search the final diff for artifact tokens
  (STEP, REQ, GOAL, FIND, AC, EDGE, PRD, epic) and `.md` paths in code and code
  comments; expect zero hits — those citations belong in documents like this one,
  never in code.

- **Standing check, conformance sweep** — re-read this plan against the final
  diff: every step ticked with its verification observed, the blast radius
  respected — nothing changed in `WriteService`, `ReadService`, the store or
  database packages, the changelog, or either front door — every caveat honored,
  and any mid-build divergence already folded back into this text.

- Run both standing checks through a separate agent handed only this plan and the
  final diff, none of the builder's conversation — the builder reads its own
  intent into the diff, while a fresh reader sees only what is there.

Done when: a clean checkout runs `./gradlew check` green locally and in the
continuous-integration run; all 22 acceptance criteria of spec-3 pass as named
tests; every acceptance criterion of specs 1 and 2 passes both in-process and
across the connection; the connection offers exactly the eleven operations and
the calling library offers the same eleven under the same names; no call anywhere
is sent twice; `:contract`, `:mcp-server`, and `:web-app` still resolve no
database dependency; and both front doors are exactly as this epic found them.
