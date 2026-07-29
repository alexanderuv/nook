# Full system test — Spec-7

## Overview & scope

This spec is the requirements contract for **the milestone's programs running
together**: the core service, the MCP server and the web app, each started as its
own program, all three against one PostgreSQL database. Every epic before this one
proved itself in isolation — the core against a database with no program in front
of it, and each adapter against a stand-in core that needed no database and started
nothing else. That was the right bargain for building each piece, and it leaves the
assembled system unproven. Nothing before this epic starts the whole of it.

[PRD-1](../prd-1.md)'s north-star goal is what this contract exists to make true:
starting from a project, a coding agent creates epics, leaves, a release and blocker
edges over MCP alone, and then one listing call hands back exactly the open leaves
nothing is holding up — observed against a real database, passing unattended. The
loop is the payoff of the whole milestone, and this is the only place it can be run.

**The project is created through the web API, not by the agent.** An agent has no
tool that makes a project ([spec-4](../06-mcp-server/spec-4.md)), so the loop starts
on the one program that does. This narrows PRD-1's own wording, which describes the
loop as running over MCP alone from the very beginning; spec-4 recorded the same
narrowing when it decided which operations became tools.

**Comparing what the two adapters answer is dropped, and PRD-1's second goal is
narrowed with it.** PRD-1 GOAL2 asks for one request to reach the same verdict
called four ways, and [spec-4](../06-mcp-server/spec-4.md) AC26 and
[spec-5](../07-web-api/spec-5.md) AC20 each handed this epic a re-run of every
acceptance criterion of [spec-1](../03-core-write-path/spec-1.md) and
[spec-2](../04-structure-queries/spec-2.md) through their own adapter. Both are
struck. Every operation reaches the database through the one core, so the two
adapters cannot come to different verdicts about a rule — and they are not meant to
answer alike in any case: the MCP server answers a coding agent in the shape the
Model Context Protocol defines for a tool result, and the web API answers a program
in the core's own request and reply shape. Each already proves its own translation
against a stand-in core, which is faithful for exactly that. Running the rule suites
again through real programs re-asserts, at the cost of two long runs, what the core's
own suite proves against a real database.

What is left is what only assembly can show, and that is this contract: that three
programs started separately serve as one; that the loop runs end to end; that every
row it writes names the person and the agent it was written for; that two programs
writing one store meet a real unique constraint and a real transaction; and that a
core stopped and started again leaves both adapters usable.

In scope: how the three programs are brought up and in what order; the loop over the
MCP server and the same loop through the web API; who each written row records; two
callers writing at once through different adapters; a caller that stops listening
mid-write; the core going away and coming back underneath both adapters; a project
deleted while an agent is connected; and the token gate as it stands in front of the
assembled system.

Out of scope:

- **The rules the operations enforce** — containment, slugs, the status vocabulary,
  cycles, filtering, ordering, what a delete reaches. Those are spec-1 and spec-2,
  proved by the core's own suite against a real database. This spec restates none of
  them and adds none.
- **Each adapter's own translation** — which operations become tools, how a tool
  result is shaped, what a reply names its ending with, what a request the adapter
  cannot read comes back as. Those are [spec-4](../06-mcp-server/spec-4.md) and
  [spec-5](../07-web-api/spec-5.md), proved in their own modules against stand-in
  cores.
- **Comparing the two adapters' answers with each other**, for the reason recorded
  above.
- **How the identity travels** — what a token is checked against, what crosses to
  the core beside a request, what a caller may not name. That is
  [spec-6](../08-actor-plumbing/spec-6.md); this spec requires only that what it
  settled is true of rows written by the assembled system.
- **Rule coverage.** PRD-1's third goal — every settled rule mapping to a named test
  — is met by each epic recording its own rule-to-test mapping, and is not something
  a running system can be asked.
- **Where a database dependency may appear in the build.** PRD-1's fourth goal is
  enforced by the build itself (`checkPersistenceBoundary`), which reads the
  dependency graph. What a running system can be asked instead is which program
  actually holds a connection, and REQ2 asks it.
- **A listing large enough to test the limit.** Specs 2, 3, 4 and 5 each assume a
  project holds few enough items for a listing to cross well inside the thirty
  seconds a call waits; this spec carries the same assumption (ASM5) rather than
  measuring it across three programs.
