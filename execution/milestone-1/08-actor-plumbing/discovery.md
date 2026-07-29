# Actor plumbing approach

## Summary

- **Both identities are already reachable, and the agent surface hands a tool
  each of them from the right place.** Who a call is for travels with the call:
  the same open connection, given a different person's token on its next
  request, records that person and not the first. What agent is acting comes
  from the opening exchange and stays put for the life of the connection. Two
  connections working at once, 200 calls, none recorded the other's pair. The
  protocol library already carries both — one line added to the transport is the
  whole of the plumbing.
- **One library reads a token at both adapters, and it is the smallest of the
  three.** Nimbus JOSE+JWT is one jar with nothing behind it; Auth0's java-jwt
  brings four; jjwt six; Ktor's own token support brings thirty-seven and serves
  only one of the two adapters, because the agent surface is a Java servlet — a
  request handler a web container runs for you — and not a Ktor route. All three refuse the same five bad tokens. None of them makes
  the checks spec-6 asks for on the person's name — an empty one, spaces only,
  201 characters, a NUL character — so those are Nook's own work at both adapters,
  whichever library is chosen.
- **The calling library has nowhere to put who a call is for, and the tempting
  way to add it is the one that silently attributes a write to the wrong
  person.** Holding the identity on the thread is right until the call is handed
  to a thread that is allowed to sit and wait — which is exactly what epic 07
  recommended doing — and then it is wrong on all 160 calls; worse, a thread
  nobody cleared recorded a second call, naming nobody, as `alex`. One client
  for the program with a small view of it bound to each call costs no threads at
  all, 100,000 views in 15 milliseconds, and got all 160 right.
- **Nothing about the request changes, and no token goes past the adapters.** The
  request the core receives is byte for byte what it received before this epic;
  the two identities ride beside it in headers of their own; and no header the
  core saw carried a caller's token.
- Recommendation in brief: take Nimbus in its assembled one-call form, at both
  adapters, built when the program starts — which turns "started with nothing to
  check tokens against" into a program that stops on its own; put the gate in
  front of the protocol library on the agent surface and in the route on the web
  API, writing the refusal the bearer-token standard describes rather than the
  bare word two Ktor plugins emit; grow the calling library with a view bound to
  one call's identity and never a thread-local; and turn away an over-long
  client name when the connection opens, which works but costs handing the opening request's
  body on so it can be read a second time.

## Questions

- **Q1** — Can each adapter learn who a call is for from the token it presents, and
  can the agent surface learn what agent is acting from what the client names
  itself, so that neither can be named by a caller?; informs: [spec-6](./spec-6.md)'s
  requirements that the person come from the token's `sub` claim and nothing
  else, that the acting agent come from the opening exchange, that a caller be
  unable to name either, and that two connections never take each other's
  identity.
- **Q2** — Which library reads a token, given that one adapter is a Java servlet
  and the other is a Ktor route, and can one library serve both?; informs: what
  the build pins, the gate on both adapters, and the requirement that an adapter
  started with nothing to check tokens against stop rather than serve.
- **Q3** — How do the two identities cross to the core without changing the
  request and without the caller's token going with them?; informs: spec-6's
  requirements about what crosses to the core — two values alongside the
  request, the request itself untouched, and no token handed onward.
- **Q4 (emerged)** — Can a client naming itself with more than 200 characters be
  turned away when the connection opens, given that the protocol library serves it happily?;
  informs: spec-6's requirement that such a connection not be served, and its
  edge case saying the answer must say the name is too long. Asked once the
  probes showed the library accepting a 201-character name and handing it
  straight to the code behind a tool.

Bound: one throwaway Kotlin program on one machine (macOS, Apple silicon, JDK
25), every library version taken from the repository's own pins except the token
libraries, which the repository does not yet pin and which were taken at their
current releases; six probe groups; a stand-in for the core rather than the
core; no database; no real coding agent's client; no second machine; and no
crowd larger than sixteen callers at once. That sufficed because every question
here is about how the two adapters take an identity in and pass it on, which one
honest execution settles, and because what the core does with what it is told is
already settled and tested by epics 03 to 05. Six things the bound leaves
untested are recorded as limitations.

