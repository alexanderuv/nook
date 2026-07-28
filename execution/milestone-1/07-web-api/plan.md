# Web API — Plan

A note on references. This plan leans on the documents beside it.
[Spec-5](./spec-5.md) numbers what it pins — REQ for a requirement, EDGE for an
edge case, AC for an acceptance criterion. [The discovery](./discovery.md)
numbers its findings FIND and its questions Q. Where this plan cites one of
those codes it says the point in plain words alongside, so the pointer is a
cross-check and never required reading. None of those codes belongs in code or
in a code comment.

Some words that recur. **JSON-RPC 2.0** is a published specification for calling
an operation over a connection: the request is a set of named fields carrying
`jsonrpc`, `method` (the operation's name), `params` (its arguments) and `id`
(a value the reply hands back so a caller can pair the two), and the reply
carries either `result` or an `error` object of `code`, `message` and an
optional `data`. **The core** is the program that owns the database, built by
epics 03 and 04. **The client** is the one piece of code, shared by both front
doors, that calls the core and reads its replies, built by epic 05. **A shape**
is one of the plain data classes in `:contract` that a call carries, and **a
shape's declaration** is the runtime information the serialization library keeps
about it — its field names, which of them may be left out, and whether each
holds text, a list, or a set of named fields. **The loopback address**
(`127.0.0.1`) is the address a machine uses to reach itself, which nothing
outside the machine can route to.

## Analysis

### What is there now, read from the repository rather than remembered

- **`:web-app` is an empty program.** `web-app/src/main/kotlin/io/nook/web/Main.kt`
  is a `main` whose body does nothing. Its build file depends on `:contract` and
  on `ktor-server-core` — the part of Ktor that describes a server, left over
  from the skeleton and not the part that listens on a port — and it carries the
  build rule that fails the build if a database library appears anywhere in its
  dependencies.

- **The wire is Nook's own envelope, not JSON-RPC.**
  `contract/src/main/kotlin/io/nook/contract/Wire.kt` defines `CatalogRequest`
  (`operation`, `project`, `payload`) and `CatalogReply`, a three-way choice
  between `answer`, `refusal` and `fault` told apart by a field named `outcome`.
  That is the shape [ADR-2](../../../architecture/adrs/adr-2.md) replaced, and
  it is still what every program in this repository speaks. The five specs,
  `ARCHITECTURE.md` and [docs/01](../../../docs/01-interface-contracts.md) were
  amended to JSON-RPC in the two commits before this plan; no code was.

- **The core's answering side sits inside the module that owns the database.**
  `core-service/src/main/kotlin/io/nook/core/catalog/CatalogServer.kt` is a Ktor
  server on one address, plus a twenty-line function that reads a request, runs
  it, and maps every way it can end. `:web-app` cannot reach that code: its
  build rule refuses any dependency that drags a database library into its
  graph, in every source set, and `:core-service` drags several.

- **What a request means is already decided in one place.**
  `contract/src/main/kotlin/io/nook/contract/CatalogProtocol.kt` holds the
  wiring table — one entry per operation, naming the operation, the shape its
  arguments take, the shape its answer takes, and how to invoke it on any
  catalog — and `perform`, which looks an operation up by name and runs it.
  Three of the ways a request can be unreadable are refused there and nowhere
  else: an operation nobody defined, a call inside a project naming no project,
  and a call on the whole instance naming one.

- **The client is finished and public.**
  `contract/src/main/kotlin/io/nook/contract/CatalogClient.kt` offers all eleven
  operations, holds one web client for the life of the program, gives up after
  thirty seconds, and never sends a call twice. It implements the same interface
  the core's own answering side runs a request against, which is what makes the
  web app the core's handler with one argument changed (FIND1 — twenty-five
  requests came back word for word identical through both doors).

- **Three refusals quote the serialization library at the caller.** A field the
  operation does not define, a value of the wrong kind, and a missing required
  argument each come back in kotlinx.serialization's own words (FIND4). Two of
  them tell the caller to change a setting in a library the caller does not have
  (`ignoreUnknownKeys`, `isLenient`), two echo the whole request back inside the
  message, and one names an internal class
  (`io.nook.contract.CreateItem`). [ADR-4](../../../architecture/adrs/adr-4.md)
  now forbids all of that, and records the leak as unfixed and landing on this
  epic.

