# MCP server — Spec-4

## Overview & scope

This spec is the requirements contract for **the agent's front door**: the app
that lets a coding agent do Nook's work by calling tools, over the Model Context
Protocol — the shared convention by which an agent discovers what another
program can do for it and then calls those things by name. It is the one
translation Nook cannot avoid, because that protocol's shape is set by someone
else's specification rather than by Nook
([ARCHITECTURE §5](../../../ARCHITECTURE.md),
[01 — Interface contracts](../../../docs/01-interface-contracts.md)).
[PRD-1](../prd-1.md) REQ6 asks for it: a connection that names its project, the
operations offered as tools, references given as either an id or a slug, and
errors that arrive as something an agent can act on.

An agent opens a connection at an address that names one project, and every tool
it calls acts inside that project. The server owns no store of its own: it turns
each tool call into a call on the core, through the one client epic 05
built ([spec-3](../05-operation-catalog/spec-3.md)), and turns what comes back
into what the protocol says a tool result is.

**Projects are not part of this surface.** An agent cannot create a project, list
the instance's projects, or delete one, and there is no tool that reads a project
either. Those four operations belong to the human surface — a project is made and
disposed of by a person through the web app, and the agent is handed one to work
in. The rest of the catalog — the seven operations that act on items and
releases inside a project — is what becomes tools here. This is a decision of
this spec, and it narrows what the milestone's PRD describes: PRD-1's north-star
loop is written as running over MCP alone, starting by creating a project, and
its one-contract goal counts all eleven operations as reachable through MCP.
Neither holds once projects leave this surface; the loop starts from a project
that already exists, and four operations are reachable over the web surface only.

**Which project the agent is working in is settled twice over, and never by a
tool.** A person hands an agent a project by configuring the agent's client with
that project's address; the server reads the project out of that address, and out
of nothing else. But a client's configuration is not something an agent itself
gets to read, so the address alone would leave the agent unable to say where it
is. So when a connection opens, the server asks the core about that project once,
before it serves anything: the answer decides whether the address names a real
project — refusing a mistyped one at the door rather than on every call — and it
is also what the server tells the client about the connection, so the agent can
name the project it is in, and read what that project is for, without calling
anything. Nothing in that announcement is reachable through a tool, so an agent
still cannot ask about a project — not its own, and not any other.

**A connection stays honest about that project for as long as it lives.** A
project's slug never changes — project slugs are fixed at creation — so an
address that named a project once goes on naming it, and a configuration written
into a repository keeps working. What can still happen is deletion, and that is
what the connection has to survive honestly, in two parts. It keeps the project's
**id** rather than the words in its address, because a slug is freed when its
project is deleted and can later be given to a different project: an agent
holding the words would quietly begin working in that other project, while an
agent holding the id cannot. And every call already names the project to the
core, so the core's own answer is what reports the disappearance: the first call
to find the project gone ends the connection, rather than leaving an agent to
work on for hours against something that is not there.

In scope: which tools exist and which deliberately do not; how a connection says
which project it is for, how the agent comes to know it, and what keeps the two
in step as the project changes underneath; what an agent may put
in a tool call and what must reach the core unchanged; the three ways a call can
end and how an agent tells them apart; several agents connected at once, to one
project and to different ones; and where the server listens and who may reach it.

Out of scope:

- **The rules the operations enforce** — containment, slugs, the status
  vocabulary, cycles, filtering, ordering, what a delete reaches. Those are
  [spec-1](../03-core-write-path/spec-1.md) and
  [spec-2](../04-structure-queries/spec-2.md); this spec requires only that a
  tool call arrives at them intact and their verdict arrives back intact.
- **The connection to the core** — how a call crosses to the core service and
  what must survive that crossing is [spec-3](../05-operation-catalog/spec-3.md).
  This spec sits on top of it and repeats none of it, including the rule that
  neither app in front of the core — this server or the web app — holds any
  database access.
- **The four project operations** — `create_project`, `get_project`,
  `list_projects`, `delete_project`. They stay in the catalog and stay reachable
  over the web surface (epic 07); they are simply not offered as tools here. The
  server calls `get_project` itself, once per connection, for the announcement
  described above — that is the server using the catalog, not the agent.
- **The web surface itself** — epic 07.
- **How the protocol carries any of this** — which shape a tool result uses to
  hold an entity, which part of the opening exchange carries the project
  announcement, how the server hosts the protocol library's transport, how a
  tool's arguments are described to a client. Those are development-time choices,
  settled by this epic's discovery rather than by a requirements contract; this
  spec states only what must be observably true, whatever shape carries it.
