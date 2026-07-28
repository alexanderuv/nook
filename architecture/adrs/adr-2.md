# Serve the operation catalog as JSON-RPC 2.0 — ADR-2

## Context

Nook defines its eleven operations once, in the shared contract library, and
serves them on two front doors: the core service's own connection, which both
adapter apps call, and the web API, which serves that same shape outward
([01 — Interface contracts](../../docs/01-interface-contracts.md),
[ARCHITECTURE §5](../../ARCHITECTURE.md)). The shape itself was designed here: a
request carrying `operation`, `project` and `payload`, and a reply naming its own
ending — `answer`, `refusal` or `fault` — with a refusal carrying `{code,
message, details?}` under one of four names.

That design recorded a real reason for not being REST: the surface is
action-heavy, so paths and verbs would mean two designs over one operation set,
and one status number cannot say both "no such item" and "no such route". What it
never recorded is the published standard for the shape it chose instead. JSON-RPC
2.0 specifies exactly this arrangement — one endpoint, the operation named in the
body, the ending in the reply rather than in the status number — and the repo
already carries an implementation of it: the Model Context Protocol is defined on
JSON-RPC, so `:mcp-server` has spoken it since epic 06, whose
[discovery](../../execution/milestone-1/06-mcp-server/discovery.md) records
JSON-RPC responses as probe evidence. Nook therefore ships a standard protocol
and a hand-built isomorph of it, and pays to document, teach and test the second
one.

Any acceptable option had to satisfy four drivers: one contract across both front
doors and no second design for the interface arriving in milestone 4; a caller
able to reach Nook with tooling that already exists rather than tooling written
for Nook; the ending readable from the reply rather than from the HTTP status
number; and the four domain failures still reaching a caller as something
actionable.

## Decision

We will serve the operation catalog as **JSON-RPC 2.0 over HTTP**, on the core's
own connection and on the web API alike, replacing the envelope designed here.

A call is a JSON-RPC request object: `method` names the operation, `params`
carries the project (for the seven project-scoped operations) and the operation's
arguments, and every call carries an `id`. A call that succeeds answers with
`result`; a call that fails answers with `error {code, message, data?}`. Both
replies come back under one HTTP status, which is what the standard already
prescribes for a transport binding of this kind and what the original design
wanted.

The four domain failures map onto the standard's code space rather than
alongside it:

| what happened | code | `data` |
| --- | --- | --- |
| contents that are not JSON | `-32700` | — |
| the envelope is not a JSON-RPC request | `-32600` | — |
| an operation nobody defined | `-32601` | — |
| a field the operation does not define, a missing required argument, a value of the wrong kind, a project named where none belongs or missing where one belongs, **and every `validation_failed` the core produces** | `-32602` | `{"reason": "validation_failed", …}` |
| the core produced `not_found` | `-32001` | `{"reason": "not_found", "missing": "project"}` |
| the core produced `conflict` | `-32002` | `{"reason": "conflict", …}` |
| the core produced `cycle` | `-32003` | `{"reason": "cycle", …}` |
| the call produced no verdict at all | `-32603` | — |

`data.reason` carries the domain name so a caller reads it without matching a
number against a table, and the details a failure already carries ride alongside
it. The three domain codes sit in `-32000..-32099`, the range the specification
reserves for implementation-defined server errors.

Scope: this decides the shape of the operation catalog on both doors. It does not
decide the document operations' arguments ([02](../../docs/02-document-layer.md)),
and it does not reopen the MCP surface, which is JSON-RPC already by its own
specification. Batch requests and notifications — both optional parts of the
standard — are not served: every call names one operation and expects one reply.

## Options considered

- **OPT1 — Keep the envelope designed here, renaming its endings to `ok` /
  `bad_request` / `error`** — the smallest change, and it fixes the invented
  vocabulary without touching the shape; *ruled out:* the shape is JSON-RPC with
  different field names, so it keeps every cost of a private protocol — nothing
  off the shelf reads it, every client is written against Nook specifically, and
  the specs must teach it — while buying nothing the standard does not already
  give.
- **OPT2 — REST with RFC 9457 Problem Details** — genuinely strong: every proxy,
  browser and HTTP tool understands paths, verbs and status codes without being
  taught, reads become cacheable, and `application/problem+json` is a published
  error format; *ruled out:* the reason recorded when REST was first rejected
  still holds — an action-heavy catalog becomes two designs over one operation
  set, with two places to change a field, and the status number cannot separate
  "no such item" from "no such route". Worth revisiting if the interface ever
  needs HTTP caching on reads.
- **OPT3 — gRPC** — a mature RPC standard with generated clients and a strong
  contract; *ruled out:* it needs code generation and a binary protocol on a
  surface that is small, already HTTP and JSON, and reached from a browser, which
  needs a translating proxy to speak it at all.

## Consequences

- **gain** — Nook stops having a protocol of its own: a caller reaches it with a
  JSON-RPC client that already exists, and both front doors speak what
  `:mcp-server` already speaks; lands on: every caller, and the interface
  arriving in milestone 4.
- **gain** — the invented endings disappear with the envelope. `answer`,
  `refusal`, `fault` and `breakdown` become `result` and `error`, which removes
  the fork where one spec called a thing a breakdown and another called it a
  fault; lands on: all five milestone-1 specs and their definitions sections.
- **gain** — one of the four domain codes collapses into the standard's own:
  `validation_failed` is `-32602 Invalid params`, whether the request was
  unreadable or the core refused its contents; lands on: the contract library and
  the specs' edge cases.
- **cost** — numeric codes read worse than names for domain failures, which is
  why `data.reason` exists; a caller that ignores it is matching integers; lands
  on: whoever writes a client.
- **cost** — every test asserting on the envelope changes, in the contract
  library, the core service and `:mcp-server`, and the reply's single-field
  discriminator arrangement goes away; lands on: the epic that carries the
  change.
- **cost** — five specs, `ARCHITECTURE.md` and `docs/01` describe the old
  envelope in prose and must be amended before the epics they govern are built;
  lands on: the same epic, before epic 07 can be planned.
- **cost** — a call now carries an `id` that Nook has no use for beyond echoing
  it, and the reply must echo it correctly; lands on: the contract library.

## Revisit when

- **The web interface needs HTTP caching, conditional requests or range reads on
  the read operations** — reconsider: whether the read half of the catalog is
  better served as REST resources alongside the JSON-RPC write half.
- **A third party asks for a generated client or an OpenAPI description** —
  reconsider: JSON-RPC has no equivalent ecosystem, and OPT2's strengths grow
  with an outside integrator.
