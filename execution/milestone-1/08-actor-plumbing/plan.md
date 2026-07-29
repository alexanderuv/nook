# Actor plumbing — Plan

A note on references. This plan leans on the documents beside it.
[Spec-6](./spec-6.md) numbers what it pins — REQ for a requirement, EDGE for an
edge case, AC for an acceptance criterion. [The discovery](./discovery.md)
numbers its findings FIND and its questions Q. Where this plan cites one of
those codes it says the point in plain words alongside, so the pointer is a
cross-check and never required reading. None of those codes belongs in code or
in a code comment.

Some words that recur. **A bearer token** is the standard way a caller presents
an already-issued identity over HTTP: the call carries `Authorization: Bearer
<token>`, and whoever receives it checks the token rather than asking who is
calling. **A JSON Web Token** is the shape that token takes here — a small set
of claims, signed, so that changing one breaks the signature; its `sub` claim is
the established name for "who this token is about". **The person** is that
subject, and **the acting agent** is the coding agent working on their behalf,
by the name its own client announces when it opens a connection. **The two
adapters** are the agent surface (`:mcp-server`) and the web API (`:web-app`);
**the core** is the program that owns the database. **The calling library** is
`CatalogClient` in `:contract`, the one piece of code both adapters reach the core
by. **A shared secret** is one string used both to sign a token and to check it,
as against a key pair where the two halves differ.

## Analysis

### What is there now, read from the repository rather than remembered

- **Nothing anywhere carries a person.** The three entities in
  `contract/src/main/kotlin/io/nook/contract/Entities.kt` — `Project`,
  `ProjectItem`, `Release` — carry ids, names, timestamps and nothing about who
  wrote them. The eleven operations of `OperationCatalog.kt` take a project
  reference, a target reference and a command, with no room for an identity.

- **The store already has the columns, and every row says `system`.**
  `db/changelog/changes/0001-initial-schema.yaml` gives `project`,
  `project_item`, `release` and `document` a `created_by` and an `updated_by` of
  `varchar(200)`, not null, defaulting to the literal `system`, and gives
  `project` an `owner_subject` on the same terms.
  `core-service/src/main/kotlin/io/nook/core/db/Tables.kt` declares all of them.
  `WriteService.kt` sets none of them on any insert or update, so every row in
  existence carries the default. There are **no agent columns anywhere** — that
  is this epic's one schema change.

- **The blocker edges and the deletes need nothing.** `item_dependency` carries
  `item_id`, `depends_on_id` and `created_at` and no audit column at all, so
  "the edges record nobody" (REQ9) holds by the schema rather than by a rule.
  Deleting removes rows, and there is no mark and no trash (REQ10, EDGE13).

- **The two identities have nowhere to travel.**
  `contract/src/main/kotlin/io/nook/contract/CatalogClient.kt` holds one web
  client for the life of the program, numbers each call so a reply can be paired
  with it, and sends five headers, none of which says who the call is for
  (FIND10 — the recording core saw `Accept`, `Content-Length`, `Content-Type`,
  `Host` and `User-Agent`). `CatalogAnswering.kt`'s `OperationCatalog.answer`
  turns request text into reply text and reads nothing but the request.

- **Both adapters ask for no credential, deliberately, and both say so.**
  `web-app/src/main/kotlin/io/nook/web/WebApi.kt` serves `POST /api` straight
  onto the shared answering function; `mcp-server/.../ToolServer.kt` mounts one
  servlet under `/mcp/*` and `Dispatcher.kt` routes by the address a connection
  opened at and by the session header afterwards. Both fix the host to the
  loopback address in code because that binding is currently the whole of the
  protection. [Spec-4](../06-mcp-server/spec-4.md) REQ45 and
  [spec-5](../07-web-api/spec-5.md) REQ31 state it as a requirement; spec-6
  supersedes both, and this epic amends them.

- **A connection opening already calls the core.** `Dispatcher.opening` asks
  `catalog.getProject(address)` once, before any protocol server exists, so the
  gate has to sit in front of that call as well as in front of the protocol.

