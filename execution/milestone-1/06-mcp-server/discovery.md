# MCP server approach

## Summary

- **The web transport the protocol library ships is a Java servlet — a request
  handler a web container runs for you — and an ordinary embedded Jetty container
  hosts it without complaint.** The tension epic 01 recorded, a servlet-shaped
  library in a codebase built on Ktor, dissolves rather than being resolved:
  `:mcp-server` serves the protocol with Jetty and calls the core with the Ktor
  client the calling library already uses, both in one program, on two ports, with
  nothing shared between them.
- **An address of the form `/mcp/{projectRef}` means one MCP server per project,
  made when that project's first connection opens.** The library turns away any
  address that does not end with the one endpoint it was told about, so a single
  shared server cannot sit under a family of addresses unless it is told to accept
  every address — and a server told that serves *every* address, including one
  naming no project at all, while telling every connection the same thing about
  its project. A server per project is the only arrangement in which what a
  connection is told on opening is its own project's.
- **The announcement rides in the opening exchange's `instructions` field, and
  refusing to open the connection works as the spec hoped.** Both the library's
  own client and the official MCP Inspector — a separate program, written by
  someone else, in another language — read the announcement back, complete the
  handshake, list the seven tools and call them with no change made for Nook's
  sake; and pointed at a mistyped project, the Inspector reports Nook as
  unavailable and repeats the project it asked for.
- **The three endings arrive distinguishable, but two of spec-4's requirements
  cannot be met as the library comes.** A refusal is a result flagged as failed
  carrying Nook's own code, message and details, and a deletion's empty success is
  plainly not one. But a fault inside the core and a call naming a tool that does
  not exist both arrive as protocol-level errors, told apart only by the number
  they carry — so the unknown-tool case never reaches the agent as the
  `validation_failed` refusal REQ35 asks for; and the library advertises a logging
  capability for every server it builds, against REQ7's "tools and nothing else".
  Both wordings are frozen inside the library, so both are recorded as decisions
  the epic still has to make rather than settled here.
- Recommendation in brief: Jetty hosting with `ktor-server-core` dropped from the
  module; a server per project behind a small dispatcher that resolves the address
  once and writes its own refusal body; the announcement in `instructions`; the
  library's argument checking left on, because turning it off checks nothing at
  all; and the server bound to the loopback address, which a control run shows is
  what keeps a caller on another address out.

## Questions

- **Q1** — How does `:mcp-server` host the protocol library's long-lived-HTTP
  server transport, given the library ships it as a Java servlet and the rest of
  the backend is built on Ktor?; informs: REQ41, and epic 01's open Q8, which
  named this epic's spike as what would answer it.
- **Q2** — Can a connection be bound to the project named in its address, refused
  when that address names no project, and told which project it is for — and does
  an ordinary client actually show an agent both of those?; informs: REQ8 through
  REQ19, and the assumption (ASM4) that an agent learns its project from what the
  server says on opening.
- **Q3** — Do the three ways a call can end arrive so that an agent can tell them
  apart, and does everything an agent supplies reach the code behind the tool
  unchanged?; informs: REQ23 through REQ36 — the whole of what the server owes a
  call in each direction.
- **Q4 (emerged)** — Which checks of the library's own stand between a tool call
  and Nook's code, and what happens if they are turned off?; informs: REQ28, which
  says every acceptance and refusal of a readable call must be the core's verdict,
  and REQ35, which names four kinds of unreadable call. Asked once Q3's probes
  showed calls being refused before Nook's code ran at all.

Bound: one throwaway build on one machine (macOS, Apple silicon, JDK 25), the
protocol library and Ktor at the versions the repository already pins, thirteen
probe groups, a stand-in for the core rather than the core itself, no load
testing, and no second machine. That sufficed because every question is about how
the library and its host behave, which one honest execution settles, and because
the behavior of the core and of the crossing to it is already settled and tested
by epics 03 to 05 — putting the real core behind these probes would have measured
that work again rather than this library. Three things the bound leaves untested
are recorded as limitations.

## Method

