# MCP server — Plan

A note on references. This plan leans on the two documents beside it.
[Spec-4](./spec-4.md) numbers what it pins — REQ for a requirement, EDGE for an
edge case, AC for an acceptance criterion. [The discovery](./discovery.md)
numbers its findings FIND and its questions Q. Where this plan cites one of
those codes it says the point in plain words alongside, so the pointer is a
cross-check and never required reading. None of those codes belongs in code or
in a code comment.

Some words that recur. **The protocol** is the Model Context Protocol, the
shared convention by which a coding agent discovers what another program can do
for it and calls those things by name. **The protocol library** is the official
Java implementation of it, which this module uses rather than writes. **A
servlet** is a request handler that a **web container** — a program that
receives requests off the network — runs on your behalf; the protocol library
ships its long-lived-HTTP transport as one, and Jetty is the web container that
will run it. **The core** is the program that owns the database, built by epics
03 and 04. **The calling library** is the one piece of code, shared by both
adapters, that calls the core and reads its replies, built by epic 05. **A
shape** is one of the plain data classes in `:contract` that a call carries, and
**a shape's description of itself** is the runtime information the serialization
library keeps about it — its field names, which of them may be left out, and
whether each is text or a list.

## Analysis

### What is there now, read from the repository rather than remembered

- **`:mcp-server` is an empty program.** `mcp-server/src/main/kotlin/io/nook/mcp/Main.kt`
  is a `main` whose body does nothing. Its build file depends on `:contract`, on
  `ktor-server-core` — the part of Ktor that describes a server, left over from
  the skeleton and not the part that listens on a port — and on the protocol
  library, pinned at 2.0.0 in `gradle/libs.versions.toml` and referenced from
  nowhere else in the repository.

- **The calling library is finished and public.**
  `contract/src/main/kotlin/io/nook/contract/CatalogClient.kt` offers all eleven
  operations, holds one web client for the life of the program, gives up after
  thirty seconds, and never sends a call twice. A refusal from the core arrives
  as a thrown `StructuredErrorException` carrying the core's own code, message
  and details; anything that produced no verdict at all arrives as a thrown
  `BreakdownException` saying whether the core broke or could not be reached.
  This module builds one of these and calls it. It adds nothing to it.

- **The eleven operations are already stated once, in `:contract`.**
  `CatalogProtocol.kt` holds a table — one entry per operation — where each
  entry names the operation, names the shape its payload takes, names the shape
  its answer takes, and says how to invoke it on any catalog. Whether an
  operation acts inside a project is the shape of its entry rather than a flag
  on it: an entry for a project-scoped operation cannot be invoked without a
  project, and an instance-level one has nowhere to put one. The table is
  `internal` to the module today. **The seven entries this server offers as
  tools are exactly the seven project-scoped ones**, which is where REQ1's list
  of seven and REQ2's "no eighth tool, and none that names a project" come from
  without either being written down a second time.

- **Two of the seven shapes describe nothing about themselves.** Five payload
  shapes are ordinary data classes whose conversion the serialization library
  writes, so at runtime each can be asked for its field names and which of them
  may be left out. The two partial updates cannot: their conversions are written
  by hand, for the reason epic 05 recorded — a written-for-you conversion has one
  slot per field and no way to record that a field was mentioned at all, which
  makes "leave the description alone" and "clear the description" the same
  request in both directions. Those hand-written conversions read the incoming
  JSON document directly and never use the field machinery, so nobody had reason
  to declare their fields:

  ```kotlin
  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("io.nook.contract.ItemUpdate")
  ```

  That names zero fields. The real list lives beside it as `defined`, a private
  set of strings used to refuse a field no operation defines. So today, for
  `update_item` and `update_release`, there is nothing a tool declaration could
  be built from.

- **Nothing anywhere says what a field means.** The prose that would tell an
  agent what `parentRef` is for exists only as a documentation comment above the
  class, which is gone by the time the program runs.