- **Resources** — the tenets and document reads that MCP also offers
  ([01](../../../docs/01-interface-contracts.md),
  [03](../../../docs/03-skills-and-tenets.md)). No document exists yet to serve,
  and tenets arrive with milestone 3. This milestone's server serves none, and
  offers an agent nothing to call but its seven tools.
- **The version stamp on responses** — the marker that tells an agent its copy
  of the tenets is stale ([ARCHITECTURE §5](../../../ARCHITECTURE.md)) belongs
  with the tenets themselves, in milestone 3.
- **Skills** — never served over MCP at all. A skill is distributed into the
  agent's own environment and run there; what it does to Nook, it does by calling
  these tools ([03](../../../docs/03-skills-and-tenets.md)).
- **Who the agent is** — nothing here carries a caller's identity in this
  milestone; the fields that would record it arrive with epic 08.
- **Proving who is calling** — deferred by the design itself
  ([ARCHITECTURE §8](../../../ARCHITECTURE.md)). No credential is asked for and
  none is checked.
- **The other transport** — the protocol also allows a server to be launched as a
  child process and talked to over its input and output streams. Deferred by
  [01](../../../docs/01-interface-contracts.md).
- **Paging, free-text search, and sort options** — deferred by the design docs. A
  listing arrives whole, in one ordering.

## Scenarios

### SCEN1 — An agent finds out where it is

**Initiator:** a coding agent, opening its first connection of a session.
**Flow:**
1. A person has configured the agent's client with the address of the project
   they want worked on.
2. The agent connects, and reads what the server says about the connection.
3. It asks what it can call.
**Outcome:** the agent can name the project it is in — its name, its slug, and
what the project is for — before it has called anything, and without any tool
offering that. The listing then names seven tools and no others, none of which
mentions a project.

### SCEN2 — An agent builds out the project it was pointed at

**Initiator:** a coding agent, in a session, connected to one project.
**Flow:**
1. It creates a release, then an epic, two tasks under that epic, and a
   project-level bug.
2. It calls the update tool twice — once to put the epic in the release, once to
   make the second task wait on the first.
3. It files a second bug by mistake and deletes it.
**Outcome:** every create hands back the whole entity the core made, slug and
timestamps included; the delete reports success and hands back no entity, which
the agent can still tell apart from an error; and every one of those entities
lives in the project the address named.

### SCEN3 — An agent asks what it can work on

**Initiator:** a coding agent, starting a session.
**Flow:**
1. The agent calls the listing tool asking for the leaf types — task, bug and
   chore — with status `todo`, and nothing unfinished holding them up.
2. It reads back the items and picks one.
3. It moves that item to `in_progress`.
**Outcome:** the listing holds exactly the open leaves nothing is holding up, in
the order the core produced them, and the status change lands. The agent never
names its project in any of those calls.

### SCEN4 — An error reaches the agent as something it can fix

**Initiator:** a coding agent, mid-reasoning.
**Flow:**
1. It asks to make two items wait on each other in a circle; the core refuses it
   as a loop.
2. It asks for a slug another item in the project already holds.
3. It asks for an item that does not exist.
4. It asks for a status that is not in the vocabulary.
**Outcome:** each comes back marked as a failed call carrying the core's own
code, its message, and its details — enough for the agent to see what to change
and try again without a person reading a log. Nothing was written in any of the
four cases.

### SCEN5 — The core is not there

**Initiator:** a coding agent, on a machine where the core has not started.
**Flow:**
1. The agent's client tries to open a connection.
2. The core is started, and the client connects.
3. The agent calls a tool, the core is stopped and started again, and the agent
   calls the same tool once more.
**Outcome:** the first attempt is not served, and what it says is that the core
could not be reached — not that the project is missing, which is a different
problem with a different fix. Once the core is up, the connection opens; and the
call that lands while the core is down comes back saying the work could not be
attempted, carrying none of the codes an error carries, so the agent does not go
off rewriting a request that was never the problem. The call after that succeeds
without the agent reconnecting.

### SCEN6 — An agent is pointed at a project that is not there

**Initiator:** a coding agent whose configuration carries a typo.
**Flow:**
1. Its client tries to open a connection at an address naming `serch-revamp`,
   which no project answers to.
2. The agent starts its session.
**Outcome:** the connection is never established, so the agent's client reports
Nook as unavailable and the agent sees no Nook tools at all — rather than a
working-looking server whose every call fails. What the client shows names the
project that was asked for, so the person can see it is their address that is
wrong. No tool call reaches the core, and nothing is written.