- **The protocol library is handed nothing and asked for nothing.**
  `ProjectServer.kt` builds `HttpServletStreamableServerTransportProvider` with
  an endpoint, a check that turns away anything a web page sent, and no reader
  of arriving requests; `Tools.kt`'s `toolsFor` writes each tool handler as
  `{ _, request -> … }`, discarding the exchange that carries both the
  client's name and anything the transport read (FIND1).

- **The build pins no library that can read a token.**
  `gradle/libs.versions.toml` pins the official MCP library at 2.0.0, Jetty at
  12.1.11, Ktor and the rest; nothing for JSON Web Tokens.

- **The checks that must keep passing.** `DriftGuardTest` fails if the Exposed
  declarations and the Liquibase-built schema differ by one statement, so a
  column added in one place and not the other is caught. Around fifteen
  behavior suites in `core-service/src/test/.../write` call `WriteService`
  directly. `:mcp-server` has forty-six checks and `:web-app` seven suites, all
  written against adapters that ask for nothing.

### The framing documents, linked rather than restated

- **[Spec-6](./spec-6.md)** is the requirements contract: 30 requirements, 19
  edge cases, 18 acceptance criteria. Its load-bearing decisions are that two
  identities are recorded rather than one, that both adapters gain a gate at the
  same time so the identity is the same whichever adapter a call arrives at, that
  the person comes from the token's `sub` claim and from nothing a caller can
  write, and that the two identities cross to the core beside the request rather
  than inside it. Two of its criteria are not this epic's: the milestone's loop
  run against the real store, which spec-6 assigns to
  [epic 09](../09-full-system-test/), and the rest of that assembled run.

- **[The discovery](./discovery.md)** settled how, by building a throwaway
  program against the real `:contract` module. Adopted here without
  re-investigation: the token travels with each request and the client's name
  with the connection, and the protocol library hands a tool both (FIND1, FIND2,
  FIND3); a gate in front of the dispatcher turns a call away before any of the
  protocol runs (FIND4); three token libraries refuse the same five bad tokens
  and none of them checks anything about what is *in* the `sub` claim (FIND5);
  Nimbus's low-level calls report a bad signature by returning a value that can
  be ignored, and its assembled form throws instead (FIND6); Nimbus is one jar
  where the alternatives are four, six and thirty-seven (FIND7); a verifier
  built with no usable key fails when it is built, not at the first call
  (FIND8); only a hand-written refusal carries what the bearer-token standard
  defines (FIND9); the calling library has nowhere to put an identity and one
  library per identity leaks threads (FIND10); an identity held on the thread is
  wrong on every call once the work moves to another thread, and worse where it
  passes (FIND11); a view bound to one call costs nothing and got all 160
  concurrent calls right (FIND12); the request itself crosses unchanged and no
  token goes past the adapters (FIND13); and an over-long client name can be turned
  away at the cost of handing the opening request's body on re-readable
  (FIND14).

- **[PRD-1](../prd-1.md)** frames the epic: REQ8 asks for a person on every
  mutation and an owner on every project.

### Four decisions taken before this plan, because a plan is not where they belong

- **The identity binds to a short-lived view of the catalog, not to a
  parameter.** The discovery left this open (Q6) because the surface belongs to
  [spec-3](../05-operation-catalog/spec-3.md). Decided: `OperationCatalog` gains
  `forActor`, returning a catalog bound to one call's identity, and the eleven
  operations keep the arguments they have. Every existing call site and every
  behavior suite compiles untouched, and the alternative — an identity argument
  on all eleven — would put one on the four reads, which never record it.

- **Both adapters hold a shared secret.** The discovery left this open (Q5).
  Decided: each adapter is started with one secret, the same string that signs the
  milestone's token. It is one setting rather than a key pair to generate and
  store, and Nimbus refuses a secret shorter than 256 bits at the moment the
  verifier is built (FIND8), which is what turns "started with nothing to check
  tokens against" into a program that stops. The cost is stated plainly: either
  adapter holds everything needed to mint a token it would then accept, which a
  public key would have prevented. That is the trade a loopback-only milestone
  can take, and the login server deferred to
  [08](../../../docs/08-deployment-and-cloud.md) is where it stops being one.