A throwaway Kotlin program in a scratch directory, with its own build, run
2026-07-27 and deleted after this report. It depends on the official Java MCP
library at the pinned `2.0.0`, Ktor 3.5.1 for both calling and serving, and Jetty
12.1.11 as the web container — the program that receives requests off the network
and hands them to code like the library's transport. Jetty's `ee11` flavour is the
one that speaks the version of the servlet interface the library was built against
(6.1). The library's own source was read alongside running it, and two findings
below rest on a named line of it rather than on behavior alone.

The program builds both candidate hostings and drives them with two clients:

- **The library's own client**, used for the probes that need to be scripted —
  it can be pointed at any address, and it reports what it received rather than
  drawing it on a screen.
- **The official MCP Inspector**, fetched and run with `npx` — the command that
  downloads a published package and runs it in one step — in its command-line
  mode. It is written in TypeScript by the protocol's own maintainers and knows
  nothing of Nook, so it is the only evidence here about what an unmodified,
  independent client does. Raw requests made with `curl` sit alongside both, so
  that what travels on the wire is quoted rather than inferred from what a client
  chose to show.

The two candidate hostings, both mounted under `/mcp/`:

- **Candidate A — a server per project.** A small dispatcher servlet takes the
  first path segment as the project reference, asks the stand-in core about it
  once, and — if it names a project — builds a protocol server for that project
  and hands the request to it. Later connections to the same project reuse it.
- **Candidate B — one server for every project.** A single protocol server told
  to accept every address, with the project pulled out of each request into the
  per-request context the library offers, for the code behind a tool to read.

Thirteen probe groups drove them: the endpoint the library will answer at; each
candidate's announcement and project isolation; four ways an address can fail to
name a project (a typo, nothing at all, an unexpanded configuration placeholder,
and a core that cannot be reached); the tool listing and the capabilities
advertised; each of the endings a call can have, including a fault planted inside
the handler and four kinds of unreadable call; the three states of a partial
update; text carrying emoji, non-Latin script, line breaks, quotation marks and a
backslash; a listing of 5,000 items; three connections calling at once with one
call deliberately slow; a project's server closed under a connected client; Jetty
and Ktor running in one program; and the loopback binding, with a control run
first to establish which of this machine's addresses reach a server at all.

**The core was a stand-in, not the core.** It is a directory of two projects that
answers the one question the server asks of the core when a connection opens, and
a set of seven tools whose handlers return canned entities, canned refusals, and
one deliberate fault. What crosses to the real core, and what that crossing
guarantees, is [spec-3](../05-operation-catalog/spec-3.md)'s subject and epic 05's
discovery already reports it from the real services.

Not done: any second web container (Tomcat and Undertow were not tried); any load
beyond three connections; any second machine; the Inspector's browser view, as
opposed to its command-line mode; the real core behind the tools; and any
protocol revision other than the current one both clients negotiated.

## Findings

### FIND1 — Jetty hosts the library's transport, and Ktor sits beside it in the same program without contest

**Confidence:** solid — both servers started and both were called in one process ·
answers Q1

Epic 01 left this question open because the library ships its long-lived-HTTP
transport as a Java servlet, which Ktor has no way to host. Run, the difficulty
turns out not to arise: nothing requires this module to *serve* with Ktor. It
serves with Jetty and calls the core with the Ktor client that the calling library
is already built on.

One program, started once:

```
the stand-in core is listening on -> 64187
an MCP tool call                  -> {"ref":"x","projectId":"aaaaaaaa-…-000000000001"}
the Ktor client's call to the core -> {"ending":"answer","received":24}
both servers in one JVM           -> Jetty on 64188, Ktor on 64187
```

Jetty 12.1.11 started in 28 milliseconds and served the protocol; the Ktor client
called the stand-in core over its own connection in the same program; neither
noticed the other. `:mcp-server` therefore needs no Ktor server at all — its
current `ktor-server-core` dependency is left over from the skeleton and is not
what serves anything here.

### FIND2 — One transport instance answers at exactly one address, so a family of addresses means a family of servers

**Confidence:** solid — the behavior executed, and its cause read in the library's
own source · answers Q1, Q2

The library's servlet compares each request's address against the single endpoint
it was configured with, and turns away anything else before the protocol is
reached:

```java
String requestURI = request.getRequestURI();
if (!requestURI.endsWith(mcpEndpoint)) {
    response.sendError(HttpServletResponse.SC_NOT_FOUND);
    return;
}
```