### SCEN7 — The project goes away under a working agent

**Initiator:** a person on the web surface, while an agent is mid-session.
**Flow:**
1. An agent has been working in a project for an hour, on one connection.
2. The person deletes that project.
3. The agent calls a tool.
4. Its client, seeing the connection end, opens a new one at the same address.
**Outcome:** the call finds the project gone and says so, and that connection
stops there rather than letting the agent work on for another hour against
something that no longer exists. The fresh connection is refused too, naming the
project — so what the agent is told is that its project is gone, not that Nook is
broken.

### SCEN8 — An agent tries to reach outside its project

**Initiator:** a coding agent connected to one project, holding an id it read
somewhere else.
**Flow:**
1. It asks for an item by an id that belongs to a different project.
2. It asks the listing tool for everything, supplying no filter at all.
**Outcome:** the first comes back as "not found" — the id is real, but not in
this project, and the connection is the only thing that says which project
applies. The second returns every item of the connection's own project and not
one item of any other.

### SCEN9 — Two agents, two projects, at once

**Initiator:** two coding agents in two sessions.
**Flow:**
1. Each connects at the address of its own project.
2. Both create an item at the same moment, and both then list their project.
3. A third agent connects to the first agent's project and lists it too.
**Outcome:** each of the first two listings holds only its own project's items;
the third sees the first project's items, including the one just created; and no
agent's call waited on another agent's call to finish.

### SCEN10 — An edit that clears a field, and an edit that leaves it alone

**Initiator:** a coding agent tidying an item.
**Flow:**
1. The item has a description. The agent sends an update changing only the name.
2. The agent then sends an update that clears the description.
3. The agent then sends an update naming no field at all.
**Outcome:** the first leaves the description as it was, the second empties it,
the third changes nothing and is not refused — three outcomes the agent can tell
apart, because "say nothing about this field" and "set this field to nothing"
are two different things all the way down to the store.

### SCEN11 — Something else on the network tries the server's address

**Initiator:** any program on another machine.
**Flow:**
1. It connects to the server's address from a different machine on the network
   and calls `delete_item` on a project it names in the address.
**Outcome:** it is not served. Nothing is deleted, and no credential was needed
to keep it out, because the server is not reachable from where it called.

## Requirements

### The tool surface

- **REQ1** — The server MUST offer exactly these seven tools: `create_item`,
  `update_item`, `delete_item`, `create_release`, `update_release`, `get_item`,
  `list_items`.
- **REQ2** — The server MUST offer no eighth tool. In particular it MUST offer
  no tool that creates, reads, lists, or deletes a project.
- **REQ3** — Each tool MUST carry the name of the operation it stands for, as
  listed in REQ1.
- **REQ4** — Each tool MUST accept exactly the arguments its operation defines,
  minus the project — no argument beyond them, and none of them dropped.
- **REQ5** — No tool MUST accept a project as an argument, under any name.
- **REQ6** — Every tool MUST appear in the listing a client asks for, each
  carrying a description of what it does and a description of every argument it
  takes. What those descriptions say is settled in development; that each exists
  is required here.
- **REQ7** — The server MUST give an agent no way to reach Nook beyond the seven
  tools: it MUST serve no resources, and MUST announce no other capability the
  protocol defines through which any Nook operation can be called. The protocol's
  own logging channel MAY appear in what a connection is told on opening; it
  reaches nothing of Nook's.

> REQ7 first read "tools and nothing else: no resources, and no other capability
> the protocol defines". The protocol library adds its own logging channel to
> every server it builds, on a line that runs after anything Nook hands it, so
> announcing tools alone means giving up the library's server assembly — a far
> larger change than the wording is worth. The requirement now asks for what that
> wording was protecting: that nothing but a tool reaches a Nook operation. The
> evidence is in [discovery](./discovery.md), FIND12.

### The project a connection is for

- **REQ8** — The project every call acts inside MUST come from the address the
  connection was opened at, and from nothing else.
- **REQ9** — The address MUST accept either the project's id or its slug, and
  MUST resolve both to the same project.
- **REQ10** — Before it serves any tool call on a connection, the server MUST
  establish with the core that the address names a project that exists.
- **REQ11** — When the address names no project that exists — including an address
  that names no project at all — the connection MUST NOT be served, and no tool
  call made on it MUST reach the core.
- **REQ12** — A connection that is not served MUST fail the protocol's opening
  exchange itself, so that an ordinary client reports the server as unavailable
  rather than presenting a working server whose every tool call fails.
- **REQ13** — When the address names no project that exists, what the server
  answers MUST name the project that was asked for, so an agent can see that its
  address is wrong rather than that Nook is broken.