- **Nothing in this epic mints a token, and the milestone's token is a fixed
  value in the caller's configuration.** The agent client's configuration
  carries it as a literal, and the web UI arriving in milestone 4 will be handed
  one by signing in through the browser instead. So no command, no Gradle task
  and no fourth entry point ships here; the epic's own checks mint the tokens
  they need with Nimbus, in test code.

- **The intended recipient is `nook`, at both adapters, fixed in code.** A token
  must be refused when it was issued for a different recipient (REQ16), which
  means each adapter checks the `aud` claim. One recipient rather than one per adapter,
  because spec-6's scenarios present the *same* token at both, and a setting for
  it would be a second copy of a value that must not differ.

### Constraints that bound the change

- **The core keeps asking for no credential** (REQ28). Only the machine it runs
  on can reach it, and that stays the whole of its protection: the gate is on
  the adapters, and the core trusts what an adapter tells it.
- **No token crosses to the core** (REQ24) — the protocol's own security rules
  forbid handing a client's token to anything behind the surface.
- **The request is unchanged** (REQ25): the same fields under the same names as
  before this epic, so every check the two adapter epics wrote stays meaningful.
- **The eleven operations' rules are not this epic's** (spec-1, spec-2, ASM5).
  This adds fields to what they write and takes nothing away from what they
  check.
- **No permissions, no reading by actor, no per-owner narrowing, no login
  server, no HTTPS, no origin check** — spec-6 puts all of them out of scope.
- **`:web-app` still resolves no database library**, in any source set; the
  build rule fails otherwise.

## Approach

Read the token in one place, gate both adapters with it, bind the identity to one
call, and let the write path record what it is handed. In that order, which is
riskiest first: the gate landing in the real `:mcp-server` — in front of a
dispatcher that already routes by session — is the one piece the discovery
rebuilt rather than modified, and the one its limitations name.

**One reading of a token, in `:contract`.** A small type takes the secret and
hands back the person a token names, or nothing. It is built with Nimbus in its
assembled one-call form, which throws where the low-level calls return a value a
caller can forget to look at (FIND6), and it adds the four checks no library
makes: a `sub` that is empty, only spaces, longer than 200 characters, or
holding a NUL character is not a person this store can attribute a row to
(FIND5). It lives in `:contract` because that is where code both adapters mount
already lives — the shared answering function is there for exactly this reason —
and two readings of a token would make "the identity is the same whichever adapter
a call arrives at" a promise two programs keep rather than a fact. The core
gains a jar it never calls, which is the price of the guarantee.

**The gate on the agent surface is a servlet in front of the dispatcher.**
Nothing of the protocol runs before it — not the opening exchange, not the tool
listing — so a connection cannot be opened without a token (REQ21, FIND4). It
puts the person on the request as an attribute, and the transport's reader —
the one line the protocol library takes for reading each arriving request —
carries it to wherever a tool runs (FIND1). The acting agent comes from the
opening exchange and the library holds it against the session, so a tool reads
both off the exchange it is handed today and ignores. The same gate checks the
client's name, which means reading the opening request's body and handing the
transport a request that can be read again (FIND14).

**The gate on the web API is written into the route.** Neither Ktor plugin will
produce the challenge the bearer-token standard defines: both stop at the word
`Bearer` plus a realm, where the standard's §3 form also says the token was the
problem and why (FIND9). Ktor's own token support is ruled out twice over — it
plugs into a Ktor route and could never reach a Jetty servlet, and it brings
thirty-seven jars against Nimbus's one (FIND7).

**The identity is bound to one call and never left on a thread.** Both catalogs
gain `forActor`: the calling library's returns a view holding the identity and
sharing the one web client, which sends `Nook-Subject` and `Nook-Agent` beside
the request; the core's returns a view that hands the identity to the write
path. The thread-shaped alternative is refused outright — it fails on every call
once the work moves to a thread allowed to sit and wait, which is exactly what
the web API does today, and where it passes it silently records one person's
write under whoever used that thread last (FIND11). A view costs one small
object per call and no threads (FIND12).

