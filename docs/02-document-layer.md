# 02 — Document layer

**Status:** Settled · **Milestone:** 2

The editor-grade document API (addressing, edit operations, concurrency, reads), the
artifact repo's on-disk layout, and the attachment policy. The *contents* of the
generated documents — what sections a manifesto or plan has — are a development-time
concern, deferred to build in [07 — Document templates](./07-document-templates.md). The
storage/consistency substrate comes from ARCHITECTURE.md §4.2 and
[05](./05-project-and-ops.md); this spec settles the document mechanics.

## Decided

### Storage & ownership (from §3.1, §4.2, §6)

- Document **content lives in git**; the DB stores only a `(path, current_version)`
  pointer. History is read from git, never duplicated.
- Access is **granular and editor-grade** — section reads and edits, not
  read-whole/write-whole. Granularity is a wire concern; git stores whole-file
  snapshots per version.
- Every document is **project-scoped** (`project_id` always set) and **optionally
  attached to one project item** (`item_id`); kinds are `manifesto`, `plan`, `rfc`,
  `design_doc`, `attachment`, `tenet`, `prd`, `adr`, `discovery`, `test_plan`,
  `retro`, `spec`, `architecture`. Which kinds are templated, and the catalog principle behind the list, is
  [07](./07-document-templates.md). **ADRs are project-level by rule**: kind `adr`
  requires `item_id` NULL — a project keeps **one decision stream** (ADR-1,
  ADR-2, …), never a log per epic. A decision constrains the whole project, and
  supersession must work across epics; prior art is unanimous (Nygard, adr-tools,
  MADR all keep a single sequential decision log per repo). **The architecture
  overview is a project-level singleton**: kind `architecture` requires `item_id`
  NULL and **at most one per project** — the project's living map of how the
  system hangs together, fixed-named `/architecture.md` beside `tenets.md`,
  updated in place as reality changes (the ADR stream keeps the why-history).
  **Plans are item-attached by rule**, the mirror constraint: kind `plan` requires
  `item_id` set — a plan is the route for building one item (typically a leaf)
  and has no meaning floating at project level. All three rules are enforced in
  the write path, like every per-kind rule. The schema enforces *structure* (a valid kind,
  and — via a composite FK — that an attached document's item is in the same project),
  not *counts*: it does not cap how many manifestos an epic or plans a leaf may have. A
  **project's tenets** are `tenet` documents with **no item** (project-level) —
  Nook-canonical, versioned markdown ([03](./03-skills-and-tenets.md)); **skills are
  not documents** (they are system-level, distributed to the agent's environment, not
  stored here).
- **Name uniqueness** is enforced by the single global `UNIQUE` on a document's
  **path**. A path is the entity-scoped name (`/epics/<slug>/attachments/notes.md`), so the same
  name may repeat freely across different entities in a project; only a same-name
  clash **within the same entity** (a path collision) is rejected.

### Per-project sequence numbers

- Every document of a **numbered kind** (`prd`, `rfc`, `adr`, `spec`,
  `design_doc`, `test_plan`, `retro`) carries a **sequence number**, unique per
  **(project, kind)**: the third RFC anywhere in a project is `RFC-3`,
  regardless of which item it attaches to. The fixed-name docs (`manifesto`,
  `plan`, `architecture`), `discovery` (an investigation belongs to the item
  whose uncertainty it reduced, like a plan — cited by its item, never as a
  project-wide series), and the non-catalog kinds (`attachment`, `tenet`) are
  unnumbered — cited by their item, or (for the project singletons) as "the
  architecture", "the tenets". Numbered kinds are a subset of the `docs/`-area
  kinds (the path area below): `discovery` lives in the `docs/` area too, just
  without a number.
- The number is a **citation handle**, nothing more: a stable, short way to
  reference a document from other documents, commit messages, and conversation
  ("per RFC-3", "supersedes ADR-2"). It carries no ordering or workflow meaning.
- **Allocated by the core service** (single writer) when the document row is
  created, from a per-(project, kind) counter (`document_sequence`) that only
  increases: numbers are **never reused** — deleting RFC-3 retires the number, and
  the next RFC is RFC-4. Same retire-don't-renumber rule the templates apply to
  in-document item IDs ([07](./07-document-templates.md)).
- Stored on the document row (`document.seq`, NULL for unnumbered kinds) and
  returned on the full document entity ([01](./01-interface-contracts.md)'s
  convention). Uniqueness is a **write-path guarantee**, not a DB constraint — the
  schema's portable-SQL policy (engines disagree on multi-NULL UNIQUE semantics)
  and the single-writer rule make the allocator the right owner.
- The number appears in the **document title** (`# {Title} — RFC-3`; templates
  carry a `{seq}` placeholder the skills stamp at instantiation — call choreography
  is development-time) **and in the filename**: a numbered kind is stored as
  `<kind>-<seq>.md` (`rfc-3.md`; the layout below), so the citation handle and
  the on-disk name always agree. The DB value stays authoritative, the title is
  display. No zero padding: `RFC-3`, never `RFC-003`.

### Addressing — heading paths, no line numbers

Addressing and fine editing are both settled in
[ADR-6](../architecture/adrs/adr-6.md), which records the standards rejected and
why.

- A location in a document is a **heading path**: the sequence of heading texts from
  the document root to the target section, joined by `/` — e.g.
  `Implementation Approach` or `Implementation Approach/Rollback`.
- **Duplicate siblings** (same heading text under the same parent) are disambiguated
  by a 1-based **ordinal suffix**: `Rollback#2` is the second `Rollback` among its
  siblings. Unique headings need no suffix. The convention every markdown renderer
  uses for the same problem — GitHub's anchor slugs, where a repeated heading
  becomes `rollback-1`, `rollback-2` — was rejected deliberately: it lowercases and
  hyphenates the heading text, so the address stops being the text a caller can
  read in the document, and its suffix is 0-based on the *second* occurrence, which
  is a well-known source of off-by-one mistakes.
- Heading paths are the only addressing scheme — **never line numbers or offsets**.
  Paths survive reordering and edits to unrelated sections (stable anchors, §4.2).

### A section is a block

- A **section** is a heading plus everything beneath it, down to — but not including
  — the next heading of **equal-or-higher level**. Subsections are part of their
  parent section.
- So an operation on `Approach` covers `Approach` and every subsection under it until
  the next same-or-higher heading.

### Edit operations

All are addressed by heading path, go through the core service's single write path,
and each produces a new forward-only document version (§3.2).

A **`docRef`** is the document's **path or UUID** (mirroring item refs,
[01](./01-interface-contracts.md)). The path carries the *scope*: where it sits
fixes the item attachment (`/epics/<slug>/…` → that epic, `…/tasks/<slug>/…` →
that leaf, root paths → project-level) — so operations on an existing document
need no separate itemRef argument. (Creation is the reverse: no document exists
yet to point at, so it takes the scope directly — below.)
**Kind is set at creation and immutable** (a re-kinded document is a new
document). **Every path implies it**: filenames are kind-named (`manifesto.md`,
`plan.md`, `tenets.md`, `architecture.md`, `discovery.md`; `rfc-3.md` for
numbered kinds; anything under `attachments/` is `attachment`), so on an
existing document the kind is read from the path, and a `kind` argument is
only ever validated for agreement. **Creation takes no path**: document paths
are derived, never chosen — the caller can't know a numbered filename before
the number is allocated, and every other filename is fixed by its kind — so a
document is created by **scope + kind** (an item ref, or project level), and
the write path derives the path (`plan.md` at the item root,
`docs/discovery.md`, `docs/rfc-<seq>.md` with the freshly allocated number)
and returns it. The one exception is `attachment`, freeform by design: its
creation supplies the filename under `attachments/`. The creating write
validates the per-kind level rules and returns the **full document entity**
(including `seq` and the derived `path` — how skills stamp `{seq}` into the
title rides this response). `title` is maintained by the write path from the
document's H1.