- **REQ14** — When the core cannot be reached as a connection opens, the
  connection MUST NOT be served, and what the server answers MUST say the core
  could not be reached — never that the project does not exist, which is a
  different problem with a different fix.
- **REQ15** — On opening a connection, the server MUST tell the client which
  project that connection is for, giving the project's id, its slug, its name,
  and its description, so that an agent can name what it is working on without
  calling a tool.
- **REQ16** — Those four values MUST be the ones the core holds for that project
  at the moment the connection opened.
- **REQ17** — Nothing an agent can call MUST change which project a connection
  is for.

A connection can outlive the thing it names. A person can delete a project on the
web surface while an agent sits on an open connection for hours, so checking the
address once at the door is not enough on its own. Two things keep a connection
honest without a second round of checking before every call: it holds the
project's **id**, which is never handed to another project, whereas the slug in
its address is freed by deletion and can be given to one — so an agent cannot be
silently redirected into work nobody meant; and the core's own answer to an
ordinary call is what reports the project's disappearance, since every call names
the project anyway. An error does not say by itself which of the references in a
call it was about, so telling "your project is gone" apart from "that item is
gone" is the server's own work — done only on the path that has already failed,
and how it is done is a development-time choice.

- **REQ18** — On opening a connection, the server MUST resolve the address's
  reference to the project's id, and MUST use that id — not the text of the
  address — for every call it makes on that connection.
- **REQ19** — When a call's error shows that the connection's project no longer
  exists, the server MUST stop serving that connection, and MUST NOT serve a
  further tool call on it.
- **REQ20** — An error about an item or a release MUST leave the connection
  serving.
- **REQ21** — Every call MUST act inside the connection's project and no other:
  a reference naming an entity of another project MUST fail exactly as a
  reference naming nothing at all does.
- **REQ22** — A listing MUST return items of the connection's project only.

### What reaches the core

- **REQ23** — The server MUST NOT alter what an agent supplies: nothing trimmed,
  lowercased, reordered, filled in, deduplicated, or dropped, and no argument the
  agent left out supplied on its behalf.
- **REQ24** — For every field of an update that allows both, an agent MUST be
  able to say "leave this field alone" and "set this field to nothing", and the
  two MUST reach the core as different things.
- **REQ25** — An agent MUST be able to express the whole listing filter: each of
  its five parts, and several values within a part where the part takes values.
- **REQ26** — For each part of the filter, "do not filter on this part" and
  "filter on no values at all" MUST reach the core as different things — the
  first means every item matches, the second is a mistake the core refuses.
- **REQ27** — A reference argument MUST reach the core as the agent wrote it,
  whether it is an id, a slug, or neither; what it means is the core's
  decision, not the server's.
- **REQ28** — The server MUST apply no rule of its own to a call it can read:
  every acceptance and every error of such a call MUST be the core's verdict.

### How a call ends

- **REQ29** — Every tool call MUST end in exactly one of three ways: an answer,
  an error, or an internal error — and an agent MUST be able to tell which of the
  three it received.
- **REQ30** — An answer MUST carry what the core produced, whole: every field of
  an entity holding the value the core gave it, and a listing holding every item
  the core matched, in the order the core put them in.
- **REQ31** — `delete_item` MUST report success carrying no entity, and that
  success MUST be distinguishable from an error.
- **REQ32** — An error the core produced MUST reach the agent marked as a failed
  call, carrying the core's code, its message, and its details unchanged.
- **REQ33** — An internal error MUST carry none of the four error codes, and MUST be
  distinguishable by the agent from every error — there is nothing for the
  agent to fix, so it must not read as though there were.
- **REQ34** — The server MUST NOT send a call to the core a second time on an
  agent's behalf, after any ending whatsoever.
- **REQ35** — A tool call the server cannot read MUST NOT reach the store, and
  MUST reach the agent as a failure naming what was wrong with it, never as an
  answer. Where the call names one of the seven tools but its arguments do not
  fit it — an argument the tool requires is missing, an argument it does not
  define is present, or a value is of the wrong kind — the failure MUST name the
  argument at fault; it need not carry `validation_failed`, since the call is
  turned back before any Nook code runs. Where the call names a tool the server
  does not offer at all, including one of the four project operations, the
  failure MUST name the tool that was asked for, and MAY be the protocol's own
  error for a call it cannot route rather than an error of Nook's — the protocol
  carries an internal error inside the server the same way, so naming the tool is what lets
  an agent tell the one it can fix from the one it cannot.