## Method

A throwaway Kotlin program in a scratch directory, with its own build, run
2026-07-28 and deleted after this report. It depends on the repository's own
built `:contract` module, so the probes drive the real calling library and the
real reading of a request rather than imitations of them. Alongside it: the
official Java MCP library at the pinned 2.0.0, Jetty 12.1.11 as the web
container hosting its servlet-shaped transport, and Ktor 3.5.1 for the web adapter
— all the versions the repository already pins.

**A JSON Web Token — the standard's own name for a signed set of claims a caller
presents — was minted for every probe rather than issued by anything.** That is
what spec-6 describes: one token minted by hand and written into configuration.
Every token here was minted with Nimbus, and read back by each candidate in
turn, so the reading is measured against a token none of them wrote.

**The core was a stand-in, not the core.** For the crossing it is a small server
that records the two identity headers, the request body, and every header name
it received, and answers with what it was told, so a probe can check what the
core received rather than what a caller believed it sent. For the web adapter it is
`RecordingCore`, the stand-in both adapters already share. What the core does
with an identity once it has one is [spec-3](../05-operation-catalog/spec-3.md)'s
subject and belongs to the build, not to this report.

Three token libraries were compared, on five axes: whether one library can serve
both adapters, which bad tokens each refuses without being asked, how a mistake in
the code using it fails, what each costs the build, and what each does when the
adapter is configured with a key it cannot use.

- **Nimbus JOSE+JWT 10.9.1** — the library Spring Security's own token support is
  built on. Tried in two forms: its low-level parse-then-verify calls, and its
  assembled one-call form.
- **Auth0's java-jwt 4.6.0** — the library Ktor's own token support is built on.
- **jjwt 0.13.0** — a third, widely used, in its api/impl/jackson trio.
- **Ktor's own token support 3.5.1** was measured for what it costs and read for
  what it can reach, but not built into a candidate for the MCP server: it plugs
  into a Ktor route, and the agent surface is a servlet hosted by Jetty.

Six probe groups drove them: the eleven kinds of token each library is asked to
read; an adapter built the way `:mcp-server` builds one, with a gate in front
and the one line this epic would add to the transport, driven by the library's
own client with the token changed mid-connection and by hand-written requests
that say things no client library will say; two connections calling at once, 100
times each, with different people and different agents; three ways of building
the gate on the web adapter, each driven with eight kinds of call; four shapes for
carrying the identity to the core, driven by sixteen callers at once; and a
client naming itself with 200 and 201 characters.

Not done: the real core and any database; the repository's own `:mcp-server` and
`:web-app` modules, whose gates these probes rebuild rather than modify; any
real coding agent's client, so nothing here says whether one can be told to
present a token; the official MCP Inspector, which epic 06 used and this report
did not; any second machine; any second web container or web server engine; any
load beyond sixteen callers; and a signing key pair at either adapter, which was
tried once against a token and not built into an adapter.

## Findings

### FIND1 — The token travels with each request and the client's name with the connection, and the protocol library hands a tool both

**Confidence:** solid — driven through the library's own client, with the token
changed between calls on one open connection · answers Q1

The protocol library takes a piece of code that reads whatever it likes off each
arriving request and carries it to wherever a tool runs. Nook's transport is
built without one today, so nothing is carried. Adding it is one line, and what
it carries is whatever the gate already read off the token:

```kotlin
.contextExtractor { request -> McpTransportContext.create(mapOf(SUBJECT to request.getAttribute(SUBJECT))) }
```

The client's own name comes from somewhere else entirely — the opening
exchange — and the library holds it against the session. A tool reads both from
the same object it is handed and currently ignores:

```kotlin
McpServerFeatures.SyncToolSpecification(declaration) { exchange, request ->  // `exchange` is `_` today
    exchange.transportContext().get(SUBJECT)   // the person, from this request's token
    exchange.clientInfo?.name                  // the agent, from the opening exchange
}
```

One connection, opened by a client naming itself `claude-code`, with the token
swapped between calls:

```
what the connection was told  -> This connection works in the Nook project search-revamp.
first call                    -> subject=alex   agent='claude-code'
same connection, a new token  -> subject=jordan agent='claude-code'
and back again                -> subject=alex   agent='claude-code'
```

