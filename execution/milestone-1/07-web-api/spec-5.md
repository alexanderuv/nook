# Web API — Spec-5

## Overview & scope

This spec is the requirements contract for **the human surface's front door**: the
app that people and their own programs reach Nook through. Where the agent surface
is a translation — the shape of the Model Context Protocol is set by someone
else's specification — this one is deliberately not. The web app **serves the
core's own request and reply shape outward** rather than a shape of its own
([01 — Interface contracts](../../../docs/01-interface-contracts.md),
[ARCHITECTURE Appendix A, "Wire shape"](../../../ARCHITECTURE.md)), so there is
one contract to learn and one place a field changes. [PRD-1](../prd-1.md) REQ7
asks for exactly that: one address, the operation and the project named inside the
request, and the reply naming its own ending.

A caller sends one request to one address, naming the operation it wants and, for
the seven operations that act inside a project, naming the project. The app owns
no store of its own: it hands each call to the core through the one calling
library epic 05 built ([spec-3](../05-operation-catalog/spec-3.md)) and hands the
core's reply back.

**All eleven operations are served here, and four of them are served nowhere
else.** `create_project`, `get_project`, `list_projects` and `delete_project` are
not on the agent surface at all — a project is made and disposed of by a person,
and an agent is handed one to work in ([spec-4](../06-mcp-server/spec-4.md)). So
this is the surface on which a project comes into existence, which is what makes
the agent surface's own assumption true: there is always an existing project to
point an agent at.

**Nothing here decides whether a request is any good.** Reading a request — what
its fields are called, which of them an operation requires, which may be left out,
what kind of value each takes — is settled once in the shared contract library
that the core's own connection reads requests with, and every other verdict is the
core's. The web app writes no rule of its own about what a request contains. A
check that is genuinely about this surface and no other would belong here, and in
this milestone there is none.

**Security is deferred wholesale, by decision, while this is a proof of concept.**
The app asks for no credential, and it does not care which program or which page
in a browser a request came from: a request naming a page is served exactly as one
naming nothing. What that leaves open is stated plainly rather than glossed — any
page a person happens to have open in a browser on this machine can call this
address and delete a project. The one thing that does stand is the deployment shape
the design already settled: the app answers on the loopback address (`127.0.0.1`), which nothing outside
the machine can route to
([ARCHITECTURE §8](../../../ARCHITECTURE.md)). Sign-in, an access gate, HTTPS, and
any restriction on where a request came from arrive together in a later phase
([08 — Deployment & cloud](../../../docs/08-deployment-and-cloud.md)).

In scope: which operations this surface serves and at what address; that what it
serves is the core's own shape rather than a second one; what must reach the core
untouched; the three ways a call can end and how a caller tells them apart; who
may reach the app; several callers at once; and what the program is told from
outside before it starts.

Out of scope:

- **The rules the operations enforce** — containment, slugs, the status
  vocabulary, cycles, filtering, ordering, what a delete reaches. Those are
  [spec-1](../03-core-write-path/spec-1.md) and
  [spec-2](../04-structure-queries/spec-2.md); this spec requires only that a
  request arrives at them intact and their verdict arrives back intact.
- **The connection to the core** — how a call crosses to the core and what must
  survive that crossing is [spec-3](../05-operation-catalog/spec-3.md). This spec
  sits on top of it and repeats none of it, including the rule that neither app in
  front of the core holds any database access, the thirty seconds a call waits, and
  the promise that a caller who stops waiting leaves no half-applied change behind.
- **The agent surface** — epic 06. Which operations become tools there, how a
  connection names its project, and how a tool result is shaped are
  [spec-4](../06-mcp-server/spec-4.md)'s business.
- **Sign-in, credentials, and any check of where a request came from** — deferred
  by the decision recorded above and by the design itself
  ([ARCHITECTURE §8](../../../ARCHITECTURE.md),
  [08](../../../docs/08-deployment-and-cloud.md)). HTTPS belongs to the same later
  phase.
- **Who the caller is** — nothing here carries a caller's identity in this
  milestone; the fields that would record it arrive with epic 08.
- **The user interface** — the screens, and how their files are built and served by
  this same app, are milestone 4 ([06 — Web UI](../../../docs/06-web-ui.md)). Until
  then this app serves the operations and nothing else.
