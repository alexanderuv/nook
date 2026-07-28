# Epic 06 — MCP server

**Addresses:** REQ6 (streamable HTTP at `/mcp/{projectRef}`, project bound per
connection and reported to the client when the connection opens, the catalog's
seven project-scoped operations as tools, UUID-or-slug references, structured
errors).

Documents, in the order they were produced:

- [spec-4.md](./spec-4.md) — the requirements contract. Its load-bearing
  decision: **projects are not on this surface.** An agent cannot create, read,
  list, or delete a project — those belong to the human surface — so the seven
  operations that act on items and releases are the whole tool set, and none of
  them names a project. Which project applies comes from the address the
  connection was opened at, and the server reports it to the client so the agent
  can say where it is without a tool for it.
- [discovery.md](./discovery.md) — thirteen probe groups against the pinned
  protocol library, an embedded web container, and two clients — one of them the
  protocol's own Inspector, which knows nothing of Nook — settling how the server
  gets built: that a servlet-hosted transport and Ktor coexist without contest,
  that `/mcp/{projectRef}` means one protocol server per project, where the
  connection's announcement rides, and which two of spec-4's requirements the
  library cannot satisfy as written — both since amended in the spec, each
  carrying a note on what it now asks for and why.

- [plan.md](./plan.md) — the build route, steps ticked as execution proceeds. Its
  three decisions taken before the steps: everything an agent is told about a
  tool comes from `:contract`, so this module holds no list of arguments and no
  words of its own; the core's refusals name what they could not find, which is
  what lets a connection stop when its project is the thing that disappeared; and
  the whole epic is checked against a stand-in core, needing no database and
  starting no other program.

Two of spec-4's twenty-six acceptance criteria are deliberately not met here —
the milestone loop run over this surface, and epics 03 and 04's own criteria
re-run through the tools. Both need the real core with a real store behind it,
which means starting every piece at once; they belong to
[epic 09](../09-full-system-test/), together with the discovery's own limitation
that what these tools reached was a stand-in rather than the core.

## Results

`:mcp-server` became a real module. It holds a dispatcher that decides which
project a request is for, one protocol server per project, a derivation of a
tool's arguments from a payload shape, the mapping of the three endings, a web
container to host them, and a program to start it all. `ktor-server-core` left
the module: the protocol library ships its transport as a servlet, which Jetty
runs unchanged, and nothing here serves with Ktor. `Main.kt` stopped being a
placeholder.

**Nothing an agent is told is written down in this module.** `:contract` gained
a description annotation that survives into a shape's runtime description, a
description on every payload field and on every operation, and `projectOperations`
— the seven project-scoped entries as a public view carrying a name, a
description, an argument shape, and a way to run one. The seven fall out of the
wiring table by type rather than by a list, so this module cannot fall behind a
twelfth operation and has nowhere to add an eighth tool. Everything a client
reads — the tool names, the arguments, which may be left out, whether each takes
text or a list or nothing, and the words on each — is derived from that.

Filling in the two hand-written conversions' declarations turned up the one
thing that could have gone wrong quietly. The parent filter's conversion writes
JSON null for "no epic above it" and always has; its declaration claimed text
alone. A schema derived mechanically from that declaration would have forbidden
the one value that part of the filter exists for. The declaration now says text
or nothing, and epic 05's round-trip checks passed unedited throughout, which is
what says the encoding did not move.

Two things are deliberately absent from a tool's arguments. There is no closed
list of allowed values for the vocabularies — a list here would have the protocol
library turn a mistyped status back in its own wording before the core saw it,
so the same call would reach two different verdicts depending on which door it
came in by, and the agent would lose the refusal that spells out the words it
could have written. And there is no default for anything: a field the caller left
out arrives left out, which is the whole of what keeps "leave this alone" apart
from "set this to nothing". What is present is the marker refusing any argument
the shape does not define.

**A connection's project is settled at the door and never again.** One protocol
server per project, because what a connection is told on opening is a single
piece of text held on the server — a shared one could say something true of every
project or one project's own words, never both. The core is asked which project
an address names exactly once, when a connection opens; every request after that
carries a session and is routed without the core being asked anything. A refusal
from the core is answered as an address that names no project, repeating the
words that were asked for; anything else is the core not answering at all and is
never reported as a missing project. Both bodies are written straight onto the
response, because the container's own page for a refusal escapes them into
markup and a placeholder nobody filled in would come back looking like nothing
anybody wrote.

A connection can outlive its project, so the core's answer to an ordinary call is
what reports the disappearance. That needed the refusal to say *which* of the
things a call named was missing: `:contract` gained `Missing`, and
`:core-service`'s four "not found" sites now say `project`, `item` or `release`
in the details a refusal already carries. A refusal naming the project closes
that project's server — ending every connection held against it — while a
refusal naming an item leaves the connection serving. The closing happens at the
door on the next request rather than inside the call that found out, so the
agent still receives the refusal that told it.

The address a connection was opened at is never what a call is made with. Every
call carries the project's id, resolved once: a handle is freed when its project
is deleted and can later be given to a different project, and an agent holding
the words would begin working somewhere nobody sent it.

### Rule-to-test mapping

Every acceptance criterion of [spec-4](./spec-4.md), as the named test that
executes it (tests carry no criterion numbers by design — code never cites
planning artifacts):