- **Packaging, service management, and a production deployment** — how the programs
  are shipped, supervised or reached from anywhere but the machine they run on
  belongs to [05](../../../docs/05-project-and-ops.md) and
  [08](../../../docs/08-deployment-and-cloud.md).
- **Documents, skills and tenets** — milestones 2 and 3. No operation in this
  milestone writes a document, and the MCP server serves no resources.
- **The web UI** — milestone 4. The web app serves the eleven operations and nothing
  else.

## Scenarios

### SCEN1 — An operator brings the whole thing up

**Initiator:** a person, or a script, starting the milestone's programs.
**Flow:**
1. A PostgreSQL database is prepared and the committed changelog is applied to it.
2. The MCP server and the web app are started first, each told its port, where the
   core is, and what to check a token against; the core is started afterwards, told
   its port and where the database is.
3. A call is made to each adapter.
**Outcome:** all three programs serve, though two of them were started before the
program they call. Nothing was arranged in a fixed order, and no program was
restarted to notice another.

### SCEN2 — An agent runs the milestone's loop

**Initiator:** a coding agent, over MCP alone, in a project a person made for it.
**Flow:**
1. A person creates the project through the web API and configures the agent's
   client with that project's address and a token.
2. The agent creates a release, an epic, two tasks under that epic, and a
   project-level bug.
3. It puts the epic in the release, and makes the second task wait on the first.
4. It asks for the leaf types — task, bug and chore — with status `todo` and nothing
   unfinished holding them up.
**Outcome:** the listing holds exactly the first task and the bug, in the order the
core produced them. Everything the agent created is in the database it never
touched, and the whole run needed no person after the project existed.

### SCEN3 — A program runs the same loop through the web API

**Initiator:** a program acting for a person — a command-line tool today, the
interface that arrives in milestone 4 later.
**Flow:**
1. It creates a project, and sees it in a listing of projects.
2. It creates the same release, epic, two tasks and bug, sets the release and the
   blocker, and asks the same listing question.
**Outcome:** the same answer, reached without the MCP server running at all — the web
API being the only program a project can be made through, and a complete way in of
its own.

### SCEN4 — Reading back who wrote everything

**Initiator:** whoever is checking that the identity survived the whole run.
**Flow:**
1. The loop of SCEN2 has run, over a connection whose token named a person and whose
   client named itself.
2. Every entity it created is read back.
**Outcome:** each carries that person as what created it and last changed it, and
that client's name as the agent that acted; the project created in SCEN2's first step
carries the person as its owner and no agent at all, no agent having made it.

### SCEN5 — The core is stopped and started underneath

**Initiator:** an operator restarting the core while people and agents are connected.
**Flow:**
1. An agent holds an open connection to the MCP server and a program is calling the
   web API.
2. The core is stopped. Each of them calls.
3. The core is started again, at the same address. Each calls once more.
**Outcome:** the calls made while the core was down come back saying no verdict was
reached, carrying nothing for the caller to fix. The calls after it is back succeed —
on the same connection, with neither adapter restarted and no client rebuilt.

### SCEN6 — Two callers, two adapters, one name

**Initiator:** an agent on the MCP server and a program on the web API.
**Flow:**
1. Both create an item of the same name in the same project, at the same moment.
2. Someone lists the project.
**Outcome:** both succeed, and the two items hold different slugs — decided by the
database itself, which is the only thing either caller shares.

### SCEN7 — A caller walks away mid-write

**Initiator:** a program that stops listening.
**Flow:**
1. It sends a create carrying two blockers and drops its connection before the
   answer arrives.
2. Someone reads the project afterwards.
**Outcome:** the item is there whole, with both blocker edges, or it is not there at
all. Never an item missing what the same write was to give it.

### SCEN8 — A project is deleted under a working agent

**Initiator:** a person on the web API, while an agent is mid-session.
**Flow:**
1. An agent has been working in a project on an open connection.
2. The person deletes that project through the web API.
3. The agent calls a tool, and its client then opens a fresh connection at the same
   address.
**Outcome:** the call finds the project gone and that connection stops being served;
the fresh connection is refused, naming the project. The deletion happened on one
program and was noticed by another, with nothing shared between them but the
database.

### SCEN9 — A call presenting no token