- **Extra reads for that interface** — the design leaves room for a few plain reads
  added for the interface's convenience, as ordinary operations in the same shape
  ([01](../../../docs/01-interface-contracts.md)). None exists yet, and this
  milestone adds none.
- **Documents** — milestone 2. No document operation exists yet to serve.
- **Paging, free-text search, and sort options** — deferred by the design docs. A
  listing arrives whole, in one ordering.
- **Running the program** — packaging, an address the app could answer "yes, I am
  alive" on, the local database stack, and everything else about deployment belong
  to [05](../../../docs/05-project-and-ops.md). This spec requires only that the two
  addresses involved come from outside the program.
- **The assembled run** — the milestone's north-star loop, and the check that one
  request reaches the same verdict whichever door it comes in by, need the store,
  the core, and both front doors running at once. They belong to
  [epic 09](../09-full-system-test/), which already records this surface's share of
  them; this epic's own checks run against a stand-in core, needing no database and
  starting no other program, on the same bargain epic 06 took.

## Scenarios

### SCEN1 — A person starts a project, and hands it to an agent

**Initiator:** a person setting up work, through a program of their own.
**Flow:**
1. They call `create_project`, naming the project and what it is for.
2. They call `list_projects` and see it there.
3. They configure a coding agent's client with that project's address on the agent
   surface, and the agent starts a session.
**Outcome:** the project exists, created on the only surface that offers creating
one, and the agent has something real to be pointed at. Nothing in the agent's
session needed a person to name the project again.

### SCEN2 — A program builds out a project through this surface alone

**Initiator:** a program acting for a person — the interface that arrives in
milestone 4, or a command-line tool today.
**Flow:**
1. It creates a release, an epic, two tasks under that epic, and a project-level
   bug.
2. It calls the update operation twice — once to put the epic in the release, once
   to make the second task wait on the first.
3. It asks for the leaf types — task, bug and chore — with status `todo` and
   nothing unfinished holding them up.
4. It files a second bug by mistake and deletes it.
**Outcome:** every create hands back the whole entity the core made, slug and
timestamps included; the listing holds exactly the open leaves nothing is holding
up, in the order the core produced them; and the delete reports success and hands
back no entity, which the caller can still tell apart from an error.

### SCEN3 — The same request, sent to two doors

**Initiator:** whoever is checking that there is one contract and not two.
**Flow:**
1. A request is written once — an operation named inside it, a project named inside
   it, its arguments alongside.
2. That same request is sent to the core's own connection.
3. The very same request is sent to the web API.
**Outcome:** both are accepted, and what comes back says the same thing in the same
words: the same entity field for field, or the same error carrying the same code,
message and details. Nothing had to be rewritten between the two sends, because
there is one shape and this surface serves it.

### SCEN4 — An error reaches the caller as something to fix

**Initiator:** a program with a wrong request.
**Flow:**
1. It asks to make two items wait on each other in a circle; the core refuses it as
   a loop.
2. It asks for a slug another item in the project already holds.
3. It asks for an item that does not exist.
4. It asks for a status that is not in the vocabulary.
**Outcome:** each comes back marked as an error carrying the core's own code, its
message and its details — enough for the caller to see what to change without a
person reading a log. The numeric code the reply arrives under is the same in all
four cases and in every success, so nothing about the ending is read off it.
Nothing was written in any of the four cases.

### SCEN5 — The core is not there, and then is

**Initiator:** a program calling on a machine where the core has not started.
**Flow:**
1. It makes a call while nothing is listening for the core.
2. The core is started, and it makes the same call again.
3. The core is stopped in the middle of a later call, then started again, and the
   call is made once more.
**Outcome:** the first call comes back saying the work could not be attempted
because the core could not be reached — not that the request was wrong, which is a
different problem with a different fix. The second succeeds. The interrupted call
comes back the same way, and the one after it succeeds, with nothing restarted and
no caller rebuilt.

### SCEN6 — A request the surface cannot read

**Initiator:** a program with a defect, or a person typing a request by hand.
**Flow:**
1. It names an operation nobody defined.
2. It carries a field the operation does not define, and another request leaves out
   a field the operation requires.
3. It names a project on an operation that acts on the whole instance, and another
   names no project on one that acts inside a project.
4. It sends contents that cannot be read as the format at all.
**Outcome:** every one comes back as an error saying what was wrong with it and
naming the operation, the field or the missing project — never as the web server's
own empty answer, and never as an answer. Nothing reached the store in any of them,
and the next well-formed call is served normally.

