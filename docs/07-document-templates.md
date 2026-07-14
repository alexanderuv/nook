# 07 — Document templates

**Status:** Outline · **Milestone:** 3 (feeds skills)

The section structure of the generated documents — the **manifesto** (per epic) and
the **task plan** (per task), plus any future templated document. Carved out of
[02](./02-document-layer.md) deliberately: 02 settles *where* these live and *how*
they're edited; the *content shape* is product design and deserves its own analysis
rather than a hand-wavy inline guess.

## Open decisions

- [ ] **Plan template** — the section set for a task plan. Starting point from the
      original workflow sketch (to be examined, not assumed): Summary, Analysis,
      Background, Implementation Approach, Caveats, Test Plan.
- [ ] **Manifesto template** — the section set for an epic's guiding doc. Candidate
      (likewise to be examined): Summary, Goals, Non-goals, Scope, Background, Open
      Questions.
- [ ] **Required vs optional sections**, and whether a skill may add ad-hoc sections
      beyond the template.
- [ ] **Regeneration semantics** — when a plan/manifesto is re-generated, is it a full
      rewrite, a section-wise refresh, or a proposed diff the author accepts? (Couples
      to the edit API in [02](./02-document-layer.md) and the skills in
      [03](./03-skills-and-tenets.md).)
- [ ] **Template source & override** — are templates shipped by Nook, overridable per
      project, and if so where do the overrides live? [03](./03-skills-and-tenets.md)
      now settles the pattern this should follow: base shipped by Nook as a
      system-level asset, project refinements as append-only layers, distributed to the
      agent and versioned by Nook (templates are consumed by the skills, so the same
      shipped-base + project-overlay model likely applies).

## Depends on / feeds

- Sits on the edit API and layout of [02](./02-document-layer.md).
- Consumed by the skills in [03](./03-skills-and-tenets.md), which fill these
  templates.