**Initiator:** a program written before the gate existed.
**Flow:**
1. It calls the web API with no `Authorization` header.
2. An agent's client tries to open a connection to the MCP server with none either.
**Outcome:** both are turned away as unauthorized, nothing is written, and the core
is never called. The gate stands in front of the assembled system exactly as it
stands in front of each adapter alone.

## Requirements

### The assembled system

- **REQ1** — The system MUST run as three separate programs — the core service, the
  MCP server and the web app — each started from its own entry point and taking
  every address, port and secret from its environment.
- **REQ2** — The three programs MUST reach one PostgreSQL database, and only the
  core service MUST connect to it.
- **REQ3** — The programs MUST serve whatever order they were started in: an adapter
  started before the core MUST serve calls once the core is up, without being
  restarted.
- **REQ4** — Every requirement below MUST hold with all three programs running as
  separate programs; none of them MUST be satisfiable by a program standing in for
  another.

### The loop

- **REQ5** — Starting from an empty database, a person MUST be able to create a
  project through the web API, and MUST have no way to create one over MCP.
- **REQ6** — Given such a project, a coding agent calling MCP tools alone MUST be
  able to create a release, an epic, two leaves under that epic, and a leaf directly
  under the project; put the epic in the release; and make one leaf wait on another.
- **REQ7** — One listing call asking for the leaf types, status `todo`, and nothing
  unfinished holding an item up MUST then return exactly the leaves that are open and
  unblocked, in the order the core produced them.
- **REQ8** — The same sequence, called through the web API alone, MUST reach the same
  state in the database and return the same listing.
- **REQ9** — The whole of REQ6 and REQ7 MUST run unattended: no step MUST require a
  person to intervene once the project exists.

### Who wrote each row

- **REQ10** — Every row written during the run MUST record the person named by the
  token the call presented, as what created it and as what last changed it.
- **REQ11** — Every row written over MCP MUST record the name the connecting client
  gave for itself as the agent that acted; every row written through the web API MUST
  record no agent at all.
- **REQ12** — A project created through the web API MUST record the person the
  creating call presented a token for as its owner.

### Two programs writing one database

- **REQ13** — Two callers creating an item of the same name in the same project at
  the same moment, one calling the MCP server and one the web API, MUST both succeed,
  and the two items MUST hold different slugs.
- **REQ14** — A caller that stops listening before its answer arrives MUST leave the
  database holding either the whole of its write, including every blocker edge, or
  none of it.

### The core going away and coming back

- **REQ15** — While the core is not running, a call to either adapter MUST come back
  saying no verdict was reached, carrying none of the four domain reasons — there is
  nothing in such a call for its caller to fix.
- **REQ16** — Once the core is running again at the same address, a later call MUST
  succeed on the same connection, without either adapter being restarted and without
  the client reconnecting.
- **REQ17** — A project deleted through the web API MUST end the MCP connections
  bound to it: the first tool call after the deletion MUST fail as not found and that
  connection MUST NOT be served again.

### The gate

- **REQ18** — A call presenting no valid token MUST be refused by whichever adapter
  it reaches, MUST NOT reach the core, and MUST leave the database unchanged.
- **REQ19** — A call presenting a valid token MUST be served by both adapters, so
  that the gate turns away only what it is meant to.

## Edge cases

- **EDGE1** — Both adapters started before the core: each serves once the core is up,
  and neither is restarted to notice it.
- **EDGE2** — The core started before either adapter: the same, from the other side —
  no program depends on being started first.
- **EDGE3** — The core stopped in the middle of a call: the call reports no verdict,
  and the next call after the core returns succeeds on the same connection.
- **EDGE4** — The database stopped while all three programs run: calls report no
  verdict, carrying none of the four domain reasons, and calls succeed again once it
  is back, with no program restarted.
- **EDGE5** — The same project's items created from both adapters at once, repeatedly:
  every run leaves both items with different slugs, and neither caller's call waits on
  the other's.
- **EDGE6** — A caller dropping its connection mid-write, repeatedly: every run leaves
  the item whole with its blocker edges or absent altogether — never an item without
  the edges the same write was to give it.
- **EDGE7** — A project deleted while an agent is connected, and a new project later
  given its slug: the agent's connection stops at its first call after the deletion,
  and no call of its is ever carried out against the new project.
