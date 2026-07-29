# Epic 07 — Web API

**Addresses:** REQ7 (the core's own request and reply shape served outward —
one address, the operation and the project named inside the request, the reply
naming its own ending — rather than a second shape of its own).

Documents, in the order they were produced:

- [spec-5.md](./spec-5.md) — the requirements contract. Its load-bearing
  decision: **this surface adds nothing.** Every acceptance and every failure
  comes either from the shared reading of a request or from the core's verdict,
  and the four operations that act on the whole instance are served here and
  nowhere else — which is what makes the agent surface's own assumption true,
  that there is always an existing project to point an agent at.
- [discovery.md](./discovery.md) — three candidate apps driven against a
  stand-in core, settling how this one gets built: the core's own handler with
  the calling library underneath, at `/api`, translating nothing; one numeric
  status for every reply, with every other address left to the web server; a
  caller that walks away leaving the write alone; and the loopback binding shown
  against a control run.
- [plan.md](./plan.md) — the build route, steps ticked as execution proceeded.
  Its four decisions taken before the steps: that this epic lands
  [ADR-2](../../../architecture/adrs/adr-2.md) across the whole wire, that
  ADR-2's table of codes stands where spec-5's older wording disagreed, that the
  reading and running of a request moves to `:contract` for both programs to
  mount, and that a failure names no part of Nook.

Two of spec-5's twenty acceptance criteria are deliberately not met here — the
milestone's loop run through this surface, and epics 03 and 04's own criteria
re-run through it. Both need the real core over a real store, which means
starting every piece at once; they belong to
[epic 09](../09-full-system-test/), which already records the same debt from
epic 06.

## Results

`:web-app` became a real module. It holds one address, the engine that serves
it, the binding that decides who may reach it, and a program to start it all.
`Main.kt` stopped being a placeholder. What it does *not* hold is any rule about
what a request means — and that absence is the whole result of this epic.

**Nook stopped having a protocol of its own.** The envelope designed in
[docs/01](../../../docs/01-interface-contracts.md) — `operation`, `project`,
`payload`, and a reply naming one of three endings under a field called
`outcome` — is gone, replaced by JSON-RPC 2.0 as ADR-2 decided. A call is a
request object: `method` names the operation, `params` carries the project
beside the operation's own arguments, and `id` comes back on the reply. A call
that succeeded answers with `result` — written even where the operation produced
none, so a delete is plainly a success rather than something a caller has to
guess about — and one that failed answers with `error`. Both come back under one
HTTP status, which is what keeps "the item is not there" from arriving as the
same number as "the address is not there".

The four domain failures ride in the standard's own error object. `not_found` is
`-32001`, `conflict` `-32002`, `cycle` `-32003`, and `validation_failed`
collapses into `-32602`, whether this side could not read the arguments or the
core refused them. `data.reason` carries the failure's own name and the details
it already carries ride alongside, so a caller reads which one it was without
matching integers — and an error naming *no* reason is not a refusal at all,
which is what keeps a call that settled nothing from ever reading as something
in the request to correct. `-32005` is reserved where the table is written and
has no case: no operation in this milestone can produce it.

**The domain failure type did not move.** `ErrorCode`, `StructuredError` and
`Missing` are exactly as this epic found them, and so are the roughly thirty
test files in `:core-service` that assert on them through `assertFailsWithCode`.
What changed is only the journey onto the wire: a conversion at each edge, and
`Missing` gained a reading off the error object beside its reading off the
details.

**One answering side, mounted twice.** The twenty lines inside
`core-service`'s `CatalogServer` that read a request and mapped its endings
moved to `:contract` as a function from request text to reply text. The core
hands it the catalog over its own store; `:web-app` hands it the calling
library. Each program keeps its own address, its own engine and its own
binding, and neither holds a rule about what a request contains — so "the same
request reaches the same verdict at either adapter" is structural rather than two
programs agreeing to it. What moved is not a web server: `:contract` defines the
shapes every program agrees on and ships no engine to listen with.

That is also why `:web-app` could be built at all. It may not resolve a
database dependency in any source set, so it cannot see `:core-service`; the
alternative to moving the code was a second copy of it, which is exactly what
spec-5's demand for one contract forbids.