Told `/mcp` and mounted so that every address under `/mcp/` reaches it, it serves
a client at `/mcp` and refuses one at `/mcp/search-revamp`:

```
client at /mcp (the address the provider was told) -> ok: 2025-11-25
client at /mcp/search-revamp                       -> failed to initialize
raw POST to /mcp/search-revamp                     -> status 404, the container's own "Not Found" page
```

So `/mcp/{projectRef}` cannot be served by one transport instance told about
`/mcp`. Either each project gets its own instance, told about its own address, or
one instance is told to accept every address — which is what candidate B does, and
FIND3 is what that costs.

### FIND3 — A server per project is the only candidate that can tell one connection's project from another's

**Confidence:** solid — both candidates built and driven through the same probes ·
answers Q2

| | Candidate A: a server per project | Candidate B: one server for every project |
| --- | --- | --- |
| serves `/mcp/search-revamp` and `/mcp/billing` | yes | yes |
| the project's handle and its id reach the same project | yes | yes |
| what each connection is told about its project | its own project's name, handle, id and description | the same words for every project |
| a call knows which project it acts in | from the server it was made on | from the request, read into the per-call context |
| an address naming **no** project | refused at the door | **served** |

Candidate A, three connections — two at one project's handle and its id, one at
another project:

```
announcement on /mcp/search-revamp   -> …the Nook project Search revamp (handle search-revamp,
                                        id aaaaaaaa-…-000000000001). Rebuild search so it stops timing out.
announcement on /mcp/billing         -> …the Nook project Billing (handle billing,
                                        id bbbbbbbb-…-000000000002). Invoices and dunning.
announcement on /mcp/{that project's id} -> identical to the first
handle and id reach the same announcement -> true
a call on the first names its own project -> projectId=aaaaaaaa-…-000000000001
a call on the second names its own project -> projectId=bbbbbbbb-…-000000000002
core asked about a project (times)   -> 3
```

The core was asked exactly once per connection, which is what REQ10 asks for and
what keeps a mistyped address from being discovered on every call instead of at
the door.

Candidate B serves the same two addresses and its tool handlers can see which
project a call came in on — but every connection is told the same thing:

```
announcement on /mcp/search-revamp -> This connection works in a Nook project. (One announcement, every project.)
announcement on /mcp/billing       -> This connection works in a Nook project. (One announcement, every project.)
the two announcements differ       -> false
a call on the first sees which project  -> projectRef=search-revamp projectId=aaaaaaaa-…-000000000001
a call on the second sees which project -> projectRef=billing projectId=bbbbbbbb-…-000000000002
an address naming no project at all (/mcp/) -> ok: 2025-11-25
```

The cause is in the library: what a connection is told on opening is a single
piece of text held on the server, and the code that answers an opening request
reads that one field and nothing about the request. So a server can say something
true of every project it serves, or one project's own words — never both. The last
line is the second cost: told to accept every address, the transport accepts
`/mcp/` as readily as `/mcp/billing`, so nothing is refused at the door and EDGE2
fails outright.

### FIND4 — What a connection is told rides in the opening exchange, and independent clients read it back

**Confidence:** solid — read off the wire and out of two different clients ·
answers Q2

The protocol's opening reply carries an `instructions` field, and that is where
the four values REQ15 asks for fit. On the wire, from `curl`:

```json
{"jsonrpc":"2.0","id":1,"result":{
  "protocolVersion":"2025-11-25",
  "capabilities":{"logging":{},"tools":{"listChanged":false}},
  "serverInfo":{"name":"nook","version":"1.0.0"},
  "instructions":"This connection works in the Nook project Search revamp (handle search-revamp,
                  id aaaaaaaa-…-000000000001). Rebuild search so it stops timing out."}}
```

The library's own client exposes it as `getServerInstructions()`, and the whole
opening result is available to a client that wants it. This says nothing about
whether the program an agent runs inside puts those words in front of the agent —
that is ASM4's business and no probe here can settle it — only that the words
arrive, per connection, before any tool is called.

### FIND5 — Refusing to open makes an ordinary client report Nook as unavailable, naming the project it asked for

**Confidence:** solid — four failing addresses, driven through both clients ·
answers Q2