### SCEN7 — A person deletes a project while an agent is working in it

**Initiator:** a person, on this surface.
**Flow:**
1. An agent has been working in a project for an hour on the agent surface.
2. The person calls `delete_project` here.
3. The agent calls a tool.
**Outcome:** the project and everything inside it are gone, reported as a success
carrying no entity. The agent's next call finds the project gone and its connection
stops there, which is the agent surface's own behavior
([spec-4](../06-mcp-server/spec-4.md)) — this surface's part is that the deletion
happens here and nowhere else.

### SCEN8 — An edit that clears a field, and an edit that leaves it alone

**Initiator:** a program saving a form's contents.
**Flow:**
1. The item has a description. The program sends an update changing only the name.
2. It then sends an update that clears the description.
3. It then sends an update naming no field at all.
**Outcome:** the first leaves the description as it was, the second empties it, the
third changes nothing and is not refused — three outcomes the caller can tell
apart, because "say nothing about this field" and "set this field to nothing" are
two different things all the way down to the store.

### SCEN9 — Two callers at once, and one that walks away

**Initiator:** two people's programs, and a third that stops listening.
**Flow:**
1. Both create an item of the same name at the same moment, and both then list the
   project.
2. A third caller sends a create and goes away before the answer arrives.
3. Someone lists the project afterwards.
**Outcome:** both creates succeed with different slugs, neither caller's call
waited on the other's, and the listing afterwards is what says whether the
abandoned write landed — it landed whole, or it is not there at all, never half
of it.

### SCEN10 — Something else on the network tries the address

**Initiator:** any program on another machine.
**Flow:**
1. It sends a well-formed `delete_project` call to this app's address from a
   different machine on the network.
**Outcome:** it is not served. Nothing is deleted, and no credential was needed to
keep it out, because the app is not reachable from where it called.

### SCEN11 — Bringing the app up

**Initiator:** an operator starting the milestone's programs.
**Flow:**
1. The app is started, told from outside which address to answer on and where the
   core is.
2. A call passes end to end from a program to the core and back.
3. A second copy is started with no core address configured.
**Outcome:** the first comes up and serves; the second stops immediately and names
the setting it is missing, rather than starting up and calling an address nobody
chose.

## Requirements

### What the surface serves

- **REQ1** — The app MUST serve exactly these eleven operations, under these names:
  `create_project`, `get_project`, `list_projects`, `delete_project`,
  `create_item`, `update_item`, `delete_item`, `create_release`, `update_release`,
  `get_item`, `list_items`.
- **REQ2** — The app MUST serve no twelfth operation, and no argument beyond the
  ones those eleven already define.
- **REQ3** — The app MUST serve all eleven at one address, `/api`. Nothing about
  the address MUST say which operation a call is for, and no operation MUST have an
  address of its own. (The root address is left free for the interface that arrives
  in milestone 4.)
- **REQ4** — Which operation a call is for MUST be named inside the request.
- **REQ5** — For each of the seven operations that act inside a project, the
  project MUST be named inside the request, and the address MUST carry no project.
- **REQ6** — The four operations that act on the whole instance MUST name no
  project, and MUST be served here — this being the only surface in the milestone
  that offers them at all.
- **REQ7** — The request a caller sends MUST be the request the core's own
  connection accepts: the same fields under the same names, nothing added, nothing
  renamed, nothing dropped.
- **REQ8** — The reply a caller receives MUST be the core's own reply, on the same
  terms.

### Where a verdict comes from

- **REQ9** — The app MUST apply no rule of its own to what a request contains:
  every acceptance and every error MUST come either from the shared reading of
  the request that the core's own connection uses, or from the core's verdict.
- **REQ10** — For any request, the ending this surface reports MUST be the ending
  the core's own connection reports for that same request — the same entity, the
  same code, message and details, and the same change to the store.
- **REQ11** — A request this surface cannot read MUST NOT reach the store.
- **REQ12** — Such a request MUST come back as an error carrying
  `validation_failed`, and MUST name what was wrong with it: an operation nobody
  defined by the name that was asked for, a field the operation does not define by
  that field's name, a required argument that is missing by its name, and a value
  of the wrong kind by the field carrying it.
- **REQ13** — A request naming a project on an operation that acts on the whole
  instance, and one naming no project on an operation that acts inside a project,
  MUST each be refused with `validation_failed`.