- **The core's refusals never say what was missing.**
  `contract/src/main/kotlin/io/nook/contract/Errors.kt` gives every refusal a
  code, a message, and a details map, and epic 05's tests confirm the details
  cross unchanged — but no operation fills them in. A missing project and a
  missing item are both `not_found` with different prose:
  `core-service/src/main/kotlin/io/nook/core/store/References.kt` raises "no
  project matches reference …", "no item in the project matches reference …" and
  "no release in the project matches reference …", and
  `write/WriteTransactions.kt` raises the project one again on the write path.
  Everywhere the existing tests check a `not_found` they check the code alone,
  through a helper named `assertFailsWithCode`, so nothing asserts that details
  stay empty.

- **The build rules that bound this module.** `nook.persistence-boundary` fails
  the build if a database library appears anywhere in `:mcp-server`'s compile or
  runtime graph, in **every** source set including tests. `nook.kotlin-jvm`
  makes every compiler warning a failure, bounds each test at two minutes and
  each test task at twenty. `:contract` declares every public name explicitly,
  so anything this module is meant to read has to be made public on purpose.
  Continuous integration runs one `./gradlew check` on Ubuntu.

- **Prior art for driving a program from a test.**
  `core-service/src/test/…/catalog/CoreProgramTest.kt` launches real processes
  with a chosen set of environment settings, reads what they print, and checks
  that a program with a setting missing stops and names it. The same file
  establishes how to show that a server bound to the loopback address — the
  address a machine uses to reach itself, which nothing outside it can route to
  — refuses callers elsewhere: find the addresses of this machine that reach a
  server listening on all of them, then show the same addresses refused by a
  server bound to loopback. This epic reuses both shapes.

### The framing documents, linked rather than restated

- **[Spec-4](./spec-4.md)** is the requirements contract: 47 requirements, 27
  edge cases, 26 acceptance criteria. It pins which tools exist and which
  deliberately do not, how a connection says which project it is for, what an
  agent may put in a call and what must reach the core untouched, the three ways
  a call can end, several agents at once, and where the server listens. Its
  load-bearing decision is that **projects are not on this surface**: an agent
  cannot create, read, list or delete one, so the seven operations that act
  inside a project are the whole tool set.

- **[The discovery](./discovery.md)** settled how the server gets built, by
  running the protocol library against two clients — one of them the protocol's
  own Inspector, an independent program that knows nothing of Nook. Adopted here
  without re-investigation:
  - Jetty hosts the library's transport, and Ktor sits beside it in the same
    program with nothing to reconcile — so this module needs no Ktor server at
    all and its `ktor-server-core` dependency goes (FIND1).
  - One transport instance answers at exactly one address, because the library
    compares each request's address against the single endpoint it was given and
    turns away anything else. A family of addresses therefore means a family of
    servers (FIND2).
  - A server per project is the only arrangement in which what a connection is
    told on opening is its own project's, and the only one in which an address
    naming no project is refused rather than served. The one-server-for-everyone
    alternative fails both and buys nothing (FIND3).
  - What a connection is told rides in the opening exchange's `instructions`
    field, and both clients read it back (FIND4).
  - Refusing to open the connection makes an ordinary client report Nook as
    unavailable and repeat the project it asked for — provided the refusal is
    written straight onto the response. Handing it to the web container's own
    error page turns the words a person wrote into an escaped HTML page
    (FIND5).
  - Closing a project's server ends the connections held against it, and the
    client's next call fails (FIND6).
  - The endings arrive distinguishable: an answer, a refusal carrying Nook's own
    code, and an empty success for a deletion are all results, while a fault
    inside the core and a call naming a tool that does not exist are
    protocol-level errors (FIND7).
  - Everything an agent sends reaches the code behind the tool unchanged,
    including the three states of a partial update, a blocker list still
    carrying its duplicate, emoji and line breaks, and a five-thousand-item
    listing in about a tenth of a second (FIND8).
  - Several connections are served at once and a slow call blocks nothing, even
    on the same project (FIND9).
  - The loopback binding is what turns an outside caller away, shown against a
    control run; and the library's own protection against a web page in a
    browser calling a local server is off by default (FIND10).
  - The library checks a call's arguments against the tool's declared arguments
    before Nook's code runs, and turning that off checks nothing whatsoever —
    a call with no name created an item with no name (FIND11).
  - The library advertises a logging channel for every server it builds,
    whatever it is told to advertise (FIND12); and an unmodified client needs
    nothing done for Nook's sake (FIND13).

  Two requirements were amended in spec-4 rather than implemented, each carrying
  its evidence: the server may advertise the library's logging channel provided
  nothing of Nook's is reachable through it, and a call the server cannot read
  must name what was wrong rather than carry a code of Nook's.