Candidate A's dispatcher resolves the address before any protocol server exists
for it, so a refusal happens in the opening exchange itself:

| the address | what the server answered | what a client did |
| --- | --- | --- |
| `/mcp/serch-revamp` (a typo) | 404, `no project answers to 'serch-revamp'` | failed to connect |
| `/mcp/` (no project at all) | 404, `no project named in the address` | failed to connect |
| `/mcp/{{PROJECT_SLUG}}` (a placeholder never filled in) | 404, `no project answers to '{{PROJECT_SLUG}}'` | failed to connect |
| `/mcp/billing`, core unreachable | 503, `the core could not be reached` | failed to connect |
| `/mcp/billing`, core back | — | opened normally |

The Inspector — the independent client — reports it as unavailability, repeating
the project that was asked for, which is exactly what makes a wrong address
diagnosable where it was written:

```
Failed to connect to MCP server: Streamable HTTP error: Error POSTing to endpoint:
{"error":"no project answers to 'serch-revamp'"}
```

One detail decides whether that message survives. Handing the refusal to the
container's own error page mangles it — Jetty writes an HTML-escaped page, and the
apostrophes come out as `&apos;`:

```
{"message":"no project answers to &apos;serch-revamp&apos;", "url":"…", "status":"404"}
```

Writing the body directly onto the response instead produces the clean line quoted
above. Both refuse the connection; only one of them hands a person back the words
they wrote.

### FIND6 — Closing a project's server ends the connections held against it

**Confidence:** solid — executed against a connected client · answers Q2

With a server per project, "stop serving this connection because its project is
gone" is one call. A connected client, before and after:

```
a call before the deletion -> {"ref":"x","projectId":"bbbbbbbb-…-000000000002"}
a call after the server for that project is closed ->
    Failed to send message: {"message":"Server is shutting down", "status":"503"}
```

The client's next call fails and its session is over. The wording is the library's
own and says nothing about a project; telling an agent *which* thing disappeared
is the server's own work, on the path that has already failed — which is what
spec-4 says it is.

### FIND7 — The endings are distinguishable, but a fault and an unknown tool are both protocol errors

**Confidence:** solid — every ending captured on the wire · answers Q3

What actually travels, for each of the endings a call can have:

| the call | what arrives |
| --- | --- |
| an answer | a result, `"isError":false`, the entity in both readable text and structured form |
| a refusal from the core | a result, `"isError":true`, carrying `{"code":"not_found","message":…,"details":{…}}` |
| `delete_item` succeeding | a result, `"isError":false`, empty text, no entity |
| a call the library cannot read | a result, `"isError":true`, the library's own wording, **no code** |
| a fault inside the handler | an **error**, number `-32603`, the fault's message |
| a tool that does not exist | an **error**, number `-32602`, `"Unknown tool: invalid_tool_name"` |

The first four are results; the last two are protocol-level errors, which the
library's client raises as an exception rather than handing back. So a refusal is
never mistakable for a fault, and an empty deletion is never mistakable for
either — REQ29, REQ31 and REQ32 hold as the library comes.

Two things do not. A call naming a tool that does not exist — which spec-4 names
twice, once as an unreadable call and once as an agent reaching for one of the
four project operations — comes back as the protocol's own "invalid params" error,
not as a `validation_failed` refusal; it is told apart from a fault only by the
number, and the message does not even name the tool that was asked for (the name
is tucked into a separate field: `"data":"Tool not found: create_project"`). Both
of those wordings are fixed in the library's code, in the same method that
dispatches every tool call, with no way to supply your own.

After all six endings above, the next call on the same connection was served
normally — REQ36 holds.

### FIND8 — Everything an agent sends reaches the code behind the tool unchanged

**Confidence:** solid — every case executed and compared against what was sent ·
answers Q3

The three states of a partial update stay apart all the way through. What the code
behind the tool received:

```
only the name changed   -> mentioned=[name, ref]        description=unmentioned
the description cleared -> mentioned=[description, ref] description=set-to-nothing
the description set     -> mentioned=[description, ref] description=set-to-'text'
no field named at all   -> mentioned=[ref]              description=unmentioned
```

A field nobody mentioned and a field set to nothing are different things on
arrival, which is the distinction the whole partial update rests on and the one
epic 05 had to hand-write the crossing to preserve.