**The write path records what it is handed, and refuses a mutation that names
nobody.** The seven mutations take the identity as a parameter — its lifetime is
the call, so it belongs on the call rather than on the service — and each
refuses, as a failed validation, a call naming no person. That is what keeps the
store's own `system` default unreachable through the connection (EDGE17): a
adapter with a defect gets a refusal rather than an unattributable row. The four
reads take nothing and are served whether a call names a person or not (REQ27).

**Why this way over the obvious alternative.** The obvious alternative is to let
each adapter read its own token and pass its own headers, which needs no shared
code and no new dependency in `:contract`. It puts two readings of a token in
two modules, and the first time one of them adds a check the other does not, the
identity stops being the same at both adapters — which is the one thing this epic
exists to make true.

**Blast radius.** `:contract`: the three entities gain their fields, the
interface gains `forActor`, the calling library gains a view and two headers,
the answering side reads them, and the token reading arrives with Nimbus pinned
in `gradle/libs.versions.toml`. `:core-service`: a new changelog file, the
matching declarations, the seven mutations of `WriteService`, the row mappings,
and the route in `CatalogServer` which now binds before it answers.
`:mcp-server`: the gate servlet, the transport's reader, the tool handlers, the
dispatcher's own call to the core, and its entry point. `:web-app`: the route,
the entry point, and its test helper. And spec-4 and spec-5, whose statement
that these surfaces need no credential this epic reverses.

**What it must leave untouched.** What the eleven operations validate; the slug,
containment, cycle and ordering rules; the wire shape of a request; the four
domain failure codes and the roughly thirty test files that assert on them; the
`document` table, which keeps its audit columns and gains no agent columns until
milestone 2; `item_dependency`, which records nobody; the loopback binding at
both adapters and in the core; and the check that turns away anything a web page
sent.

**Unverified assumptions, named.** Two, and both are settled by the third and
fourth steps — as early as the token reading they depend on allows. The gate has only ever been built in the discovery's own program, never in front
of the real dispatcher, which routes a request carrying a session to a server
without asking the core anything — if the gate disturbs that routing, it shows
in STEP3. And the transport's reader has only been driven against a transport
built for the probe, not against `ProjectServer`'s, which also carries the check
that turns away anything a web page sent — if the two do not compose, it shows
in STEP4.

## Steps

- [x] **STEP1** — In `:contract`, add the reading of a bearer token: Nimbus
  pinned in `gradle/libs.versions.toml` and used in its assembled one-call form,
  a verifier built from a secret and refusing to be built without a usable one,
  a fixed intended recipient of `nook`, and Nook's own four checks on the `sub`
  claim; verify: a table of twelve tokens — valid; signed with something else;
  expired; issued for another recipient; signed with nothing at all; not a token
  at all; no `sub`; an empty `sub`; a `sub` of only spaces; a `sub` of 201
  characters; a `sub` holding a NUL character; and a `sub` of emoji and
  non-Latin script — yields the person for exactly the first and the last, with
  the last coming back byte for byte as it went in, and nothing for the other
  ten; and a verifier built with no secret and with a secret under 256 bits each
  fails at the moment it is built (REQ16, REQ17, EDGE2 to EDGE6, EDGE15).

- [x] **STEP2** — In `:contract`, carry the identity: the pair of names as one
  small type, `forActor` on `OperationCatalog`, a view of the calling library
  that sends `Nook-Subject` and `Nook-Agent` beside the request, the answering
  side binding a catalog from those two headers before it runs anything, and the
  five fields added to the three entities; verify: driven against the recording
  core, the request body is byte for byte the one sent before this epic, the two
  identities arrive as headers, no header anywhere carries a token, and sixteen
  callers making ten calls each with different identities produce 160 calls of
  which none was told the wrong person; a call made through an unbound calling
  library reaches the core naming nobody; and building 100,000 views leaves the
  program's thread count where it started (REQ23, REQ24, REQ25, REQ26, AC13).