### What reaches the core untouched

- **REQ14** — The app MUST NOT alter what a caller supplies: nothing trimmed,
  lowercased, reordered, filled in, deduplicated, or dropped, and no argument the
  caller left out supplied on its behalf.
- **REQ15** — For every field of an update that allows both, a caller MUST be able
  to say "leave this field alone" and "set this field to nothing", and the two MUST
  reach the core as different things.
- **REQ16** — For each part of the listing filter, "do not filter on this part" and
  "filter on no values at all" MUST reach the core as different things — the first
  means every item matches, the second is a mistake the core refuses.
- **REQ17** — A reference naming an entity MUST reach the core as the caller wrote
  it, whether it is an id, a slug, or neither; what it means is the core's
  decision.
- **REQ18** — An answer MUST carry what the core produced, whole: every field of an
  entity holding the value the core gave it, and a listing holding every item the
  core matched, in the order the core put them in.
- **REQ19** — `delete_item` and `delete_project` MUST report success carrying no
  entity, and that success MUST be distinguishable from an error.

### How a call ends

- **REQ20** — Every call MUST end in exactly one of the two ways JSON-RPC 2.0
  defines ([ADR-2](../../../architecture/adrs/adr-2.md)): a `result`, or an
  `error` object naming its own code.
- **REQ21** — Every reply the app produces MUST come back under the same HTTP
  status, so that a caller reads the ending from the reply and never from the
  number.
- **REQ22** — An error the core produced MUST reach the caller carrying the
  core's code, its message, and its `data`, unchanged — including `data.reason`,
  which names the domain failure.
- **REQ23** — A call that produced no verdict MUST arrive as `-32603` and MUST
  carry none of the domain reasons — there is nothing for the caller to fix, so
  it must not read as though there were.
- **REQ24** — *Struck by [ADR-4](../../../architecture/adrs/adr-4.md).* This
  requirement asked an internal error to say whether
  the core had answered at all — that it could not be reached, or that it
  answered and broke. Which half of Nook failed is not an outside caller's
  business: naming a part behind the surface makes that part a promise, and the
  caller can do nothing differently for knowing. The client still tells the two
  apart for its own recovery ([spec-3](../05-operation-catalog/spec-3.md)
  REQ15); the app MUST NOT pass the distinction outward, in a field or in the
  wording of a message.
- **REQ25** — The app MUST NOT send a call to the core a second time on a caller's
  behalf, after any ending whatsoever.
- **REQ26** — An error or an internal error MUST leave the app serving: the next call MUST
  be served normally.
- **REQ27** — When a caller stops listening before its answer arrives, the app MUST
  leave no half-applied change behind: the work the core has begun runs to its own
  conclusion, and a later read is what says whether it landed.

### Several callers at once

- **REQ28** — The app MUST serve several callers at the same time, and MUST NOT
  make one caller's call wait on an unrelated call of another.
- **REQ29** — When the core becomes reachable again after being unreachable, the
  app MUST become usable again on its own: a later call MUST succeed without the
  app being restarted.

### Where the app listens, and who may reach it

- **REQ30** — The app MUST serve only callers on the machine it runs on; a call
  arriving from any other machine MUST NOT be served.
- **REQ31** — The app MUST require no credential in this milestone: a caller
  presents none and the app checks for none. REQ30 is the whole of its protection.
- **REQ32** — The app MUST NOT turn a call down on account of where it came from,
  beyond REQ30. A browser attaches to every request it sends the address of the page
  that sent it; a request carrying one MUST be served exactly as one carrying none.
  Any restriction of that kind belongs with sign-in, in a later phase, so this
  milestone builds no half of it.
- **REQ33** — The address the app answers on, and the address of the core it calls,
  MUST be settable from outside the program rather than fixed in its code.
- **REQ34** — Started without either of those addresses, the app MUST stop with a
  message naming what is missing, rather than starting on a default address nobody
  chose.

## Edge cases

- **EDGE1** — A request naming an operation nobody defined: an error carrying
  `validation_failed` and naming the operation that was asked for, never the web
  server's own empty answer for an address nobody defined.
- **EDGE2** — A request carrying a field its operation does not define: an error
  naming that field. An unknown field is a defect to surface, not a difference to
  absorb — ignoring one would silently drop something a caller meant.
- **EDGE3** — A request missing an argument its operation requires: an error
  naming that argument.
