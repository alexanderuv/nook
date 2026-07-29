# A failure never names the part of Nook that failed — ADR-4

> **Corrected 2026-07-28** — the Context below described the web API's callers as
> people's own programs. `/api` is the web UI's back end
> ([docs/01](../../docs/01-interface-contracts.md)), and the callers outside this
> repo are the MCP surface's. The decision, options and consequences are unchanged;
> "Revisit when" had this right already.

## Context

Nook is several programs behind one address. A call from the web UI reaches the web
app, which reaches the core service, which reaches Postgres and a git working copy;
a call from an external agent reaches the MCP server, which reaches that same core.
When a call produces no verdict, each of those can be the reason, and the temptation
is to say which — the information is right there in the exception the client caught.

[Spec-5](../../execution/milestone-1/07-web-api/spec-5.md) took that temptation as a
requirement. Its REQ24 obliged a failure reply to say whether the core had answered
at all — that it could not be reached, or that it answered and something inside it
broke — on the reasoning that one is worth attempting again and the other is not.
Both adapters serve one reply shape ([ADR-2](./adr-2.md)), so that requirement put
Nook's internal topology into every contract at once — the MCP surface's included,
whose callers are agents outside this repo: it told a caller there is a "core", that
it is reachable separately, and that its being down is a state they should recognize.

Two things make that a mistake rather than a kindness. A caller cannot act on it —
whichever half failed, the call produced no verdict, the request was not wrong, and
the only recovery is to try again later or report it. And naming a part in a
contract promises that part: a caller who branches on "the core is unreachable"
cannot be given a Nook whose halves are merged, split differently, or fronted by a
cache, without breaking.

Any acceptable option had to keep a caller able to tell "your request was wrong"
from "this did not work", keep Nook's own recovery logic able to distinguish
failures it genuinely acts on, and stop the internal arrangement from becoming
something outside callers depend on.

## Decision

We will not tell a caller which part of Nook failed. A call that produced no
verdict comes back as JSON-RPC `-32603` with a message that describes the
situation, and neither a field nor the wording of that message says whether the
core was reached, which program noticed, or what component was involved
([ADR-2](./adr-2.md)). Spec-5's REQ24 is struck, and its acceptance criteria now
require the two failures to be **indistinguishable** to a caller.

The distinction survives where it is useful and private: the client the adapters
share may know whether an answer arrived, and may use it to decide its own
recovery ([spec-3](../../execution/milestone-1/05-operation-catalog/spec-3.md)
REQ15). What it must not do is pass that outward.

This generalizes beyond the one requirement. No reply on any Nook surface names a
component, a process, a table, a queue, or a library behind the surface, and no
failure reports which of them gave way. What a caller receives describes their
call and what they can do about it.

Scope: replies to callers, on every surface. Logs, metrics and anything an
operator reads are explicitly **not** covered — they exist precisely to carry what
this decision keeps out of replies.

## Options considered

- **OPT1 — Keep REQ24 with a field on the reply** — the reply carries `reached`,
  valued "core" or "connection", and a caller branches on it without matching
  prose; *ruled out:* it is the internal arrangement published as a contract, and
  the caller has no action that depends on the answer. Its genuine strength — a
  machine-readable retry hint — is better served by a general "this produced no
  verdict, it may work later" than by naming the part.
- **OPT2 — Keep the distinction in the message wording** — the message begins
  `core:` or `connection:`, so nothing about the reply's shape changes; *ruled
  out:* it is the same disclosure with a worse interface, since a caller acting on
  it must match on prose, which then cannot be reworded.
- **OPT3 — Status quo before spec-5, where every failure said "something inside the
  core failed"** — no topology disclosed on purpose; *ruled out:* it is not that
  the words revealed too much but that they were false — a core that was never
  started reported a defect inside it. The decision here keeps the silence and
  fixes the falsehood.

## Consequences

- **gain** — the internal arrangement stays changeable: the core and the web app
  can merge, split, or gain a layer between them without any caller noticing;
  lands on: every future deployment and topology decision
  ([08](../../docs/08-deployment-and-cloud.md)).
- **gain** — one less thing for every surface to define and test. The MCP server
  and the web API report a verdictless call the same way, and neither has a
  requirement about internal origins to satisfy; lands on: specs 4 and 5.
- **cost** — diagnosing a failure from the caller's side gets harder: "it did not
  work" no longer distinguishes "start the core" from "read the stack trace", so
  whoever is debugging must go to the app's logs. Nothing in this milestone
  produces those logs to a standard that makes it easy; lands on: whoever operates
  Nook, and on the deployment work in [05](../../docs/05-project-and-ops.md).
- **cost** — a caller that wants to retry intelligently must treat every
  verdictless call as possibly transient, including ones that are not, so a
  retrying client will re-attempt calls that can never succeed; lands on: the
  interface arriving in milestone 4.
- **cost** — the existing message wording leaks by accident where it quotes an
  underlying library, which this decision now forbids and which is not yet fixed;
  lands on: the epic that lands [ADR-2](./adr-2.md).

## Revisit when

- **Nook gains an operator-facing surface of its own** — a health address, a
  status page, an admin view — reconsider: that surface may name components
  freely, and the line between it and a caller's reply needs drawing once it
  exists.
- **Callers are no longer only Nook's own interface** — a third-party integrator
  with a support relationship may reasonably be given a correlation id to quote,
  which is a different mechanism than naming the part and should be decided on its
  own.