- **A core that was never started is reported as a defect inside the core.**
  `CatalogServer`'s last clause catches everything left and says "something
  inside the core failed", so a call that never reached the core reads as one
  that reached it and broke (FIND5). ADR-4's answer is not to tell the two
  apart outward but to stop claiming either: both are a call that produced no
  verdict.

- **The domain failure type is separate from the wire, and stays.**
  `contract/src/main/kotlin/io/nook/contract/Errors.kt` holds `ErrorCode` (the
  four names a refusal travels under), `StructuredError` (code, message,
  details) and `Missing` (which of the things a call named was the one that was
  not there, riding in the details). Around thirty test files in `:core-service`
  assert on `ErrorCode` through a helper named `assertFailsWithCode`; none of
  them touches the envelope. Keeping the domain type and changing only its
  journey onto the wire is what keeps those files out of this change.

- **`:mcp-server` reads a refusal as `StructuredError` in one place.**
  `mcp-server/src/main/kotlin/io/nook/mcp/Tools.kt` writes the refusal into a
  tool result *and* reads `Missing` off it to decide whether a project has
  disappeared; both move to the error object ADR-2 defines, which spec-4 already
  describes: a refusal carries `data.reason` naming the domain failure.
  `Dispatcher.kt` catches a refusal from resolving an address but reads nothing
  out of it, so it is left alone. (This paragraph first said the reading was in
  two places, which the build found to be wrong.)

- **The build rules that bound this work.** `nook.persistence-boundary` fails
  the build if a database library appears anywhere in `:web-app`'s compile or
  runtime graph, in every source set including tests. `nook.kotlin-jvm` makes
  every compiler warning a failure, bounds each test at two minutes and each
  test task at twenty. `:contract` declares every public name explicitly, so
  anything another module is meant to read has to be made public on purpose.
  Continuous integration runs one `./gradlew check` on Ubuntu.

- **Prior art for the two programs this one resembles.** The core's entry point
  (`core-service/src/main/kotlin/io/nook/core/Main.kt`) and the agent front
  door's (`mcp-server/src/main/kotlin/io/nook/mcp/Main.kt`) each take a port and
  one other address from the environment, refuse to start when either is
  missing, and fix the host to the loopback address in code rather than taking
  it as a setting — a host from a setting is one typo away from removing the
  whole of the protection. `CoreProgramTest` and `ToolProgramTest` show how to
  launch such a program from a test and how to earn the loopback verdict: first
  establish which of this machine's addresses reach a server listening on all of
  them, then show those same addresses refused by a server bound to loopback.

### The framing documents, linked rather than restated

- **[Spec-5](./spec-5.md)** is the requirements contract: 34 requirements (one
  struck), 24 edge cases, 20 acceptance criteria. It pins which operations this
  surface serves and at what address, that what it serves is the core's own
  shape rather than a second one, what must reach the core untouched, how a call
  ends, who may reach the app, several callers at once, and what the program is
  told from outside. Its load-bearing decision is that **this surface adds
  nothing**: every acceptance and every failure comes from the shared reading of
  a request or from the core's verdict, and the four operations that act on the
  whole instance are served here and nowhere else, which is what makes the agent
  surface's assumption true that a project already exists to point an agent at.
  Two of its twenty criteria are not this epic's: the milestone's loop run
  through this surface, and epics 03 and 04's own criteria re-run through it.
  Both need the real core over a real store, and spec-5 already assigns them to
  [epic 09](../09-full-system-test/), which records the same debt from epic 06.

- **[The discovery](./discovery.md)** settled how the app gets built, by running
  three candidate apps against a stand-in core. Adopted here without
  re-investigation: the app is the core's own handler with the client
  underneath, at `/api`, and nothing is translated (FIND1, FIND2, FIND3); every
  reply comes back under one numeric code and every other address gives the web
  server's own answer, leaving the root free for the interface arriving in
  milestone 4 (FIND8); a caller that walks away leaves the write alone, so
  nothing needs building for it (FIND9); the app recovers on its own when the
  core comes back (FIND10); five thousand items cross whole and in order
  (FIND11); the loopback binding is what turns an outside caller away, shown
  against a control run (FIND12); and the app serves a request carrying a
  page's address exactly as one carrying none (FIND13). Two of its findings are
  now decided against by ADR-4 rather than adopted — see the decisions below.
  Its recommendation to hand the call to the core to threads that are allowed to
  sit and wait is carried forward as epic 05's reasoning, not as a measurement
  (FIND14).

