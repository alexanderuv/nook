# Operation catalog approach

## Summary

- **The connection can carry everything the contract holds, with one exception
  that has to be written by hand.** Whole entities, the listing filter, emoji and
  line breaks in text, timestamps to the microsecond, and a 5,000-item listing
  all crossed and came back equal to what the core produced. The exception is a
  partial update: the code that turns values into text and back, when the library
  generates it, reads an absent field and a field set to nothing as the same
  thing, so "leave this alone" and "clear this" arrive identical — and go back
  out identical. Deciding by whether the field is mentioned at all keeps the
  three states apart, and every case then survived the crossing unchanged.
  Annotating the contract's own three-state field so the library generates that
  code instead does not rescue it: the generic form the contract already declares
  compiles and then throws when asked to write a cleared field, while the same
  shape without a generic parameter works — so the choice is a hand-written
  mapping or a redesigned contract field, not a free one.
- **One address that every call goes to, naming its operation inside, is the only
  layout in which every unreadable request comes back as a refusal.** With one
  address per operation, a request naming an operation that does not exist gets
  the web server's own empty "no such address" answer — a breakdown, where the
  spec asks for a refusal. Letting the reply name its own ending, rather than
  leaving it to the numeric code every web reply carries, also removes a collision
  that code cannot avoid: an item that is not there and an address that is not
  there both answer 404.
- **The waiting, resending, and giving-up rules hold as the client comes, with
  nothing added.** The limit fired at 30,055 ms; the core received the abandoned
  call exactly once, and each of the four refusals exactly once. Nothing
  listening, a connection dying mid-answer, no answer in time, and a fault inside
  the core each arrive as a different, recognizable shape, and an adapter recovers
  on its own once the core is back.
- **A caller that gives up mid-write leaves the write alone.** Across 400 runs on
  two web-server engines, every write ran to its own end and every item landed
  whole with its blocker edge; deliberately shielding the write from the caller's
  departure changed nothing, because store work that blocks cannot be interrupted
  by the caller leaving anyway.
- Recommendation in brief: one address with the operation named inside and the
  ending carried in the reply; the partial-update fields written by hand and
  decided by whether a field is mentioned; that code living in `:contract`, with
  the serialization plugin applied from the convention plugin rather than from the
  module — which is what the module's recorded obstacle was actually caused by; a
  30-second limit on the client and no retry installed; the store's work handed to
  threads that may sit and wait; and the core bound to the loopback address, which
  a control run shows is what keeps a caller on another address out.

## Questions

- **Q1** — Can the contract's shapes cross as text and come back unchanged,
  including the two distinctions the spec insists on — "leave this field alone"
  against "set this field to nothing", and "do not filter on this part" against
  "filter on no values at all"?; informs: the wire format `:contract` deferred
  until a wire existed, and the requirements about what must survive the
  crossing.
- **Q2** — Should the catalog be laid out as one address per operation, or one
  address that every call goes to with its operation named inside, so that an
  unknown operation, a missing project, an undefined field, and contents that
  cannot be read at all all come back as refusals rather than as whatever the web
  server says?; informs: the shape of both halves of the connection and how a
  caller tells an answer from a refusal from a breakdown.
- **Q3** — Do the rules about a call that does not end normally hold as the
  client comes — a limit that fires between 30 and 31 seconds, never a second
  send, a breakdown a caller can tell apart from a fault inside the core — and
  what does a caller that stops waiting during a write leave in the store?;
  informs: how the calling library is configured, and whether the core needs to
  protect a write from the caller's departure.
- **Q4** — Do several callers at once behave across the connection as they do
  inside the core, and can "only this machine may call" be true of the address
  the core listens on alone, with no credential anywhere?; informs: how the
  server is run, and what the protection in this milestone actually is.
- **Q5 (emerged)** — Where can the code that turns the contract's values into
  text and back live? `:contract` records that it deliberately carries no
  serialization plugin — the build-time helper that writes such code for you —
  because applying one gave the module a build set-up of its own and loaded the
  Kotlin plugin twice; asked once Q1's answer turned out to need code written
  against those very classes; informs: which module grows in this epic, and
  whether that recorded obstacle still stands.