That is the distinction spec-6 rests on, executed: the person follows the token
on each request, and the agent follows the connection. Every request of a
connection carried the token, not only the first — the gate counted 222 `POST`s,
4 `GET`s and 4 `DELETE`s past it, which are the three kinds the long-lived-HTTP
transport makes.

### FIND2 — Two connections working at once never take each other's identity

**Confidence:** solid — 200 calls, checked one by one against what each should
have recorded · answers Q1

Two connections to the same project, one for `alex` opened by `claude-code` and
one for `jordan` opened by an agent made deliberately slow, with 100 calls each
driven from eight threads at once:

```
calls made                                -> 200
calls whose row would name the wrong pair -> 0
```

The identity is held per call and per session by the library rather than by
anything Nook writes, which is why there was nothing to get wrong.

### FIND3 — A client that gives no name at all is served, and what the tool sees is an empty name

**Confidence:** solid — three opening exchanges written by hand · answers Q1

The protocol asks a client to name itself. It is not enforced: an opening
exchange carrying no `clientInfo` at all is served, and so is one naming the
client with an empty string. What reaches the code behind a tool is the same in
both cases:

```
an opening exchange naming no client at all   -> served · the tool saw: subject=alex agent=''
a client naming itself with an empty string   -> served · the tool saw: subject=alex agent=''
a client naming itself with 201 characters    -> served · the tool saw all 201 characters
```

So "no agent acted" arrives as an empty name and never as anything else, which
is what spec-6's edge case describes and what makes "record nothing where the
name is blank" a complete rule. The third line is a requirement the library does
not help with at all — see FIND14.

### FIND4 — The gate turns a call away before any of the protocol runs, and the client's own failure names it an authorization problem

**Confidence:** solid — read off the wire, and out of the library's client ·
answers Q1

A servlet in front of the dispatcher refuses anything not presenting a valid
token, so nothing of the protocol — not the opening exchange, not the tool
listing — happens first:

```
no Authorization header                 -> 401  WWW-Authenticate: Bearer realm="nook"  {"error":"this call presents no valid bearer token"}
something that is not a bearer token    -> 401  the same
a token signed with something else      -> 401  the same
a token whose subject is empty          -> 401  the same
a token whose subject is 201 characters -> 401  the same
a valid token                           -> 200  the opening exchange's own answer
```

The library's client reports it as a failure to initialize, and the reason is in
the cause rather than in the message:

```
java.lang.RuntimeException: Client failed to initialize by explicit API call
  caused by McpHttpClientTransportAuthorizationException: Authorization error when sending message
```

So the client does tell an authorization failure apart from every other kind —
whether the program an agent runs inside shows that to a person is a different
question, and not one this probe can answer.

### FIND5 — Three libraries refuse the same five bad tokens, and none of them makes the checks spec-6 asks for on the person's name

**Confidence:** solid — eleven tokens through three libraries, each minted by a
fourth party to the reading · answers Q2

| the token | Nimbus JOSE+JWT 10.9.1 | Auth0 java-jwt 4.6.0 | jjwt 0.13.0 |
| --- | --- | --- | --- |
| a valid token | read, `alex` | read, `alex` | read, `alex` |
| signed with something else | refused | refused | refused |
| expired an hour ago | refused | refused | refused |
| minted for another recipient | refused | refused | refused |
| signed with nothing at all | refused | refused | refused |
| not a token at all | refused | refused | refused |
| no subject at all | refused | refused | **read**, none |
| an empty subject | **read**, `''` | **read**, `''` | **read**, none |
| a subject of only spaces | **read**, `'   '` | **read**, `'   '` | **read**, none |
| a subject of 201 characters | **read**, all 201 | **read**, all 201 | **read**, all 201 |
| a subject holding a NUL character | **read** | **read** | **read** |
| a subject of emoji and non-Latin script | read, `søk-🔍-用户` | read, `søk-🔍-用户` | read, `søk-🔍-用户` |