- **[PRD-1](../prd-1.md)** frames the epic: its requirement REQ6 asks for this
  surface and already records that projects are not on it; its north-star goal
  is the loop run over this surface; its one-contract goal counts the seven
  project-scoped operations reaching the same verdict called as a tool as they
  do called inside the core.

### Three decisions taken before this plan, because a plan is not where they belong

- **Everything an agent is told about a tool comes from `:contract`.** The
  contract module is the one place a data shape is defined, so it is also where
  the words describing that shape live. It gains a description annotation that
  survives into the runtime information about a shape, the two empty
  descriptions of the update shapes get filled in, and each operation's own
  one-line description sits on its entry in the table that already states
  everything else about it. `:mcp-server` reads all of that and defines nothing
  of its own — no second list of arguments, no second set of words, nothing to
  drift.

- **A refusal says what was missing.** REQ19 requires a connection to stop being
  served once its project is gone, which means telling "your project is
  disappeared" apart from "that item is not there". Those arrive identically
  today. The server could ask the core about its own project after every
  `not_found`, but that spends a call on an ordinary and frequent refusal and
  leaves the question answered by prose-matching elsewhere; instead the core's
  four "not found" sites name what they could not find, in the details field
  that already exists and that epic 05 already proved crosses unchanged. That is
  a change inside epics 03 and 04's code, taken deliberately and named here
  because this plan otherwise leaves that code alone — and the web app faces the
  same question in epic 07.

- **This epic's tests start no other program and need no database.** The
  server is driven against a stand-in core: an object offering the eleven
  operations, returning what a test tells it to return, refusing what a test
  tells it to refuse, and breaking where a test wants a fault. That covers
  everything spec-4 asks for except its last two criteria — the milestone loop
  run over this surface, and epics 03 and 04's own criteria re-run through the
  tools — both of which need the real core with a real store behind it. **Those
  two move to a new epic 09, full system test**, at the end of milestone 1,
  where every piece exists and one integration target can start them all. Until
  it runs, the discovery's own stated limitation stands: what these tools reach
  is a stand-in, not the core.

### Constraints that bound the change

- **No database anywhere in this module**, in any source set. The build fails
  otherwise, and that is the point.
- **The server applies no rule of its own** to a call it can read (REQ28). Every
  acceptance and every refusal of such a call is the core's verdict. This is
  what forbids declaring the vocabularies as a fixed list of allowed values in a
  tool's arguments — see the traps below.
- **No credential is asked for and none is checked** (REQ45). Binding to
  loopback is the whole of the protection (REQ44), which is why this program
  fixes that address in code exactly as the core does, and takes only its port
  from a setting.
- **The seven operations' behavior is not this epic's** (spec-1, spec-2, ASM1):
  if an operation looks wrong through a tool, it is wrong in the core too.
- **No actor fields, no documents, no resources, no tenets, no version stamp** —
  deferred to epic 08 and to later milestones by the design documents.
- **The database schema is not touched**, and neither is how requests and
  replies turn into text: the annotation added to `:contract` changes what a
  shape can say about itself, never what crosses.

## Approach

Build from the inside out, riskiest first: make `:contract` able to describe its
own shapes, prove the protocol library accepts a tool built from that
description, then the address and the connection, then the seven tools and the
endings, then the program.

**The seven tools are the seven project-scoped entries in the contract's own
table.** `:contract` gains a small public view of that table: for each of the
seven, its name, its own one-line description, the description of the payload it
takes, and a way to run it against any catalog. `:mcp-server` walks that view
once at startup, turns each entry into a tool declaration, and hands each tool
call straight to the entry, which decodes the arguments with the operation's own
conversion and invokes it on the calling library. Adding a twelfth operation to
the contract cannot leave this server behind, and this server cannot offer an
eighth tool, because there is nothing here that lists tools.