- **REQ36** — An internal error or an error MUST leave the connection usable, the next
  call on it being served normally — except for the one error REQ19 names,
  where there is no longer a project to serve.

> REQ35 first required `validation_failed` for all four of these. The protocol
> library turns every one of them back before Nook's code runs — the three
> argument cases as a failed call in the library's own wording, an unknown tool
> name as the protocol's own error — so at the point they are decided there is no
> Nook code to attach a code. Producing one anyway would mean describing every
> tool's arguments loosely enough for the call to get through and re-checking it
> in Nook's code, where a gap writes bad data instead of refusing it: with the
> library's checking removed and nothing in its place, a call carrying no name
> created an item with no name. The requirement now asks for what the code was
> there to buy — the agent is told what was wrong, and nothing is written. The
> evidence is in [discovery](./discovery.md), FIND7 and FIND11.

### Several agents at once

- **REQ37** — The server MUST serve several connections at the same time, bound
  to the same project or to different ones.
- **REQ38** — The server MUST NOT make one connection's call wait on an
  unrelated call of another connection.
- **REQ39** — A call made on one connection MUST NOT be affected by any other
  connection's project.
- **REQ40** — When the core becomes reachable again after being unreachable, a
  connection MUST become usable again on its own: a later call MUST succeed
  without the agent reconnecting and without the server being restarted.

### Where the server listens

- **REQ41** — The server MUST serve the protocol over long-lived HTTP — the
  transport the protocol calls streamable HTTP, where a client posts to one
  address and the server may answer over a stream held open on that same address
  — at an address of the form `/mcp/{projectRef}`.
- **REQ42** — An unmodified MCP client MUST be able to complete the protocol's
  `initialize` exchange, list the tools, and call them, with no change made to that
  client for Nook's sake.
- **REQ43** — The server MUST NOT serve a tool call on a connection that has not
  completed the protocol's `initialize` exchange.
- **REQ44** — The server MUST serve only callers on the machine it runs on; a
  call arriving from any other machine MUST NOT be served.
- **REQ45** — The server MUST require no credential in this milestone: an agent
  presents none and the server checks for none. REQ44 is the whole of its
  protection.
- **REQ46** — The address the server listens on, and the address of the core it
  calls, MUST be settable from outside the program rather than fixed in its code.
- **REQ47** — Started without either of those addresses, the server MUST stop
  with a message naming what is missing, rather than starting on a default
  address nobody chose.

## Edge cases

- **EDGE1** — The address names a project that does not exist: the connection is
  not served, and the answer names the project asked for. This covers the way a
  real setup most often breaks — an agent client configured with a placeholder
  that was never filled in sends the placeholder itself as the project, and
  repeating it back is what tells the person their configuration never took
  effect. The reference is read as plain text, with whatever escaping the address
  form required undone first, so what comes back is what a person would recognize
  as the thing they wrote.
- **EDGE2** — The address carries no project at all: not served, exactly as an
  address naming a project that does not exist is not served — there is no way in
  that leaves the project unsaid.
- **EDGE3** — The address names the project by its slug in one connection and
  by its id in another: both reach the same project, and both are told the same
  four values about it.
- **EDGE4** — The core cannot be reached as a connection opens: the connection is
  not served, and the answer says the core could not be reached rather than
  naming the project as missing.
- **EDGE5** — The project is deleted through the web surface while an agent is
  connected: the next call fails with `not_found`, that connection stops being
  served, and a fresh connection at the same address is refused naming the
  project.
- **EDGE6** — A project is deleted and a new project is later given its slug,
  while an agent is connected at that slug: the agent's connection stops being
  served at its first call after the deletion, and no call of its is ever served
  against the new project.
- **EDGE7** — A tool call carrying an argument the tool does not define: a failed
  call naming that argument, and nothing reaches the store.
- **EDGE8** — A tool call missing an argument its tool requires: a failed call
  naming that argument.
- **EDGE9** — A tool call whose argument holds a value of the wrong kind — a
  number where text belongs, text where a list belongs: a failed call naming that
  argument.
- **EDGE10** — A call naming a tool the server does not offer, including one of
  the four project operations: a failure naming that tool — the protocol's own
  error for a call it cannot route — and nothing reaches the store.
- **EDGE11** — A reference that is a well-formed id belonging to another project:
  `not_found`.
- **EDGE12** — A reference that looks like an id but is not a well-formed one:
  reaches the core as written, and the core decides what it means.
- **EDGE13** — A name or description carrying emoji, non-Latin script, line
  breaks, or quotation marks: stored exactly as the agent sent it.