- **EDGE4** — A request whose field holds a value of the wrong kind — a number
  where text belongs, text where a list belongs: an error naming that field.
- **EDGE5** — A request whose contents cannot be read as the format at all: a
  error carrying `validation_failed`, not an internal error.
- **EDGE6** — A request naming a project that does not exist: the core's own
  `not_found`, reaching the caller unchanged.
- **EDGE7** — A request naming a project, and an item that belongs to a different
  project: `not_found` — the item is real, but not in the project the request
  named.
- **EDGE8** — A reference that looks like an id but is not a well-formed one:
  reaches the core as written, and the core decides what it means.
- **EDGE9** — A name or description carrying emoji, non-Latin script, line breaks,
  or quotation marks: stored exactly as the caller sent it, and read back the same.
- **EDGE10** — A description set to empty text, against one left unmentioned: the
  first empties the field, the second leaves it alone.
- **EDGE11** — An update naming no field at all: reaches the core naming no field,
  and comes back as the core's own do-nothing answer rather than being refused or
  discarded before it is sent.
- **EDGE12** — A blocker list supplied empty: reaches the core empty and clears the
  set, never mistaken for no list having been supplied.
- **EDGE13** — A filter part supplied with no values: reaches the core that way and
  is refused with `validation_failed`, never quietly turned into "do not filter on
  this part".
- **EDGE14** — A listing of 5,000 items: arrives whole, in order, in one reply,
  inside the timeout.
- **EDGE15** — The core is not running when a call is made: `-32603`, saying no
  more than that the call produced no verdict, and a call made after the core
  comes up succeeds without the app being restarted.
- **EDGE16** — The core is stopped in the middle of a call: `-32603`, carrying
  no domain reason.
- **EDGE17** — A defect inside the core: `-32603`, carrying none of the domain
  reasons, and the next call is served normally. Indistinguishable from EDGE15
  and EDGE16 to the caller, deliberately (REQ24).
- **EDGE18** — The core is silent and answers nothing at all: `-32603` when the wait
  limit runs out, and the request reached the core exactly once.
- **EDGE19** — A caller that goes away while its write is being carried out: the
  write stands whole, and a later read shows it — never a row missing what the same
  write was to give it.
- **EDGE20** — Two callers creating an item of the same name at the same moment:
  both succeed, with different slugs.
- **EDGE21** — A request sent to any other address on this app, or one asking to
  read `/api` rather than sending it a request: not an answer and not Nook's error
  — it is the web server's own reply, carrying no JSON-RPC error at all. No caller
  this surface is built for sends one, and REQ3's single address is what keeps that
  case away from every request that names an operation.
- **EDGE22** — A well-formed call arriving from another machine: not served.
- **EDGE23** — A call arriving from a page open in a browser on this machine: served,
  like any other local caller. This is the accepted consequence of deferring
  sign-in and every check of where a request came from (REQ32), and it is what
  makes revisiting that decision a condition of this surface reaching real users.
- **EDGE24** — The app is started with the core's address unset: it stops, naming
  the missing setting.

## Acceptance criteria

- **AC1** (REQ1, REQ2, REQ3, REQ4) — Given a running app, when each of the eleven
  operations of REQ1 is called by name at `/api`, then each is served; when a
  twelfth name is called, then it is refused; and when the app's addresses are
  examined, then `/api` is the only one that serves an operation and no operation
  has an address of its own.
- **AC2** (REQ5, REQ6, REQ13) — Given the eleven operations, when each is called,
  then the seven that act inside a project require the project to be named inside
  the request and the four that act on the whole instance offer nowhere to name
  one; and when a project is named on one of those four, or left unnamed on one of
  the seven, then each fails with `validation_failed`.
- **AC3** (REQ7, REQ8, REQ10) — Given one request written once — an operation and
  a project named inside it, its arguments alongside — when the identical request
  is sent to the core's own connection and to `/api`, for a create, a read, a
  listing, a delete, and one request producing each of the four domain reasons, then
  the two replies are equal: the same entity compared as a whole value, or the same
  code, message and details.
- **AC4** (REQ9, REQ11, REQ12, EDGE1, EDGE2, EDGE3, EDGE4, EDGE5) — Given a
  request naming an operation nobody defined, one carrying a field its operation
  does not define, one missing a required argument, one holding a value of the
  wrong kind, and one whose contents cannot be read at all, when each is sent,
  then each comes back as an error carrying `validation_failed` and naming what
  was wrong, the store is unchanged, and the next well-formed call is served
  normally.