A blocker list supplied empty arrived empty; a blocker list carrying the same
reference twice arrived still carrying it twice (`blockedBy=[a, a, b]`) — the
library deduplicates nothing. Text arrived exactly as sent:

```
name as received        -> Søk 🔍 épico                            (unchanged: true)
description as received -> line one\nline two "quoted" and \ backslash  (unchanged: true)
```

A listing of 5,000 items arrived whole, in order, in one answer, in **112 and 115
milliseconds** across two runs — well inside the 30-second limit, and consistent
with what epic 05 measured for the same listing crossing to the core.

### FIND9 — Several connections are served at once, and a slow call blocks nothing

**Confidence:** solid — three connections, one call deliberately slow · answers Q3

Three connections, two on one project and one on another. One call was made to
take a second and a half; two ordinary calls were started fifty milliseconds
later, one on the *other* project and one on a second connection to the *same*
project:

```
third connection, same project as the slow one -> finished at 56 ms
second connection, the other project            -> finished at 56 ms
first connection, the slow call                 -> finished at 1508 ms
```

Neither fast call waited on the slow one, including the one sharing a project —
and therefore a server — with it. Each connection's calls stayed inside its own
project throughout.

### FIND10 — The loopback binding is what turns an outside caller away, and a control run proves it

**Confidence:** solid for the binding; the caller was on this machine · answers Q3

The loopback address is the one a machine uses to reach itself, and nothing
outside the machine can route to it. This machine has two addresses that are not
the loopback, and only one of them reaches a server at all — so the control run
comes first, exactly as epic 05's did:

| Address | server listening everywhere | server listening on 127.0.0.1 |
| --- | --- | --- |
| `192.168.50.95` (the local network) | reached it | `ConnectException — Connection refused` |
| `10.5.0.2` (a tunnel) | could not connect either way | — |
| `127.0.0.1` | — | opened normally |

The address that demonstrably reaches a server listening on every address is
refused by the same server bound to the loopback. No credential was presented in
any of these calls and none was asked for. Note that the library's own protection
against a web page in a browser calling a local server is **off** by default in
this transport — the setting exists and defaults to a validator that checks
nothing.

### FIND11 — The library checks a tool call's arguments before Nook's code runs, and turning that off checks nothing at all

**Confidence:** solid — all three kinds of bad call executed both ways · answers Q4

Left on, as it comes, the library validates each call against the tool's declared
arguments and refuses what does not fit, as a failed call, without the code behind
the tool ever running:

```
a required argument missing        -> isError=true  "required property 'name' not found"
an argument the tool does not define -> isError=true  "property 'colour' is not defined in the schema
                                                       and the schema does not allow additional properties"
a value of the wrong kind          -> isError=true  "/type: integer found, string expected"
```

That is EDGE7, EDGE8 and EDGE9 answered by the library, and nothing reached the
store in any of the three. What the refusals do *not* carry is Nook's own code:
the wording is the library's and there is no `validation_failed` anywhere in it.

Turned off, the library checks nothing whatsoever — and the same three calls all
succeeded:

```
an argument the tool does not define -> isError=false, the extra argument silently dropped
a required argument missing          -> isError=false, an item created with name=null
a value of the wrong kind            -> isError=false, the wrong-typed value passed through
```

The middle line is the one to read twice: with checking off and nothing written in
its place, a call missing a required argument was not refused — an item was
created, its name empty.

### FIND12 — The server always tells a client it can do logging, whatever it is told to advertise

**Confidence:** solid — read off the wire and traced to the line that causes it ·
answers Q3

Built with tools declared as its only capability, the server advertises two:

```
capabilities on the wire  -> {"logging":{},"tools":{"listChanged":false}}
capabilities beyond tools -> [logging]
```

The cause is one line in the library, run for every server it builds, which adds
logging to whatever capabilities it was handed:

```java
this.serverCapabilities = features.serverCapabilities().mutate().logging().build();
```

Everything else genuinely is absent — a client asking for resources is turned away
by its own library before a request is sent, and a request for prompts comes back
`Method not found`. But REQ7 says a client must be told the server offers tools
and nothing else, and as the library comes, that is not what a client is told.

