# 02 — Document layer

**Status:** Outline · **Milestone:** 2

The richest undesigned area. How documents are addressed, edited, laid out on disk,
and structured. The *principle* is settled (editor-grade, anchor-addressed —
ARCHITECTURE.md §4.2); the mechanics are open.

## Decided

- Document **content lives in git**; the DB stores only a `(path, current_version)`
  pointer. History is read from git, never duplicated. (§3.1, §6)
- Access is **granular and editor-grade** — section/range reads and edits, not
  read-whole/write-whole. Addressing is by **stable anchors (heading paths)**, not
  line numbers. Granularity is a wire concern; git stores whole-file snapshots. (§4.2)
- Documents attach to project/epic/task via an exclusive-arc owner; kinds are
  `manifesto`, `plan`, `rfc`, `design_doc`, `attachment`; one manifesto per epic,
  one plan per task. (§6, db schema)
- Git paths are slug-based and readable; rename = `git mv`. (§4.3)

## Open decisions

- [ ] **Heading-path addressing grammar** — how a section is named (e.g.
      `Implementation Approach` vs. a path `Approach/Rollback`), and how **duplicate
      or repeated headings** are disambiguated (ordinal? nearest-unique-ancestor?).
- [ ] **Edit operation semantics** — exact behavior of `replace_section`, `insert`
      (before/after which anchor), `append_to_section`, `apply_patch`,
      `replace_range`. What "range" means without line numbers.
- [ ] **Patch format** for `apply_patch` (unified diff? a structured op list?).
- [ ] **Concurrency/versioning at the edit level** — optimistic checks (edit against
      an expected version) to prevent lost updates.
- [ ] **Artifact-repo on-disk layout** — concrete paths for manifestos, plans,
      attachments, skill layers, and tenets within a project's repo.
- [ ] **Document templates** — the canonical section structure of a **manifesto**
      and of a **plan** (analysis / background / approach / caveats / test-plan):
      required vs. optional sections, headings that the edit API and skills rely on.
- [ ] **Rendering/read shapes** — what `read_doc` returns (raw markdown, or a
      parsed section tree the UI/agent can navigate).
- [ ] **Binary/large attachments** — are non-markdown attachments in scope, and how
      are they stored/served.

## Depends on / feeds

- Underlies the document tools in **01** and the skill outputs in **03**.
- Repo provisioning (where the git repo comes from) is **05**.
