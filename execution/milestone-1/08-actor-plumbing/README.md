# Epic 08 — Actor plumbing

**Addresses:** [PRD-1](../prd-1.md) REQ8 (every mutation records who made it, and
every project records who owns it).

Documents, in the order they were produced:

- [spec-6.md](./spec-6.md) — the requirements contract. Its load-bearing
  decisions: **two identities are recorded, not one**, because on the agent
  surface a coding agent does the work and a person the agent is working for is
  whose project it is; **both doors gain a gate at the same time**, which is what
  makes the identity the same whichever door a call arrives at; the person comes
  from the token's `sub` claim and from nothing a caller can write; and the two
  identities cross to the core beside the request rather than inside it.
- [discovery.md](./discovery.md) — a throwaway program built against the real
  `:contract` module, settling how: the token travels with each request and the
  client's name with the connection; one token library serves both doors and is
  the smallest of three; the tempting way to carry an identity is the one that
  silently attributes a write to the wrong person; and nothing about the request
  changes.
- [plan.md](./plan.md) — the build route, steps ticked as execution proceeded.
  Its four decisions taken before the steps: that the identity binds to a
  short-lived view of the catalog rather than to a twelfth argument; that both
  doors hold a shared secret rather than a public key; that nothing here mints a
  token; and that the intended recipient is `nook` at both doors, fixed in code.

One of spec-6's eighteen acceptance criteria is deliberately not met here —
AC17, the milestone's loop run against the real store with both doors up. It
belongs to [epic 09](../09-full-system-test/), which spec-6 already assigns it
to and which already records the same debt from epics 06 and 07.

## Results

**Every row the eleven operations write now names two parties.** `project`,
`project_item` and `release` each carry `created_by` and `updated_by` — the
person a call was made for — and gained `created_by_agent` and
`updated_by_agent`, the coding agent that made it on their behalf. A project also
records `owner_subject`, set when it is created and altered by nothing
afterwards. The pair moves as a pair: what created a row stays for good, and what
last changed it is replaced whole on every change — the agent *cleared* where a
person acted alone, rather than left saying an agent made a change it did not
make. `item_dependency` records nobody, by having no audit column to record one
in; `document` keeps its audit columns and gains no agent columns until the
document layer writes it.