- [x] **STEP3** — In `:mcp-server`, put the gate in front of the dispatcher: a
  servlet mounted under `/mcp/*` that refuses anything not presenting a valid
  token with 401 and the challenge the bearer-token standard defines, puts the
  person on the request for whatever runs next, reads the opening request's body
  to turn away a client naming itself with more than 200 characters, and hands
  that request on re-readable; verify: a call with no `Authorization` header,
  one carrying something that is not a bearer token, and each of STEP1's bad
  tokens come back 401 carrying `WWW-Authenticate`, with nothing of the protocol
  having run and no call having reached the core; a client opening a connection
  without a token gets no session and can list no tool; a client naming itself
  with 200 characters is served and one naming itself with 201 is refused with
  an answer saying the name is too long; and an ordinary client opens a
  connection, lists its tools and calls one through the same gate — which is
  where the routing of a request carrying a session is shown undisturbed (REQ7,
  REQ15, REQ18, REQ19, REQ21, EDGE1, EDGE6, EDGE7, EDGE19, AC11).

- [x] **STEP4** — In `:mcp-server`, hand a tool both identities: the one reader
  the transport takes, carrying the person the gate read; each tool handler
  reading the person off the exchange and the agent off the client's own name,
  and calling the catalog bound to that pair; and the dispatcher's own call to
  the core, made when a connection opens, bound the same way; verify: one
  connection whose token is swapped between calls records the first person, then
  the second, then the first again, with the agent unchanged throughout; two
  connections opened by different clients for different people, driven from
  eight threads with 100 calls each, produce 200 calls of which none recorded
  the other's pair; a client that gives no name at all and one that names itself
  with an empty string both have their calls served recording no acting agent;
  and every request of a connection is gated, the `GET` and `DELETE` the
  long-lived transport makes included (REQ12, REQ14, EDGE8, EDGE16, AC1, AC12,
  AC16).

- [x] **STEP5** — In `:web-app`, gate the route: the token read before the
  shared answering function is reached, a refusal written by hand as 401 with
  the standard's fuller challenge, and the catalog bound to the person with no
  acting agent; verify: the same bad-token table comes back 401 with nothing
  reaching the stand-in core, and a valid call afterwards is served normally; a
  call refused for its token and a call refused for its contents differ in
  numeric status, and only the second carries one of the four domain reasons;
  and every call through this adapter records no acting agent (REQ13, REQ15, REQ20,
  REQ22, AC8, AC9, AC10).

- [x] **STEP6** — Give both entry points the secret: one setting each, read at
  startup, the verifier built there, and a missing or unusable secret stopping
  the program with a message naming the setting rather than the key length
  Nimbus complains about; verify: each adapter started without the setting stops
  and names it; started with a secret under 256 bits it stops and names it;
  started with a usable one it serves, and a token minted against that same
  secret is accepted (REQ29, REQ30, EDGE18, AC15).

- [x] **STEP7** — In `:core-service`, record what the core is told: a changelog
  file adding `created_by_agent` and `updated_by_agent` to `project`,
  `project_item` and `release` and to no other table, the matching
  declarations in `Tables.kt`, the seven mutations of `WriteService` taking the
  identity as a parameter, `create_project` also writing `owner_subject`, the
  row mappings and both services carrying all five fields out onto the entities,
  and a mutation naming no person refused as a failed validation; verify: the
  drift guard passes, so the declarations and the migrated schema still mirror
  each other exactly; a created row reads back with the person in both audit
  fields and the agent in both agent fields, and a change replaces the second of
  each pair; a person changing a row an agent created leaves the created agent
  as it was and clears the updated one, and the reverse leaves the created agent
  empty; each of the seven mutations invoked across the connection naming no
  person fails as a validation failure and writes nothing, while each of the
  four reads naming none is served; and the roughly fifteen write-path behavior
  suites pass with nothing edited but the identity they now pass in (REQ1 to
  REQ6, REQ27, EDGE9, EDGE10, EDGE11, EDGE14, EDGE17, AC1, AC2, AC3, AC14).