- **EDGE14** — An update naming no field at all: reaches the core as an update
  naming no field, and comes back as the core's own do-nothing answer rather
  than being refused or discarded before it is sent.
- **EDGE15** — A description set to empty text, against one left unmentioned:
  the first empties the field, the second leaves it alone.
- **EDGE16** — A blocker list supplied empty: reaches the core empty and clears
  the set, never mistaken for no list having been supplied.
- **EDGE17** — A filter part supplied with no values: reaches the core that way
  and is refused with `validation_failed`, never quietly turned into "do not
  filter on this part".
- **EDGE18** — A listing of 5,000 items: arrives whole, in order, in one answer.
- **EDGE19** — The core is not running when a tool is called: an internal error, and a
  call made after the core comes up succeeds on the same connection.
- **EDGE20** — The core is stopped in the middle of a call: an internal error, not a
  error.
- **EDGE21** — An internal error inside the core: an internal error carrying none of the four
  error codes.
- **EDGE22** — Two agents connected to the same project create an item of the
  same name at the same moment: both succeed, with different slugs.
- **EDGE23** — Two agents connected to different projects list at the same
  moment: neither listing holds one item of the other's project.
- **EDGE24** — A client asks for resources: it is told there is none. A client
  that reaches for the protocol's own logging channel reaches no Nook operation.
  Nothing is written in either case.
- **EDGE25** — A tool call arrives before the protocol's `initialize` exchange has
  completed: it is not served, and nothing reaches the store.
- **EDGE26** — A well-formed call arriving from another machine: not served.
- **EDGE27** — The server is started with the core's address unset: it stops,
  naming the missing setting.

## Acceptance criteria

- **AC1** (REQ1, REQ2, REQ3, REQ7) — Given a running server and a project, when
  an ordinary MCP client connects and asks what it can call, then the listing
  holds exactly the seven tools of REQ1 under those names, holds no tool that
  names a project in any way, and what the connection is told on opening offers
  no resources and nothing beyond tools through which a Nook operation could be
  called — the protocol's own logging channel aside.
- **AC2** (REQ4, REQ5, REQ6) — Given that listing, when each tool's description
  of its arguments is compared against its operation's own arguments, then the
  two match one for one with the project left out, no tool offers an argument its
  operation does not define, and every tool and every argument carries a
  description that is not empty.
- **AC3** (REQ9, REQ15, REQ16, REQ17, EDGE3) — Given a project with a description, when
  a client connects at its slug and another connects at its id, then each is
  told the project's id, slug, name and description on opening, both are told
  the same four, and each equals what `get_project` returns from the core; and
  when every tool call either client can make is examined, then none of them
  changes which project its connection is for.
- **AC4** (REQ8, REQ21, REQ22, EDGE11) — Given two projects, each holding items,
  when an agent connected to the first lists items, then it gets exactly the
  first project's items; and when it asks for an item of the second project by
  that item's id, then the call fails with `not_found`.
- **AC5** (REQ10, REQ11, REQ13, EDGE1, EDGE2) — Given a running server, when a
  client connects at an address naming a project no project answers to, and again
  at an address naming no project at all, then neither connection is served, the
  first answer names the project that was asked for, and the core received no tool
  call from either.
- **AC6** (REQ12) — Given an ordinary MCP client configured against an address
  naming no project that exists, when it starts, then it reports the server as
  unavailable — the opening exchange having failed — rather than listing the
  server as working with tools that fail when called.
- **AC7** (REQ12, REQ14, EDGE4) — Given a server whose core is not running, when a
  client connects, then the connection is not served, the client reports the
  server as unavailable, and the answer says the core could not be reached,
  carrying no claim that the project is missing.
- **AC8** (REQ19, REQ36, EDGE5) — Given an agent connected to a project that is
  then deleted through the core, when it calls a tool, then that call fails with
  `not_found`, every later call on that same connection is not served, and a
  fresh connection at the same address is refused naming the project.
- **AC9** (REQ20) — Given a connected agent, when it asks for an item that does
  not exist and then calls a tool that succeeds, then the first fails with
  `not_found` and the second is served normally on the same connection.
- **AC10** (REQ18, EDGE6) — Given an agent connected at a project's slug, when
  that project is deleted and a new project is created holding the same slug,
  then the agent's next call is its last — the connection stops being served —
  and no call it made was carried out against the new project.
- **AC11** (REQ23, REQ27, EDGE12, EDGE13) — Given tool calls carrying a name with
  emoji, a description with line breaks and quotation marks, a blocker list
  holding the same reference twice, and a reference that is nearly but not quite
  a well-formed id, when each is called, then the values the client is
  invoked with equal the values the agent supplied, one for one — same text, same
  reference, and the blocker list still holding its duplicate — and the stored
  item's name and description are the text that was sent.