- **AC5** (REQ14, REQ17, EDGE8, EDGE9) — Given calls carrying a name with emoji and
  non-Latin script, a description with line breaks and quotation marks, a blocker
  list holding the same reference twice, and a reference that is nearly but not
  quite a well-formed id, when each is sent, then the values the client is
  invoked with equal the values the caller supplied, one for one — same text, same
  reference, and the blocker list still holding its duplicate — and the stored
  item's name and description are the text that was sent.
- **AC6** (REQ15, EDGE10, EDGE11) — Given a task with a description, when an update
  changes only its name, then the description is unchanged; when a later update
  sets the description to nothing, then it is empty; and when a later update names
  no field, then the call succeeds with nothing changed.
- **AC7** (REQ16, EDGE12, EDGE13) — Given a project holding items of several types,
  some held up and some not, when `list_items` is called with each filter part
  alone and with all five together, then each answer matches the same filter called
  directly on the core; when it is called with a type part holding no values, then
  it fails with `validation_failed`; and when `update_item` is called with an empty
  blocker list, then the item's blocker set is empty afterwards.
- **AC8** (REQ18, REQ19, EDGE6, EDGE7) — Given a project, an item under an epic
  with two blockers, and a release with a target date, when each is read through
  this surface, then each entity equals the entity the core produced, field for
  field — compared as the whole entity, not a named subset, so a field added later
  is covered without this check being edited; when `delete_item` and
  `delete_project` are called, then both report success, neither carries an entity,
  and both are distinguishable from an error; and when a project that does not
  exist, and an item belonging to another project, are asked for, then each fails
  with `not_found`.
- **AC9** (REQ18, EDGE14) — Given a project holding 5,000 items, when it is listed
  through this surface, then all 5,000 arrive in one reply, in the same order the
  core produced, inside the timeout.
- **AC10** (REQ20, REQ21, REQ22) — Given calls producing each of the four error
  codes in turn, and calls that succeed, when each is made, then every reply names
  its own ending, every error carries the core's own code, message and details,
  and every reply — success and error alike — comes back under the same numeric
  code.
- **AC11** (REQ20, REQ23, REQ26, EDGE17) — Given a defect deliberately planted
  inside the core, when a call hits it, then the caller receives `-32603`
  carrying no domain reason, told apart from every error the caller could fix;
  and the next call is served normally.
- **AC12** (REQ23, REQ24, REQ29, EDGE15, EDGE16) — Given the core not running,
  when a call is made, then it comes back as `-32603`; and when that reply is
  compared with AC11's, then the two are indistinguishable — neither a field nor
  the message says which half failed; when the core is then started and the same
  call is made, then it succeeds; and when the core is stopped mid-call and
  started again, then that call is `-32603` and the next call succeeds — with
  nothing restarted and no caller rebuilt.
- **AC13** (REQ25, EDGE18) — Given a core made to answer nothing at all, and calls
  that end in each of the four domain reasons, in a dropped connection, and in the
  timeout running out, when each ends, then the core received that request exactly
  once, and the one held past the limit came back as an internal error.
- **AC14** (REQ27, EDGE19) — Given a caller that stops listening while the core is
  still writing an item and its blocker edges (repeated 100 times), when the project
  is read afterwards, then in every run the item is either fully there with the
  edges it was written with, or not there at all.
- **AC15** (REQ28, EDGE20) — Given two callers calling at once, when both create an
  item of the same name (repeated 100 times) and one call is deliberately made slow
  while another is fast, then every run leaves two items with different slugs,
  and the fast call is answered before the slow one.
- **AC16** (REQ30, REQ31, REQ32, EDGE22, EDGE23) — Given a running app, when a
  well-formed call is made from the same machine with no credential, then it is
  served; when the same call carries the address of a browser page as its sender,
  then it is served identically; and when it is made to the app's address from
  another machine, then it is not served and the store is unchanged.
- **AC17** (REQ33, REQ34, EDGE24) — Given an app configured with both addresses
  from outside the program, when it starts and a call is made, then it succeeds;
  and when it is started with either address unset, then it stops with a message
  naming the missing setting rather than starting on a default.
- **AC18** (REQ3, EDGE21) — Given a running app, when a request is sent to an
  address this app does not serve, then what comes back carries none of the four
  error codes and nothing reaches the store; and when a well-formed call is then
  sent to `/api`, then it is served normally.