Bound: two throwaway builds on one machine (macOS, Apple silicon, JDK 25), every
library version taken from the repository's own pins, seven probe groups, no load
testing, and no second machine. That sufficed because each question is about how
a mechanism behaves, which one honest execution settles, and the code that builds
on the answers lands in this same epic. Two things the bound leaves untested are
recorded as limitations: the callers were separate clients inside one program
rather than separate adapter processes, and the call "from another machine" was
made from this one, over its own address on the network.

## Method

A throwaway Kotlin program (in a scratch directory, with its own build, deleted
after this report) that depends on the repository itself, so the probes drive the
real `WriteService` and `ReadService` and the committed changelog rather than
imitations of them. Underneath: Zonky embedded-postgres 2.2.2 with the PostgreSQL
17.10 binaries, Liquibase 5.0.3 applying the repo's changelog in-process, Exposed
1.3.1 for data access, Ktor 3.5.1 for both the serving and the calling side, and
kotlinx.serialization 1.11.0 for the text format — every one of them the version
the repository already pins. Each probe group got its own freshly created, fully
migrated database.

The probe built both halves the epic will build: an answering side offering the
eleven operations by name, and one calling library that makes the calls and reads
the replies, reporting each call as one of three endings — an answer, a refusal,
or a breakdown. Requests and replies travel as JSON: ordinary text made of named
fields, which is what both adapters' own surfaces already speak. Three operations
no real catalog would carry were added for the probes' own purposes: one that
sleeps, one that fails on purpose, and one that writes an item row, pauses, then
writes its blocker edge, so a caller can give up in the middle of a write.

Seven probe groups:

- **What survives the crossing.** Every state of every field of a partial update,
  each of the five filter parts including one supplied with no values and one
  supplied twice, and whole entities the core had just produced — a project with
  a description carrying a line break and quotation marks, an item named with
  emoji and non-Latin script, an item carrying two blockers, and a release with a
  target date — encoded, decoded, and compared against the original as whole
  values rather than field by field. Alongside them, the same three states read
  through the serialization library's own generated field mapping, for
  comparison, and a mapping deliberately written missing one field.
- **The layout of the catalog.** The same catalog served two ways — one address
  per operation, and one address with the operation named inside — each driven
  through good calls, each of the four refusals the core produces, an operation
  that does not exist, a project-scoped call naming no project, an instance-level
  call naming one, a field the operation does not define, contents that are not
  text the reader could parse, a fault planted inside the core, and a request to
  an address nobody defined. Entities and listings that came back were compared
  against the same operations called inside the core's own process.
- **The wait limit and the resend.** A call the core was made to leave unanswered
  for 45 seconds against a 30-second limit, timed; then the core's own count of
  how many times each request actually reached it, after a call that ran past the
  limit, after each of the four refusals, and after a fault.
- **The core absent, arriving, leaving, and coming back.** A first call made with
  nothing listening; the core then started and the same client used again; the
  core stopped mid-call; the core restarted and called again — all without the
  calling side being recreated.
