# Operation catalog — Spec-3

## Overview & scope

This spec is the requirements contract for the **internal connection**: the link
the core service offers on the machine it runs on, and the only way the two
adapter apps — the agent-facing MCP server and the web app — reach anything the
core owns. The design documents call it the internal RPC API
([ARCHITECTURE §3.3](../../../ARCHITECTURE.md), [05 — Project bootstrapping &
ops](../../../docs/05-project-and-ops.md)); it is internal because nothing
outside those two apps calls it, and because it is not the surface either app
shows the world. [PRD-1](../prd-1.md) REQ5 asks for the operation catalog to be
exposed **once** by the core and reached by both adapters, so that the two
surfaces of milestone 1 are two translators over one contract rather than two
contracts.

The catalog is **eleven operations**: the seven mutations of
[epic 03's spec](../03-core-write-path/spec-1.md) and the four reads of
[epic 04's spec](../04-structure-queries/spec-2.md). Four act on the whole
instance and seven inside one project. (Three operations were folded away while
this spec was being written — release assignment and blocker replacement became
fields of `update_item`, and readiness became a combination of listing filters;
both sibling specs carry a note recording it.) What each one *does* is settled
by those two specs
and by [01 — Interface contracts](../../../docs/01-interface-contracts.md); this
spec never restates a rule of theirs. It requires something narrower and
load-bearing: that crossing the connection changes nothing — not what a caller
asked for, not what came back, and not the verdict in between.

This epic builds **both halves**: the core's answering side, and one calling
library that both adapters use. That is what makes PRD-1's parity goal
structural rather than hopeful — the two adapters cannot read a reply
differently if there is only one piece of code that reads it.

Throughout, an **adapter** is one of those two apps; **the connection** is the
internal link described above; and **a handle** is the short lowercase name an
entity is known by in paths (`Add search` becomes `add-search`), usable
anywhere its id is.

In scope: the eleven operations as reached across the connection and through
the calling library — what may cross it, what must survive it unchanged, the
three ways a call can end and how a caller tells them apart, what happens when
the core is slow, absent, or sent something it cannot read, several callers at
once, and where the core listens.

Out of scope:

- **The rules the operations enforce** — handles, containment, status
  vocabulary, cycles, filtering, ordering, readiness. Those are specs 1 and 2;
  this spec requires only that they arrive intact.
- **The MCP tool surface** — epic 06 — and **the web HTTP API** — epic 07. Each
  translates its own protocol into calls on this connection; the shapes, tool
  names, and status codes either one shows the outside world are that epic's
  business, not this one's.
- **Who the caller is.** Nothing on this connection carries a caller's identity
  in this milestone; how a subject travels from an adapter to the core arrives
  with epic 08 alongside the fields that store it.
- **Documents** — milestone 2. No document operation exists yet to cross.
- **Proving who is calling** — deferred by the design itself. The connection
  asks a caller for nothing and checks nothing; what stands in place of that is
  that only the machine it runs on can reach it at all.
- **Running the processes** — packaging, an address the core could answer "yes,
  I am alive" on, the database settings, and everything else about deployment
  belong to [05](../../../docs/05-project-and-ops.md). This spec requires only
  that the two addresses involved are settable from outside the program.
- **Paging, free-text search, and sort options** — deferred by the design docs.
  A listing crosses whole, in one ordering.

## Scenarios

### SCEN1 — An adapter builds out a project without touching the store

**Initiator:** an adapter process, serving a request from an agent.
**Flow:**
1. The adapter, which holds no database access of any kind, calls
   `create_project` through the calling library.
2. It then calls `create_release`, `create_item` for an epic, two tasks under
   it, and a project-level bug, then `update_item` twice — once to put the epic
   in the release, once to make the second task wait on the first.
3. It calls `list_items`, asking for the leaf types, status `todo`, and nothing
   holding them up.
4. It deletes a second bug it had filed by mistake.
**Outcome:** every call returns the same entity the core would have returned
inside its own process, with every field intact; the readiness answer holds
exactly the open, unheld-up leaves, in the order the core put them in; the
delete reports success and hands back no entity, which the adapter can still
tell apart from a refusal; and the adapter never opened a database connection to
get any of it.

### SCEN2 — An edit that clears a field, and an edit that leaves it alone

**Initiator:** the web app, saving a form.
**Flow:**
1. A task has a description. The adapter sends an update that changes only the
   name.
2. The adapter then sends an update that clears the description.
3. The adapter then sends an update naming no field at all.
**Outcome:** the first leaves the description as it was, the second empties it,
the third changes nothing — three distinguishable outcomes, because "say
nothing about this field" and "set this field to nothing" survive the crossing
as different things.

### SCEN3 — A refusal comes back as a refusal

**Initiator:** an agent, through the MCP server.
**Flow:**
1. The agent asks to close a dependency loop; the core refuses it as a cycle.
2. The agent asks for a handle another item already holds; the core refuses it
   as a conflict.
3. The agent asks for an item that does not exist.
4. A defect inside the core makes a fourth, perfectly good request fail.
**Outcome:** the first three arrive at the adapter as refusals carrying the code
the core chose, its message, and its details — enough for the adapter to map
each to its own protocol's shape without guessing which kind of failure it was.
The fourth arrives as something else entirely, carrying none of those codes, so
the adapter never tells the agent to fix a request that was never the problem.

### SCEN4 — The core is not there

**Initiator:** an adapter, started before the core.
**Flow:**
1. The adapter's first call is made while nothing is listening.
2. The core is started.
3. The adapter, still running, makes the same call again.
**Outcome:** the first call fails quickly and says the core could not be
reached — not that the request was wrong — and the second succeeds without the
adapter being restarted.

### SCEN5 — A call gives up, and the write lands anyway

**Initiator:** the web app, saving while the core is wedged.
**Flow:**
1. The adapter sends a create, and the core does not answer.
2. Thirty seconds pass and the adapter's call gives up.
3. Nothing is sent again.
4. The core recovers, and the adapter lists the project.
**Outcome:** the caller was told the call failed, the request was never repeated
— so there is at most one new item, never two — and the listing afterwards is
what says whether the write landed. Giving up is a report about the wait, not a
statement that the work did not happen.

### SCEN6 — Two adapter processes write at the same moment

**Initiator:** the MCP server and the web app, serving two people at once.
**Flow:**
1. Both create an item named "Add search" at the same moment.
2. Each writes one half of what would together be a two-step dependency loop —
   two items each waiting on the other.
3. While those writes are in flight, a third call lists the project.
**Outcome:** both creates succeed with different handles; of the two loop halves
exactly one commits and the other is refused as a cycle; and the listing waits
on neither writer and shows only items whose writes had finished. Arriving over
a connection rather than from inside the core changes none of it.

### SCEN7 — A buggy adapter sends something the core cannot read

**Initiator:** an adapter with a defect.
**Flow:**
1. It sends a request naming an operation that does not exist.
2. It sends a request for a project-scoped operation without naming a project.
3. It sends a request carrying a field the operation does not define.
**Outcome:** each is refused as a failed validation, nothing reaches the store,
and the core keeps serving other callers throughout.

### SCEN8 — Something else on the network tries the core's port

**Initiator:** any program on another machine.
**Flow:**
1. It connects to the core's address from a different machine on the network
   and sends a well-formed `delete_project` call.
**Outcome:** it is not served. Nothing is deleted, and no credential was needed
to keep it out, because the connection is not reachable from where it called.

### SCEN9 — Bringing the pair up

**Initiator:** an operator starting the milestone's processes.
**Flow:**
1. The core is started, told from outside which address to listen on.
2. An adapter is started, told from outside which address to call.
3. A second adapter is started with no address configured.
**Outcome:** the first two come up and a call passes end to end between them;
the third stops immediately and names the setting it is missing, rather than
starting up and calling an address nobody chose.

## Requirements

### The surface

- **REQ1** — The connection MUST offer exactly these eleven operations:
  `create_project`, `get_project`, `list_projects`, `delete_project`,
  `create_item`, `update_item`, `delete_item`, `create_release`,
  `update_release`, `get_item`, `list_items`.
- **REQ2** — The connection MUST offer nothing besides those eleven: no other
  operation, and no argument or option beyond the ones those operations already
  take.
- **REQ3** — The calling library MUST offer the same eleven operations under
  the same names, accepting the same command values and filter and returning
  the same entities.
- **REQ4** — An operation called through the calling library MUST produce the
  same outcome as the same operation called inside the core's own process: the
  same entity, the same refusal, and the same change to the store.
- **REQ5** — Each of the seven project-scoped operations MUST take the project
  it acts on as an argument of the call; the four instance-level ones
  (`create_project`, `get_project`, `list_projects`, `delete_project`) MUST NOT
  take one.

### What crosses unchanged

- **REQ6** — The connection MUST NOT alter what a caller supplies: nothing
  trimmed, lowercased, reordered, filled in, deduplicated, or dropped. What the
  core receives is what the caller wrote.
- **REQ7** — For every field of a partial update that allows both, "leave this
  field alone" and "set this field to nothing" MUST remain distinguishable
  after crossing.
- **REQ8** — For each of the listing filter's five parts, "do not filter on
  this part" and "filter on no values at all" MUST remain distinguishable after
  crossing — the first means every item matches, the second is a caller mistake
  the core refuses. The held-up part carries one of two answers rather than a
  set of values, so what MUST survive there is the difference between asking
  neither answer and asking one.
- **REQ9** — An entity MUST arrive whole: every field it carries, each holding
  the value the core produced — a timestamp naming the same moment, a blocker
  set holding the same ids, a field that is absent staying absent rather than
  arriving as empty text.
- **REQ10** — A listing MUST arrive in the order the core produced it.
- **REQ11** — The two deletes MUST return no entity, and a caller MUST be able
  to tell their success apart from a refusal.

### How a call ends

- **REQ12** — Every call MUST end in exactly one of three ways: an answer, a
  refusal, or a breakdown.
- **REQ13** — A refusal MUST arrive carrying the code, the message, and the
  details the core produced, unchanged.
- **REQ14** — A fault inside the core MUST arrive as a breakdown, and MUST NOT
  carry any of the four refusal codes — there is nothing for a caller to fix, so
  it must not read as though there were.
- **REQ15** — A failure of the connection itself MUST arrive as a breakdown
  too, and MUST be distinguishable from a fault inside the core.
- **REQ16** — The connection MUST apply no rule of its own to a request it can
  read: every acceptance and every refusal of such a request is the core's
  verdict, not the connection's.
- **REQ17** — A request the connection cannot read — unreadable, naming no
  operation, missing an argument its operation requires, or carrying a field
  that operation does not define — MUST fail with `validation_failed` and MUST
  NOT reach the store.

### When the core does not answer

- **REQ18** — A call still unanswered 30 seconds after it was made MUST fail
  with a breakdown rather than wait longer.
- **REQ19** — The calling library MUST NOT send any call a second time on the
  caller's behalf — not after the wait limit, not after a dropped connection,
  not after any refusal or breakdown. A write cannot be repeated safely, and no
  rule that repeats only some calls is worth the risk of getting the set wrong.
- **REQ20** — When a caller stops waiting, the work the core has already begun
  MUST run to its own conclusion: a write that reached the moment its change
  becomes permanent in the database MUST stay permanent, and a caller that
  stopped listening MUST NOT leave a half-applied change behind.
- **REQ21** — When the core is not running, or cannot be reached, a call MUST
  fail with a breakdown within the wait limit rather than hang.
- **REQ22** — An adapter MUST recover on its own once the core is reachable
  again: a later call MUST succeed without the adapter being restarted.

### Several callers at once

- **REQ23** — The core MUST serve calls from several adapter processes at the
  same time, and MUST NOT make one call wait on an unrelated one.
- **REQ24** — The store's guarantees under callers writing at once MUST hold
  when those writers arrive across the connection: the store MUST NOT come to
  hold a loop of items waiting on each other in a circle, nor two entities
  sharing a handle that has to be unique — two items or releases inside one
  project, or two projects on the instance.
- **REQ25** — A read taken across the connection MUST show the store either
  wholly before or wholly after a concurrent write, never half-applied.

### Where the core listens

- **REQ26** — The core MUST serve the connection only to callers on the machine
  it runs on; a call arriving from any other machine MUST NOT be served.
- **REQ27** — The connection MUST require no credential in this milestone:
  a caller presents none and the core checks for none. REQ26 is the whole of its
  protection.
- **REQ28** — The address the core listens on, and the address each adapter
  calls, MUST be settable from outside the program rather than fixed in its
  code.
- **REQ29** — A process started without its address MUST stop with a message
  naming what is missing, rather than falling back to a default address nobody
  chose.
- **REQ30** — Neither adapter, nor the calling library they share, MUST hold any
  database or persistence dependency.

## Edge cases

- **EDGE1** — A name or description carrying emoji, non-Latin script, line
  breaks, or quotation marks: stored exactly as sent.
- **EDGE2** — A description set to empty text, versus one left unmentioned:
  the first empties the field, the second leaves it alone.
- **EDGE3** — An update naming no field at all: crosses as an update naming no
  field, and comes back as the core's own do-nothing result rather than being
  refused or discarded before it is sent.
- **EDGE4** — A blocker list supplied empty: arrives empty and clears the set —
  never mistaken for no list having been supplied.
- **EDGE5** — A filter part supplied with no values: arrives that way and is
  refused with `validation_failed`, never quietly turned into "do not filter on
  this part".
- **EDGE6** — A listing of 5,000 items: arrives whole, in order, within the wait
  limit.
- **EDGE7** — A reference that looks like an id but is not a well-formed one:
  crosses unchanged, and the core decides what it means.
- **EDGE8** — The core is restarted between two of an adapter's calls: the
  second call succeeds.
- **EDGE9** — The core is stopped in the middle of a call: a breakdown, not a
  refusal, and no wait beyond the limit.
- **EDGE10** — A caller stops waiting while its write is committing: the write
  stands, and a later read shows it.
- **EDGE11** — A request naming an operation that does not exist:
  `validation_failed`, and nothing reaches the store.
- **EDGE12** — A request whose contents cannot be read at all:
  `validation_failed`.
- **EDGE13** — A project-scoped request naming no project: `validation_failed`.
- **EDGE14** — A request carrying a field its operation does not define:
  `validation_failed`. Both halves of the connection are built together, so an
  unknown field is a defect to surface, not a difference to absorb — and
  ignoring one would silently drop something a caller meant.
- **EDGE15** — Two adapter processes creating an item of the same name at the
  same moment: both succeed, with different handles.
- **EDGE16** — Two adapter processes each writing one half of a two-step
  dependency loop: exactly one commits, the other is refused as a cycle.
- **EDGE17** — A slow call and a fast call in flight together: the fast one is
  answered without waiting for the slow one.
- **EDGE18** — A well-formed call arriving from another machine: not served.
- **EDGE19** — An adapter started before the core: its first call is a
  breakdown, and a call made after the core comes up succeeds.
- **EDGE20** — A process started with no address configured: it stops, naming
  the missing setting.

## Acceptance criteria

- **AC1** (REQ1, REQ2, REQ3) — The core's connection answers exactly the
  eleven named operations and no twelfth; the calling library's public
  surface is exactly those same eleven under the same names; and neither side
  accepts an argument that no operation defines.
- **AC2** (REQ4) — Given the acceptance criteria of
  [spec-1](../03-core-write-path/spec-1.md) and
  [spec-2](../04-structure-queries/spec-2.md), when every one of them is driven
  through the calling library instead of against the core's services directly,
  then each reaches the same verdict it reaches in-process.
- **AC3** (REQ5) — Given the eleven operations, when each is called, then the
  seven project-scoped ones require a project to be named and the four
  instance-level ones offer no place to name one.
- **AC4** (REQ6, EDGE1, EDGE7) — Given calls carrying a name with emoji, a
  description with line breaks and quotation marks, a blocker list holding the
  same reference twice, and a reference that is nearly but not quite a
  well-formed id, when each crosses, then the values the core's own operation is
  invoked with equal the values handed to the calling library, one for one —
  same text, same reference, and the blocker list still holding its duplicate —
  and the stored item's name and description are the text that was sent.
- **AC5** (REQ7, EDGE2, EDGE3) — Given a task with a description, when an update
  changes only its name, then the description is unchanged; when a later update
  sets the description to nothing, then it is empty; and when a later update
  names no field, then the call succeeds with nothing changed.
- **AC6** (REQ8, EDGE5) — Given a project holding items of several types, when
  `list_items` is called with no type part, then every item comes back; and when
  it is called with a type part holding no values, then it fails with
  `validation_failed`.
- **AC7** (REQ6, EDGE4) — Given an item with two blockers, when `update_item`
  is called with `blockedBy` set to an empty list, then its blocker set is empty
  afterwards; and when a later `update_item` omits `blockedBy` entirely, then
  the set is left exactly as it was.
- **AC8** (REQ9, REQ10, EDGE6) — Given a project, an item under an epic with two
  blockers, and a release with a target date, when each is fetched across the
  connection, then each returned entity equals the entity the core produced,
  field for field — comparing the whole entity, not a named subset, so a field
  added later is covered without this check being edited; and given a project
  holding 5,000 items, when it is listed, then all 5,000 arrive within 30
  seconds in the same order the core produced.
- **AC9** (REQ11) — Given an item and a project, when `delete_item` and
  `delete_project` are called across the connection, then both report success,
  neither returns an entity, and both are distinguishable from a refusal.
- **AC10** (REQ12, REQ13) — Given calls that produce each of the four refusal
  codes in turn, when each is made across the connection, then each arrives as a
  refusal carrying that same code, the core's message, and the core's details.
- **AC11** (REQ14) — Given a fault deliberately planted inside the core, when an
  operation hits it, then the caller receives a breakdown carrying none of the
  four codes, and the caller can tell it apart from every refusal.
- **AC12** (REQ15, REQ21, REQ22, EDGE8, EDGE9, EDGE19) — Given an adapter
  started while the core is not running, when it calls, then the call fails
  within 30 seconds as a breakdown distinguishable from a fault inside the core;
  when the core is then started and the same adapter calls again, then the call
  succeeds; and when the core is stopped mid-call and restarted, then that call
  is a breakdown and the next call succeeds — with no adapter restart at any
  point.
- **AC13** (REQ16, REQ17, EDGE11, EDGE12, EDGE13, EDGE14) — Given a request
  naming an unknown operation, one whose contents cannot be read, a
  project-scoped one naming no project, and one carrying a field its operation
  does not define, when each is sent, then each fails with `validation_failed`,
  the store is unchanged, and the core answers the next well-formed call
  normally.
- **AC14** (REQ18) — Given a core made to hold a call without answering, when
  the call is made, then it fails as a breakdown between 30 and 31 seconds after
  it was sent.
- **AC15** (REQ19) — Given a call that ends in a dropped connection, in the wait
  limit, and in each of the four refusals, when each ends, then the core has
  received that request exactly once.
- **AC16** (REQ20, EDGE10) — Given a caller that stops waiting while the core is
  still writing an item and its blocker edges (repeated 100 times), when the
  project is read afterwards, then in every run the item is either fully there
  with the edges it was written with, or not there at all — never a row missing
  its edges.
- **AC17** (REQ23, EDGE17) — Given one call the core is made to answer slowly
  and a second call made from another adapter process while the first is in
  flight, when both are made, then the second is answered before the first.
- **AC18** (REQ24, EDGE15, EDGE16) — Given two adapter processes calling at
  once, when both create an item of the same name (repeated 100 times) and when
  each writes one half of a two-step dependency loop (repeated 100 times), then
  every run of the first leaves two items with different handles, and every run
  of the second leaves exactly one commit, one `cycle` refusal, and no loop in
  the store.
- **AC19** (REQ25) — Given one adapter process repeatedly listing a project
  while another repeatedly creates items in it (repeated 100 times), then every
  listing returns only fully committed items, each complete with its blocker
  set, and no call fails.
- **AC20** (REQ26, REQ27, EDGE18) — Given a running core, when a well-formed
  call is made from the same machine with no credential, then it is served; and
  when the same call is made to the core's address from another machine, then it
  is not served and the store is unchanged.
- **AC21** (REQ28, REQ29, EDGE20) — Given the core and an adapter configured
  with addresses from outside the program, when both start and a call is made,
  then it succeeds; and when either process is started with its address unset,
  then it stops with a message naming the missing setting rather than starting
  on a default.
- **AC22** (REQ30) — Given the build's dependency graph, when the adapters and
  the calling library are checked, then none of them resolves a database or
  persistence dependency.

## Definitions

- **the connection** — the link the core service offers on the machine it runs
  on, and the only way an adapter reaches anything the core owns. The design
  documents call it the internal RPC API.
- **adapter** — one of the two apps in front of the core: the MCP server, which
  serves agents, and the web app, which serves people. Each translates its own
  protocol into calls on the connection and owns no store.
- **the calling library** — the one piece of code, shared by both adapters, that
  makes those calls and reads the replies. Built by this epic, so that the two
  adapters cannot read a reply differently.
- **the catalog** — the eleven operations of REQ1, taken together: everything
  an adapter can ask the core to do in this milestone.
- **project-scoped operation** — one that acts inside a single named project
  (seven of the eleven); **instance-level operation** — one that acts on the
  whole **instance** — one running Nook and every project in it — and so names
  no project (the other four).
- **answer** — a call that ended with the result the operation produces: an
  entity, a list, or, for the two deletes, nothing at all.
- **refusal** — a call the core turned down because the request was wrong,
  carrying `{code, message, details?}` with a code of `validation_failed`,
  `not_found`, `conflict`, or `cycle`. A refusal tells the caller what to fix.
- **breakdown** — a call that ended without a verdict, because the core is
  broken or could not be reached. There is nothing for the caller to fix, which
  is exactly why it must never look like a refusal.
- **the wait limit** — the 30 seconds a call waits for an answer before it
  becomes a breakdown (REQ18).
- **handle** — the short lowercase name an entity is known by in paths, usable
  anywhere its id is; **reference** — a string naming an entity, either an id or
  a handle.
- **partial update** — a command whose every field may be left unmentioned; the
  three states a field can be in are unmentioned, set to a value, and set to
  nothing.

## Assumptions

- **ASM1** — Specs 1 and 2 remain the whole behavior of the eleven
  operations, and this spec adds no rule of its own about what they do; if
  false: REQ4's "the same outcome" has no fixed meaning to be checked against.
- **ASM2** — Both halves of the connection are built and released together from
  one source tree, so they never hold different ideas of the contract; if false:
  EDGE14's refusal of an unknown field stops catching defects and starts
  breaking working deployments, and the connection needs a compatibility rule it
  does not have.
- **ASM3** — In this milestone the core and both adapters run on one machine
  ([ARCHITECTURE §8](../../../ARCHITECTURE.md),
  [05](../../../docs/05-project-and-ops.md)); if false: REQ26 blocks the
  deployment outright, and the connection needs authentication before it can be
  reached from anywhere else
  ([08](../../../docs/08-deployment-and-cloud.md)).
- **ASM4** — Nothing but the two adapters calls this connection; if false: the
  refusal codes and entity shapes become a contract for callers nobody designed
  for, and changing either stops being a local decision.
- **ASM5** — Projects hold few enough items that a listing returning everything
  it matches in one go crosses well inside the wait limit
  ([spec-2](../04-structure-queries/spec-2.md) makes the same assumption); if
  false: a large listing turns into a breakdown, and handing results back a page
  at a time — deferred by the design docs — becomes the fix rather than a tuning
  exercise.
- **ASM6** — Epic 08 adds the actor fields by growing the entities and commands
  that already cross, not by adding something the connection must carry
  separately; if false: the fidelity requirements have to be revisited for a
  value the caller supplies rather than the core produces.