- **AC12** (REQ24, EDGE14, EDGE15) — Given a task with a description, when an
  update changes only its name, then the description is unchanged; when a later
  update sets the description to nothing, then it is empty; and when a later
  update names no field, then the call succeeds with nothing changed.
- **AC13** (REQ25, REQ26, EDGE16, EDGE17) — Given a project holding items of
  several types, some held up and some not, when `list_items` is called with each
  filter part alone and with all five together, then each answer matches the same
  filter called directly on the core; when it is called with a type part holding
  no values, then it fails with `validation_failed`; and when `update_item` is
  called with an empty blocker list, then the item's blocker set is empty
  afterwards.
- **AC14** (REQ28, REQ30, REQ31) — Given a project, an item under an epic with two
  blockers, and a release with a target date, when each is fetched through a tool,
  then each entity equals the entity the core produced, field for field —
  comparing the whole entity, not a named subset, so a field added later is
  covered without this check being edited; and when `delete_item` is called, then
  it reports success, carries no entity, and is distinguishable from an error.
- **AC15** (REQ30, EDGE18) — Given a project holding 5,000 items, when it is
  listed through the tool, then all 5,000 arrive in one answer, in the same order
  the core produced, within the timeout.
- **AC16** (REQ29, REQ32) — Given calls that produce each of the four error
  codes in turn, when each is made as a tool call, then each arrives marked as a
  failed call carrying that same code, the core's message, and the core's
  details, and the store is unchanged.
- **AC17** (REQ29, REQ33, REQ36, EDGE21) — Given an internal error deliberately planted
  inside the core, when a tool call hits it, then the agent receives an internal error
  carrying none of the four codes, can tell it apart from every error, and the
  next call on the same connection is served normally.
- **AC18** (REQ35, EDGE7, EDGE8, EDGE9, EDGE10) — Given a call missing a required
  argument, one carrying an argument its tool does not define, and one carrying a
  value of the wrong kind, when each is sent, then each comes back as a failed
  call naming the argument at fault; and given a call naming a tool the server
  does not offer and one naming `create_project`, when each is sent, then each
  comes back as a failure naming the tool that was asked for, told apart by the
  agent from an internal error inside the server; and in all five the store is unchanged
  and the server serves the next well-formed call normally.
- **AC19** (REQ34) — Given a call that ends in a dropped connection, in the wait
  limit running out, and in each of the four errors, when each ends, then the
  core received that request exactly once.
- **AC20** (REQ40, EDGE19, EDGE20) — Given an agent connected while the core is
  running, when the core is stopped and the agent calls a tool, then the call is a
  internal error; when the core is started again and the same agent calls on the same
  connection, then the call succeeds; and when the core is stopped mid-call and
  started again, then that call is an internal error and the next call succeeds — with
  the agent never reconnecting and the server never restarted.
- **AC21** (REQ37, REQ38, REQ39, EDGE22, EDGE23) — Given two projects and three
  connected agents — two on the first project, one on the second — when all three
  call at once, with the two on the first project creating an item of the same
  name (repeated 100 times) and one call deliberately made slow, then every run
  leaves two items with different slugs in the first project, the second
  project's listing holds none of them, and the fast call is answered before the
  slow one.
- **AC22** (REQ41, REQ42, REQ43, EDGE24, EDGE25) — Given a running server, when
  an unmodified MCP client connects, completes the `initialize` exchange, lists the
  tools and calls one, then all four succeed with no client change made for
  Nook's sake; and when a tool call is sent before that handshake completes, or a
  resource is asked for, then neither is served and the store is unchanged.
- **AC23** (REQ44, REQ45, EDGE26) — Given a running server, when a well-formed
  call is made from the same machine with no credential, then it is served; and
  when the same call is made to the server's address from another machine, then
  it is not served and the store is unchanged.
- **AC24** (REQ46, REQ47, EDGE27) — Given a server configured with both addresses
  from outside the program, when it starts and a call is made, then it succeeds;
  and when it is started with either address unset, then it stops with a message
  naming the missing setting rather than starting on a default.
- **AC25** (REQ1, REQ8, REQ30) — the milestone's loop. Given a project created
  through the core and nothing else, when one agent, over MCP alone, creates a
  release, an epic, two tasks under it, a project-level bug, puts the epic in the
  release, makes the second task wait on the first, and then calls `list_items`
  asking for the leaf types, status `todo`, and nothing holding them up, then the
  answer holds exactly the open leaves nothing is holding up — the first task and
  the bug, not the second task — in the core's own order, and the run passes
  unattended against local Postgres.