The top six rows are the standard's own business and every library does them.
The next five are Nook's: the four cases spec-6 names as refusals reach the adapter
as ordinary tokens, and an adapter that does not check them will record an empty
person, a person made of spaces, or a name longer than the store can hold.
Nimbus and java-jwt can be told to insist the claim is *present*, and both were
told to here — which is why they refuse the seventh row and jjwt, which offers
no such setting, does not. None of the three can be told anything about what is
in it.

The last row is the one to keep: a person's name in emoji and non-Latin script
comes back exactly as it went in, which is what spec-6's edge case asks and what
would break if an adapter normalized anything.

### FIND6 — Nimbus's low-level form has an answer a caller can forget to look at, and its assembled form does not

**Confidence:** solid — the mistake written out and executed · answers Q2

Nimbus's parse-then-verify calls report a bad signature by *returning false*.
The forgetting is one missing `if`, and nothing about the code looks wrong:

```
a token signed with something else, the verifying step's answer not looked at -> read, sub=alex
the same token, the answer looked at                                          -> refused
```

Its own assembled one-call form has no such answer to forget, and refuses the
same token by throwing, like the other two libraries do:

```
a token signed with something else -> BadJWSException: Signed JWT rejected: Invalid signature
a token with no subject            -> BadJWTException: JWT missing required claims: [sub]
a valid token                      -> alex
```

This is the same trap epic 05 recorded about where the reading of a request sits
and epic 07 recorded about telling an unreachable core from a broken one: code
that is wrong while looking right. Here the cost of getting it wrong is that
every token is accepted, whoever signed it.

### FIND7 — What each library costs the build: one jar against four, six, and thirty-seven

**Confidence:** solid — each resolved on its own · answers Q2

| | jars | size | what comes with it |
| --- | --- | --- | --- |
| Nimbus JOSE+JWT | 1 | 794 KiB | nothing |
| Auth0 java-jwt | 4 | 2.3 MiB | Jackson 2.22 |
| jjwt (api + impl + jackson) | 6 | 2.5 MiB | Jackson 2.12.7, four years behind |
| Ktor's own token support | 37 | 17.1 MiB | java-jwt, a key-fetching library, Guava, Jackson |

Ktor's is the odd one out in kind as well as size: it plugs into a Ktor route,
so it could serve the web API and nothing on the agent surface, which is a
servlet hosted by Jetty. Two adapters sharing one reading of a token is the reason
the identity is the same whichever adapter a call arrives at — so a library that
can only reach one of them would mean two readings, and the sameness would be a
promise two pieces of code keep rather than a fact.

### FIND8 — An adapter with nothing to check tokens against stops when it builds its reader, before it serves anything

**Confidence:** solid — executed at the moment a program would do it · answers Q2

Nimbus refuses a key too short for the signing it is asked to do, at the moment
the verifier is built rather than at the first call:

```
built with no key at all   -> KeyLengthException: The secret length must be at least 256 bits
built with a key too short -> KeyLengthException: The secret length must be at least 256 bits
built with a usable key    -> built
```

So spec-6's requirement that an adapter started without that setting stop, naming
what is missing, needs no mechanism of its own beyond building the reader during
startup and letting the failure out. What it does not get for free is the
*naming*: the message above says a secret is too short, not which setting was
missing, so the words are the epic's to write.

### FIND9 — On the web adapter all three ways of gating refuse identically, and only a hand-written refusal says what the standard defines

**Confidence:** solid — eight kinds of call through three apps · answers Q2

Ktor's plain bearer support, Ktor's own token support, and a route that reads the
header itself all answered the same way to all eight: 401 with an empty body for
the seven bad ones, served for the valid one, and the stand-in core recorded
only the calls that presented a valid token. Where they differ is what the
refusal tells the caller:

```
Ktor's bearer support         -> WWW-Authenticate: Bearer
Ktor's own token support      -> WWW-Authenticate: Bearer realm=nook
the route checking for itself -> WWW-Authenticate: Bearer realm="nook", error="invalid_token",
                                 error_description="this call presents no valid bearer token"
```