- **[PRD-1](../prd-1.md)** frames the epic: its requirement REQ7 asks for this
  surface, and its one-contract goal counts all eleven operations reaching the
  same verdict called inside the core, called across the connection, and called
  over this surface.

### Four decisions taken before this plan, because a plan is not where they belong

- **This epic lands ADR-2 across the whole wire.** ADR-2 replaced Nook's own
  envelope with JSON-RPC 2.0 and named no epic to carry it, recording only that
  the documents had to be amended before this epic could be planned. Spec-5
  requires this surface to serve the core's own shape, so `/api` cannot be built
  ahead of the conversion, and the conversion has no other owner. It therefore
  runs first inside this epic and reaches `:contract`, `:core-service` and
  `:mcp-server` — the same kind of deliberate crossing epic 06 made into the
  core's refusals, named here rather than discovered in the diff.

- **ADR-2's table of codes stands where spec-5's older wording disagrees.**
  Spec-5 still says in three places that every request the surface cannot read
  comes back carrying `validation_failed` (REQ12, and the edge cases for an
  operation nobody defined and for contents that are not the format at all).
  ADR-2 gives contents that are not JSON the standard's `-32700`, an envelope
  that is not a request `-32600`, an operation nobody defined `-32601`, and
  everything else about the arguments `-32602`; `docs/01` already carries that
  table verbatim, and spec-5's own requirement about how a call ends cites
  ADR-2. Those three passages are leftovers from the amendment pass, and this
  epic amends them (see the last step).

- **The reading and running of a request moves to `:contract`, and both programs
  mount it.** `:web-app` cannot see `:core-service`, so the alternative is a
  second copy of the code that decides what a request means — which is exactly
  what spec-5's demand for one contract forbids. What moves is a function from
  request text to reply text, not a web server: `:contract` defines the shapes
  every program agrees on and should not also ship an engine to listen with, and
  each program keeps its own address, its own engine and its own binding.

- **A failure names no part of Nook.** ADR-4 struck the requirement that a
  failure say whether the core had answered, and forbids the wording that says
  it: a call that produced no verdict is `-32603` with a message that describes
  the situation, whether the core was never started, dropped the connection, ran
  out of time, or broke. The client keeps the distinction for its own recovery,
  where spec-3 puts it, and never passes it outward. This is why the discovery's
  recommended extra clause — the one that would have prefixed a message with
  `connection:` or `core:` — is not built.

### Constraints that bound the change

- **No database anywhere in `:web-app`**, in any source set. The build fails
  otherwise, and that is the point.
- **The app applies no rule of its own** to a request it can read (REQ9). Every
  acceptance and every failure of such a request is the shared reading's or the
  core's, which is what makes one contract structural rather than a promise.
- **No credential is asked for and none is checked** (REQ31). Binding to the
  loopback address is the whole of the protection (REQ30), which is why this
  program fixes that address in code exactly as the core and the agent front
  door do, and takes only its port from a setting.
