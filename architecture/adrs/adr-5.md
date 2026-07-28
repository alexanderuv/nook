# Document updates use conditional-request semantics — ADR-5

## Context

Documents in Nook are edited section by section, and every edit produces a new
forward-only version ([02 — Document layer](../../docs/02-document-layer.md)). Two
callers can therefore read the same section, both edit it, and the second write can
erase the first without either noticing — the lost-update problem, which is
ordinary the moment the web interface and an agent work in one project at once.

The design already carried an answer: every mutating operation accepts an optional
`expectedVersion`, and a write whose value does not match the document's current
version is refused with nothing written. That is correct, and it is also
[RFC 9110 §13](https://www.rfc-editor.org/rfc/rfc9110#section-13) — HTTP's
conditional requests — where the version is an **entity tag**, supplying it is
**`If-Match`**, and a mismatch is a **precondition failure**. The design named none
of that, and gave the mismatch Nook's `conflict` code, which already meant a slug
collision: two unrelated situations under one name.

Any acceptable option had to make lost-update protection available without
imposing it on a single-user instance, keep the check cheap enough that the
interface can send it on every save, and stop a failed check from being mistaken
for a naming collision.

## Decision

We will state and serve the optional check as **conditional-request semantics
(RFC 9110 §13)**, using the standard's vocabulary in the contract and in the docs:
`expectedVersion` is an entity tag, supplying it is `If-Match`, and a mismatch is a
precondition failure that writes nothing. A caller that supplies nothing gets
last-writer-wins, which stays the default.

The failure is its **own** reason, not `conflict`. It joins the domain reasons of
[ADR-2](./adr-2.md) as `-32005`, with `data.reason` of `precondition_failed`, so a
caller can tell "someone edited this while you were editing it — re-read and
retry" from "that name is taken", which need different handling and different
words in an interface.

HTTP's own headers are deliberately not used, because these operations are not
addressed by URL — the wire is JSON-RPC ([ADR-2](./adr-2.md)) and the document is
named in the parameters. What is adopted is the semantics and the vocabulary, so a
caller who knows `If-Match` already knows this, and nothing here has to be invented
or explained twice.

Scope: the document layer's mutating operations. The structure operations
(`update_item`, `update_release`) take no version and are last-writer-wins; adding
one there is a separate decision, and the store's own row locking already prevents
the corruption cases that matter.

## Options considered

- **OPT1 — Keep `expectedVersion` as a mechanism of Nook's own, unnamed, failing
  with `conflict`** — the status quo, and it works; *ruled out:* it re-derives a
  specified mechanism, so a reader cannot tell whether Nook's version matches the
  one they know, and it overloads `conflict` with two situations an interface must
  present differently.
- **OPT2 — HTTP conditional requests properly, with `If-Match` headers and `412`**
  — the standard used as intended, with every proxy and client library
  understanding it for free; *ruled out:* it requires the documents to be URL
  addressed resources, which is the REST design [ADR-2](./adr-2.md) declined for
  the catalog as a whole. Worth revisiting together with that decision, never
  separately.
- **OPT3 — Locks held across a read and a write** — no lost updates at all, and no
  retry for the caller to handle; *ruled out:* it needs a lock that survives a
  caller walking away, which means expiry, renewal and stealing — a great deal of
  machinery for a single-user instance, and it makes an agent that stops mid-edit
  everyone else's problem.
- **OPT4 — Last-writer-wins only, offering no check** — the simplest thing, and
  honest for one user; *ruled out:* the embedded authoring agent and a person can
  already edit one document at once, so the case is real today rather than
  hypothetical, and adding the field later is a contract change.

## Consequences

- **gain** — the rule has a specification behind it: a caller who knows `If-Match`
  needs no explanation, and the docs state a deviation rather than a mechanism;
  lands on: [02](../../docs/02-document-layer.md) and whoever writes a client.
- **gain** — `conflict` means one thing again, so an interface can say "that name
  is taken" and "this changed under you" in the words each deserves; lands on: the
  web interface in milestone 4.
- **cost** — the domain reasons grow by one, and every surface that maps them has a
  fifth case; lands on: the epic that lands [ADR-2](./adr-2.md), and
  [spec-4](../../execution/milestone-1/06-mcp-server/spec-4.md)'s tool result
  mapping.
- **cost** — adopting the vocabulary without the headers means a reader who knows
  HTTP will look for the header and not find it, so every statement of the rule has
  to say where the value actually travels; lands on: the document layer's spec.
- **cost** — the check stays optional, so a careless caller still loses updates
  silently. Nothing here makes the safe path the default; lands on: whoever writes
  each client.

## Revisit when

- **The catalog moves to REST** — reconsider: OPT2 becomes available and strictly
  better, since the headers arrive for free with the addressing.
- **A second concurrent writer appears on the structure operations** — reconsider:
  the scope limit above was drawn because only documents have the problem today.