- **A caller that gives up mid-write.** 100 runs against each of four
  configurations — two web-server engines (Ktor's own CIO engine and Netty),
  each with the write left ordinary and with the write deliberately shielded from
  the caller's departure — where the client's limit was 40 milliseconds and the
  write paused 120 milliseconds between its two statements. Afterwards the store
  was read directly, not through the read path, for items sitting there without
  the blocker edge their write was to give them.
- **Several callers at once, and where the core listens.** Two clients standing
  for the two adapter processes: a slow call and a fast call in flight together;
  100 runs of both creating an item of the same name at the same moment; 100 runs
  of each writing one half of a two-step dependency loop; 100 rounds of one
  listing while the other wrote. Then the same catalog served bound to the
  loopback address — the address a machine uses to reach itself, which nothing
  outside it can route to — and bound to every address, called on each of this
  machine's own network addresses, so the refusal could be attributed to the
  binding rather than to the network. Separately, a 5,000-item listing timed across the
  connection and in the core's own process.
- **Where that code can live.** A second throwaway build reproducing the
  repository's own structure — a `build-logic` folder holding a convention plugin
  (a shared build file the modules apply instead of each repeating the same
  settings), one module taking Kotlin from it alone, and the annotated module in
  two variants: one declaring the serialization plugin itself with a version, as
  `:contract` had tried, and one taking both plugins from a convention plugin.
  Both variants carried the same source: the contract's own
  shapes annotated, under explicit-visibility mode and with warnings treated as
  errors, exactly as the repository configures them.

One candidate was ruled out before any hands-on trial. **kotlinx-rpc**, JetBrains'
own library for calling Kotlin functions across a process boundary, would in
principle deliver both halves from one interface — which is what the epic wants
of its calling library. It was judged on published release metadata and its own
documentation rather than tried, on the same ground epic 01 used to reject the
Kotlin MCP server library: maturity of a load-bearing dependency (FIND12).

Not done: any load or volume testing beyond the 5,000-item listing; more than two
callers at once; any second machine; the two callers as two operating-system
processes rather than two clients inside one; the behavior of a process started
without its address configured, which is ordinary startup code and needs no
probe; and any exercise of the epic's own calling library, which does not exist
yet. Probe output is quoted in the findings; the programs themselves keep no
authority.

## Findings

### FIND1 — Library-generated field reading cannot express a partial update's three states; deciding by whether a field is mentioned can

**Confidence:** solid — both ways executed on every state of every field · answers Q1

A field of a partial update has three states: unmentioned, set to a value, and
set to nothing. Asked to read them, the code the serialization library generates
collapses two of them:

| What was sent | What the generated mapping produced |
| --- | --- |
| `{"name":"n"}` | `GeneratedUpdate(name=n, description=null)` |
| `{"name":"n","description":null}` | `GeneratedUpdate(name=n, description=null)` |

The two decoded values are equal, and both re-encode to `{"name":"n"}` — so a
caller asking to clear a description produces the same request as a caller who
never mentioned it, in both directions. Nothing about this is a configuration
mistake to be corrected: the mapping has one slot per field and no way to record
that the field was there at all.

Deciding by whether the field is mentioned at all keeps the three apart:

| What was sent | What that reading produced |
| --- | --- |
| `{}` | `Keep` |
| `{"description":null}` | `Set(value=null)` |
| `{"description":"text"}` | `Set(value=text)` |

Every state of every field of the update command was then written out as text and
read back unchanged: nothing at all (`{}`), a name alone, a description cleared, a
description set, a release cleared, a blocker list emptied (`{"blockedBy":[]}`),
a blocker list still carrying its duplicate (`["a","a","b"]`), and all eight
fields at once. The resulting text is also the plainer one — `{"name":"Renamed"}`
rather than a wrapper naming a type.

The listing filter needs no such handling, because a part that is not being
filtered on is *absent* while a part with no values is an empty list, and those
are already different in the text. Every filter shape came back unchanged: no part
at all, a part holding no values (`{"types":[]}`), several parts together, a
repeated value, "no epic above", a named epic and "no epic above" in one part,
and the held-up part in both of its answers.

### FIND2 — The contract's three-state field, annotated instead of hand-written, breaks on exactly the case that matters

**Confidence:** solid — encoding and decoding both executed, and the cause isolated by a second variant · answers Q1, Q5

The contract already declares its partial-update field as one generic shape
covering every carried type — the same declaration serves a name, a description,
a release, a blocker list. Annotated so the library writes the text-conversion
code for it, that shape compiles — under explicit-visibility mode and with
warnings treated as errors — and works for two of the three states:

```
nothing at all:      {}                                                    round-trips
name set:            {"name":{"type":"demo.FieldChange.Set","value":"Renamed"}}   round-trips
description set:     {"description":{"type":"demo.FieldChange.Set","value":"text"}} round-trips
description cleared: encode failed: NullPointerException: Parameter specified as
                     non-null is null: method
                     kotlinx.serialization.internal.StringSerializer.serialize
```

Reading a cleared field somebody else wrote fails to match:

```
{"description":{"type":"demo.FieldChange.Set","value":null}}
  → Expected string literal but 'null' literal was found at path: $.description.value
```

The cause is the generic parameter: at the point of use the field's carried type
is "text or nothing", and the generated code keeps only the "text" half. The same
three states declared without a generic parameter — one declaration per carried
type, the "or nothing" written into it — carried all three across, cleared field
included.

### FIND3 — Everything else the contract carries crosses whole

**Confidence:** solid — every entity compared as a whole value against the one the core produced · answers Q1

Entities the core had just produced were encoded, decoded, and compared whole —
not field by field, so a field added later is covered without the check being
edited. A project, an item named `Søk 🔍 épico`, an item carrying two blockers,
and a release with a target date all came back equal. Text arrived exactly as
sent, line breaks and quotation marks included; a timestamp crossed as
`"2026-07-27T02:27:11.357547Z"` and named the same moment afterwards; a field
that was absent stayed absent rather than arriving as empty text; and a blocker
list carrying the same reference twice still carried it twice.

Two of the value types the contract uses are unknown to the library and need
their conversion written by hand: asked for `java.time.Instant` and
`java.time.LocalDate`, it answered `Serializer for class 'Instant' is not found`.
Identifiers need nothing — Kotlin's own `Uuid` type is supported out of the box.

A listing of 5,000 items crossed whole and in the core's own order, 217
milliseconds on the first call and 38.6 milliseconds averaged over five, against
13.8 milliseconds for the same listing taken inside the core's process — well
inside the 30-second limit. At 501 items the same comparison was 7.15
milliseconds against 4.05.

One trap belongs with this finding rather than against it. A mapping written by
hand that is missing a field compiles perfectly and drops that field silently:
asked to write a release whose target date was `2026-12-24`, a mapping that had
never been taught about target dates produced a reply with no target date in it
and no complaint anywhere. Nothing in the compiler notices, because nothing is
wrong with the code — it simply says less than it used to.

### FIND4 — Only one of the two layouts turns every unreadable request into a refusal

**Confidence:** solid — the same catalog served both ways and driven through every case · answers Q2

Both layouts handled the four refusals the core produces identically, each
arriving with the core's own code, message, and details, and matching what the
core answers in its own process. Both refused a project-scoped call that named no
project, an instance-level call that named one, and a field the operation does
not define. They part on one case:

| The request | One address, operation named inside | One address per operation |
| --- | --- | --- |
| an operation that does not exist | `refusal validation_failed: this connection carries no operation named 'teleport_item'` | `breakdown — status 404, empty body` |

With one address per operation, there is no handler for an operation nobody
defined, so the answer is the web server's own "no such address" — an empty
reply the calling library has nothing to read, and reports as a breakdown.

A second difference showed up in the same runs. When the ending is carried by the
numeric code every web reply comes back with, an item that is not there and an
address that is not there both answer 404:

```
one address per operation:  a not_found refusal → status 404, body carrying the refusal
                            an address nobody defined → status 404, empty body
```

The body still says which is which, so nothing is unrecoverable — but the number,
which is the thing that layout uses to carry the ending, does not decide it. On
the other layout the reply names its own ending.

### FIND5 — Reading the request has to sit inside the same attempt as running it

**Confidence:** solid — the same request against two servers differing only in that · answers Q2

The probe's first server read the request, then ran it inside the part that maps
failures to refusals. Contents that could not be read therefore failed before the
mapping was reached, and came back as the web server's default:

```
reading outside the attempt: breakdown (core reached) — status 500,
                             body: this request's contents could not be read as JSON
reading inside the attempt:  refusal validation_failed:
                             this request's contents could not be read as JSON
```

Both servers detected the same problem and said the same words; only the shape
differed. Nothing about the framework forced either shape — the difference is
which line of the handler the reading sits on, and the first arrangement is the
one a handler falls into by default.

### FIND6 — The wait limit fires where it should, and nothing is ever sent twice

**Confidence:** solid — timed, and counted on the receiving side · answers Q3

Against a core made to leave a call unanswered for 45 seconds, a client
configured with a 30-second limit gave up after **30,055 milliseconds** — inside
the 30-to-31-second window the spec asks for — reporting
`Request timeout has expired [request_timeout=30000 ms]`.

The core's own count of what actually reached it, taken after the call had run
its course, was **1**. The same count after a shorter limit, after each of the
four refusals, and after a fault inside the core was 1 in every case. The client
as it comes installs no retry; the count is what shows that nothing anywhere else
does either.

### FIND7 — The four ways a call can fail arrive as four recognizable shapes, and an adapter recovers on its own

**Confidence:** solid — every case executed · answers Q3

| What happened | What the caller received | How long it took |
| --- | --- | --- |
| nothing listening | `java.net.ConnectException — Connection refused` | 3 ms |
| the core stopped mid-call | `java.io.EOFException — Failed to parse HTTP response: the server prematurely closed the connection` | 506 ms |
| no answer within the limit | `HttpRequestTimeoutException` | 30,055 ms |
| a fault inside the core | a reply naming its ending as a fault, carrying none of the four refusal codes | immediate |

All four are distinguishable, and none can be mistaken for a refusal. The first
three carry no code at all; the fourth carries a named fault and no code. How the
calling library groups them is its own choice — the shapes themselves are
distinct.

Recovery needed nothing. The same client that had failed with nothing listening
succeeded once the core was started, failed again when it was stopped, and
succeeded again once it was restarted — four calls, one client, never recreated.

### FIND8 — A caller that gives up mid-write changes nothing about the write

**Confidence:** solid — 400 runs across two engines and both shapes, the store read directly afterwards · answers Q3

Each run wrote an item row, paused 120 milliseconds, then wrote the item's
blocker edge, in one transaction; the caller's limit was 40 milliseconds, so
every caller left during the pause. In all four configurations — Ktor's CIO
engine and Netty, each with the write left ordinary and with it deliberately
shielded from the caller's departure — the result was identical:

| | every configuration |
| --- | --- |
| what the callers saw | 100 of 100 gave up waiting |
| writes the core carried to their end | 100 |
| items in the store afterwards | 100 of 100 |
| items sitting there without their blocker edge | 0 |

The shield made no measurable difference, in either engine: store work that
blocks the thread it runs on cannot be interrupted by the caller going away, so
the write reached its end in every configuration. Neither did any item arrive
half-written — both statements sat in one transaction, which makes them permanent
together or not at all.

The core also never noticed it had been abandoned: every reply was written out
without complaint, to a caller that had stopped listening.

### FIND9 — Several callers at once behave across the connection exactly as they do inside the core

**Confidence:** solid — 100 runs of each contested case · answers Q4

Two clients standing for the two adapters:

| | result |
| --- | --- |
| a slow call and a fast call in flight together | the fast one answered at 270 ms, the slow one at 3,094 ms |
| both creating an item of the same name, 100 runs | 100 of 100 left two items, with different handles every time |
| each writing one half of a two-step loop, 100 runs | 100 of 100 ended one commit and one `cycle` refusal; 0 loops in the store |
| one listing while the other wrote, 100 rounds | 100 listings taken, 0 items seen without the blocker their write gave them, 0 failed calls |

The store's guarantees held unchanged when the writers arrived over the
connection, and no call waited on an unrelated one. Every run was taken with the
store's work on threads that are allowed to sit and wait, kept apart from the
small pool the web server answers on; the probe was built that way throughout and
never measured the alternative.

### FIND10 — The loopback address is what keeps an outside caller out, and a control run proves it

**Confidence:** solid for the binding; the caller was on this machine · answers Q4

This machine has two addresses off the loopback. Against a core listening on
every address, one of them reached it and one did not, so only the reachable one
is evidence of anything:

| Address | core listening everywhere | core listening on 127.0.0.1 |
| --- | --- | --- |
| `192.168.50.179` (the local network) | answer | `ConnectException — Connection refused` |
| `10.5.0.2` (a tunnel) | could not connect either way | — |
| `127.0.0.1` | — | answer |

So the address that demonstrably reaches a core listening everywhere is turned
away by a core bound to the loopback: the binding is what refuses it, not the
network. No credential was presented in any of these calls and none was asked
for.

### FIND11 — The text-conversion code can live in `:contract`; the obstacle it recorded comes from where the plugin is applied

**Confidence:** solid — the repository's structure reproduced, with and without the change · answers Q5

`:contract` records that it carries no serialization plugin because applying one
gave the module a build set-up of its own and loaded the Kotlin plugin twice,
which the build tool warns "is not supported and may break the build". The
warning is real and reproduced exactly — and it depends entirely on where the
plugin is applied:

| The build | The warning |
| --- | --- |
| the annotated module declares the serialization plugin itself, with a version, alongside a module that takes Kotlin from the convention plugin | `The Kotlin Gradle plugin was loaded multiple times in different subprojects, which is not supported and may break the build.` |
| the convention plugin carries both, and the module declares neither | none |

Both variants compiled the same source: the contract's shapes annotated, carrying
identifiers, a set of identifiers, a timestamp with its conversion written by
hand, and the generic three-state field — under explicit-visibility mode and with
warnings treated as errors, as the repository configures every module. So the
module can carry this code; what it cannot do is declare the plugin in its own
build file.

FIND2 bounds what that buys: the entities can be generated, the partial-update
commands still cannot.

### FIND12 — The one library that would deliver both halves from one interface has not declared itself finished

**Confidence:** suggestive — published records read first-hand, the library not tried · answers Q2, Q5

kotlinx-rpc is JetBrains' library for calling Kotlin functions across a process
boundary, which is exactly the shape of this epic's calling library. Its
published record: the newest release is **0.10.3**, dated 24 June 2026, and every
one of its 21 published versions since 0.2.1 carries a version number below 1 —
the convention by which a library says its interfaces are still moving. Its
release notes describe recent versions as development previews whose interfaces
may change without notice, and carry breaking changes between them. Its own
documentation states no stability level at all.

Epic 01 rejected the Kotlin MCP server library on this ground and took the mature
alternative instead. There is no mature alternative here: the connection's needs
are one text format and one web server, both of which the repository already
pins.

## Implications & recommendation

- **Lay the catalog out as one address every call goes to, with its operation
  named inside** (FIND4, FIND5) — it is the only layout in which a request naming
  an operation that does not exist comes back as a refusal rather than as the web
  server's empty answer, and it is the case the spec names explicitly. The
  address-per-operation layout costs that case outright and gains nothing the
  other lacks.
- **Let the reply name its own ending, rather than leaving it to the numeric code
  the web reply carries** (FIND4) — the reply says which of the three endings it
  is and the caller reads that, instead of inferring it from a number that has to
  serve two purposes at once. It is also what keeps "the item is not there" and
  "the address is not there" from arriving identically, and it leaves each adapter
  free to choose its own protocol's shapes, which is that epic's job and not this
  connection's.
- **Read the request inside the same attempt that runs it** (FIND5) — otherwise
  contents that cannot be read arrive as a fault, and the requirement that they
  arrive as a refusal fails on a detail of where a line of code sits. Worth a test
  of its own, because nothing about the code looks wrong when it is wrong.
- **Write the partial-update commands by hand, deciding each field by whether the
  request mentions it** (FIND1, FIND2) — the generated code cannot express the
  three states, and annotating the contract's own field throws on the clearing
  case. The hand-written form keeps all three, produces the plainer text, and
  leaves the contract's field exactly as the write path already uses it.
  Redesigning that field to suit the generator is the alternative, and it is a
  larger change for a worse result.
- **Put the text-conversion code in `:contract`, applying the serialization
  plugin from the convention plugin** (FIND11, FIND3) — a wire is what that module
  was waiting for, and the core and both adapters need the same one. Applying the
  plugin from the convention plugin is what avoids the double-load warning the
  module recorded; declaring it in the module reproduces that warning exactly.
  This retires a decision the module states about itself in a comment, so
  amending the comment is part of the change.
- **Compare every hand-written entity conversion against the whole entity, never
  a named subset** (FIND3) — a conversion missing a field compiles and drops that
  field in silence, and the spec's own criterion asks for whole-entity comparison
  precisely so that a field added later is covered without the test being edited.
- **Configure the calling library with a 30-second limit and install nothing that
  retries** (FIND6, FIND7) — both rules then hold as the client comes, and the
  counts on the receiving side are what prove it. The four failure shapes are
  already distinct enough for the library to report a breakdown that is never
  mistakable for a refusal.
- **Build nothing to protect a write from a caller that gives up** (FIND8) — 400
  runs say the write finishes on its own and lands whole, and the deliberate
  shield changed nothing. What guarantees it is the transaction the write path
  already opens, not anything the connection does; the corresponding criterion is
  a test to write, not a mechanism to build.
- **Hand the store's work to threads that may sit and wait, rather than doing it
  on the ones the web server answers on** (FIND9) — every result in FIND9 was
  taken that way and none was taken the other way, so this is the arrangement
  those numbers actually describe rather than a measured winner over its
  alternative. It is one call inside the handler, and worth naming because it
  looks like a detail: the store's work waits on the database, and the pool the
  server answers on is small.
- **Bind the core to the loopback address, and take both addresses from
  configuration** (FIND10) — the binding is demonstrably what turns a caller on
  another address away, with no credential involved, which is exactly what the
  spec claims its whole protection is.
- **Do not adopt kotlinx-rpc for the calling library** (FIND12) — its interfaces
  are still declared to be moving, on the same ground epic 01 used to reject an
  unfinished dependency for `:mcp-server`. What it would have saved is small here:
  the calling library is one class over a web client, and the epic builds both
  halves together anyway.

## Limitations

- **The two callers were two clients inside one program, not two adapter
  processes** — at risk: every concurrency result is stated of callers that shared
  a process, while the spec asks about two separate ones; nothing in the results
  depends on the sharing, but nothing here proves that either; would raise
  confidence: repeating the same-name, loop-half, and listing-during-write runs
  with two operating-system processes, which the epic can do once an adapter
  exists to be one. *(No longer a gap: the spec was amended afterwards to ask
  about several callers rather than several processes — see Q6.)*
- **The call "from another machine" was made from this machine** — at risk: the
  binding result is evidence about which addresses a bound server answers on, not
  about a genuinely remote caller; a network arrangement that forwards traffic
  onto the loopback would defeat it and this probe would not have noticed; would
  raise confidence: one call from a second machine on the same network, which
  takes minutes when a second machine is at hand.
- **The write that a caller abandoned was the probe's own, not the write path's**
  — at risk: it imitates the write path's shape — two statements, one transaction
  — but the real one also takes a row lock and runs its validation inside it,
  which this did not; would raise confidence: repeating the runs against a real
  `create_item` followed by a blocker-set update once both are reachable across
  the connection, which is this epic's own work.
- **Only the abandoned-write runs were taken on both web-server engines; every
  other finding is from one of them** — at risk: the layout, refusal, waiting, and
  concurrency results are stated of Ktor's CIO engine alone, and the epic has not
  chosen an engine; the one question that was asked of both got the same answer,
  which is weak evidence that the rest would; would raise confidence: re-running
  the layout and concurrency groups on Netty, an afternoon's work at most, or
  simply choosing the engine first and re-running once.
- **Timings are one machine, one shape, one size** — at risk: 38.6 milliseconds
  for 5,000 items says nothing about a project ten times larger, and the roughly
  threefold gap between crossing the connection and staying inside the core may
  not hold at other sizes; would raise confidence: repeating at several sizes if
  listing cost ever matters.
- **kotlinx-rpc was judged on its published record, not tried** — at risk: the
  recommendation against it rests on version numbers and release notes, so if its
  interfaces are in fact settled the epic passes up a real simplification; would
  raise confidence: a small prototype against it, worth building only if the
  hand-written calling library turns out to be larger than expected.
- **A process started without its address was not probed** — at risk: the
  requirement that such a process stop and name the missing setting is left
  entirely to the epic's own code, with no evidence here that anything makes it
  easy or hard; would raise confidence: nothing worth spending — it is ordinary
  startup code, and a test of it is cheaper than a probe of it.
- **Nothing was measured under load** — at risk: every concurrency result is from
  two callers doing one thing each, and the recommendation to run store work on
  threads that may block says nothing about how many such threads a real workload
  needs; would raise confidence: a load probe against the built connection.

## Open questions

**Follow-ups:**

- **Q6 (closed)** — Do the results about several callers at once hold when the
  two callers are two operating-system processes?; asked because three of the
  spec's acceptance criteria named two adapter processes outright. **Closed by
  amending the spec rather than by running it.** The core cannot tell whether
  two callers share a program, so keeping them apart is not a property the
  connection has to get right; what genuinely differs between the two adapters
  is how each turns its own protocol into a call, and that is tested where each
  is built. The criteria now ask for several callers, which this report already
  answers.
- **Q7** — Does the connection hold up under real multi-agent load?; matters
  because: the thread arrangement everything else depends on was only shown
  correct at two callers, never shown cheap; would take: a load probe against the
  built connection when there is multi-agent usage worth imitating. (The write and
  read paths each left the same question behind; this is the third instance of it,
  and one probe against the assembled system would answer all three. All three
  stalled on the same thing — there is no real workload yet to imitate, and a
  load probe against an invented one measures the invention.)
- **Q8** — At what project size does a listing stop arriving inside the wait
  limit?; matters because: the spec assumes projects stay small enough that
  returning every match in one go is fine, and 5,000 items at 38.6 milliseconds
  says the ceiling is far away but not where it is; would take: timing listings
  against progressively larger seeded projects, once a real project suggests a
  size worth testing.