The third is the form the bearer-token standard ([RFC 6750](https://www.rfc-editor.org/rfc/rfc6750)
§3) defines, and neither plugin will produce it: both fix the challenge at the
word `Bearer` plus a realm.

A refusal of the token and a refusal of the call's contents stay plainly
different, which is what spec-6 asks:

```
the contents were wrong -> 200, {"jsonrpc":"2.0","error":{"code":-32602,"message":"this operation defines
                                 no field named \"colour\"","data":{"reason":"validation_failed",…
the token was wrong     -> 401, empty body
```

### FIND10 — The calling library sends no identity and has nowhere to put one, and a client per identity costs threads it does not give back

**Confidence:** solid — the real calling library driven against a recording
server · answers Q3

Driven as it is today, the request arrives carrying five headers and nothing
about who it is for:

```
every header the core received -> [Accept, Content-Length, Content-Type, Host, User-Agent]
who the core was told it is for -> subject=null agent=null
```

Nothing on its surface can change that: it is built with an address and a wait
limit, and its eleven operations take a project, a reference and a command. So
the only way to vary a header without changing the library is a whole library
per identity, and that is not free — each holds a web client of its own:

```
50 calling libraries built in           -> 7.0 ms
threads this machine is running, before -> 7, after -> 14
after closing all 50                    -> 14
```

Seven threads for fifty identities, and closing them did not give the threads
back within the run. An adapter that made one per person would accumulate them.

### FIND11 — An identity held on the thread is wrong every time the work moves, and a thread nobody cleared attributes one person's write to another

**Confidence:** solid — 160 calls per shape, checked against what the core
received · answers Q3

Three shapes, each driven by sixteen callers at once making ten calls each, with
every call checked against what the recording core was actually told:

| how the identity is carried | calls | told the wrong person |
| --- | --- | --- |
| beside the call itself | 160 | 0 |
| on the thread the call was made on | 160 | 0 |
| on the thread, with the work handed to a thread allowed to sit and wait | 160 | **160** |

The third row is the arrangement epic 07 recommended for this exact call — hand
the core's work to threads that may block, rather than running it on the few a
web server answers on. Under it, an identity left on the calling thread is not
there when the request is built, and every write would be refused for naming
nobody.

The second row is worse than the third, because it passes. What it hides:

```
alex's call, on a pooled thread, leaving its identity behind -> recorded for alex
the next call on that thread, naming nobody at all           -> recorded for alex
```

A call that named nobody was attributed to `alex`. That is a wrong row in the
store, written silently, by code that passed every concurrent probe put to it.

### FIND12 — One client for the program with a view of it per call costs nothing and gets every call right

**Confidence:** solid — 100,000 views built, and 160 concurrent calls checked ·
answers Q3

The shape that works keeps one web client for the life of the program and hands
out a small object bound to one call's identity, so the eleven operations keep
the arguments they have and the identity is settled where the call is made:

```
100,000 views built in          -> 14.8 ms
threads before -> 7, after      -> 7
sixteen callers, ten calls each -> 160 calls, wrongly attributed: 0
alex's call, then jordan's on the same thread -> alex|claude-code, then jordan|
```

The last line is the case FIND11 got wrong: nothing is left on a thread, so
nothing can be inherited from one.

### FIND13 — The request itself is unchanged, and no token crosses to the core

**Confidence:** solid — the same request compared byte for byte, with and
without the identity · answers Q3

```
the request the core received, before -> {"jsonrpc":"2.0","method":"create_project","params":{"name":"Search revamp"},"id":1}
the request the core received, after  -> the same, identical: true
headers now arriving                  -> [Accept, Content-Length, Content-Type, Host, Nook-Agent, Nook-Subject, User-Agent]
any header holding a caller's token   -> false
```

Two names added beside the request, nothing added to it, and nothing of the
caller's credentials past the adapter — which is the protocol's own security rule
and the reason the two names exist at all.

### FIND14 — A client naming itself with more than 200 characters can be turned away when the connection opens, at the cost of handing the opening request's body on so it can be read twice

**Confidence:** solid — three opening exchanges through a gate that checks it ·
answers Q4

The name arrives inside the body of the opening request, which the protocol
library's transport reads for itself — and a request's body can be read once.
So checking the name means reading the body at the gate and handing the
transport a request that can be read again. Built that way:

```
a client naming itself normally            -> 200, the opening exchange's own answer
a client naming itself with 200 characters -> 200, served
a client naming itself with 201 characters -> 400, {"error":"this client names itself with 201 characters,
                                                     and 200 is the most Nook records"}
an ordinary connection through the same gate, then calling a tool -> subject=alex agent='claude-code'
```

So the requirement stands as written — unlike epic 06, which found two of
spec-4's requirements frozen inside the library and had to amend them. This one
costs a wrapper around the request and nothing else.

## Implications & recommendation

- **Take Nimbus JOSE+JWT, in its assembled one-call form, at both adapters**
  (FIND5, FIND6, FIND7) — it is the only candidate that is one jar with nothing
  behind it, it reaches both a servlet and a Ktor route because it knows about
  neither, and its assembled form removes the one way of using it that silently
  accepts every token. Ktor's own token support is ruled out on the first count
  alone: it can gate the web API and cannot touch the agent surface, which would
  leave two readings of a token where spec-6 wants one.
- **Write Nook's own four checks on the person's name at both adapters** (FIND5) —
  no library makes them, and each unchecked case is a row in the store nobody
  can attribute: an empty name, a name of spaces, a name longer than the column,
  a name holding a NUL character. They belong beside the token reading rather
  than inside an operation, because a call that cannot be attributed must not
  reach the core at all.
- **Build the reader when the program starts, and let its failure stop the
  program** (FIND8) — that is spec-6's requirement about an adapter started with
  nothing to check tokens against, met by ordering rather than by mechanism. The
  message it fails with is about key length and not about a missing setting, so
  the words are still the epic's to write.
- **Gate the agent surface with a servlet in front of the dispatcher, and the
  web API in its route** (FIND4, FIND9) — on the agent side this is what keeps
  the opening exchange itself behind the gate, so a connection cannot be opened
  and its tools cannot be listed without a token. On the web side, write the
  refusal by hand rather than taking either Ktor plugin's: both stop at the word
  `Bearer`, and the bearer-token standard defines a fuller challenge that says
  the token was the problem.
- **Add the one line to the transport, and read the agent from the exchange the
  tools already receive** (FIND1, FIND2, FIND3) — the plumbing this epic needs
  on the agent surface is the one piece of code the transport takes for reading
  a request, plus the argument seven tool handlers currently ignore. Nothing else about the protocol
  server changes, and the library's own per-call and per-session handling is
  what makes two connections at once safe rather than anything Nook writes.
- **Record "no agent acted" as an empty name** (FIND3) — a client that gives no
  name and a client that names itself with an empty string arrive identically,
  so one rule covers both, and the web API — where no agent ever acts — is the
  same rule again rather than a second one.
- **Grow the calling library with a view bound to one call's identity, and never
  with a thread-local** (FIND10, FIND11, FIND12) — the library cannot say who a
  call is for today and no arrangement outside it can make it, so something in
  `:contract` changes either way. The thread-shaped answer is the one to refuse:
  it fails outright under the threading epic 07 recommended, and where it
  passes, it attributes an unattributed write to whoever used that thread last.
  The view costs one object per call, no threads, and leaves the eleven
  operations' arguments alone.
- **Expect the request and the entities to grow rather than the crossing**
  (FIND13) — the request the core receives is unchanged and the two identities
  ride beside it, which is what spec-6 requires and what keeps every check
  carried over from the two adapter epics meaningful. This is also the case epic
  07's discovery warned about, from the other side: a core answering with fields
  the adapter was not built with becomes a fault, and it stays impossible only
  while both halves ship from one source tree.
- **Turn away an over-long client name when the connection opens, and hand the body on
  re-readable** (FIND14) — the requirement stands as written, which is worth
  saying plainly because epic 06 found two of spec-4's did not. The mechanism is
  a wrapper around the opening request, and it is the kind of thing that looks
  like an implementation detail until the body has already been consumed and the
  connection fails for a reason nobody can see.

## Limitations

- **The core behind the crossing was a stand-in, and there was no store** — at
  risk: nothing here shows an identity reaching a written row and being read back
  off it, so every claim above is about whwhen the connection openss send, not about what gets
  recorded; would raise confidence: driving spec-6's own criteria against the
  real core and store, which spec-6 already assigns to
  [epic 09](../09-full-system-test/).
- **The adapters here are the spike's, not the repository's** — at risk: both gates
  were rebuilt to the shape `:mcp-server` and `:web-app` already have rather than
  added to those modules, so nothing here proves the change lands as cleanly in
  the real ones — in particular the dispatcher's existing routing, which the
  gate now sits in front of; would raise confidence: making the change in the
  modules themselves, which is the epic's build work.
- **No real coding agent's client was involved** — at risk: the whole gate
  assumes an agent's client can be told to present a token on every request, and
  the only client driven here was the protocol library's own, which was told to
  by a piece of code written for the probe; would raise confidence: pointing a
  real coding agent's client at the built server with a token in its
  configuration. (Epic 06 recorded the same limitation about the same client
  question, and used the official MCP Inspector; this report used neither.)
- **One machine, one web container, one web server engine, and no load** — at
  risk: every result is Jetty and Ktor's CIO engine on macOS with at most
  sixteen callers, and the concurrency findings are the ones a real crowd would
  test hardest; would raise confidence: a load probe against the built adapters.
  (The write path, the read path, the connection, and both adapter epics have
  each left this question behind; this is the fifth instance, and one probe
  against the assembled system would answer all five.)
- **Only a shared secret was built into an adapter** — at risk: a signing key pair
  was minted and read back once, but no adapter was built holding only a public
  key, so nothing here says what that costs or what an adapter would be configured
  with; would raise confidence: building one adapter each way, which is a small
  change to the reader and no change to anything else.
- **Minting the token was done in code, not by a person** — at risk: spec-6 says
  a person mints one token by hand and writes it into configuration, and none of
  these probes exercised that as a person would do it — there is no command to
  run, and none of the three libraries ships one; would raise confidence: writing
  whatever the milestone's minting step actually is and using its output here.
- **The four refusal cases on the person's name were checked at the gate the
  probe wrote** — at risk: the finding is that no library makes those checks, not
  that these particular ones are complete; a case nobody thought of is exactly
  what this method cannot find; would raise confidence: nothing worth spending —
  the cases are spec-6's, and checking them is a test to write rather than a
  question to investigate.

## Open questions

**Needs action:**

- **Q5** — Does each adapter hold a shared secret, or a public key it can only
  verify with?; blocks: what "settable from outside the program" actually holds,
  and what the by-hand minting step produces; would take: a decision. The
  evidence both ways is thin and even — a shared secret is one line of
  configuration and the same string at both adapters, and a key pair means an adapter
  physically cannot mint a token it would then accept, which is what the
  protocol's authorization specification means by calling a server a resource
  server. Nothing here measured the cost of either.
- **Q6** — Does the calling library gain a view bound to an identity, or do its
  eleven operations gain an argument?; blocks: the shape of the change inside
  `:contract`, and the same question mirrored on the core's answering side, which
  has to hand an identity to whatever runs the operation; would take: a decision,
  because the surface belongs to [spec-3](../05-operation-catalog/spec-3.md)
  rather than to either adapter — FIND12 shows the view works and costs nothing, and
  FIND11 shows what the tempting third answer costs.

**Follow-ups:**

- **Q7** — Can a real coding agent's client be told to present a token on every
  request, and what does a person see when it is refused?; matters because: the
  gate is worth nothing if an agent cannot get past it, and a misconfigured token
  is the everyday failure this surface will actually produce — FIND4 shows the
  protocol library's own client names the failure an authorization error, and
  says nothing about what a person is shown; would take: pointing a real client
  at the built server, once there is one to point it at.
- **Q8** — What should the refusal point a client at, once there is somewhere to
  point it?; matters because: the protocol's authorization specification expects
  a refused call to tell a client where to go and get a token, spec-6 defers that
  along with the login server, and the challenge written today names a realm and
  nothing else — so the shape of the refusal changes the day the server arrives;
  would take: a decision when it does.
- **Q9** — Does anything need doing about a token that has to be replaced?;
  matters because: one token minted to outlast the milestone is an assumption
  spec-6 records, and the day it stops holding, every caller's configuration is
  rewritten at once and both adapters refuse everything until they are; would take:
  nothing now — it becomes a question the login server answers.