### FIND13 — An unmodified client needs nothing done for Nook's sake

**Confidence:** solid — the Inspector driven through the whole surface · answers Q2, Q3

The Inspector, fetched and run as it ships, completed the opening handshake,
listed the tools with their descriptions and argument descriptions intact, and
called them. A call and a refusal, as it printed them:

```json
{"content":[{"type":"text","text":"{\"ref\":\"add-search\",…}"}],
 "structuredContent":{"ref":"add-search","projectId":"aaaaaaaa-…-000000000001"},
 "isError":false}

{"content":[{"type":"text","text":"{\"code\":\"not_found\",…}"}],
 "structuredContent":{"code":"not_found","message":"no item answers to 'missing'",
                      "details":{"ref":"missing"}},
 "isError":true}
```

The tool listing it received carried all seven names, a description on every tool,
a description on every argument, no argument naming a project, and the "no other
arguments accepted" marker that FIND11's middle case rests on. One thing had to be
told to it: the Inspector reaches first for the protocol's older transport, the
one where a client posts to one address and reads answers on a stream held open at
a second, and it failed with `SSE error: Non-200 status code (400)` until asked
for the long-lived-HTTP one instead — which is the Inspector choosing a default,
not the server refusing anything.

## Implications & recommendation

- **Host the protocol with an embedded Jetty container, and drop
  `ktor-server-core` from `:mcp-server`** (FIND1) — the library's transport is a
  servlet, Jetty runs it unchanged, and the module's serving side has no other
  work to do. The Ktor *client* stays, because that is what the calling library
  uses to reach the core; the two coexisted in one program with nothing to
  reconcile. This closes epic 01's Q8 without the trade-off that question
  anticipated.
- **Build one protocol server per project, behind a small dispatcher that reads
  the project out of the address** (FIND2, FIND3) — it is the only arrangement in
  which what a connection is told on opening is its own project's, which is REQ15
  outright; and it is the arrangement in which an address naming no project is
  refused rather than served. The shared-server alternative fails REQ15 and EDGE2
  together, and buys nothing either.
- **Let the dispatcher ask the core about the project once, before the protocol
  server for it exists** (FIND3, FIND5) — the connection then fails in the opening
  exchange, which is what makes an ordinary client report Nook as unavailable
  instead of showing an agent seven tools that will fail. The probe's count
  confirms the core is asked exactly once per connection, not once per call.
- **Write the refusal body onto the response directly rather than handing it to
  the container's error page** (FIND5) — the container escapes the text into HTML
  and wraps it, so an unexpanded placeholder comes back looking like something
  nobody wrote. Writing it plainly is what hands a person back the words they
  configured, which is the entire point of EDGE1.
- **Carry the announcement in the opening exchange's `instructions` field**
  (FIND4) — it is the field the protocol has for this, it arrives per connection,
  and both clients read it back. Nothing about it is reachable through a tool, so
  an agent still cannot ask about a project.
- **Stop serving a project's connections by closing that project's server**
  (FIND6) — with a server per project it is one call, and the connected client's
  next call fails and its session ends. Telling the agent that it was the
  *project* that disappeared remains the server's own work on the failed path,
  which spec-4 already says.
- **Leave the library's argument checking on** (FIND11) — it answers three of the
  four unreadable-call cases before anything reaches the store, and the
  alternative is not "check it ourselves later" but "check nothing", which
  silently created an item with no name. Its refusals carry the library's wording
  rather than Nook's code; whether that satisfies REQ35 is a decision this report
  does not close (see Open questions).
- **Bind the server to the loopback address, and take both addresses from
  configuration** (FIND10) — the binding is demonstrably what refuses a caller on
  an address that otherwise reaches the server, with no credential involved, which
  is what REQ44 and REQ45 claim the whole protection is. Turn the library's own
  browser-origin checking on as well while doing it: it defaults to a validator
  that checks nothing, and it costs one line.
- **Expect two requirements of spec-4 to need amending rather than implementing**
  (FIND7, FIND12) — REQ7's "tools and nothing else" is contradicted by a line the
  library runs for every server it builds, and REQ35's unknown-tool case is
  answered by the protocol's own error rather than by a Nook refusal. Both are
  wordings frozen inside the library, so the choice is to amend the requirement or
  to stop using the library's own server assembly — a much larger change than
  either requirement is worth. This report recommends amending; recording that
  decision is the epic's own act, not this document's.