**Both front doors now ask for a bearer token, and this reverses a settled
decision.** [Spec-4](../06-mcp-server/spec-4.md) REQ45 and
[spec-5](../07-web-api/spec-5.md) REQ31 each said the surface requires no
credential; both are amended in place, pointing here. A call presenting no valid
token comes back 401 carrying `WWW-Authenticate` in the fuller form
[RFC 6750](https://www.rfc-editor.org/rfc/rfc6750) §3 defines — the realm, that
the token was what was wrong, and what was wrong with it. The loopback binding
stays at both doors and at the core, and its job changed: it is no longer the
whole of the protection but what keeps a token travelling in the clear off any
other machine.

**One reading of a token, mounted twice.** `BearerTokens` lives in `:contract`
beside the shared answering function, for the same reason that does: two
readings in two modules would make "the identity is the same whichever door a
call arrives at" a promise two programs keep rather than a fact, and the first
time one of them added a check the other did not, it would stop being true. It
uses Nimbus in its assembled one-call form — the parse-then-verify calls report a
bad signature by *returning* a value, so one missing `if` accepts every token
whoever signed it — and adds the four checks no token library makes: a `sub` that
is empty, only spaces, longer than 200 characters, or holding a NUL character is
not a person this store can attribute a row to. Everything else is recorded
verbatim: emoji and non-Latin script come back byte for byte, because a door that
normalised anything would credit a row to somebody the token never named. The
core gains a jar it never calls, which is what that guarantee costs.

**The gate on the agent surface sits in front of the dispatcher.** Nothing of the
protocol runs before it — not the opening exchange, not the tool listing — so a
connection cannot be opened, and its tools cannot be listed, without a token.
Every request of a connection passes it, the `GET` and `DELETE` the long-lived
transport makes included. It also checks the name a client gives for itself,
which costs the one thing this would otherwise not do: the name arrives inside
the body of the opening request, a body is read once, and the protocol library
reads it for itself — so the body is read at the gate and handed on re-readable.
A client naming itself with more than 200 characters is turned away saying so,
rather than served with a shortened name that is not its own.

**The identity is bound to one call and never left on a thread.**
`OperationCatalog` gained `forActor`, which hands back the same eleven operations
as one call's identity sees them. The calling library's view shares the one web
client and sends `Nook-Subject` and `Nook-Agent` beside the request; the core's
view hands the pair to the write path as a parameter. The thread-shaped
alternative was refused outright: it fails on every call once the work moves to a
thread allowed to sit and wait — which is exactly what both doors do — and where
it passes it silently records one person's write under whoever used that thread
last. A view costs one small object per call and no threads at all.

**The request the core receives is what it received before this epic.** Byte for
byte, field for field: the two identities ride beside it in headers of Nook's
own, and no token of a caller's goes past either door — which the protocol's own
security rules require and which is why those two headers exist at all. The core
still asks its callers for no credential: only the machine it runs on can reach
it, and that stays the whole of its protection.

**A mutation reaching the core naming no person is refused.** The store carries a
fallback for the rows that predate any of this, and it must stay unreachable
through the connection — so a door with a defect gets a `validation_failed`
rather than a row nobody can attribute. The four reads take nothing and are
served whether a call names a person or not: recording who acted is not deciding
who may look.

**What a caller cannot do.** None of the five fields is an argument any operation
defines, so each is refused by the rule that was already there for a field nobody
defined — at both doors and as a tool argument, with no list here to forget a
sixth name. A caller writing the two crossing headers onto its own request
reaches a door that never reads them; the person comes from the token and from
nowhere else.

### Configuration

Each door reads what it checks a token against from `NOOK_TOKEN_SECRET`, beside
the port and the core's address it already read. The reading is built at startup,
so a door given a secret it could check nothing with — absent, or shorter than
the 256 bits the signing requires — stops before it serves anything, naming the
setting rather than the key length the library complains about.

The milestone's token is minted once by hand and written into a caller's
configuration; nothing in this epic mints one, and no command, task or fourth
entry point ships here. The web UI arriving in milestone 4 will be handed one by
signing in through the browser instead.

Both doors holding the *same* secret is a decision with a stated cost: either
could mint a token it would then accept, which a key pair would have prevented.
That is the trade a loopback-only milestone can take, and the login server
deferred to [08 — Deployment & cloud](../../../docs/08-deployment-and-cloud.md)
is where it stops being one.

### Rule-to-test mapping

Every acceptance criterion of [spec-6](./spec-6.md), as the named test that
executes it (tests carry no criterion numbers by design — code never cites
planning artifacts):

| Criterion | Test |
| --- | --- |
| AC1 | `ActingIdentityTest` in `:mcp-server` — a connection's tool calls record the person its token named and the agent its client announced; `ActorRecordedTest` in `:core-service` — a created row names its person in both audit fields and its agent in both agent fields, read back off the store as well as off the write |
| AC2 | `GatedApiTest` in `:web-app` — every call through this door names its person and no acting agent; `ActorRecordedTest` — a project records the person as its owner, and a row written with no agent records none |
| AC3 | `ActorRecordedTest` — a change replaces what last touched a row and leaves what created it alone; a person changing an agent's row leaves the created agent and clears the updated one; an agent changing a person's row leaves the created agent empty; an update naming no field at all still succeeds |
| AC4 | `UnnameableActorTest` in `:core-service` — a project's owner still reads what it read when it was created, after every operation in the catalog has run against it |
| AC5 | `WhoWroteItCrossesTest` in `:web-app` — a project, an item under an epic with two blockers, and a release each arrive carrying who wrote them, equal to the entity the core produced; `WhoWroteItCrossesTest` in `:mcp-server` — the same for the entities that cross that door, against the same shared stand-in. The agent surface serves the seven project-scoped operations and so reads no project at all: the project half of this criterion is the web API's, and the assembled comparison is epic 09's |
| AC6 | `UnnameableActorTest` — each of the five sent as an argument is refused as a field the operation does not define, with the store unchanged; `SmuggledActorTest` in `:mcp-server` — each of the five sent as a tool argument is turned back naming the one at fault, and no declared tool offers an argument for either identity; `GatedApiTest` — a caller naming somebody else in headers of its own is recorded as the person its token named |
| AC7 | `UnnameableActorTest` — replacing a blocker set advances the item and the edges hold no such field at all; deleting an epic takes its children and leaves no row anywhere naming who removed them |
| AC8 | `GatedConnectionTest` in `:mcp-server` and `GatedApiTest` in `:web-app` — a call with no `Authorization` header and one carrying something that is not a bearer token each come back 401 carrying `WWW-Authenticate`, with the stand-in core recording nothing, and the next valid call served normally |
| AC9 | `GatedConnectionTest` and `GatedApiTest` — the same table of tokens that do not hold up, at each door: signed with something else, expired, issued for another recipient, carrying no `sub`, an empty one, one of only spaces, and one of 201 characters; `BearerTokenTest` in `:contract` — the twelve-token table itself, including the two that must be read |
| AC10 | `GatedApiTest` — a call refused for its token and one refused for its contents differ in numeric status, and only the second carries one of the four domain reasons |
| AC11 | `GatedConnectionTest` — a client opening a connection without a token gets no session and can list no tool; an ordinary client opens a connection, lists its tools and calls one through the gate |
| AC12 | `ActingIdentityTest` — a client naming itself with an empty string and one giving no name at all are both served, recording no acting agent; `GatedConnectionTest` — a client naming itself with 200 characters is served and one with 201 is refused saying the name is too long |
| AC13 | `IdentityCrossingTest` in `:contract` — driven against a recording core, the request body is byte for byte the one sent before this epic, the two identities arrive as headers, no header carries a token, and sixteen callers making ten calls each are all 160 attributed correctly |
| AC14 | `ActorRecordedTest` — each of the seven mutations across the connection naming no person fails as a validation failure writing nothing, and each of the four reads naming none is served, the core asking for no credential of its own |
| AC15 | `ToolProgramTest` in `:mcp-server` and `WebProgramTest` in `:web-app` — each door started without `NOOK_TOKEN_SECRET` stops and names it; started with a secret it could check no token with it stops and names it; started with a usable one it serves a call presenting a token minted against that same secret |
| AC16 | `BearerTokenTest` — a person written in emoji and another script comes back byte for byte; `ActorRecordedTest` — the same survives the store; `ActingIdentityTest` — two connections driven from eight threads with 100 calls each record 200 calls, none carrying the other's pair |
| AC17 | Epic 09 — the milestone's loop needs the real core over a real store with both doors up |
| AC18 | Every acceptance criterion of spec-4 and spec-5 runs again through each module's own test helpers, which now present a valid token on every request — `FrontDoor` in `:mcp-server` and `sentTo` in `:web-app`. Every one of those checks reaches the verdict it reached before this epic, unedited but for the identity it now presents |

### What this epic changed elsewhere

Editing `:mcp-server` and `:web-app`, which epics 06 and 07 finished, was a
deliberate crossing rather than something discovered in the diff:

- **`:contract`** — the reading of a token, the identity pair and the two header
  names, `forActor` on the catalog, a view of the calling library sharing one web
  client, the shared answering function taking the identity so a route cannot
  reach the eleven operations without saying who a call is for, and five fields
  on the three entities. Nimbus is pinned in `gradle/libs.versions.toml`.
- **`:core-service`** — a changelog file, the matching declarations, the row
  mappings, the seven mutations taking the identity as a parameter, and the
  route, which now binds from the two headers before it answers. Nothing about
  what the eleven operations validate moved.
- **`:mcp-server`** — the gate servlet, the one line the transport takes for
  reading each arriving request, the tool handlers, the dispatcher's own call to
  the core, and the entry point. The tools' declarations, the dispatcher's
  routing, the announcement a connection opens with, and the check that turns
  away anything a web page sent are exactly as this epic found them.
- **`:web-app`** — the route, the entry point, and the test helper. The engine,
  the address and the binding are untouched, and `:web-app` still resolves no
  database library in any source set.

The entities gaining five fields touched every stand-in and every test that
builds one, in three modules and all of it in test code. The fields were added
without default values deliberately: a stand-in that forgot one fails to compile
rather than quietly handing back an entity crediting nobody.

### What is deferred, and why

- **A login server, and any flow that issues a token.** Signing in, refreshing an
  expiring token, and the discovery documents an MCP client uses to find where to
  sign in all belong with it. A discovery document must name a server to send a
  client to, and there is none to name.
- **What a refusal should point a client at.** The protocol's authorization
  specification expects a refused call to say where to get a token; the challenge
  written today names a realm and nothing else, and its shape changes the day that
  server arrives.
- **Pointing a real coding agent's client at the gate.** The whole gate assumes
  such a client can be told to present a token on every request. Neither the
  discovery nor this epic could try one; epic 09 has the assembled system to
  point one at.
- **Permissions, reading by actor, per-owner narrowing, HTTPS, and checking where
  a request came from.** All out of scope by spec-6. `owner_subject` is recorded
  so that narrowing by it is a filter later rather than a migration; this
  milestone adds no filter.