- **The eleven operations' behavior is not this epic's** (spec-1, spec-2,
  spec-5's first assumption): if an operation looks wrong through this surface,
  it is wrong in the core too.
- **No actor fields, no documents, no screens, no paging, no free-text search,
  no packaging** — deferred to epic 08 and to later milestones by the design
  documents.
- **The database schema is not touched**, and neither is what any operation
  does. What changes is the shape a call travels in and the words a refusal
  carries.

## Approach

Convert the wire, then serve it. The order is riskiest first: the words a
refusal carries, then the shapes, then the one answering side both programs
share, then the client, then the agent front door that reads a refusal, and only
then the web app — which by that point is a route, a client, and a program.

**A request the surface cannot read comes back in Nook's own words.** Today
those words are the serialization library's, and ADR-4 forbids handing a caller
advice about a library they do not have. The fix is a check that reads the
arguments as a set of named fields and compares them against the shape's own
declaration before anything is decoded: a field the shape does not declare, a
required field that is absent, and a field holding the wrong kind of value each
become a refusal naming that field, which is what spec-5 requires a caller to be
told (REQ12). The two conversions written by hand already do exactly this —
"this operation defines no field named …", "this operation requires …", "… takes
text, and 42 is not text" — so their wording is the model and their approach
generalizes to the other nine shapes through the declaration every shape now
carries.

**The wire becomes the standard's.** A call is a JSON-RPC request object:
`method` names the operation, `params` carries the project for the seven
operations that act inside one and the operation's arguments alongside, and `id`
is handed back on the reply. A call that succeeds answers with `result`; a call
that fails answers with `error`. Both come back under one numeric code, which
is what the standard prescribes for this kind of connection and what keeps "the
item is not there" from arriving as the same number as "the address is not
there". Batches and calls expecting no reply are two optional parts of the
standard that Nook does not serve, so a request naming no `id` is an envelope
this does not accept rather than a call answered silently.

**The four domain failures ride in the standard's own error object.**
`not_found` is `-32001`, `conflict` `-32002`, `cycle` `-32003`, and
`validation_failed` collapses into the standard's `-32602`, whether the request
was unreadable or the core refused its contents. `data.reason` carries the
domain name so a caller reads it without matching integers, and the details a
refusal already carries — which of the things a call named was missing, most of
all — ride alongside it. The three Nook-specific numbers sit in the range the
specification reserves for a server's own errors.

**One answering side, mounted twice.** `:contract` gains a function that takes
the text of a request and returns the text of a reply: it reads the envelope,
looks the operation up, runs it against whatever catalog it was handed, and maps
every way that can end. The core hands it the catalog over its own store; the
web app hands it the client. Neither program holds a rule about what a request
means, and there is one piece of code deciding — which is what spec-5's
requirement that both doors reach the same verdict rests on, rather than on
discipline.

**The web app is that function on a route.** One address, `/api`, answering
`POST`; the root and every other address left to the web server's own reply,
which carries no error of Nook's; the call to the core handed to threads that
are allowed to sit and wait, kept off the small pool the server answers on; one
client for the life of the program, which is what recovers on its own when the
core comes back; the host fixed to the loopback address and the port and the
core's address taken from the environment, with a missing setting stopping the
program and naming itself.

**Why this way over the obvious alternative.** The obvious alternative is the
app that hands the request on as it arrived and hands the reply back untouched,
which the discovery built and drove: it agrees with this one on every case this
milestone can produce, it cannot use the client at all — its whole surface is
the eleven typed operations — and using it would make spec-5's statement that
every call goes through the client false. What it would have bought is one
thing: an answer carrying a field this build has never heard of reaches the
caller instead of becoming a failure (FIND7). That case cannot arise while both
halves ship from one source tree, and the day it can, the fix is a rule about
versions rather than a second design. The other obvious alternative — leaving
the answering side in `:core-service` and writing a second one in `:web-app` —
is two pieces of code deciding what a request means, which is the thing this
epic exists to prevent.

**Unverified assumptions, named, and made the first step.** Nothing yet shows
that a check derived from a shape's declaration can name what was wrong for
every one of the eleven operations' argument shapes: two are written by hand,
one is empty, one carries a nested filter whose parent part accepts either text
or nothing, and the rest are ordinary. If a case comes back unnamed or the
declaration turns out not to say enough, the wording requirement is met some
other way and the first step is where that shows. Second, nothing yet shows a
tool result carrying the standard's error object rather than Nook's — spec-4
says it should, and epic 06's tests assert the old shape.

**Blast radius — what this change touches.** `:contract`: the envelope shapes,
the numbers on the four domain failures, the check that produces Nook's own
words, the shared answering function, and the client. `:core-service`: the
route in `CatalogServer`, which becomes a call to the shared function, and the
connection tests' expectations about a reply's shape. `:mcp-server`: the two
places that read a refusal, and the tests that assert what a tool result
carries. `:web-app` entirely — its build file, its route, its program, and its
tests, which do not exist yet. `gradle/libs.versions.toml` gains nothing: every
library this needs is already pinned. And [spec-5](./spec-5.md), where three
passages still carry the wording ADR-2 replaced.

**What it must leave untouched.** What the write and read services do; the
database schema and the changelog; the three-state field's encoding and decoding
— the check reads a payload before decoding it and the conversions do not
change; the domain failure type and the roughly thirty test files that assert on
it; the agent front door's tools, its dispatcher's routing, and what a
connection is told when it opens; and the fourteen behavior suites epics 03 to
05 built, which keep their two runs.

## Steps

- [x] **STEP1** — In `:contract`, add the check that reads a payload against its
  shape's own declaration and refuses it in Nook's words: a field the shape does
  not declare, a required field absent, and a field holding the wrong kind of
  value, each naming that field; run it ahead of decoding for every operation;
  verify: a test walks all eleven operations' argument shapes and, for each,
  drives those three mistakes and reads back a message naming the field at
  fault, containing no mention of a serialization library, no advice about one
  of its settings, no echo of the request, and no internal class name; the two
  conversions written by hand keep their own wording; and epic 05's round-trip
  checks pass unedited, which is what says nothing about what crosses moved.
  This is the step that can invalidate the approach: if a shape's declaration
  does not say enough to name a case, the wording requirement needs another
  answer before anything below is built.

- [x] **STEP2** — In `:contract`, replace the envelope with JSON-RPC 2.0: the
  request object with `jsonrpc`, `method`, `params` and `id`; the reply carrying
  `result` or `error` of `code`, `message` and `data`; and the numbers on the
  four domain failures, with `data.reason` naming the failure and the details it
  already carries riding alongside; verify: a request and both replies convert
  in both directions; the numbers are the ones ADR-2's table gives, checked
  against a test that spells the table out rather than reading it from the code
  it checks; a refusal's details survive into `data` beside the reason; and the
  discriminator field the old envelope needed is gone with it.

- [x] **STEP3** — Move the reading and running of a request out of
  `core-service`'s `CatalogServer` into `:contract` as a function from request
  text to reply text, and leave the core's route calling it; verify: an
  unreadable body is still a refusal rather than the web server's own answer —
  the reading stays inside the attempt, which is the whole difficulty of that
  function and the reason the test exists; an operation nobody defined is
  `-32601`, contents that are not the format are `-32700`, an envelope that is
  not a request is `-32600`, and a project named where none belongs is `-32602`;
  a defect planted in the catalog is `-32603` carrying no reason and naming no
  part of Nook; and every existing connection test in `:core-service` passes
  with nothing edited but its expectations about the reply's shape.

- [x] **STEP4** — Have the client build a JSON-RPC request and read a JSON-RPC
  reply: an `id` on every call and checked on the way back, `result` read as the
  operation's answer, an error carrying one of the four domain reasons thrown as
  a refusal with its code, message and details, and `-32603` thrown as a call
  that produced no verdict; verify: epic 05's suites pass with their expectations
  about the reply edited and nothing else; a reply whose `id` is not the one
  sent is a call that produced no verdict rather than an answer; an answer this
  build cannot read is still a call that produced no verdict and never a refusal,
  so nobody is told to correct work that already landed; a core that was never
  started and a defect inside the core both arrive as `-32603` and are
  indistinguishable to anything outside the client; and the client still tells
  them apart privately for its own recovery.

- [x] **STEP5** — In `:mcp-server`, carry the standard's error object into a
  tool result and read the disappeared project off `data`; verify: each of the
  four refusals arrives as a failed call carrying its code, its message and its
  `data.reason`; a refusal saying the project is gone still ends every
  connection held against that project while one saying the item is gone leaves
  them serving; a call that produced no verdict still arrives as a fault of the
  protocol's rather than as a failed call; and the module's forty-six existing
  checks pass with nothing edited but what they expect a result to carry.

- [x] **STEP6** — Give `:web-app` its route: `POST /api` over the shared
  answering function with the client underneath, the call to the core handed to
  threads that may sit and wait, and one client for the life of the app; verify:
  a set of requests written once — each of the eleven operations, each of the
  four refusals, and eight requests that cannot be read — is sent to the core's
  own connection and to `/api`, and the replies are equal as whole values in
  every case; the stand-in core receives every readable request exactly once and
  none of the eight unreadable ones; and `POST /` , `GET /api` and
  `POST /api/create_project` each come back as the web server's own reply
  carrying no error of Nook's (REQ1 to REQ13, EDGE1 to EDGE5, EDGE21, AC1 to
  AC4, AC18).