**A tool's arguments are its payload's fields, and its descriptions come from an
annotation.** The serialization library lets an annotation survive into the
runtime information about a shape; `:contract` declares one, puts it on every
payload field of the seven, and puts a one-line description on each operation.
The two hand-written conversions declare their fields properly at last — which a
hand-written conversion is meant to do anyway — and their private list of
allowed field names is then read off that declaration rather than kept beside it
as a second copy. A field that may be cleared is declared as one that accepts
nothing, which is exactly the distinction its conversion already makes, so the
declaration and the conversion say the same thing by construction.

**The vocabularies travel as prose, never as a fixed list of allowed values.**
An item's type and the two status vocabularies are text as far as the shapes are
concerned; the core validates them and, when it refuses, spells out the words
the caller could have written. If a tool declared them as a closed list instead,
the protocol library would turn back a bad status before Nook's code ever ran,
and the agent would get the library's wording rather than the core's verdict —
which REQ28 forbids and which would make the same call reach two different
verdicts depending on the adapter it came in by. So the allowed words go in the
argument's description, where an agent reads them, and the verdict stays the
core's.

**One protocol server per project, made when that project's first connection
opens, behind a dispatcher that reads the project out of the address.** The
dispatcher takes what follows `/mcp/`, undoes whatever escaping
the address form required, and asks the core about that project once. If the
core says it exists, the dispatcher builds that project's server — telling it
the project's id, its handle, its name and its description, which is what every
connection to it is told on opening — and hands the request on. If the core says
it does not exist, or if the address named no project at all, the refusal is
written straight onto the response, naming the project that was asked for, so
the person who mistyped it reads back the words they wrote. If the core cannot
be reached, the answer says that instead, because it is a different problem with
a different fix.

**The server holds the project's id, and closes a project's server when the core
says the project is gone.** The dispatcher resolves the address to an id once
and every call uses it, so a handle later given to a different project cannot
silently redirect an agent's work. When a call is refused as "not found" and the
refusal names the project as the missing thing, that project's server is closed:
the connections held against it end, and their next call fails — which is
REQ19's "stop serving" for every agent in a project that no longer exists.

**The three endings map onto the protocol as the discovery found them.** An
answer becomes a successful result carrying the entity both as readable text and
in structured form; a deletion becomes a successful result carrying nothing; a
refusal from the core becomes a failed result carrying the core's code, message
and details unchanged; a breakdown becomes a failed result that carries none of
the four codes, so nothing tells an agent to fix a request that was never the
problem. Calls the server cannot read are already turned back by the library
before Nook's code runs, which spec-4's amended wording accepts.

**Why this way over the obvious alternative.** The obvious alternative for the
address is one shared protocol server told to accept every address, and the
discovery ran it: it serves every project with the same announcement and serves
an address naming no project at all, failing two requirements together and
buying nothing. The obvious alternative for the tool declarations is to write
them out in this module, next to the code that serves them; that is a second
copy of every argument name and every optional flag, kept in step by discipline,
in the one module whose job is to add nothing.

**Unverified assumptions, named, and made the first steps.** The discovery built
its tools by hand and never derived one from a contract shape, so nothing yet
shows that the protocol library accepts a declaration shaped the way these
shapes describe themselves — in particular a field that accepts either text or
nothing, which is how a clearable field must be declared. That is STEP2, before
six more tools depend on it. And no probe covered a tool whose arguments came
back through the library's own checking with a cleared field in them, which is
the same step's second half.

**Blast radius — what this change touches.** `gradle/libs.versions.toml` (Jetty,
which nothing pins yet); `:mcp-server` entirely (its build file, the dispatcher,
the tool declarations, the ending mapping, the program, and its tests, which do
not exist yet); `:contract` (the description annotation, descriptions on the
seven operations and their payload fields, the two hand-written conversions'
declarations of their own fields, and a small public view of the table that is
internal today); `:core-service` (the four places a "not found" is raised, which
gain the name of what was missing, and the tests that cover them).