- `read_doc(docRef, section?)` — raw markdown: the whole document, or the block at
  `section`.
- `doc_outline(docRef)` — the heading tree (path, level, ordinal) for navigation,
  without bodies.
- `write_doc(target, content)` — create or replace the entire document. Replace
  addresses an existing document by `docRef`; create addresses **scope + kind**
  (an item ref or project level; plus a filename only for attachments) — paths
  are derived, never chosen (creation rules above). For initial authoring and
  import.
- `replace_section(docRef, section, content)` — replace the block at `section`.
- `prepend_to_section(docRef, section, content)` — insert at the **start** of the
  section's body (after the heading, before existing content and subsections).
- `append_to_section(docRef, section, content)` — insert at the **end** of the
  section's body (after existing content and subsections).
- `apply_patch(docRef, {old, new, section?})` — **structured find/replace**: replace
  the occurrence of `old` with `new`, optionally constrained to a section's block. If
  `old` is missing or not unique within scope, the op fails (`validation_failed`) —
  no silent or partial match.
- `doc_history(docRef)` — the version list (commit id, timestamp, actor, message),
  newest-first.

`replace_range` was considered and dropped: section ops plus `apply_patch` cover fine
edits without reintroducing line/offset addressing.

> **On `apply_patch` not being a patch format.** Unified diff (POSIX `diff -u`) is
> the standard way to express a text edit, and it was rejected rather than
> overlooked: it addresses by line number and context, which is precisely the
> addressing this layer refuses, and a hunk whose context has shifted either
> misapplies or fails in ways a caller cannot repair. Exact-string find/replace,
> failing loudly when `old` is missing or not unique, is the same guarantee
> without line addressing — and is what every code-editing agent already emits.
> RFC 6902 and RFC 7396 are JSON patch formats and do not apply to markdown
> bodies ([ADR-3](../architecture/adrs/adr-3.md) adopts the latter for structure
> updates).

### Concurrency — optional optimistic check

This is HTTP's conditional-request mechanism (RFC 9110 §13) carried on an RPC
wire ([ADR-5](../architecture/adrs/adr-5.md)): `expectedVersion` is an entity
tag, and supplying it is `If-Match`. The semantics are taken from there rather
than invented, including that a failed precondition writes nothing.