- [x] **STEP7** — Check how a call ends through this surface: the four domain
  failures, a defect planted in the stand-in core, a core that was never
  started, a core stopped mid-call, and a core that answers nothing at all;
  verify: every reply — success and failure alike — comes back under one numeric
  code; each domain failure carries the core's own code, message and `data`
  unchanged; the three ways a call can produce no verdict all arrive as `-32603`
  carrying no domain reason, and the replies for a core that broke and a core
  that was never reached are equal, neither a field nor the wording separating
  them; the stand-in records exactly one request after every one of those
  endings; and the next call after each is served normally (REQ20 to REQ26,
  EDGE15 to EDGE18, AC10, AC11, AC12, AC13).

- [x] **STEP8** — Check that what a caller supplies reaches the client
  untouched and what the core produced comes back whole: an update changing one
  field, one clearing a field and one naming no field at all; each filter part
  alone and all five together, and a part supplied with no values; a blocker
  list holding a duplicate and one supplied empty; a name carrying emoji and
  non-Latin script and a description carrying line breaks and quotation marks; a
  reference that is nearly but not quite a well-formed id; both deletes; and a
  listing of five thousand items; verify: for each, the values the client is
  invoked with equal the values the caller supplied, compared as whole values
  rather than named fields — so a field added later is covered without the check
  being edited — each entity comes back equal to the one the core produced, the
  listing arrives whole and in the core's own order inside the wait limit, and
  both deletes report success carrying no entity and stay distinguishable from a
  failure (REQ14 to REQ19, EDGE6 to EDGE14, AC5 to AC9).

