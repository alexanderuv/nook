# Partial updates are JSON Merge Patch — ADR-3

## Context

`update_item` and `update_release` are the one way an item or a release changes,
whatever the field ([01](../../docs/01-interface-contracts.md),
[04](../../docs/04-structure-semantics.md)). Every field may be left unmentioned,
and a field the stored value may be taken away from — a description, a parent, a
release, a target date — may also be set to nothing. So a field arrives in one of
three states: unmentioned, set to a value, or set to nothing, and "say nothing
about this field" must stay distinct from "set this field to nothing" all the way
to the store.

Nook built that by hand. The contract library carries a `FieldChange` type with
`Keep` and `Set` cases, two serializers written by hand rather than generated, and
a comment explaining that a generated conversion has one slot per field and no way
to record whether the field was mentioned at all. Four specs state the three states
as requirements and edge cases of their own.

The three-state rule is not Nook's invention. **RFC 7396, JSON Merge Patch**,
specifies it exactly: a member absent from the patch leaves the target's member
alone, a member present with a value replaces it, and a member present with `null`
removes it. It further specifies that arrays are replaced whole rather than merged
— which is the rule [04](../../docs/04-structure-semantics.md) states in its own
words for the blocker set. Nothing in the design documents names it.

Any acceptable option had to keep the three states distinguishable end to end,
keep a caller able to clear a clearable field, and stop a caller from clearing a
field that must always hold a value.

## Decision

We will describe and serve partial updates as **JSON Merge Patch (RFC 7396)**, and
say so in the contract: an absent member leaves the field alone, a member carrying
a value sets it, and a member carrying `null` clears it. Arrays — the blocker set
— are replaced whole, which is the standard's own rule rather than a Nook one.

Two deviations from the standard are deliberate, and are stated wherever the
behavior is specified rather than left for a reader to discover:

- **A field the stored value may not be taken away from refuses `null`** rather
  than removing it. A name, a handle, a status and a type always hold a value, so
  asking to clear one is a mistake worth saying out loud; the standard leaves what
  a target permits to the application.
- **A member the operation does not define is refused**, where the standard is
  silent on unknown members. Both halves of Nook ship from one source tree, so an
  unrecognized field is a defect to surface, never a difference to absorb
  ([01](../../docs/01-interface-contracts.md)).

Scope: the structure operations' partial updates. The document layer's editing
operations are addressed by heading path and are not patches in this sense
([02](../../docs/02-document-layer.md)).

## Options considered

- **OPT1 — Keep the three states as a rule of Nook's own, unnamed** — the status
  quo, and it works; *ruled out:* it re-teaches a published rule in four specs, and
  a reader who knows merge patch cannot tell whether Nook's version matches it,
  which is the specific cost of an unnamed mechanism.
- **OPT2 — RFC 6902, JSON Patch** — an operation list (`add`, `remove`, `replace`,
  `test`) with genuinely more power, including a conditional `test` that would give
  optimistic concurrency for free; *ruled out:* it is a document-editing format
  aimed at arbitrary structures, where every update here changes a handful of named
  fields on one entity — a caller would build an operation array to set a status,
  and the write path would have to interpret paths it never wants to see.
- **OPT3 — Two fields per clearable field, a value and a "clear this" flag** — an
  arrangement that needs no three-state reading at all; *ruled out:* it doubles the
  surface of every update, makes two fields able to contradict each other, and is
  the thing merge patch exists to avoid.

## Consequences

- **gain** — the rule has a name, a specification and a test suite in the world; a
  caller who knows merge patch already knows how Nook's updates behave, and the
  four specs state a deviation rather than a mechanism; lands on: every caller and
  all four specs that describe partial updates.
- **gain** — "arrays are replaced whole" stops being a Nook rule about the blocker
  set and becomes the standard's rule, so it needs no separate justification;
  lands on: [04](../../docs/04-structure-semantics.md) and the write path's spec.
- **cost** — the two deviations are now load-bearing: a reader who assumes strict
  RFC 7396 will expect `{"name": null}` to clear a name, and every place the
  behavior is specified has to say it does not; lands on: the contract library's
  documentation and the specs.
- **cost** — naming the standard does not by itself simplify the hand-written
  conversions: they exist because the serialization library's generated conversion
  cannot express an absent member, and that stays true; lands on: the contract
  library, which keeps the two hand-written serializers.

## Revisit when

- **An operation needs a conditional update** — reconsider: RFC 6902's `test`
  operation is the standard answer, and OPT2's strengths grow if more than one
  operation wants it.
- **Updates need to reach nested structures** rather than a flat set of fields on
  one entity — reconsider: merge patch's inability to address inside an array
  becomes a real limit at that point.