Several of those turned out slightly wider in the building, and are recorded
here rather than left as surprises in the diff.

**Every** entry in the table carries a description, not only the seven, and
every payload field of every one of them, not only the seven's: the table is one
list and the payloads sit in one file, and blank entries in either would read as
an oversight rather than as a decision.

The parent filter's conversion now declares itself as accepting nothing as well
as text, which is what it has always read and written — "no epic above it"
crosses as nothing at all — because a declaration saying text alone has anything
derived from it forbid the one value that part of the filter exists for.

Naming what a "not found" could not find needed a place for the three answers to
be written once, so `:contract` gained a small public enum for them and the way
to read one out of a refusal. Both sides need it — the core to say it, this
module to act on it — and neither can hold it.

A project's server is closed as the next request arrives rather than inside
the call that discovered its project was gone. Closing it mid-call would take
away the very response that was about to tell the agent why, so the call records
what it found and the adapter acts on it.

The dispatcher reads the whole of the address after the mount rather than its
first piece. No reference holds a slash, so an address with more in it is one
nobody wrote, and reading only the first piece would serve it as though they
had.

The Done-when clause below asks for nothing about the tools written down in
`:mcp-server`, and one thing is: the tests spell out the seven and their
arguments in full. That is the deliberate other side of the comparison — a check
that read the same declaration the server reads would agree with it whatever it
said, including a field silently lost on the way. Nothing in the shipped code
holds a second copy.

**What it must leave untouched.** What the write and read services do; the
database schema and the changelog; how requests and replies turn into text, and
the three-state field's encoding and decoding — the declarations get filled in,
the conversions do not change; the calling library; `:web-app`; and the fourteen
behavior suites epics 03 to 05 built, which keep their two runs.

## Steps

- [x] **STEP1** — In `:contract`, add the description annotation and make every
  one of the seven shapes describe itself: declare the fields of the two
  hand-written conversions, read their allowed-field lists off those
  declarations instead of the private copies beside them, declare a clearable
  field as one that accepts nothing, and put a description on every payload
  field and on each of the seven operations; verify: the existing round-trip
  tests pass unedited — nothing about what crosses changed — and a new test
  walks all seven and finds every field named, every field carrying a
  description that is not empty, every operation carrying one, and the fields
  each conversion accepts equal to the fields it declares (which is REQ4 and
  REQ6 held at the source, and AC2's comparison made possible for all seven
  rather than five).

- [x] **STEP2** — In `:mcp-server`, swap `ktor-server-core` for Jetty, host the
  protocol library's transport at one address, and turn one operation —
  `update_item`, the hardest — into a tool built from what its shape says about
  itself; verify: the library's own client completes the opening handshake and
  lists that tool with a description on it and on every argument; a call
  clearing the description reaches the stand-in core as "set to nothing" while a
  call not mentioning it arrives as "leave alone" and one setting it arrives as
  "set to that text"; and a call carrying a field the tool does not define is
  turned back with nothing reaching the stand-in. This is the step that can
  invalidate the approach: if the library will not accept a declaration derived
  this way, everything after it changes.

- [x] **STEP3** — Build the dispatcher: the address `/mcp/{projectRef}`, the
  project reference read as plain text with the address form's escaping undone,
  the core asked about it once before any protocol server for it exists, one
  protocol server per project telling every connection its project's id, handle,
  name and description, and refusals written straight onto the response; verify:
  two projects each get their own announcement and a connection at a project's
  handle and one at its id are told the same four values; a mistyped project, an
  address naming no project, and a configuration placeholder nobody filled in
  are each refused in the opening exchange with the words that were asked for
  coming back unescaped; a core that cannot be reached refuses the connection
  saying so and never claiming the project is missing; the stand-in core records
  exactly one project question per connection, not one per call; every tool call
  made on a connection reaches the stand-in naming that connection's project and
  no other; and no tool call reaches the stand-in from any refused connection
  (REQ8 to REQ17, REQ21, REQ22, EDGE1 to EDGE4, AC3, AC4, AC5, AC6, AC7).