- [x] **STEP9** — Check the surface under several callers and under a caller
  that leaves: one call made deliberately slow with a fast one sent after it,
  and a hundred runs of a caller that stops listening while the stand-in core is
  still writing; verify: the fast call is answered before the slow one and
  neither waits on the other; and in every one of the hundred runs the core
  carried its write to its own end, counted on the core's side (REQ27, REQ28,
  EDGE19, AC14, and the half of AC15 this surface can answer — two callers
  contesting one handle is the store's own arbitration, which epic 05 drove
  across the connection and epic 09 drives through this surface).

- [x] **STEP10** — Give `:web-app` a real program: its port and the core's
  address both taken from the environment, the host fixed to the loopback
  address, and a start refused with a message naming the missing setting rather
  than a default nobody chose; verify: launched with either setting unset the
  program stops and names it; launched with both set against a stand-in core
  served over HTTP in the test, a caller reaches an operation and back; the app
  started before that core answers the first call as a call that produced no
  verdict and serves the same call once the core is up, then again after the
  core is stopped and started, with nothing restarted; a request carrying the
  address of a page as its sender is served identically to one carrying none;
  and a server bound to the loopback address refuses every other address this
  machine has while answering there — with the control run first, and a
  spoken-out-loud skip where a machine has no other address (REQ29 to REQ34,
  EDGE22 to EDGE24, AC16, AC17).

- [x] **STEP11** — Close the epic: amend spec-5's three passages that still
  carry the wording ADR-2 replaced — the requirement and the two edge cases that
  say an unreadable request carries `validation_failed` — and the acceptance
  criterion that repeats it, each with a line saying what it now asks for and
  why; then run the whole build from a clean checkout, confirm the database
  boundary still passes for `:web-app`, push for the continuous-integration run,
  and write the epic's results into its README as epics 03 to 06 did — what was
  built, what was decided along the way, and each of spec-5's criteria against
  the named test that executes it; verify: green locally and in that run with
  the new tests visibly executed, and every criterion but the two that belong to
  epic 09 appearing in that mapping against a test that exists.

## Caveats & rabbit holes

- **no-go: telling a caller which half of Nook failed** — the discovery
  recommended one added clause that would answer `connection:` or `core:`, and
  ADR-4 struck the requirement it served: a caller can do nothing differently
  for knowing, and naming a part in a reply promises that part; instead: one
  message for every call that produced no verdict, and the distinction kept
  inside the client for its own recovery.

- **no-go: building the app that hands the request on untouched** — it agrees
  with this one on every case this milestone can produce, it cannot use the
  client at all, and building it would make spec-5's statement that every call
  goes through the client false (FIND7, FIND11); instead: decode, and accept
  that an answer carrying a field this build has never heard of becomes a
  failure — which cannot happen while both halves ship together.