- [x] **STEP8** — Check what a caller cannot do and what nothing records: each
  of the five fields sent as an argument to each adapter and as a tool argument; a
  call presenting a valid token and also carrying a header of its own naming
  somebody else; every operation in the catalog run against one project in turn;
  a leaf whose blocker set is replaced; and an epic with children deleted;
  verify: every one of the five is refused as a field the operation does not
  define, carrying the validation reason, with the store unchanged; the row
  records the token's person and not the header's; the project's owner still
  reads what it read when it was created; the replaced blocker set advances the
  item's own fields while the edges hold no such column; and no row anywhere
  names who deleted the epic or its children (REQ5, REQ8, REQ9, REQ10, REQ14,
  EDGE12, EDGE13, AC4, AC6, AC7).

- [x] **STEP9** — Re-run what the two adapter epics built, gated: every
  acceptance criterion of spec-4 and of spec-5 driven again with a valid token
  presented, through the modules' own test helpers; and one project, one item
  under an epic with two blockers, and one release read through both adapters;
  verify: each criterion reaches the verdict it reached before this epic, the
  gate turning away only calls presenting no valid token; and each adapter's entity
  equals the other's field for field, all five fields present on every entity
  (REQ6, AC5, AC18).

- [x] **STEP10** — Close the epic: amend spec-4's requirement that the agent
  surface needs no credential and spec-5's that the web API needs none, each
  with a line saying what it now asks for and why; record in this epic's README
  what was built, the milestone's token and the setting each adapter reads its
  secret from, and each of spec-6's criteria against the named test that
  executes it; then run the whole build from a clean checkout and push for the
  continuous-integration run; verify: green locally and in that run with the new
  tests visibly executed; `:web-app` still resolves no database library; and
  every criterion but the assembled run appears in that mapping against a test
  that exists.

## Caveats & rabbit holes

- **no-go: holding the identity on the thread** — it is wrong on every call once
  the work moves to a thread allowed to sit and wait, which is what the web API
  already does, and where it passes it attributes a call naming nobody to
  whoever used that thread last (FIND11); instead: bind to a view, and never
  reach for a thread-local however convenient it looks.

- **no-go: one calling library per identity** — fifty of them cost seven threads
  that were not given back when they were closed, and an adapter making one per
  person would accumulate them (FIND10); instead: one client for the life of the
  program, with a small view per call.

- **no-go: Nimbus's low-level parse-then-verify calls** — they report a bad
  signature by returning a value, so one missing `if` accepts every token
  whoever signed it, and nothing about the code looks wrong (FIND6); instead:
  the assembled one-call form, which throws.

- **no-go: either Ktor token plugin** — one brings thirty-seven jars, both fix
  the challenge at the word `Bearer` plus a realm where the standard defines
  more, and neither can reach a Jetty servlet (FIND7, FIND9); instead: Nimbus at
  both adapters and the challenge written by hand.

- **no-go: shortening an over-long client name to fit** — it would record a name
  that is not the client's on every row that connection writes (REQ7, EDGE19);
  instead: turn the connection away, saying the name is too long.

- **no-go: touching what a token holds** — trimming, lowercasing or normalizing
  a `sub` would break the case spec-6 asks for by name, where emoji and
  non-Latin script come back exactly as the token held them (REQ26, EDGE15);
  instead: refuse the five unusable shapes and record everything else verbatim.

- **no-go: a minting command, a login server, or the discovery documents an MCP
  client uses to find where to sign in** — all deferred with the login server,
  and a discovery document must name a server to send a client to, of which
  there is none; instead: one fixed token in the caller's configuration, minted
  once against the secret both adapters hold, and the browser sign-in that replaces
  it when it arrives.

- **caveat: the store's `system` default stays, and must stay unreachable** — it
  is what every existing row carries and dropping it is a migration this epic
  does not need; instead: refuse a mutation that names no person at the write
  path, so nothing reaching the core through an adapter can ever land on it
  (EDGE17).