- **EDGE8** — A tool call and a web API call made at the same moment in the same
  project: both are served, and neither waits on the other.
- **EDGE9** — An agent's client that gives no name for itself: its writes record the
  person and no agent, and the run is served normally.
- **EDGE10** — A program started with a setting missing — a port, the core's address,
  or what to check a token against: it stops and names the setting, so a system that
  came up partly is a system that did not come up.

## Acceptance criteria

- **AC1** (REQ1, REQ2, REQ3, EDGE1, EDGE2) — Given a migrated database, when the MCP
  server and the web app are started as separate programs before the core and a call
  is then made to each, then each call is served once the core is up with no program
  restarted; and when the same is done with the core started first, then both calls
  are served too.
- **AC2** (REQ2) — Given the three programs running, when each program's open
  connections to the database are examined, then only the core service holds one.
- **AC3** (REQ5, REQ6, REQ7, REQ9, REQ4) — the milestone's loop. Given an empty
  database and the three programs running, when a project is created through the web
  API and one agent then, over MCP alone, creates a release, an epic, two tasks under
  it and a project-level bug, puts the epic in the release, makes the second task wait
  on the first, and calls the listing tool asking for the leaf types with status
  `todo` and nothing holding them up, then the answer holds exactly the first task and
  the bug, in the order the core produced them, and the whole run completes with no
  intervention.
- **AC4** (REQ5) — Given an agent connected over MCP, when the tools it is offered are
  examined and a call naming `create_project` is made, then no tool creates a project
  and the call is refused naming the tool it asked for.
- **AC5** (REQ8) — Given an empty database, when the same sequence is carried out
  through the web API alone with the MCP server not running at all, then the listing
  holds exactly the first task and the bug in the order the core produced them, and
  the database holds the release, the epic, its two tasks with the second waiting on
  the first, and the project-level bug.
- **AC6** (REQ10, REQ11, REQ12) — Given AC3's run, made over a connection whose token
  names `alex` and whose client names itself `claude-code`, when every entity it
  created is read back, then each records `alex` as what created and last changed it
  and `claude-code` as the agent, the project records `alex` as its owner and no agent,
  and the rows read straight from the database say the same.
- **AC7** (REQ11, EDGE9) — Given a client that gives no name for itself, when it
  creates an item, then the item records the person and no agent, and the call
  succeeds. **Met where it already stands, and not re-run in the assembled system.**
  Epic 08 shows both halves against the things that decide them: its
  `ActingIdentityTest` shows a client giving no name being served — written out by
  hand, because the protocol library's own client refuses to be built without one —
  and its `ActorRecordedTest` shows a row written with no agent against the real
  store. Assembly makes neither of those newly true, and the run here could not ask
  the first half at all for the reason that test records.
- **AC8** (REQ13, EDGE5, EDGE8) — Given one project and two callers, one on each
  adapter, when both create an item of the same name at the same moment (repeated 100
  times), then every run leaves two items whose slugs differ, and neither call waited
  on the other.
- **AC9** (REQ14, EDGE6) — Given a caller that stops listening while the core is
  writing an item with two blockers (repeated 100 times), when the project is read
  afterwards, then in every run the item is either there with both edges or not there
  at all. **Read as: the change and both its edges landed together, or neither did.**
  No operation creates an item and its blockers in one call — `create_item` takes no
  blockers, and `update_item` is the one write that puts a row and its edges in one
  transaction — so the state a half-written row could be left in is the change without
  its edges rather than an item without them. The item is always there afterwards; what
  is checked is that its new name and both its edges arrived together.
- **AC10** (REQ15, REQ16, EDGE3) — Given an agent holding an open MCP connection and a
  program calling the web API, when the core is stopped and each calls, then each is
  told no verdict was reached, carrying none of the four domain reasons; when the core
  is started again at the same address and each calls once more, then both succeed —
  with neither adapter restarted and neither client rebuilt; and when the core is
  stopped in the middle of a call and started again, then that call reports no verdict
  and the next one succeeds. **Met through the web API alone; the rest waits for a
  leaf of its own.** The assembled run stops the core, calls the web API, starts the
  core at the same address and calls again — with no program restarted and no client
  rebuilt. The MCP half is the one thing here that already runs against a stand-in
  (`ToolProgramTest` shows a core going away and coming back leaving an open
  connection usable), and stopping the core in the middle of a call is a third
  arrangement again. Both are narrowings of this criterion and of nothing else.