- **no-go: rewriting the serialization library's message by matching its text** —
  it is the same disclosure with a worse interface, and it breaks the first time
  the library rewords anything; instead: check the payload against the shape's
  declaration before decoding, and never let the library's message reach a
  caller.

- **no-go: reading the ending off the numeric code** — every reply comes back
  under one number and the ending is in the body, which is what keeps "the item
  is not there" from arriving as the same number as "the address is not there"
  (FIND8); instead: keep the single number, and note that it is easy to lose by
  letting an exception escape the route.

- **no-go: taking the host the app binds to as a setting** — the loopback
  binding is the whole of the protection on a surface that asks for no
  credential, and a host from a setting is one typo away from removing it; the
  core and the agent front door both fix it in code; instead: fix it here too,
  and take only the port.

- **no-go: a rule of this app's own about what a request may contain** — a check
  here that the core also makes will disagree with the core one day, and one the
  core does not make is a rule nobody agreed (REQ9); instead: everything
  readable goes to the core and comes back with the core's verdict.

- **caveat: the domain failure type keeps its name and its shape** — around
  thirty test files assert on it through `assertFailsWithCode`, and none of them
  is about the envelope; instead: give the four failures their numbers and map
  them onto the error object at the edge, and expect those files to pass
  unedited — if one of them moves, the change went further than it was meant to.

- **caveat: `-32005` is spoken for** — ADR-5 assigns it to a document edit whose
  expected version did not match, which no operation in this milestone can
  produce; instead: reserve the number where the table is written, add no case
  for a failure nothing raises, and let the document layer add its own.

- **caveat: this epic edits `:mcp-server`, which epic 06 finished** — it is
  named in Approach as a deliberate crossing rather than a quiet one; instead:
  change only what a tool result carries and where the disappeared project is
  read from, leave the tools, the dispatcher's routing and the connection's
  announcement alone, and expect the rest of that module's checks to pass
  unedited.

- **caveat: the two front doors differ on a request that came from a page, and
  that is deliberate** — the agent front door turns one away, because a page in
  a browser reaches the loopback address as readily as an agent does; spec-5
  requires this surface to serve one exactly as it serves a request carrying no
  page at all, and names revisiting that as a condition of the surface reaching
  real users; instead: build no half of a check here, and leave the difference
  where the two specs put it.

- **rabbit-hole: making a failure diagnosable again** — ADR-4 keeps the detail
  out of replies on the understanding that it goes to logs, and nothing in this
  milestone produces logs to a standard that makes that easy; instead: leave it,
  and let the deployment work own it.

- **rabbit-hole: choosing between the two ways of making the call to the core** —
  the discovery could not separate them because the app and the stand-in core
  shared one program, and the recommendation rests on epic 05's reasoning
  (FIND14); instead: hand the call to threads that may sit and wait, which costs
  one line inside the route, and leave the measurement to a run with the core in
  a program of its own.

- **rabbit-hole: proving a page in a browser can call this address** — the
  discovery left it deliberately untested and spec-5 states it as fact; instead:
  leave it, and take it up with the sign-in work that would change the answer.

- **rabbit-hole: reaching for the real core to prove a call lands** — it needs a
  database, this module's build refuses one in every source set, and the two
  criteria that need it are epic 09's; instead: drive everything against a
  stand-in core here, and leave the assembled system to the epic that owns it.

- **rabbit-hole: the second web server engine, packaging, an address that says
  "yes, I am alive"** — the first is a discovery follow-up, the other two belong
  to the deployment documents; instead: serve with the same engine the core
  already serves with, and stop there.

## Test plan

Every check below runs against a stand-in core — the core's own handler over a
catalog that answers with canned entities, records what it was invoked with,
and, driven by the reference a call names, refuses with each of the four codes,
throws a defect, or takes its time — except TEST1 to TEST5, which are
`:contract`'s, `:core-service`'s and `:mcp-server`'s own.

- **TEST1** — unit: for each of the eleven operations' argument shapes, a field
  the shape does not declare, a required field absent, and a field of the wrong
  kind each come back naming that field, in Nook's words, with no mention of a
  serialization library, no advice about its settings, no echo of the request,
  and no internal class name. Epic 05's round-trip checks pass unedited
  alongside it.

- **TEST2** — unit: a request and both replies convert in both directions; the
  numbers on the four domain failures are the ones the decision records, checked
  against a table the test spells out itself; a refusal's details arrive in
  `data` beside the reason naming the failure.