- **caveat: this epic edits `:mcp-server` and `:web-app`, which epics 06 and 07
  finished** — named here as a deliberate crossing rather than discovered in the
  diff; instead: add the gate, the transport's reader and the binding, and leave
  the tools' declarations, the dispatcher's routing, the announcement a
  connection opens with and the loopback binding exactly as they are.

- **caveat: the entities gaining five fields touches every stand-in and every
  test that builds one** — there are several, in three modules, and all of them
  in test code; instead: add the fields without default values, so a stand-in
  that forgot one fails to compile rather than quietly handing back an entity
  crediting nobody, and expect that mechanical churn across the three modules'
  test sources.

- **caveat: the four reads carry an identity they never record** — a read is
  still made through a bound catalog, because an adapter tells the core who a call
  is for whatever the call does (REQ23), and a read naming nobody is served
  rather than refused (REQ27); instead: bind uniformly, and put the refusal in
  the seven mutations alone.

- **caveat: `:contract` gains a library the core never calls** — one jar of 794
  KiB, which is the price of one reading of a token serving both adapters (FIND7);
  instead: take it, and do not "tidy" it into a module of its own, which is a
  fifth module for one class.

- **caveat: a shared secret means either adapter could mint a token it would
  accept** — recorded as the cost of the decision above, not discovered later;
  instead: leave it, and let the login server be where an adapter stops holding
  anything that could mint.

- **rabbit-hole: pointing a real coding agent's client at the gate** — the
  discovery could not, and left it as a follow-up (Q7): the whole gate assumes
  such a client can be told to present a token on every request; instead: leave
  it to epic 09, which has the assembled system to point one at.

- **rabbit-hole: what a refusal should point a client at** — the protocol's
  authorization specification expects a refused call to say where to get a
  token, and there is nowhere to point (Q8); instead: name the realm, and revisit
  the day the login server exists.

- **rabbit-hole: replacing a token, expiry policy, refreshing** — one token
  minted to outlast the milestone is an assumption spec-6 records (ASM2, Q9);
  instead: mint it long, and let the login server own the question.

- **rabbit-hole: filtering or listing by who owns something** — `owner_subject`
  is recorded so that narrowing by it is a filter later rather than a migration,
  and spec-6 puts every one of those out of scope; instead: write the field,
  read it back, and add no filter.

- **rabbit-hole: a load probe against the assembled adapters** — the fifth epic in
  a row to leave the question behind, and one run against the whole system would
  answer all five; instead: leave it, and let epic 09 decide whether to spend it.

## Test plan

Every check below runs against a stand-in core — the recording core `:contract`
already ships, or the stand-ins the two adapter modules already have — except
TEST1, which needs nothing, and TEST7 and TEST8, which are `:core-service`'s and
run against the real store on the embedded database.

- **TEST1** — unit, in `:contract`: the twelve-token table of STEP1 yields a
  person for the valid token and the one of emoji and non-Latin script and
  nothing for the other ten; the emoji subject survives byte for byte; and a
  verifier built with no secret, and with one under 256 bits, fails when it is
  built.

- **TEST2** — integration, in `:contract`: against the recording core, the
  request body is identical to the one sent before this epic; `Nook-Subject` and
  `Nook-Agent` arrive beside it; no header holds a token; sixteen callers making
  ten calls each are attributed correctly in all 160; a call through an unbound
  calling library reaches the core naming nobody; and 100,000 views leave the
  thread count unchanged.

- **TEST3** — integration, in `:mcp-server`: no `Authorization` header, a header
  that is not a bearer token, and each bad token from TEST1 each come back 401
  carrying `WWW-Authenticate`, with the stand-in core recording no call and no
  session issued; a client naming itself with 200 characters is served and one
  with 201 is refused saying the name is too long; and an ordinary client opens
  a connection, lists its tools and calls one through the gate.