- [x] **STEP4** — Declare all seven tools from the contract's view, and map the
  endings: an answer carrying the entity, a deletion carrying nothing, a refusal
  carrying the core's own code and message and details, a breakdown carrying
  none of the four codes; verify: the listing holds exactly the seven names,
  offers no tool naming a project, and offers nothing beyond tools through which
  a Nook operation could be called; each of the four refusal codes arrives as a
  failed call carrying that code, message and details unchanged; a fault planted
  in the stand-in arrives as a breakdown an agent can tell from every refusal;
  `delete_item` succeeds carrying no entity and is still not mistakable for a
  refusal; a call naming a tool the server does not offer, and one naming
  `create_project`, each come back naming the tool that was asked for; each
  tool's declared arguments equal its operation's own arguments one for one with
  the project left out; the stand-in records exactly one request per call after
  every one of those endings, so nothing here sends a call a second time; and
  after every one of them the next call on the same connection is served
  normally (REQ1 to REQ7, REQ29 to REQ36, EDGE7 to EDGE10, EDGE21, EDGE24, AC1,
  AC2, AC14, AC16, AC17, AC18, AC19).

- [x] **STEP5** — In `:core-service`, have the four "not found" sites name what
  they could not find, in the details the refusal already carries; then in
  `:mcp-server`, close a project's server when a refusal names the project as
  the missing thing; verify: in the core's own tests, asking for a missing
  project, a missing item and a missing release each produce a refusal naming
  which of the three was missing, with the existing checks on those refusals
  unedited; and in this module, a stand-in refusing with the project missing
  ends the connection so that its next call is not served, while a stand-in
  refusing with the item missing leaves it serving, and a connection opened
  afterwards at the same address is refused naming the project (REQ18 to REQ20,
  EDGE5, EDGE6, AC8, AC9, AC10).

- [x] **STEP6** — Check what an agent supplies reaches the calling library
  untouched: a name carrying emoji and non-Latin script, a description carrying
  line breaks and quotation marks, a blocker list still holding its duplicate, a
  blocker list supplied empty, a reference that is nearly but not quite a
  well-formed id, an update naming no field at all, each of the listing filter's
  five parts alone and all five together, and a filter part supplied with no
  values; verify: for each, the values the stand-in core is invoked with equal
  the values the agent supplied, one for one, compared as whole values rather
  than named fields — so a field added later is covered without the check being
  edited — and the empty filter part reaches the core rather than being quietly
  turned into "do not filter on this" (REQ23 to REQ28, EDGE11 to EDGE17, AC11,
  AC12, AC13).

- [x] **STEP7** — Give `:mcp-server` a real program: its own address and the
  core's address both taken from outside it, the host fixed to loopback, the
  library's browser-origin checking turned on, and a start refused with a message
  naming the missing setting rather than a default nobody chose; verify: launched
  with either setting unset the program stops and names it; launched with both
  set against a small stand-in core served over HTTP in the test, a client
  connects and calls a tool; that stand-in core stopped, a call on the open
  connection is a breakdown, and started again, the same connection serves a call
  with the agent never reconnecting and the server never restarted; and a server
  bound to loopback refuses every other address this machine has while answering
  on loopback — with the control run first, and a spoken-out-loud skip where a
  machine has no other address (REQ40, REQ44 to REQ47, EDGE19, EDGE20, EDGE26,
  EDGE27, AC20, AC23, AC24).

- [x] **STEP8** — Check the surface holds under several agents and under size:
  three connections, two on one project and one on another, calling at once with
  one call made deliberately slow; a listing of five thousand items; and a call
  arriving before the opening handshake has completed; verify: neither fast call
  waits on the slow one, no connection's call is affected by another
  connection's project, the listing arrives whole and in order inside the wait
  limit, and the early call is not served with nothing reaching the stand-in
  (REQ37 to REQ39, REQ43, EDGE18, EDGE22, EDGE23, EDGE25, AC15, AC21, AC22).