- Every mutating op accepts an optional **`expectedVersion`**. If provided and it does
  not equal the document's current version, the op fails and nothing is written —
  lost-update protection. The failure is a **precondition failure**, distinct from
  a slug collision, which is the other thing `conflict` used to mean; it carries
  `data.reason` of `precondition_failed`.
- Omitted `expectedVersion` = last-writer-wins, fine for single-user v1. The field
  lets a careful caller (or the UI) opt into a check.
- HTTP's own headers are not used, because the operation is not addressed by URL
  ([ADR-2](../architecture/adrs/adr-2.md)); what is adopted is the semantics and
  the vocabulary, so a caller who knows `If-Match` knows this.

### On-disk layout (artifact repo)

Each project is its own repo ([05](./05-project-and-ops.md)), so paths are relative
to that repo's root — no project segment:

```
/README.md                                     self-describing scaffold
/tenets.md                                     project tenets (kind `tenet`; spec 03)
/architecture.md                               architecture overview (kind
                                               `architecture`; per-project singleton)
/docs/adr-<seq>.md                             project decision stream (ADRs — the
                                               only v1 project-level docs area)
/epics/<epic-slug>/manifesto.md                epic guiding doc
/epics/<epic-slug>/docs/<kind>[-<seq>].md      other catalog kinds (prd-1.md,
                                               rfc-3.md, discovery.md, …)
/epics/<epic-slug>/attachments/<name>.md       epic-level freeform attachments
/epics/<epic-slug>/tasks/<leaf-slug>/plan.md   leaf plan (leaf under an epic)
/epics/<epic-slug>/tasks/<leaf-slug>/docs/<kind>[-<seq>].md
/epics/<epic-slug>/tasks/<leaf-slug>/attachments/<name>.md
/tasks/<leaf-slug>/plan.md                     plan for a project-level leaf
/tasks/<leaf-slug>/docs/<kind>[-<seq>].md
/tasks/<leaf-slug>/attachments/<name>.md
```

- **Kind-named files.** Every catalog document is named after its kind, exactly
  like the pre-existing fixed-name docs (`manifesto.md`, `plan.md`): an
  unnumbered kind is `<kind>.md` (`discovery.md` — at most one per item; a
  second is a path collision), a numbered kind is `<kind>-<seq>.md` (`rfc-3.md`,
  `adr-2.md` — the series never collides). What a file *is* reads straight off
  the tree, and the DB `kind` always agrees with the name. `attachments/` stays
  freeform — its files carry any name — reserved for scratch material and,
  later, binary media (images, video). (Template *assets* — the shipped
  skeletons — are not in this repo at all; they are Nook system assets,
  [07]/[03].)
- Path segments use **slugs**; rename = `git mv` through the write path (§4.3,
  [04](./04-structure-semantics.md)).
- **Leaves** (task, bug, or chore) of any type live under `tasks/`: a project-level
  leaf sits at the repo root under `/tasks/`, a leaf under an epic beneath that epic. A
  leaf's home is fixed by whether it has an epic parent, not by its type.
- `tenets.md` is the project's **tenet document** (kind `tenet`), an `item_id`-less
  project-level document — Nook-canonical and versioned in this repo. Skills do **not**
  live here: they are system-level and distributed to the agent's environment, not
  artifact-repo documents. How tenets are distributed to agents and kept current is
  settled in [03](./03-skills-and-tenets.md).
- **Project-level docs** beyond tenets, the architecture overview, and ADRs (a
  charter, cross-cutting RFCs) are **out of scope for v1** — every other v1
  document is item-attached. ADRs materialize the root `/docs/` area (the
  project's decision stream, above); other project-level kinds can join it later
  without disturbing this tree.

### Attachments — markdown only in v1

- Attachments are **freeform markdown/text** documents, first-class under the doc
  API (addressable, editable, versioned) — notes, scratch material, anything
  without a catalog role. (Catalog kinds — RFCs, design docs, … — live under the
  item's `docs/` area, above.)
- **Binary attachments (images, PDFs) are deferred.** Images are anticipated later
  (UI sketches, bug evidence) and will need a store-whole / serve-as-is path outside
  the editor-grade API.

### Templates — split out

- *Which* artifact types exist (the catalog: manifesto, PRD, RFC, design doc, plan)
  is settled in [07 — Document templates](./07-document-templates.md); what sections
  each contains is development-time product content, deferred to build there. This
  spec fixes only *where* documents live (the tree above) and *how* they are edited.

## Deferred (not open — intentionally later)

- Binary/image attachments (store-whole + serve).
- Further project-level kinds in the root `/docs/` area (charter, cross-cutting
  RFCs) — the area itself now exists, carrying the ADR stream.
- Rich diff/merge across versions beyond `doc_history`.

## Depends on / feeds

- Stores documents in the repo provisioned by [05](./05-project-and-ops.md); realizes
  the editor-grade access principle of ARCHITECTURE.md §4.2.
- Completes the document operations named in [01](./01-interface-contracts.md).
- Template contents are deferred to development ([07](./07-document-templates.md)) and
  consumed by the skills in [03](./03-skills-and-tenets.md).
