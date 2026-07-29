# Actor plumbing — Spec-6

## Overview & scope

This spec is the requirements contract for **who a change is attributed to**, and
for how Nook comes to know. [PRD-1](../prd-1.md) REQ8 asks for two things: every
mutation recording who made it, and every project recording who owns it — the
seam that later milestones assume, and the one column the design calls the whole
of per-customer separation later on
([08 — Deployment & cloud](../../../docs/08-deployment-and-cloud.md)).

Two identities are recorded, not one, because on the agent surface two parties are
involved in every write: a coding agent does the work, and a person the agent is
working for is the one whose project it is. Recording only the agent loses the
person; recording only the person loses which agent to ask about a change. OAuth's
token exchange standard ([RFC 8693](https://www.rfc-editor.org/rfc/rfc8693)) splits
exactly this pair — the party acted for, and the party acting — and Nook follows
that split rather than inventing one.

**Both front doors gain a gate, and this is the change that reverses a settled
decision.** [Spec-4](../06-mcp-server/spec-4.md) REQ45 and
[spec-5](../07-web-api/spec-5.md) REQ31 each say the surface requires no
credential; both are superseded here. A call to either door now carries a bearer
token — the standard way a caller presents an already-issued identity over HTTP —
and a call without one is turned away. The person is the token's `sub` claim, the
established name for "who this token is about". This is what makes the identity
the same whichever door a call arrives at, which is the whole point of gating both
at once rather than one at a time.

**The token is not issued by anything Nook runs.** The Model Context Protocol's
authorization specification makes a server an OAuth 2.1 *resource server* — it
checks tokens, it does not hand them out — and puts the server that issues them
outside its own scope
([MCP authorization](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)).
Nook has no such server and this milestone builds none. Instead **one token is
minted once, by hand, and written into configuration**: the agent's client sends
it, the web app's caller sends it, and both doors check it against the key they
were configured with. Everything downstream of that check is the real mechanism,
so putting a login server in front later replaces where a token comes from and
nothing else.

In scope: the two identities and the fields that hold them; where each door learns
them; the gate on both doors and what it turns away; what crosses to the core and
what the core does with it; what a caller may not set; and what each program is
told from outside before it starts.

Out of scope:

- **A login server, and any flow that issues a token.** Signing in, consent
  screens, refreshing an expiring token, and the discovery documents an MCP client
  uses to find out where to sign in ([RFC 9728](https://www.rfc-editor.org/rfc/rfc9728))
  all belong with that server, deferred to
  [08](../../../docs/08-deployment-and-cloud.md). A discovery document must name a
  server to send a client to, and there is none to name.
- **Permissions — who may do what.** Every caller Nook accepts may call every
  operation. Recording who acted is not deciding what they are allowed to do, and
  no operation refuses anyone on account of who they are.
- **Reading by actor.** No filter asks for one person's items, no listing narrows
  by owner, and no operation sorts by who wrote a row. The fields are written and
  read back on the entity, and that is all
  ([spec-2](../04-structure-queries/spec-2.md) settles the filter, and this epic
  adds nothing to it).
- **Per-owner separation.** Every caller sees every project. `ownerSubject` is
  recorded so that narrowing by it is a filter later rather than a migration; this
  milestone does not narrow.
- **The rules the operations enforce** — containment, slugs, the status
  vocabulary, cycles, ordering, what a delete reaches
  ([spec-1](../03-core-write-path/spec-1.md),
  [spec-2](../04-structure-queries/spec-2.md)). This spec adds fields to what those
  operations write and takes nothing away from what they check.
- **Documents.** The document table carries the same audit columns and no
  operation in this milestone writes it, so the acting agent is added to the three
  tables the eleven operations write and to no others; the document layer adds its
  own in milestone 2 ([02](../../../docs/02-document-layer.md)).
- **HTTPS, and where a request came from.** A token travels in the clear on the
  loopback address (`127.0.0.1`), which nothing outside the machine can route to;
  encrypting the hop and checking a request's origin arrive with the login server
  ([ARCHITECTURE §8](../../../ARCHITECTURE.md)).
- **The assembled run.** Checks needing the store, the core and both doors running
  at once belong to [epic 09](../09-full-system-test/), on the bargain the two
  adapter epics already took; this epic's own checks run against a stand-in core.

## Scenarios

### SCEN1 — An agent does a person's work

**Initiator:** a coding agent, on the agent surface.
**Flow:**
1. A person mints the token once and writes it into the agent's client
   configuration, alongside the project's address.
2. The agent opens a connection, naming itself in the protocol's opening exchange.
3. It calls the tool that creates a task, then the tool that changes its status.
**Outcome:** the task records the person as the one it was created for and the
agent as the one that created it, and the status change records the same pair
again. Nobody typed a name into a tool call: the person came from the token and
the agent from what its own client already announces.

### SCEN2 — A person works directly

**Initiator:** a person, through the web API.
**Flow:**
1. They call `create_project`, presenting the same token.
2. They call `create_item` in it.
**Outcome:** the project records them as its creator and as its owner; the item
records them as its creator. Neither records an acting agent, because no agent
acted — the field is empty rather than repeating the person's own name.

### SCEN3 — A person takes over what an agent started

**Initiator:** a person, through the web API.
**Flow:**
1. An agent created a task an hour ago.
2. The person changes that task's name.
3. Someone reads the task.
**Outcome:** the task still names the agent as what created it, and names nobody
as what last changed it — the person acted alone, so the acting agent is cleared
rather than left saying an agent made a change it did not make.

### SCEN4 — A call arrives with no token

**Initiator:** a program written before the gate existed.
**Flow:**
1. It sends a well-formed `create_item` call to the web API with no
   `Authorization` header.
2. An agent's client opens a connection to the agent surface with none either.
**Outcome:** both are turned away as unauthorized, and each says how a caller is
meant to present a token. Nothing was created, and the store is untouched.

### SCEN5 — A token that does not hold up

**Initiator:** a program with a stale or wrong configuration.
**Flow:**
1. It presents a token signed by something else.
2. It presents a token whose expiry has passed.
3. It presents a token meant for a different program entirely.
**Outcome:** each is turned away as unauthorized, told apart from "your request
was wrong" — there is nothing in the call to fix, and the person fixes their
configuration instead. Nothing reached the store in any of the three.

### SCEN6 — Two people's agents, at the same moment

**Initiator:** two agents, each with its own token, working in the same project.
**Flow:**
1. Both create an item at the same moment.
2. Someone lists the project.
**Outcome:** each item records the person its own token named, and the agent its
own connection announced. Neither call's identity leaked into the other's row.

### SCEN7 — A caller tries to say who it is

**Initiator:** a program trying to attribute a write to somebody else.
**Flow:**
1. It sends `create_project` carrying an `ownerSubject` field.
2. It sends `create_item` carrying a `createdBy` field.
3. It calls the tool that creates an item, adding an argument naming a person.
**Outcome:** every one is refused as an argument the operation does not define,
by the rule that was already there for any field nobody defined. Who a call is
for is settled by the token and by nothing a caller can write.

### SCEN8 — Bringing the doors up

**Initiator:** an operator starting the milestone's programs.
**Flow:**
1. Both doors are started, each told from outside what to check a token against.
2. A call passes end to end and its row is read back.
3. A second copy of each door is started with that setting missing.
**Outcome:** the first two serve and the row names the right person; each second
copy stops immediately and names the setting it is missing, rather than starting
up and accepting tokens nobody vouched for.

## Requirements

### What a row records

- **REQ1** — On every entity it creates, the system MUST record the person the
  call was made for, in `createdBy`, and the same value in `updatedBy`.
- **REQ2** — On every entity it changes, the system MUST replace `updatedBy` with
  the person the changing call was made for.
- **REQ3** — On every entity it creates, the system MUST record the acting agent's
  name in `createdByAgent`, and the same value in `updatedByAgent`; where no agent
  acted, both MUST hold nothing.
- **REQ4** — On every entity it changes, the system MUST replace `updatedByAgent`
  with the acting agent of the changing call, and MUST clear it where no agent
  acted.
- **REQ5** — When `create_project` runs, the system MUST record the person the
  call was made for as that project's `ownerSubject`, and MUST NOT alter it
  afterwards.
- **REQ6** — Every entity the eleven operations hand back — project, item, and
  release — MUST carry `createdBy`, `updatedBy`, `createdByAgent` and
  `updatedByAgent`, and a project MUST also carry `ownerSubject`; on every door,
  from every operation that returns an entity.
- **REQ7** — When a client names itself with more than 200 characters, the agent
  surface MUST NOT serve its connection — the name is recorded on every row that
  connection writes, and a name the store cannot hold is not one to shorten
  silently.
- **REQ8** — No operation MUST accept any of those five as an argument. A request
  naming one MUST be refused as a field the operation does not define, carrying
  `validation_failed`.
- **REQ9** — When an item's blocker set is replaced, the system MUST record the
  change on the item; the blocker edges themselves MUST record nobody.
- **REQ10** — The two deletes MUST record nothing anywhere: the rows are gone, and
  no trace of who removed them is kept.

### Who a call is for

- **REQ11** — Both doors MUST take the person a call is for from the `sub` claim
  of the bearer token the call presents, and from nothing else.
- **REQ12** — The agent surface MUST take the acting agent's name from the name
  the client gives for itself in the protocol's `initialize` exchange
  (`clientInfo.name`), and MUST use it for every call on that connection.
- **REQ13** — The web API MUST record no acting agent on any call.
- **REQ14** — Neither door MUST let a caller name the person or the agent by any
  other route: not in the request, not in an operation's arguments, not in a tool
  call, and not in a header of its own.

### The gate on both doors

- **REQ15** — Both doors MUST refuse any call that does not present a valid bearer
  token, with HTTP status 401, and MUST NOT serve it.
- **REQ16** — A token MUST be refused when it is absent, when it does not verify
  against what the door was configured with, when it was issued for a different
  recipient than that door, or when its expiry has passed.
- **REQ17** — A token that verifies but carries no usable `sub` — absent, empty,
  only spaces, longer than 200 characters, or holding a NUL character — MUST be
  refused on the same terms.
- **REQ18** — Every such refusal MUST carry the `WWW-Authenticate` header, as
  OAuth 2.1 requires, so a caller learns how it is meant to present a token.
- **REQ19** — A refused call MUST NOT reach the core, and MUST leave the store
  unchanged.
- **REQ20** — A refusal on account of the token MUST be distinguishable from every
  refusal of a request's contents: the numeric status differs, and the reply MUST
  carry none of the four domain reasons ([ADR-2](../../../architecture/adrs/adr-2.md)),
  because there is nothing in the call for its caller to correct.
- **REQ21** — On the agent surface, the token MUST be required on every HTTP
  request of a connection, including the `initialize` exchange that opens it — so
  a connection cannot be opened, and its tools cannot be listed, without one.
- **REQ22** — A refused call MUST leave the door serving: the next call presenting
  a valid token MUST be served normally.

### What crosses to the core

- **REQ23** — Each door MUST tell the core who a call is for, as two values
  alongside the request rather than inside it: `Nook-Subject` carrying the person,
  and `Nook-Agent` carrying the acting agent where there is one.
- **REQ24** — Neither door MUST pass the caller's token on to the core. The
  protocol's own security rules forbid handing a token received from a client to
  anything behind the surface, and the core is behind this one.
- **REQ25** — The request either door accepts MUST hold nothing about who is
  calling: the same fields under the same names as before this epic, nothing
  added, nothing renamed, nothing dropped — so a request written before it is
  still the request afterwards.
- **REQ26** — The core MUST record exactly the two values it was told, altering
  neither: nothing trimmed, lowercased, filled in, or substituted.
- **REQ27** — A mutation reaching the core naming no person MUST be refused,
  carrying `validation_failed`; a read naming none MUST be served.
- **REQ28** — The core MUST ask its callers for no credential of its own, as
  before: only the machine it runs on can reach it, and that stays the whole of
  its protection ([spec-3](../05-operation-catalog/spec-3.md)).

> The two names in REQ23 are Nook's own, and that is a deliberate exception. No
> published specification registers a header for a front door telling a service
> behind it whose identity it has already checked: the standards in this area all
> describe a caller presenting a credential, and the one that would apply — handing
> the caller's own token onward — is the one the protocol's security rules forbid
> outright (REQ24). So the meaning is taken from RFC 8693's pair, the party acted
> for and the party acting, and only the two names are Nook's. They are read
> nowhere but on the connection between a door and the core.

### Configuration

- **REQ29** — What each door checks a token against MUST be settable from outside
  the program rather than fixed in its code.
- **REQ30** — Started without that setting, a door MUST stop with a message naming
  what is missing, rather than starting up and accepting tokens nobody vouched
  for.

## Edge cases

- **EDGE1** — A call with no `Authorization` header: 401, carrying
  `WWW-Authenticate`, and nothing written.
- **EDGE2** — A token signed by something the door was not configured with: 401 —
  never served, and never quietly downgraded to a call with no identity.
- **EDGE3** — A token whose expiry has passed: 401. The milestone's own token is
  minted to outlast the milestone, so this is a misconfiguration rather than an
  everyday event.
- **EDGE4** — A token issued for a different recipient: 401. Accepting one would
  let a token minted for something else act here.
- **EDGE5** — A token that verifies and carries no `sub`, an empty one, or one
  longer than 200 characters: 401 — a token Nook cannot attribute a write to is
  not a token it can act on.
- **EDGE6** — An `Authorization` header carrying something that is not a bearer
  token at all: 401, on the same terms as EDGE1.
- **EDGE7** — An agent's client that opens a connection without a token: the
  connection is not opened, so no tool is listed and none can be called.
- **EDGE8** — An agent's client that gives no name for itself in the opening
  exchange: the connection is served, and its writes record the person with no
  acting agent — the protocol asks a client for a name, and a client that gives
  none is not a reason to turn work away.
- **EDGE9** — A person changing a row an agent created: `createdByAgent` stays as
  it was and `updatedByAgent` becomes empty.
- **EDGE10** — An agent changing a row a person created: `createdByAgent` stays
  empty and `updatedByAgent` names the agent.
- **EDGE11** — An update naming no field at all: succeeds, as before. Whether
  `updatedBy` and `updatedByAgent` advance is don't care, on the same terms
  [spec-1](../03-core-write-path/spec-1.md) EDGE7 set for `updatedAt` — no
  behavior may depend on it.
- **EDGE12** — A create whose call is refused for any other reason — a taken slug,
  a name that derives to nothing: nothing is recorded, because no row was written.
- **EDGE13** — Deleting an epic, which takes its children: no row anywhere records
  who deleted them, the rows being gone.
- **EDGE14** — A project row's acting agent: always empty in this milestone,
  because a project is created only through the web API and no agent reaches those
  four operations. The field exists on projects all the same, so that the rule
  "every row that records who wrote it records what agent was acting" needs no
  exception.
- **EDGE15** — A `sub` carrying emoji, non-Latin script, or spaces inside it:
  recorded exactly as the token holds it, and read back the same. Only the five
  cases of REQ17 are refused.
- **EDGE16** — Two agents connected at once with different tokens: each call's
  row records that call's own person and agent, never the other's.
- **EDGE17** — A mutation reaching the core naming no person, which is what a door
  with a defect would send: refused as `validation_failed`, so the store's own
  fallback value can never be reached through the connection.
- **EDGE18** — A door started with nothing to check tokens against: it stops,
  naming the missing setting.
- **EDGE19** — A client naming itself with more than 200 characters: its
  connection is not served, and the answer says the name is too long. No real
  client does this, and shortening the name to fit would record a name that is not
  the client's.

## Acceptance criteria

- **AC1** (REQ1, REQ3, REQ6, REQ12) — Given a connection to the agent surface
  presenting a token whose `sub` is `alex`, opened by a client naming itself
  `claude-code`, when the tool that creates an item is called, then the entity
  handed back carries `createdBy` and `updatedBy` of `alex` and `createdByAgent`
  and `updatedByAgent` of `claude-code`, and reading the same item back gives the
  same four values.
- **AC2** (REQ1, REQ3, REQ5, REQ6, REQ13, EDGE14) — Given the web API and the same
  token, when `create_project` is called and then `create_item` inside it, then
  both entities carry `createdBy` and `updatedBy` of `alex` and no acting agent at
  all, and the project carries `ownerSubject` of `alex`.
- **AC3** (REQ2, REQ4, EDGE9, EDGE10, EDGE11) — Given an item created on the agent
  surface by `claude-code` for `alex`, when a call on the web API with `alex`'s
  token changes its name, then `createdByAgent` still reads `claude-code` and
  `updatedByAgent` is empty; given an item created on the web API, when an agent
  changes it, then `createdByAgent` is empty and `updatedByAgent` names the agent;
  and when an update naming no field at all is sent, then it succeeds, whatever
  the two fields hold afterwards.
- **AC4** (REQ5) — Given a project created for `alex`, when every operation in the
  catalog has been called against it in turn, then its `ownerSubject` still reads
  `alex`.
- **AC5** (REQ6) — Given a project, an item under an epic with two blockers, and a
  release, when each is read through both doors, then all five fields are present
  on every entity, and each door's entity equals the other's field for field.
- **AC6** (REQ8, REQ14, EDGE12) — Given requests carrying `ownerSubject`,
  `createdBy`, `updatedBy`, `createdByAgent` and `updatedByAgent` in turn, when
  each is sent to either door and when the same is attempted as a tool argument,
  then every one fails with `validation_failed` naming the field and the store is
  unchanged; when a call presents a valid token and also carries a header of its
  own naming a different person, then the row records the token's person; and when
  a create is refused for a slug another item already holds, then no row exists to
  have recorded anyone.
- **AC7** (REQ9, REQ10, EDGE13) — Given a leaf with two blockers, when its blocker
  set is replaced, then the item's `updatedBy` and `updatedByAgent` are the
  replacing call's and the blocker edges hold no such fields at all; and when an
  epic with children is deleted, then no row remains anywhere naming who deleted
  them.
- **AC8** (REQ15, REQ18, REQ19, REQ22, EDGE1, EDGE6) — Given both doors running,
  when a well-formed call is sent with no `Authorization` header and again with a
  header carrying something that is not a bearer token, then each comes back as
  401 carrying `WWW-Authenticate`, the store is unchanged, and a call presenting a
  valid token afterwards is served normally.
- **AC9** (REQ16, REQ17, EDGE2, EDGE3, EDGE4, EDGE5) — Given tokens that in turn
  are signed by something else, expired, issued for a different recipient, carry
  no `sub`, carry an empty `sub`, and carry a `sub` of 201 characters, when each
  is presented at each door, then all twelve calls come back 401 and the store is
  unchanged.
- **AC10** (REQ20) — Given a call refused for its token and a call refused for its
  contents, when the two replies are compared, then they differ in numeric status
  and only the second carries one of the four domain reasons.
- **AC11** (REQ21, EDGE7) — Given the agent surface, when a client tries to open a
  connection with no token, then the opening exchange is refused and no tool can
  be listed or called on it; and when it opens one presenting a valid token, then
  its tools are listed and served.
- **AC12** (REQ7, REQ12, EDGE8, EDGE19) — Given a client that gives no name for
  itself in the opening exchange, when it creates an item, then the call succeeds
  and the item records the person with no acting agent; and given a client naming
  itself with 201 characters, when it opens a connection, then it is not served
  and the answer says the name is too long.
- **AC13** (REQ23, REQ24, REQ25, REQ26) — Given a stand-in core recording what it
  receives, when one call is made through each door, then the request body it
  receives is field for field what the same call sent before this epic, the two
  identities arrive alongside it, no token of the caller's appears anywhere in what
  the core received, and the values recorded equal the values asserted.
- **AC14** (REQ27, REQ28, EDGE17) — Given the core, when each of the seven
  mutations is invoked across the connection with no person named, then each fails
  with `validation_failed` and writes nothing; when each of the four reads is
  invoked the same way, then each is served; and when a call naming a person is
  made to the core presenting no credential of any kind, then it is served, the
  core asking for none.
- **AC15** (REQ29, REQ30, EDGE18) — Given each door configured from outside with
  what to check a token against, when it starts and a call is made, then the call
  succeeds; and when it is started with that setting missing, then it stops with a
  message naming the setting.
- **AC16** (REQ11, EDGE15, EDGE16) — Given two connections presenting tokens
  whose `sub` values are `alex` and a 200-character name holding emoji and
  non-Latin script, when both create items at the same moment (repeated 100 times),
  then in every run each item records its own connection's person exactly as the
  token held it, and neither run's identity appears on the other's row.
- **AC17** (REQ1, REQ3, REQ5, REQ6) — the milestone's loop, gated. Given both
  doors running against the real core and store, when a project is created on the
  web API and the milestone's north-star loop is then run over the agent surface
  alone, then every row it wrote names the person the token was for and the agent
  the connection announced, and the project names that person as its owner. This
  one needs every program running at once and belongs to
  [epic 09](../09-full-system-test/).
- **AC18** (REQ15, REQ25) — Given every acceptance criterion of
  [spec-4](../06-mcp-server/spec-4.md) and [spec-5](../07-web-api/spec-5.md), when
  each is run again with a valid token presented, then each reaches the verdict it
  reached before this epic — the gate turning away only the calls that present no
  valid token.

## Definitions

- **subject** — the person a call is made for, as a stable name that survives
  across sessions and machines. It is what `createdBy`, `updatedBy` and
  `ownerSubject` hold, and it comes from the token's `sub` claim.
- **acting agent** — the coding agent that made a call on a person's behalf, by
  the name its own client announces. It is what `createdByAgent` and
  `updatedByAgent` hold, and it is empty where a person acted directly.
- **owner subject** — the subject that owns a project, distinct from who created
  its row: audit says who made this, ownership says whose this is. Attributes: one
  value per project, set when the project is created and never afterwards; relates
  to **subject**, being one.
- **the two doors** — the agent surface ([spec-4](../06-mcp-server/spec-4.md)) and
  the web API ([spec-5](../07-web-api/spec-5.md)); **the core** — the service that
  owns the store and the single write path, reached by both
  ([spec-3](../05-operation-catalog/spec-3.md)).

The `Authorization` header, bearer tokens, the `sub` claim, HTTP 401,
`WWW-Authenticate`, a token's expiry and intended recipient, and the loopback
address (`127.0.0.1`) are used as their own specifications define them and are not
redefined here.

## Assumptions

- **ASM1** — One token, minted by hand and written into configuration, is enough
  for a milestone with one person and one machine; if false — a second person, or a
  need to tell two of someone's own agents apart — a login server has to arrive
  before the gate is useful, and the token in configuration becomes the thing being
  replaced rather than the thing being used.
- **ASM2** — That token is minted to outlast the milestone; if false, both doors
  stop serving on a date nobody chose, and every caller's configuration has to be
  rewritten at once.
- **ASM3** — The core keeps being reachable only from the machine it runs on and
  only by its two doors; if false, anything on that machine could call it and name
  any person, and the gate on the doors would be protecting nothing.
- **ASM4** — Every MCP client worth serving already sends the name of itself the
  protocol asks for; if false, the acting agent is empty on the surface that exists
  to record it, and the second identity earns nothing.
- **ASM5** — [Spec-1](../03-core-write-path/spec-1.md),
  [spec-2](../04-structure-queries/spec-2.md) and
  [spec-3](../05-operation-catalog/spec-3.md) remain the whole behavior of the
  eleven operations, and this epic adds fields to what they write without changing
  what they check; if false, the criteria carried over from those specs no longer
  hold and this one's are checking a moved target.
- **ASM6** — The extra fields on every entity stay small enough that a listing
  returning everything it matches still crosses well inside the timeout, on the
  same terms the sibling specs already assume; if false, a large listing turns into
  an internal error and handing results back a page at a time becomes the fix.