- [x] **STEP9** — Close the epic: run the whole build from a clean checkout,
  confirm the database boundary still passes for `:mcp-server` now that it
  carries Jetty and the protocol library, push for the continuous-integration
  run, and write the epic's results into its README as epics 03 to 05 did —
  what was built, what was decided along the way, and each of spec-4's criteria
  against the named test that executes it; verify: green locally and in that run
  with the new tests visibly executed, and every criterion but the two already
  recorded as epic 09's appearing in that mapping against a test that exists.

## Caveats & rabbit holes

- **no-go: declaring the vocabularies as a closed list of allowed values in a
  tool's arguments** — it looks like a kindness to the agent and it moves the
  verdict from the core to the protocol library, so the same bad status reaches
  two different answers depending on which adapter it came in by, and the agent
  loses the refusal that spells out the four words it could have written (REQ28);
  instead: the allowed words go in the argument's description, and the core
  decides.

- **no-go: one protocol server told to accept every address** — it is what a
  single shared server would require, and the discovery ran it: every connection
  is told the same thing about its project, and an address naming no project at
  all is served (FIND3); instead: one server per project, built when that
  project's first connection opens.

- **no-go: handing a refused connection to the web container's own error page** —
  it escapes the text into HTML and wraps it, so a placeholder nobody filled in
  comes back looking like something nobody wrote, and the one thing that makes a
  wrong address diagnosable is lost (FIND5); instead: write the body straight
  onto the response.

- **no-go: turning off the protocol library's checking of a call's arguments** —
  it does not check less, it checks nothing: a call with no name created an item
  with no name, and an argument the tool does not define was silently dropped
  (FIND11); instead: leave it on, and let it answer three of the four unreadable
  cases before anything reaches the core.

- **no-go: writing the tools' arguments out in this module** — it is the obvious
  shape and it is a second copy of every field name and every optional flag,
  which drifts the first time a command gains a field; instead: read them from
  the contract, which is where the shape is defined.

- **no-go: giving this server a rule of its own about what a call may contain** —
  a check here that the core also makes is a check that will disagree with the
  core one day, and a check the core does not make is a rule nobody agreed
  (REQ28); instead: everything readable goes to the core and comes back with the
  core's verdict.

- **caveat: `TargetRef` is shared by four operations** — `get_item` and
  `delete_item` both take it, so its field's description has to read correctly
  for both; instead: describe it as the thing the call is about, by its id or
  its handle, and put what each operation *does* in that operation's own
  description, which is per-operation.

- **caveat: filling in a hand-written conversion's declaration is not the same as
  changing what it encodes** — the delicate part of that code is that a field
  nobody mentioned and a field set to nothing stay apart, and nothing in this
  epic goes near it; instead: declare the fields, read the allowed-field list off
  the declaration, and expect epic 05's round-trip checks to pass unedited — if
  one of them moves, the change went further than it was meant to.

- **caveat: the details a refusal carries are epics 03 and 04's, and this epic
  edits them** — it is named in Approach as a deliberate crossing rather than a
  quiet one; instead: change only what the four "not found" sites report, leave
  every existing check on those refusals unedited, and stop there — the other
  three codes are not this epic's business.

- **rabbit-hole: disposing of a project's server when nobody is connected to it**
  — the arrangement holds one for every project ever connected to, for the life
  of the process, and nothing measured says whether that costs anything; the
  discovery parked it as a follow-up for want of a realistic run to measure;
  instead: leave it, and let epic 09's full system run be the first thing that
  could say.

- **rabbit-hole: making the announcement reach the agent** — whether the program
  an agent runs inside actually puts what the server says on opening in front of
  the agent is an assumption spec-4 names and no probe here can settle; instead:
  build what the protocol offers, and leave the question where the discovery
  left it.

- **caveat: the call "from another machine" is made from this machine** — what
  the check shows is that a server bound to loopback turns away this machine's
  other addresses, which is what the binding does; a genuinely remote caller is
  untested and a network arrangement forwarding traffic onto loopback would
  defeat it unnoticed (the discovery and epic 05 record the same limitation);
  instead: run the control first, and where a machine has no other address, say
  so out loud in the test rather than passing in silence.

- **rabbit-hole: reaching for the real core to prove a tool call lands** — it
  needs a database, this module's build refuses one in every source set, and the
  two criteria that need it are epic 09's; instead: drive everything against the
  stand-in core here, and leave the assembled system to the epic that owns it.

