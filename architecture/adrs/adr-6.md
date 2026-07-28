# Documents are addressed by heading path and edited by exact string — ADR-6

## Context

Nook's document access is editor-grade: an agent or a person reads and edits one
section rather than fetching and rewriting a whole file
([02 — Document layer](../../docs/02-document-layer.md),
[ARCHITECTURE §4.2](../../ARCHITECTURE.md)). That needs two things settled — how a
caller says *where*, and how a caller says *what changes* — and both have
established answers that Nook does not use.

For addressing, every markdown renderer already derives an anchor from a heading:
GitHub lowercases it, hyphenates it, and suffixes repeats (`rollback`,
`rollback-1`). For editing, unified diff (POSIX `diff -u`) is the standard way to
express a change to text, understood by every version-control tool and every
patch program.

Both were rejected when the document layer was designed, and neither rejection was
written down — so the design reads as though nobody looked, which the project's
tenets now forbid. This record states the reasoning that was applied, so the
choice can be checked rather than re-argued.

The drivers: an address must survive edits to unrelated parts of the document,
because agents read and write across long sessions; an edit must either apply
exactly or fail loudly, never apply to the wrong place; and both must be things a
language model emits reliably, since that is who calls these operations most.

## Decision

We will address a location by **heading path** — the sequence of heading texts from
the document root to the target, joined by `/`, with a 1-based `#n` suffix
disambiguating repeated siblings (`Approach/Rollback#2`) — and express a fine edit
as **exact-string find and replace**: `apply_patch(docRef, {old, new, section?})`
replaces the one occurrence of `old`, failing when `old` is absent or not unique
within its scope. No line numbers and no offsets, anywhere.

Both rejections are now recorded in the document layer's spec alongside the rules
they explain.

Scope: the document layer's addressing and editing. It says nothing about the
structure operations, whose partial updates are JSON Merge Patch
([ADR-3](./adr-3.md)).

## Options considered

- **OPT1 — GitHub-style anchor slugs for addressing** — the convention every
  markdown tool produces, so an address could be copied out of a rendered page;
  *ruled out:* the slug is a transformation of the heading, not the heading, so the
  address stops being text a caller can read in the document and match by eye, and
  its repeat suffix is 0-based on the *second* occurrence — a well-known
  off-by-one that a caller counting headings gets wrong.
- **OPT2 — Unified diff for editing** — the standard, with tooling everywhere and a
  format models emit fluently; *ruled out:* it addresses by line number plus
  surrounding context, which is exactly the addressing this layer refuses, and a
  hunk whose context has shifted either fails or — worse — applies at a similar
  place elsewhere. The failure mode is the problem, not the format.
- **OPT3 — RFC 6902 JSON Patch, or RFC 7396 merge patch, over the document** —
  standards Nook uses elsewhere; *ruled out:* they patch JSON structures, and a
  document body is markdown text. Applying them would mean modelling the document
  as a tree first, which is a much larger design and buys nothing the heading path
  does not.
- **OPT4 — `replace_range` with line or character offsets** — precise, trivial to
  implement, and unambiguous; *ruled out:* it was considered and dropped for the
  reason all offset addressing is dropped here — every unrelated edit invalidates
  every stored address, so an agent holding one across a session holds a stale
  pointer it cannot detect.

## Consequences

- **gain** — an address survives edits elsewhere in the document, so an agent can
  read an outline, plan several edits, and apply them without re-reading between
  each; lands on: every agent session, and the skills that drive them.
- **gain** — a wrong edit is a loud failure rather than a silent misapplication:
  `old` missing or ambiguous refuses the call and writes nothing; lands on: every
  caller, and the write path.
- **cost** — renaming a heading breaks every address held against it, and nothing
  detects that: the next call simply fails to find the section. There is no
  redirect and no history of heading names; lands on: agents holding addresses
  across a rename, and on whoever writes the rename operation.
- **cost** — Nook's addressing is its own, so no external markdown tool can produce
  or consume it, and a caller cannot lift an anchor out of a rendered page; lands
  on: the web interface, which must derive addresses itself.
- **cost** — exact-string replacement carries no context, so a caller must send
  enough of `old` to be unique, and a large edit means sending a large string
  twice; lands on: whoever calls the editing operations, agents included.

## Revisit when

- **Documents are edited by tools rather than by agents and the interface** —
  reconsider: OPT2's ecosystem becomes worth its failure modes once patches arrive
  from `git` or an editor rather than from a model.
- **Heading renames become common** — reconsider: the missing redirect turns from a
  theoretical cost into a daily one, and stable per-section identifiers become
  worth their own design.
