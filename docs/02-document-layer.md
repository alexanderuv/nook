# 02 — Document layer

**Status:** Settled · **Milestone:** 2

The editor-grade document API (addressing, edit operations, concurrency, reads), the
artifact repo's on-disk layout, and the attachment policy. The *contents* of the
generated documents — what sections a manifesto or plan has — are split into their
own analysis: [07 — Document templates](./07-document-templates.md). The
storage/consistency substrate comes from ARCHITECTURE.md §4.2 and
[05](./05-project-and-ops.md); this spec settles the document mechanics.

## Decided

### Storage & ownership (from §3.1, §4.2, §6)

- Document **content lives in git**; the DB stores only a `(path, current_version)`
  pointer. History is read from git, never duplicated.
- Access is **granular and editor-grade** — section reads and edits, not
  read-whole/write-whole. Granularity is a wire concern; git stores whole-file
  snapshots per version.
- Documents attach to project/epic/task via an **exclusive-arc owner**; kinds are
  `manifesto`, `plan`, `rfc`, `design_doc`, `attachment`, `tenet`. The schema enforces
  *structure* (a document has exactly one owner and a valid kind), not *counts* —
  it does not cap how many manifestos an epic or plans a task may have. A **project's
  tenets** are `tenet` documents on the project arc — Nook-canonical, versioned
  markdown ([03](./03-skills-and-tenets.md)); **skills are not documents** (they are
  system-level, distributed to the agent's environment, not stored here).
- **Name uniqueness** is enforced by the single global `UNIQUE` on a document's
  **path**. A path is the entity-scoped name (`/epics/<slug>/notes.md`), so the same
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
/epics/<epic-slug>/attachments/<name>.md       epic-level attachments
/epics/<epic-slug>/tasks/<task-slug>/plan.md   task plan (task under an epic)
/epics/<epic-slug>/tasks/<task-slug>/attachments/<name>.md
/tasks/<task-slug>/plan.md                     plan for an epic-less task
/tasks/<task-slug>/attachments/<name>.md
```

- Path segments use **slugs**; rename = `git mv` through the write path (§4.3,
  [04](./04-structure-semantics.md)).
- **Epic-less tasks** (a bug not tied to an epic — [04](./04-structure-semantics.md))
  live at the repo root under `/tasks/`; tasks under an epic live beneath it. A task's
  home is fixed by whether it has an epic parent.
- `tenets.md` is the project's **tenet document** (kind `tenet`) — Nook-canonical and
  versioned in this repo. Skills do **not** live here: they are system-level and
  distributed to the agent's environment, not artifact-repo documents. How tenets are
  distributed to agents and kept current is settled in
  [03](./03-skills-and-tenets.md).
- **Project-level docs** not tied to an epic (a charter, cross-cutting RFCs) are
  **out of scope for v1** — everything is epic- or task-scoped. A `/docs/` area can be
  added later without disturbing this tree.

### Attachments — markdown only in v1

- Attachments are **markdown/text** documents, first-class under the doc API
  (addressable, editable, versioned) — e.g. RFCs and design docs.
- **Binary attachments (images, PDFs) are deferred.** Images are anticipated later
  (UI sketches, bug evidence) and will need a store-whole / serve-as-is path outside
  the editor-grade API.

### Templates — split out

- What sections a **manifesto** or **plan** contains is **not decided here**. It is
  product-content design and gets its own analysis:
  [07 — Document templates](./07-document-templates.md). This spec fixes only *where*
  those documents live (`manifesto.md`, `plan.md`) and *how* they are edited (above).

## Deferred (not open — intentionally later)

- Binary/image attachments (store-whole + serve).
- A project-level `/docs/` area for epic-independent documents.
- Rich diff/merge across versions beyond `doc_history`.

## Depends on / feeds

- Stores documents in the repo provisioned by [05](./05-project-and-ops.md); realizes
  the editor-grade access principle of ARCHITECTURE.md §4.2.
- Completes the document operations named in [01](./01-interface-contracts.md).
- Template contents are settled in [07](./07-document-templates.md) and consumed by
  the skills in [03](./03-skills-and-tenets.md).