- **TEST4** — integration, in `:mcp-server`: one connection with its token
  swapped between calls records person, then the other person, then the first,
  with the agent constant; two connections driven from eight threads, 100 calls
  each, record 200 calls with none carrying the other's pair; a client giving no
  name and one giving an empty name both record no acting agent; and the `GET`
  and `DELETE` requests of a connection pass the gate like every `POST`.

- **TEST5** — integration, in `:web-app`: the bad-token table comes back 401
  with the stand-in core untouched and the next valid call served; a refusal for
  the token and a refusal for the contents differ in numeric status with only
  the second carrying a domain reason; and every call records no acting agent.

- **TEST6** — integration, both adapters: each adapter started without its secret
  stops naming the setting; with a secret under 256 bits it stops naming the
  setting; with a usable one it serves a call presenting a token minted against
  that same secret.

- **TEST7** — integration, in `:core-service`: a created row reads back with the
  person in both audit fields and the agent in both agent fields; a change
  replaces the second of each; a person changing an agent's row clears the
  updated agent and leaves the created one; an agent changing a person's row
  leaves the created agent empty; an update naming no field succeeds; each of
  the seven mutations across the connection naming no person fails as a
  validation failure writing nothing, and each of the four reads naming none is
  served; and the drift guard passes.

- **TEST8** — integration, in `:core-service`: each of the five fields sent as
  an argument is refused as a field the operation does not define, at both adapters
  and as a tool argument, with the store unchanged; a call carrying a header of
  its own naming somebody else records the token's person; a project's owner is
  unchanged after every operation in the catalog has run against it; a replaced
  blocker set advances the item and leaves the edges holding no such column; and
  a deleted epic leaves no row anywhere naming who deleted it or its children.

- **TEST9** — integration, in `:mcp-server` and `:web-app`: every acceptance
  criterion those two epics' suites execute, run again with a valid token,
  reaching the verdict it reached before; and one project, one item under an
  epic with two blockers, and one release read through both adapters, equal field
  for field with all five fields present.

- **Standing check, comment hygiene** — search the final diff for artifact
  tokens (STEP, REQ, GOAL, FIND, AC, EDGE, PRD, epic) and markdown paths in code
  and code comments; expect zero hits.

- **Standing check, conformance sweep** — re-read this plan against the final
  diff: every step ticked with its verification observed, the blast radius
  respected — nothing changed in what the eleven operations validate, in the
  wire shape of a request, in the four domain failure codes, in the `document`
  and `item_dependency` tables, or in the loopback binding at either adapter —
  every caveat honored, and any mid-build divergence folded back into this text.

- Run both standing checks through a separate agent handed only this plan and
  the final diff, none of the builder's conversation.

Done when: a clean checkout runs `./gradlew check` green locally and in the
continuous-integration run; seventeen of spec-6's eighteen acceptance criteria
pass as named tests, with the assembled run recorded as epic 09's; neither adapter
serves a call that presents no valid bearer token, and the opening exchange of a
connection is among what they refuse; every row the eleven operations write
names the person its call was made for and the agent its connection announced,
and no row anywhere reads `system` through either adapter; the request the core
receives is what it received before this epic and carries no token; spec-4 and
spec-5 no longer say these surfaces need no credential; and the checks epics 03
to 07 built are exactly as this epic found them, but for the identity they now
present.

## Rollback

Reverting the code is a clean `git revert` of the epic's commits: both adapters
stop asking for a token and the calling library stops sending the two headers,
which returns every caller to the state epics 06 and 07 left them in.

The schema is the part revert alone does not undo. Drop the two agent columns
from `project`, `project_item` and `release` with the changelog's own rollback,
in that order relative to the code — code first, then schema, so that no running
adapter writes a column that is no longer there. The person and owner already
written into `created_by`, `updated_by` and `owner_subject` stay, which is
harmless: those columns predate this epic and reverting only stops new rows from
being attributed.

Where turning back stops being possible: once a caller's configuration carries the milestone's
token, backing the gate out leaves that token being sent to an adapter that ignores
it, which is untidy rather than broken. There is no point past which this cannot
be reversed.