**A reply says nothing about which part of Nook failed.** A call that produced
no verdict — the core never started, the link dropped, the wait ran out, or
something inside the core broke — comes back as `-32603` carrying one sentence,
the same one every time, with no reason and no field separating the cases. The
calling library still tells them apart for its own recovery, where
[spec-3](../05-operation-catalog/spec-3.md) puts it, and never passes it
outward: what it observed now rides in the cause, where a stack trace carries it
and a reply does not. The status quo this replaces was not merely talkative but
false — a core that was never started reported a defect inside it.

**A caller is never handed the serialization library's words.** Three refusals
used to quote it: two told the caller to change a setting in a library they do
not have, two read the whole request back, and one named a class from inside
`:contract`. `:contract` now checks a payload against the shape's *own
declaration* before anything is decoded — which fields it defines, which may be
left out, and what kind of value each takes — and refuses in Nook's words: `this
operation defines no field named "colour"`, `this operation requires "ref"`,
`"name" takes text, and 42 is not text`. Being derived from the declaration, it
covers all eleven operations' argument shapes and covers a field added later
without anything being edited — which is what the first step was there to find
out, and it held.

The wording is modelled on the two conversions written by hand, which said
exactly this before anything was derived. One of their messages turned out to be
saying something the generic three could not: a field named with nothing in it
is not a value of the wrong kind but a request to take the stored value away,
and a field that must always hold one should say so in those terms. So `"name"
takes a value; it cannot be set to nothing` is now what *every* shape says, not
what two of them say — and a field the value may be taken away from still
accepts nothing, or clearing a description would have become unaskable.

One hazard the move surfaced: an answer this build cannot *write* out would have
escaped as a wrong argument, telling whoever sent the call to fix a write that
had already landed. Everything after the arguments are read is now sealed off
from anything that says "reading", so it arrives as what it is.

**The app is the shared function on a route.** `POST /api`, and nothing else.
The root and every other address are the web server's own reply, carrying no
error of Nook's — the root deliberately, since that is where the interface
arrives in milestone 4. The call to the core goes to threads that are allowed to
sit and wait, kept off the small pool the server answers on, which is also what
leaves an abandoned write running to its own end. One calling library serves the
life of the program, so an app started before the core recovers on its own once
the core is there, without being rebuilt. The host is fixed to the loopback
address in code, exactly as the core and the MCP server fix theirs: this
surface asked for no credential, so that binding was the whole of the protection
(epic 08 added a bearer token to it; the binding now keeps a token travelling in
the clear off any other machine),
and a host taken from a setting is one typo away from removing all of it. The
port and the core's address come from outside, and a missing one stops the
program naming itself.

### Rule-to-test mapping

Every acceptance criterion of [spec-5](./spec-5.md), as the named test that
executes it (tests carry no criterion numbers by design — code never cites
planning artifacts):

| Criterion | Test |
| --- | --- |
| AC1 | `OneContractTest` — each of the eleven operations is served by name at `/api`, a twelfth is refused naming what was asked for, and every other address on this app is the web server's own reply |
| AC2 | `OneContractTest` — a project named on one of the four that act on the whole instance, and left unnamed on one of the seven that act inside one, are each turned down at both adapters and reach the core at neither; `FidelityTest` — a project-scoped call reaches the core naming its project, and an instance-level one reaches it naming none |
| AC3 | `OneContractTest` — one request written once, sent to the core's own connection and to `/api`, comes back equal as a whole value and reaches the core the same way at each, for all eleven operations and for each of the four refusals |
| AC4 | `OneContractTest` — eight requests neither adapter can read, each turned down identically and reaching the core at neither, with the next call served normally; `UnreadableArgumentsTest` in `:contract` — for every one of the eleven argument shapes, a field it does not define, a required one left out and one holding the wrong kind each come back naming the field at fault, in Nook's words, with no mention of a serialization library, no advice about its settings, no echo of the request and no internal type name |
| AC5 | `FidelityTest` — a name carrying emoji and non-Latin script, a description carrying line breaks and quotation marks, a blocker list keeping its duplicate, and a reference that is nearly an id all reach the core as written, compared as whole values |
| AC6 | `FidelityTest` — an update changing one field, one clearing a field, one setting it to empty text and one naming no field at all reach the core as four different things |
| AC7 | `FidelityTest` — each filter part reaches the core alone and all five together, a part supplied with no values reaches it with no values rather than being turned into "do not filter on this", and a blocker list supplied empty arrives empty |
| AC8 | `FidelityTest` — each entity comes back equal to the one the core produced, compared as the whole entity; both deletes report success carrying none and stay apart from a failure; `CallEndingTest` — a project that is not there and an item that is not there are told apart by the failure's own data |
| AC9 | `FidelityTest` — a listing of five thousand items arrives whole, in one reply, in the core's own order, inside the wait limit |
| AC10 | `CallEndingTest` — every reply comes back under one number, success and failure alike, and each domain failure carries the core's own code, message and data unchanged |
| AC11 | `CallEndingTest` — a defect planted inside the core is `-32603` carrying no reason, reaches the core exactly once, and the next call is served normally |
| AC12 | `CallEndingTest` — a defect inside the core and a core that was never started are the same reply, byte for byte; `WebProgramTest` — a core that was not there, then is, then is not, then is, leaves the app serving throughout with nothing restarted |
| AC13 | `CallEndingTest` — a core stopped mid-call and one that answers nothing at all each produce no verdict, with the core reached exactly once after every ending |
| AC14 | `ManyAtOnceTest` — a hundred callers that stop listening mid-write, with the core's own count showing every write begun and every write carried to its end |
| AC15 | `ManyAtOnceTest` — a fast call answered while a slow one is still waiting, neither waiting on the other. Two callers contesting one handle is the store's own arbitration, which epic 05's `WriteServiceSlugRaceAcrossConnectionTest` drove across the connection and epic 09 drives through this surface |
| AC16 | `WebProgramTest` — an app bound to the loopback address answers there and nowhere else this machine can be reached, to a caller presenting a valid bearer token (epic 08 superseded REQ31's "no credential"), and a request carrying the address of a page as its sender is served exactly as one carrying none |
| AC17 | `WebProgramTest` — told both addresses, a caller reaches an operation and back; started without either, the program stops and names the one it is missing |
| AC18 | `OneContractTest` — the root, reading `/api` rather than sending it a request, and an operation given an address of its own each come back as the web server's own reply carrying no error of Nook's, reaching the core at none, with `/api` still served afterwards |
| AC19 | Epic 09 — the milestone's loop needs the real core over a real store |
| AC20 | Epic 09 — epics 03 and 04's criteria re-run through this surface need the same |

AC16's test earns its verdict the way epics 05 and 06 earned theirs. An address
that cannot reach an app listening on every address proves nothing about what
the loopback binding refuses — the network turned the call away, not the
binding. So it first stands up an app bound to everything and establishes which
of this machine's addresses reach it at all; only those are then tried against
the loopback-bound app, and on a machine with no such address it stops and says
so instead of passing emptily.

The two adapters differ deliberately on one point. The agent surface turns
away a request carrying a page's address, because a page in a browser reaches
the loopback address as readily as an agent does; this surface serves one
exactly as it serves a request carrying none, and spec-5 names revisiting that
as a condition of the surface reaching real users. No half of a check was built
here.

### What this epic changed elsewhere

Landing ADR-2 crossed three other modules, named here rather than discovered in
a diff:

- **`:contract`** — the envelope replaced, the numbers on the four domain
  failures, the check that produces Nook's own words, the shared answering
  function, and the calling library, which now builds a request object, numbers
  every call and checks that number on the way back. A reply answering a call it
  did not make is a call that produced no verdict, not an answer.
- **`:core-service`** — the route, which became a call to the shared function,
  and the connection tests' expectations about a reply's shape. Nothing about
  what the write and read services do, the schema, the changelog, or the
  three-state field's encoding and decoding moved, and the fourteen behavior
  suites epics 03 to 05 built are exactly as this epic found them.
- **`:mcp-server`** — the one place that writes a refusal into a tool result and
  the one that reads the disappeared project off it. The tools, the dispatcher's
  routing and what a connection is told when it opens are untouched, and the
  rest of that module's checks passed unedited.

Everything in this module runs against a stand-in core: twenty-seven checks,
none of them needing a database, and `checkPersistenceBoundary` still passes for
`:web-app` now that it carries a web server engine. Four of them stand up the
program an operator starts, because what a process does when it cannot start
cannot be shown from inside it.
