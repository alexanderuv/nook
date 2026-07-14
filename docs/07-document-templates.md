# 07 — Document templates

**Status:** Deferred (template *content* is a development-time concern) · **Milestone:** 3

The section structure of the generated documents — what headings a **manifesto** or a
**task plan** actually contains — is **not designed here**. That is implementation-time
product detail, on par with internal code structure: it's decided while building the
skills that author these documents, not pinned down during architecture planning. This
spec exists to record *that decision* and to point at where the genuinely architectural
parts of "templates" already live.

## Decided

- **Template content is deferred to development.** The concrete section sets (and
  whether a section is required, optional, or a skill may add ad-hoc ones) are chosen
  when the authoring skills are built, and can evolve without a design-spec change.
- **Source & override is already settled by [03](./03-skills-and-tenets.md).** Templates
  are Nook-shipped, system-level assets consumed by the skills, with append-only project
  overlays — distributed into the agent's environment and versioned by Nook, exactly as
  skills are. There is no separate template-distribution mechanism to design.
- **Regeneration rides [02](./02-document-layer.md)'s edit API.** Whole-document
  regeneration uses `write_doc`; a section-wise refresh uses the section ops; a
  propose-then-accept flow composes those with the UI (06). *Which* a given skill uses is
  a skill/dev choice, not an architecture decision — the API already supports all three.

## Depends on / feeds

- The mechanism lives in [02](./02-document-layer.md) (edit API) and
  [03](./03-skills-and-tenets.md) (shipped-base + overlay distribution).
- The skills in [03](./03-skills-and-tenets.md) fill these templates; their exact
  section sets are settled in development.