- **AC19** (REQ1, REQ6, REQ18) — the loop this surface has to start. Given a
  running app and an empty instance, when a project is created here, then it is
  listed here, then a release, an epic, two tasks under it and a project-level bug
  are created here, the epic is put in the release, the second task is made to wait
  on the first, and `list_items` is asked for the leaf types with status `todo` and
  nothing holding them up, then the answer holds exactly the first task and the
  bug, in the core's own order.
- **AC20** (REQ7, REQ8, REQ10) — Given the acceptance criteria of
  [spec-1](../03-core-write-path/spec-1.md) and
  [spec-2](../04-structure-queries/spec-2.md), when every one of them is driven
  through this surface instead of against the core's services directly, then each
  reaches the same verdict it reaches in the core's own process. This one needs the
  real core over a real store, and belongs to [epic 09](../09-full-system-test/)
  together with AC19 run against the assembled system.

## Definitions

- **the web API** — this surface: the eleven operations of REQ1, served by the web
  app at one address, in the core's own request and reply shape.
- **the core** — the core service, which owns the store and the single write path;
  **the client** — the one piece of code, shared by both apps in front of
  the core, that calls it and reads its replies
  ([spec-3](../05-operation-catalog/spec-3.md)).
- **the shared reading of a request** — the one set of definitions, held in the
  contract library, that says what each operation's request holds: the field names,
  which are required, which may be left out, and what kind of value each takes. The
  core's own connection reads requests with it, and so does this surface, which is
  why the two cannot come to different verdicts about the same request.
- **project-scoped operation** — one that acts inside a single named project (seven
  of the eleven); **instance-level operation** — one that acts on the whole
  instance, one running Nook and every project in it, and so names no project (the
  other four).
- **slug** — the short lowercase name an entity is known by in paths (`Add search`
  becomes `add-search`), usable anywhere its id is; **reference** — a string naming
  an entity, either an id or a slug.
- **leaf** — an item of type task, bug, or chore; **epic** — the one item type other
  items sit under.

The loopback address (`127.0.0.1`), JSON-RPC 2.0's `result`, `error`, `code`,
`message` and `data`, its reserved codes, and the 30-second timeout are used as
their own specifications define them and are not redefined here.

## Assumptions

- **ASM1** — Specs 1, 2 and 3 remain the whole behavior of the eleven operations
  and of the crossing to the core, and this spec adds no rule of its own about what
  an operation does; if false: the requirements here have no fixed behavior to be
  checked against.
- **ASM2** — Sign-in and every check of where a request came from stay deferred for
  as long as Nook is one person's proof of concept on one machine, which is the
  decision this milestone took; if false — the moment a browser interface reaches
  real users, or the app is reachable beyond that machine — REQ32 becomes the hole
  through which any page a person has open acts as them, and the gate has to land
  before the surface ships, not after.
- **ASM3** — The core and the web app run on one machine in this milestone
  ([ARCHITECTURE §8](../../../ARCHITECTURE.md),
  [05](../../../docs/05-project-and-ops.md)); if false: REQ30 blocks the deployment
  outright, and this surface needs authentication before it can be reached from
  anywhere else.
- **ASM4** — The interface that arrives in milestone 4 is written against this
  shape and needs no second one, and the extra plain reads the design leaves room
  for arrive as ordinary operations in the same shape; if false: the surface grows
  a second design after all, and the one-contract goal this epic serves has to be
  argued again.
- **ASM5** — Epic 08 adds the actor fields by growing the entities and commands
  that already cross, not by adding something this surface must carry separately;
  if false: the fidelity requirements have to be revisited for a value a caller
  supplies rather than the core produces.
- **ASM6** — Projects hold few enough items that a listing returning everything it
  matches crosses well inside the timeout
  ([spec-2](../04-structure-queries/spec-2.md) and
  [spec-3](../05-operation-catalog/spec-3.md) make the same assumption); if false: a
  large listing turns into an internal error, and handing results back a page at a time —
  deferred by the design docs — becomes the fix rather than a tuning exercise.
- **ASM7** — This epic's checks may stand up a stand-in core rather than the real
  one, on the bargain epic 06 took, because [epic 09](../09-full-system-test/) owns
  the assembled run; if false: this epic needs a database and every other program
  running, and its two deferred criteria come back into it.