| Criterion | Test |
| --- | --- |
| AC1 | `OfferedToolsTest` — the listing holds exactly the seven under the names the contract gives them; no tool names a project; nothing but a tool reaches a Nook operation, resources, prompts and completions all being absent and the protocol's own logging channel reaching nothing |
| AC2 | `OfferedToolsTest` — each tool takes exactly its operation's arguments with the project left out, and every tool and every argument says what it is for; `ShapeDescriptionTest` — the same held at the source, where every one of the seven shapes names its fields and each conversion accepts exactly the fields it declares |
| AC3 | `ConnectedProjectTest` — a connection at a project's handle and one at its id are told the same four things about it, each project's connections are told their own and nothing of the other's, and naming an entity of another project leaves the connection where it was |
| AC4 | `ConnectedProjectTest` — every call reaches the core naming its own connection's project, by id, including a listing and including a reference to another project's item |
| AC5 | `RefusedConnectionTest` — a mistyped project, an address naming no project at all, and an address carrying more than a project reference are each refused in the opening exchange, naming what was asked for, and no refused connection reaches the core |
| AC6 | `RefusedConnectionTest` — an ordinary client aimed at an address naming no project never opens, and a call attempted on it anyway is not served |
| AC7 | `RefusedConnectionTest` — a core that cannot be reached says so, and never that the project is missing |
| AC8 | `VanishedProjectTest` — a refusal saying the project is gone ends the connection after telling the agent why, every connection to that project ends rather than only the one that found out, and a connection opened afterwards at the same address is refused naming the project; `ReferenceResolutionTest` and `WriteLockTest` in the core — a refusal says which of the three could not be found |
| AC9 | `VanishedProjectTest` — a refusal about an item leaves the connection serving |
| AC10 | `VanishedProjectTest` — a handle given to a new project never carries the old connection's calls |
| AC11 | `FaithfulCrossingTest` — a name carrying emoji and non-Latin script, a description carrying line breaks and quotation marks, a blocker list keeping its duplicate, and a reference that is nearly an id all reach the core as written |
| AC12 | `DerivedToolTest` — a field left unmentioned, a field set, and a field cleared arrive as three different things; `FaithfulCrossingTest` — an update naming no field at all reaches the core naming no field |
| AC13 | `FaithfulCrossingTest` — each part of the listing filter reaches the core on its own, all five reach it together, a part supplied with no values reaches it with no values, and a blocker list supplied empty arrives empty |
| AC14 | `CallEndingTest` — an answer carries what the core produced, whole, for both entity kinds an agent can be handed, compared as the whole thing; a deletion succeeds carrying no entity and is not mistakable for a refusal |
| AC15 | `ManyAtOnceTest` — a listing of five thousand items arrives whole, in order, inside the wait limit |
| AC16 | `CallEndingTest` — each refusal the core makes arrives as a failed call carrying its own code, message and details |
| AC17 | `CallEndingTest` — a fault inside the core arrives as a breakdown carrying none of the four refusal codes, and the next call on the connection is served normally |
| AC18 | `DerivedToolTest` — arguments that do not fit the tool are turned back naming the one at fault, whether it is one the tool does not define, one it requires and is missing, or one holding the wrong kind; `CallEndingTest` — a tool the server does not offer, `create_project` among them, comes back naming the tool that was asked for, told apart from a fault by its number |
| AC19 | `CallEndingTest` — exactly one request reaches the core after every ending: each of the four refusals, a breakdown, a deletion and an answer. The two endings that belong to the connection rather than to a call — a dropped link and the wait limit running out — are the calling library's, and epic 05's `CatalogWaitingTest` holds them |
| AC20 | `ToolProgramTest` — a core that goes away and comes back leaves the connection usable, with the agent never reconnecting and nothing restarted |
| AC21 | `ManyAtOnceTest` — three connections, two on one project and one on another, call at once with one call held open, and neither fast call waits on it or sees another connection's project. Two agents creating the same name at once is the store's own arbitration, held by epic 05's `WriteServiceSlugRaceAcrossConnectionTest` |
| AC22 | `DerivedToolTest` and `OfferedToolsTest` — an unmodified client completes the handshake, lists the tools and calls one; `ManyAtOnceTest` — a tool call sent before the handshake is not served and reaches nothing; `OfferedToolsTest` — a client asking for resources is offered none |
| AC23 | `ToolProgramTest` — a front door bound to loopback answers there and nowhere else this machine can be reached, with no credential asked for and none presented; `RefusedConnectionTest` — an opening exchange a web page sent is turned away |
| AC24 | `ToolProgramTest` — told both addresses, a client connects and calls a tool against the core; a program started without a setting stops and names the one it is missing |
| AC25 | Epic 09 — the milestone's loop needs the real core over a real store |
| AC26 | Epic 09 — epics 03 and 04's criteria re-run through the tools need the same |

AC23's test earns its verdict the way epic 05's did. An address that cannot reach
a front door listening on every address proves nothing about what the loopback
binding refuses — the network turned the call away, not the binding. So it first
stands up a door bound to everything and establishes which of this machine's
addresses reach it at all; only those are then tried against the loopback-bound
door, and on a machine with no such address it stops and says so instead of
passing emptily. The binding is not the whole of the protection, though: a page
open in a browser on this machine reaches the loopback address as readily as an
agent does, so the transport's origin check is turned on — anything carrying a
page's origin is refused, and a request carrying none, which is every request an
agent's client makes, is served.

The supporting checks: `ShapeDescriptionTest` in `:contract` (every payload
shape names its fields, every field and every operation carries a description
that is not empty, each vocabulary is spelled out in full somewhere a caller
reads it, and each hand-written conversion accepts exactly the fields it
declares), and `ConnectedProjectTest`'s count of what the core was asked — the
address is resolved once per connection, not once per call, so a mistyped one is
discovered at the door rather than again on every call.

Everything above runs against a stand-in core: forty-six checks in this module,
none of them needing a database, and `checkPersistenceBoundary` still passes for
`:mcp-server` now that it carries Jetty and the protocol library. Two of them
reach the stand-in over a real connection rather than in process, because the
program an operator starts and a core that goes away and comes back cannot be
shown any other way.