- **TEST3** — integration, in `:core-service`: contents that are not the format,
  an envelope that is not a request, an operation nobody defined, a project named
  where none belongs and missing where one belongs, and a field an operation does
  not define each come back under the number the table gives, none of them as the
  web server's own answer; a defect planted in the catalog is `-32603` carrying no
  reason and naming no part of Nook; and the existing connection tests pass with
  only their expectations about a reply's shape edited.

- **TEST4** — integration, in `:contract`: a call whose reply carries an `id`
  other than the one sent produces no verdict; an answer this build cannot read
  produces no verdict rather than a refusal; a core that was never started and a
  defect inside the core produce replies that are equal outside the client, while
  the client still tells them apart for its own recovery.

- **TEST5** — integration, in `:mcp-server`: each of the four refusals arrives
  in a tool result carrying its code, message and `data.reason`; a refusal saying
  the project is gone still ends every connection held against it and one saying
  the item is gone leaves them serving; a call that produced no verdict still
  arrives as a fault of the protocol's.

- **TEST6** — integration: the same requests sent to the core's own connection
  and to `/api` — each of the eleven operations, each of the four refusals, and
  eight that cannot be read — come back equal as whole values; the stand-in core
  receives every readable one exactly once and none of the unreadable ones; and
  reading `/api`, or sending anything to the root or to an address under `/api`,
  gives the web server's own reply carrying no error of Nook's.

- **TEST7** — integration: every reply, success and failure alike, comes back
  under one numeric code; each domain failure carries the core's own code,
  message and `data` unchanged; a defect inside the core, a core that was never
  started, a core stopped mid-call and a core that answers nothing at all each
  arrive as `-32603` carrying no domain reason, with the first two equal to one
  another; exactly one request reached the core after every one of those endings;
  and the next call after each is served normally.

- **TEST8** — integration: what the client is invoked with equals what the caller
  supplied, compared as whole values — the three states of a partial update, each
  filter part alone and all five together, a part with no values reaching the core
  rather than being turned into "do not filter on this", a blocker list keeping
  its duplicate, a blocker list supplied empty, emoji and non-Latin script, line
  breaks and quotation marks, and a reference that is nearly an id; each entity
  comes back equal to the one the core produced, both deletes succeed carrying no
  entity and stay distinguishable from a failure, and five thousand items arrive
  whole and in order inside the wait limit.

- **TEST9** — integration: a slow call and a fast one together, with the fast one
  answered first and neither waiting on the other; and a hundred runs of a caller
  that stops listening mid-write, with the core's own count showing every write
  carried to its end.

- **TEST10** — integration: the program stops and names the setting when either
  its port or the core's address is unset; told both, a caller reaches an
  operation and back against a stand-in core served over HTTP in the test; that
  core started late, stopped and started again leaves the app serving without
  anything being restarted; a request carrying a page's address as its sender is
  served identically; and an app bound to the loopback address answers there and
  on no other address this machine has, with the control run first.

- **Standing check, comment hygiene** — search the final diff for artifact tokens
  (STEP, REQ, GOAL, FIND, AC, EDGE, PRD, epic) and markdown paths in code and
  code comments; expect zero hits.

- **Standing check, conformance sweep** — re-read this plan against the final
  diff: every step ticked with its verification observed, the blast radius
  respected — nothing changed in the write and read services' behavior, the
  schema, the three-state field's encoding and decoding, the domain failure type,
  or the agent front door's tools and routing — every caveat honored, and any
  mid-build divergence folded back into this text.

- Run both standing checks through a separate agent handed only this plan and the
  final diff, none of the builder's conversation.

Done when: a clean checkout runs `./gradlew check` green locally and in the
continuous-integration run; eighteen of spec-5's twenty acceptance criteria pass
as named tests, with the milestone loop and the re-run of epics 03 and 04's
criteria recorded as epic 09's; no program in the repository speaks the old
envelope and nothing names an `outcome`; no reply anywhere carries a
serialization library's wording or names a part of Nook; `:web-app` serves the
eleven operations at `/api` and nothing at the root; `:web-app` still resolves no
database dependency; and the fourteen behavior suites epics 03 to 05 built are
exactly as this epic found them, but for what they expect a reply to look like.