- **AC11** (REQ15, EDGE4) — Given the three programs running, when the database is
  stopped and a call is made to each adapter, then each reports no verdict carrying
  none of the four domain reasons; and when the database is started again, then a
  later call to each succeeds with no program restarted.
- **AC12** (REQ17, EDGE7) — Given an agent connected at a project's slug, when that
  project is deleted through the web API, then the agent's next tool call fails as not
  found, no further call on that connection is served, and a fresh connection at the
  same address is refused naming the project; and when a new project is then created
  holding that same slug, then no call the first agent made was carried out against it.
  **Deferred to a leaf of its own.** The behavior is built and checked against a
  stand-in — `Dispatcher.continuing` closes a project's server and stops serving its
  connections once a call has found the project gone, and epic 06's
  `VanishedProjectTest` drives it — so what is missing is the same arrangement over
  the real core and a real store, which is a scenario in its own right rather than a
  step of any other one here.
- **AC13** (REQ18, REQ19) — Given the three programs running, when a well-formed call
  is made to each adapter with no `Authorization` header, then each is refused, the
  core received no call, and the database is unchanged; and when the same calls present
  a valid token, then both are served.
- **AC14** (REQ1, EDGE10) — Given each of the three programs in turn, when it is
  started with one of its settings missing, then it stops and names that setting, and
  the system it belongs to does not come up.

## Definitions

- **the assembled system** — the core service, the MCP server and the web app running
  as three separate programs against one PostgreSQL database whose schema the
  committed changelog produced.
- **the loop** — the sequence PRD-1 calls its north star: a release, an epic, two
  leaves under that epic, a leaf directly under the project, the epic put in the
  release, one leaf made to wait on another, and one listing call asking for the open
  leaves nothing is holding up.
- **leaf** — an item of type task, bug, or chore; **epic** — the one item type other
  items sit under.
- **slug** — the short lowercase name an entity is known by in paths (`Add search`
  becomes `add-search`), usable anywhere its id is.
- **the four domain reasons** — `validation_failed`, `not_found`, `conflict` and
  `cycle`, the four failures a caller can act on
  ([ADR-2](../../../architecture/adrs/adr-2.md)).

The `Authorization` header, bearer tokens, the `sub` claim, HTTP 401, JSON-RPC 2.0's
`result`, `error` and reserved codes, its thirty-second wait, and the loopback address
(`127.0.0.1`) are used as their own specifications define them and are not redefined
here.

## Assumptions

- **ASM1** — An MCP client holds its bearer token as configuration on the entry it
  keeps for a server, and sends it on every request of a connection; if false, no real
  coding agent's client can reach the gated MCP server at all, and the token has to
  travel some other way before this loop can be run by anything but a test.
- **ASM2** — The database the three programs share has had the committed changelog
  applied to it before the core starts; no program applies it at startup. If false,
  the core serves against a schema nothing checked, and bring-up needs a step this
  spec does not describe.
- **ASM3** — PostgreSQL started from the embedded binaries this project's tests
  already use ([ADR-1](../../../architecture/adrs/adr-1.md)) behaves, for everything
  here, as a PostgreSQL an operator installs; if false, the unique constraint and the
  transaction these requirements turn on are being observed somewhere other than where
  they will run.
- **ASM4** — Specs 1 to 6 remain the whole behavior of the eleven operations, of the
  connection to the core, and of the identity that crosses it, and this spec adds no
  rule of its own; if false, the requirements here have no fixed behavior to stand on.
- **ASM5** — Projects hold few enough items that a listing returning everything it
  matches crosses three programs well inside the thirty seconds a call waits, on the
  same terms specs 2, 3, 4 and 5 already assume; if false, the loop's own listing turns
  into a call reporting no verdict, and handing results back a page at a time — deferred
  by the design docs — becomes the fix rather than a tuning exercise.
- **ASM6** — The milestone's one token is minted by hand and outlasts every run made
  against the assembled system ([spec-6](../08-actor-plumbing/spec-6.md)); if false,
  every run fails at the gate on a date nobody chose, for a reason that looks like a
  defect.