## Limitations

- **The core behind the tools was a stand-in, not the core** — at risk: nothing
  here shows a tool call reaching the real write and read paths and coming back,
  so the claim that answers and refusals cross intact is a claim about this
  library, not about the assembled system; would raise confidence: driving
  spec-1's and spec-2's own criteria through the tools against the real core,
  which is AC26 and this epic's own build work.
- **One web container, one machine, one protocol revision** — at risk: every
  hosting result is Jetty 12.1.11 on macOS negotiating the current protocol
  revision, and nothing says the transport behaves the same under Tomcat or
  Undertow, or with a client that speaks an older revision; would raise
  confidence: repeating the hosting and endings groups on a second container, and
  once against a client pinned to an older revision.
- **The "caller from another machine" was this machine** — at risk: the binding
  result is evidence about which of this machine's addresses a bound server
  answers on, not about a genuinely remote caller, and a network arrangement that
  forwards traffic onto the loopback would defeat it unnoticed; would raise
  confidence: one call from a second machine on the same network. (Epic 05's
  discovery records the same limitation on the same question.)
- **The independent client was driven from its command-line mode** — at risk: what
  the program an agent runs inside *does* with the announcement, and what a person
  sees when a connection is refused, are both inferred from what the Inspector
  printed rather than observed in a real agent client; ASM4 in particular remains
  untested;
  would raise confidence: pointing a real coding agent's client at the built
  server and reading what its transcript says about the project.
- **Nothing was measured under load, and a server per project is never disposed**
  — at risk: three connections and one slow call say nothing about many agents at
  once, and the recommended arrangement holds a protocol server for every project
  ever connected to, for the life of the process, which nothing here measures;
  would raise confidence: a load probe against the built server, and a count of
  what a long-running process actually accumulates.
- **The refusal wordings quoted are the spike's own** — at risk: the finding is
  that a refusal reaches the client intact and names the project, not that these
  particular sentences are the right ones; would raise confidence: nothing worth
  spending — the wording is the epic's to choose.

## Open questions

**Needs action:**

- **Q5** — Does REQ7 stand, given that the library advertises a logging capability
  for every server it builds?; blocks: AC1, which checks that the server declares
  tools as its only capability and would fail as written; would take: a decision
  to amend REQ7 to "no capability an agent can act through beyond tools", or to
  stop using the library's own server assembly so the advertised set can be
  chosen.
- **Q6** — Does REQ35 stand for the unknown-tool case, given that the library
  answers it with the protocol's own "invalid params" error rather than a
  `validation_failed` refusal?; blocks: AC18, which drives a call naming a tool
  that does not exist and one naming `create_project`, and expects
  `validation_failed` from both; would take: a decision to accept the protocol's
  own error for the two cases the library owns — a tool that does not exist —
  while keeping `validation_failed` for the three the tool's own arguments cover,
  or to amend AC18 to say so.

> Both were settled after this report was written, by amending the requirements
> in [spec-4](./spec-4.md), which records what each now asks for and why. Q6 went
> further than the question above supposed: the three argument cases cannot carry
> `validation_failed` either, for the reason FIND11 gives, so the amended REQ35
> asks that a failure name what was wrong rather than that it carry a code of
> Nook's.

**Follow-ups:**

- **Q7** — Does holding one protocol server per project, for the life of the
  process, cost anything that matters?; matters because: the recommended hosting
  never disposes of one, so a long-running server accumulates them at the rate
  projects are connected to; would take: counting what a process holds after a
  realistic run, once there is a realistic run to count.
- **Q8** — Does the program a real coding agent runs inside put what the server
  says on opening in front of the agent?; matters because: ASM4 is the reason
  REQ15 is the answer to "which project am I in", and if it is false the surface
  withholds something an agent genuinely needs; would take: pointing a real coding
  agent's client at the built server and reading its transcript.
- **Q9** — Does the transport behave the same under a different web container and
  against an older client?; matters because: every hosting result here is one
  container and one protocol revision, and the module's whole serving side rides
  on them; would take: repeating two of the probe groups on a second container,
  and once against a client pinned to an older revision.