## Test plan

Every check below runs in `:mcp-server`'s own tests against a stand-in core,
except TEST5's first half, which is the core's own.

- **TEST1** — unit: all seven payload shapes name their fields, every field
  carries a description that is not empty, every operation carries one, and the
  fields each conversion accepts equal the fields it declares. Epic 05's
  round-trip checks pass unedited alongside it.

- **TEST2** — integration: the library's own client completes the handshake,
  lists exactly the seven tools with a description on each tool and each
  argument, finds no tool naming a project, finds the marker that says no other
  argument is accepted, and finds nothing beyond tools through which a Nook
  operation could be called.

- **TEST3** — integration: a connection at a project's handle and one at its id
  are told the same four values about it, each equal to what the core holds; two
  projects get their own announcements; the core is asked about a project exactly
  once per connection; every call reaches the core naming its own connection's
  project and no other; and no tool call changes which project a connection is
  for.

- **TEST4** — integration: a mistyped project, an address naming no project, an
  unfilled configuration placeholder and an unreachable core each fail the
  opening exchange, the first three naming the project asked for with its
  characters intact, the fourth saying the core could not be reached; and no tool
  call reaches the stand-in from any of them.

- **TEST5** — integration: in the core's own tests, a missing project, item and
  release each produce a refusal naming which was missing; in this module, a
  refusal naming the project ends the connection so its next call is not served,
  a refusal naming an item leaves it serving, and a connection opened afterwards
  is refused naming the project.

- **TEST6** — integration: each of the four refusal codes arrives as a failed
  call carrying that code, the core's message and the core's details unchanged; a
  planted fault arrives as a breakdown carrying none of the four; a deletion
  succeeds carrying no entity and stays distinguishable from a refusal; an
  unknown tool and `create_project` each come back naming the tool asked for; the
  stand-in's own count of what reached it is exactly one after every one of those
  endings; and the next call after each is served normally.

- **TEST7** — integration: what the stand-in core is invoked with equals what the
  agent supplied, compared as whole values — emoji and non-Latin script, line
  breaks and quotation marks, a duplicate in a blocker list, an empty blocker
  list, a reference that is nearly an id, an update naming no field, each filter
  part alone and all five together, and a filter part with no values reaching the
  core rather than being turned into "do not filter".

- **TEST8** — integration: three connections, two on one project, with one call
  made deliberately slow — neither fast call waits on it and no call sees another
  connection's project; a five-thousand-item listing arrives whole, in order,
  inside the wait limit; and a tool call sent before the handshake completes is
  not served.

- **TEST9** — integration: the program stops and names the setting when either
  address is unset; told both, a client connects and calls a tool against a
  stand-in core served over HTTP in the test; that core stopped and started
  again, the same connection breaks down and then serves a call without
  reconnecting or restarting anything; and a server bound to loopback answers
  there and on no other address this machine has, with the control run first.

- **Standing check, comment hygiene** — search the final diff for artifact tokens
  (STEP, REQ, GOAL, FIND, AC, EDGE, PRD, epic) and markdown paths in code and
  code comments; expect zero hits.

- **Standing check, conformance sweep** — re-read this plan against the final
  diff: every step ticked with its verification observed, the blast radius
  respected — nothing changed in the write and read services' behavior, the
  schema, the calling library, how requests and replies turn into text, or
  `:web-app` — every caveat honored, and any mid-build divergence folded back
  into this text.

- Run both standing checks through a separate agent handed only this plan and
  the final diff, none of the builder's conversation.

Done when: a clean checkout runs `./gradlew check` green locally and in the
continuous-integration run; twenty-four of spec-4's twenty-six acceptance
criteria pass as named tests, with the milestone loop and the re-run of epics 03
and 04's criteria recorded as epic 09's; the server offers exactly seven tools,
none of them naming a project, every argument of every one of them coming from
`:contract` and nothing about them written down in `:mcp-server`; `:mcp-server`
still resolves no database dependency; and the fourteen behavior suites epics 03
to 05 built are exactly as this epic found them.