- **AC26** (REQ3, REQ21, REQ28) — Given the acceptance criteria of
  [spec-1](../03-core-write-path/spec-1.md) and
  [spec-2](../04-structure-queries/spec-2.md) that exercise the seven operations
  of REQ1, when each is driven through the matching tool instead of against the
  core directly, then each reaches the same verdict it reaches in-process.

## Definitions

- **tool** — something the protocol lets an agent call in the middle of its own
  reasoning, named and described by the server so the agent knows what it does.
  Nook's seven are the seven operations of REQ1.
- **connection** — one client's link to the server, opened at an address that
  names a project and lasting across many tool calls.
- **the connection's project** — the project named in the address a connection
  was opened at; the only project any call on that connection can reach.
- **the tool listing** — what the server hands a client that asks what it can
  call: every tool, with its description and its arguments.

- **the core** — the core service, which owns the store and the single write
  path; **the client** — the one piece of code, shared by both adapters,
  that calls it and reads its replies
  ([spec-3](../05-operation-catalog/spec-3.md)).
- **slug** — the short lowercase name an entity is known by in paths (`Add
  search` becomes `add-search`), usable anywhere its id is; **reference** — a
  string naming an entity, either an id or a slug.
- **an error the agent can fix** — a call turned down because the request was
  wrong. Either the core turned it down, carrying `data.reason` of
  `validation_failed`, `not_found`, `conflict`, or `cycle`; or the protocol
  library turned it back before Nook's code ran, carrying its own wording and
  naming the argument or the tool at fault (REQ35).

The protocol's own terms — a tool, a tool result, `isError`, the `initialize`
exchange — and JSON-RPC 2.0's `result`, `error`, `code`, `message`, `data`, its
reserved codes and its 30-second timeout ([spec-3](../05-operation-catalog/spec-3.md))
are used as their specifications define them and are not redefined here.
- **leaf** — an item of type task, bug, or chore; **epic** — the one item type
  other items sit under.

## Assumptions

- **ASM1** — Specs 1, 2 and 3 remain the whole behavior of the seven operations
  and of the crossing to the core, and this spec adds no rule of its own about
  what an operation does; if false: the requirements here have no fixed behavior
  to be checked against.
- **ASM2** — A person creates a project through the web surface and points an
  agent at it by configuring that agent's client with the project's address, so
  an agent always has an existing project to name and never chooses one itself;
  if false: REQ2 leaves an agent with no way to begin, and the surface needs a
  bootstrap this spec does not give it.
- **ASM3** — A project's slug is fixed when the project is created and no
  operation ever changes it, so an address naming a project by its slug names
  that same project for as long as it exists. Every major agent client keeps its
  server list either in the repository it belongs to or in the person's own
  configuration, so an address is written once and committed rather than retyped;
  if false: every committed address breaks the day someone renames a project, and
  the surface needs either a record of slugs a project used to have or an
  address that carries only ids.
- **ASM4** — An agent's client passes what the server says on opening into the
  agent's own context, which is what makes REQ15 the answer to "which project am
  I in"; if false: the agent is left reading its project out of tool results, and
  the surface needs a way to ask that this spec deliberately withholds.
- **ASM5** — The agent, the server, and the core all run on one machine in this
  milestone ([ARCHITECTURE §8](../../../ARCHITECTURE.md)); if false: REQ44 blocks
  the deployment outright, and the surface needs authentication before it can be
  reached from anywhere else.
- **ASM6** — The official Java MCP SDK's long-lived-HTTP server transport works
  as its documentation describes; it was chosen on documentation alone and never
  run (epic 01's discovery says so itself); if false: the transport requirements
  need a different mechanism underneath them, though not different behavior.
- **ASM7** — Projects hold few enough items that a listing returning everything
  it matches crosses well inside the timeout
  ([spec-2](../04-structure-queries/spec-2.md) and
  [spec-3](../05-operation-catalog/spec-3.md) make the same assumption); if
  false: a large listing turns into an internal error, and handing results back a page
  at a time — deferred by the design docs — becomes the fix rather than a tuning
  exercise.
- **ASM8** — An agent's client keeps one connection across a session rather than
  opening one per call, so both binding the project to the connection and
  announcing it once at the handshake are worth more than naming it per call; if
  false: the announcement is repeated work on every call and the address-per-
  project design costs more than it saves, though nothing here becomes wrong.
