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
  `design_doc`, `attachment`, `tenet`, `prd`, `adr`, `research`, `test_plan`,
  `retro`, `spec`. Which kinds are templated, and the catalog principle behind the list, is
  [07](./07-document-templates.md). The schema enforces *structure* (a valid kind,
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

### Addressing — heading paths, no line numbers

- A location in a document is a **heading path**: the sequence of heading texts from
  the document root to the target section, joined by `/` — e.g.
  `Implementation Approach` or `Implementation Approach/Rollback`.
- **Duplicate siblings** (same heading text under the same parent) are disambiguated
  by a 1-based **ordinal suffix**: `Rollback#2` is the second `Rollback` among its
  siblings. Unique headings need no suffix.
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

- `read_doc(docRef, section?)` — raw markdown: the whole document, or the block at
  `section`.
- `doc_outline(docRef)` — the heading tree (path, level, ordinal) for navigation,
  without bodies.
- `write_doc(docRef, content)` — create or replace the entire document. For initial
  authoring and import.
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

### Concurrency — optional optimistic check

- Every mutating op accepts an optional **`expectedVersion`**. If provided and it does
  not equal the document's current version, the op fails with `conflict` and nothing
  is written — lost-update protection.
- Omitted `expectedVersion` = last-writer-wins, fine for single-user v1. The field
  lets a careful caller (or the UI) opt into a check.

### On-disk layout (artifact repo)

Each project is its own repo ([05](./05-project-and-ops.md)), so paths are relative
to that repo's root — no project segment:

```
/README.md                                     self-describing scaffold
/tenets.md                                     project tenets (kind `tenet`; spec 03)
/epics/<epic-slug>/manifesto.md                epic guiding doc
/epics/<epic-slug>/docs/<name>.md              other catalog kinds (PRD, RFC, ADR, …)
/epics/<epic-slug>/attachments/<name>.md       epic-level freeform attachments
/epics/<epic-slug>/tasks/<leaf-slug>/plan.md   leaf plan (leaf under an epic)
/epics/<epic-slug>/tasks/<leaf-slug>/docs/<name>.md
/epics/<epic-slug>/tasks/<leaf-slug>/attachments/<name>.md
/tasks/<leaf-slug>/plan.md                     plan for a project-level leaf
/tasks/<leaf-slug>/docs/<name>.md
/tasks/<leaf-slug>/attachments/<name>.md
```

- **No per-kind paths.** Beyond the pre-existing fixed-name docs (`manifesto.md`,
  `plan.md`), the catalog kinds from [07](./07-document-templates.md) get no
  dedicated directories or filenames: they are slug-named markdown docs under the
  item's `docs/` area, distinguished by their DB `kind`, not their location.
  `attachments/` is reserved for freeform material — and, later, binary media
  (images, video). (Template *assets* — the shipped skeletons — are not in this
  repo at all; they are Nook system assets, [07]/[03].)
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
- **Project-level docs** beyond tenets (a charter, cross-cutting RFCs) are **out of
  scope for v1** — every v1 document other than tenets is item-attached. The model
  already allows them (an `item_id`-less document, exactly as tenets are), so a `/docs/`
  area can be added later without disturbing this tree.

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
- A project-level `/docs/` area for epic-independent documents.
- Rich diff/merge across versions beyond `doc_history`.

## Depends on / feeds

- Stores documents in the repo provisioned by [05](./05-project-and-ops.md); realizes
  the editor-grade access principle of ARCHITECTURE.md §4.2.
- Completes the document operations named in [01](./01-interface-contracts.md).
- Template contents are deferred to development ([07](./07-document-templates.md)) and
  consumed by the skills in [03](./03-skills-and-tenets.md).
