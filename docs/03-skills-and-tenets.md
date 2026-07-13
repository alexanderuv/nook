# 03 — Skills & tenets

**Status:** Outline · **Milestone:** 3

Where the product's value lives, and the least designed. The *layering mechanism* is
settled (ARCHITECTURE.md §2.3, §2.4, §9); the concrete skill specs, the composition
engine's contract, and the tenet format are open.

## Decided

- Skills are **layered, append-only**: resolved = shipped base + ordered project
  layers + active tenets; the base is never mutated. (§2.3, §9)
- Layers are markdown + frontmatter (`skill: <base-id>`, `order: <int>`); merge is
  deterministic, sectioned append. (§9) *(Where the layers physically live is
  reopened — see the layering-location open decision below.)*
- Skills are **agent-first**: the connected agent's model does the reasoning; Nook
  needs no model of its own in v1 (server-side inference is designed-for, deferred).
  (§2.3)
- Skills are invoked as **both MCP tools and prompts**. (§5)
- Tenets are **advisory** in v1 — the always-on layer, exposed via an MCP resource
  (`nook://project/tenets`), structured for later gating. (§2.4)

## Open decisions

- [ ] **Layering location & branching** — Nook ships a single **base** `tenets.md`
      (and base skills); the **project's own** tenet/skill layers are the project's,
      not Nook's. Open: do those layers live in Nook's artifact repo (versioned, not
      branched — our default rule) or in the **project's code repo** (branched with
      the code)? There is real value in these layers being **branched**, since they
      are engineering rules that legitimately vary per branch — a departure from
      versioned-not-branched that applies *only* to skills/tenets, not to
      manifestos/plans. If they live in the code repo, how does Nook read them, and on
      which ref? (Revises the "layers live in the artifact repo" line in Decided.)
- [ ] **The three core skills, specified** — for `split_epic`, `generate_task_plan`,
      `author_manifesto`: inputs, what each instructs the agent to do, and the exact
      **output structure** (which maps to the document templates in **02**).
- [ ] **Composition engine contract** — the precise resolution: how base + layers +
      tenets combine into one prompt, section ordering, and what the tool/prompt
      actually returns to the agent (a prompt string? a structured instruction?).
- [ ] **Skill invocation result** — does a skill *return instructions* the agent
      then executes, or does it also *write* the resulting document via the write
      path? Where does the boundary sit?
- [ ] **Tenet file format** — how a tenet is authored (freeform prose? a small
      structured form with an id + statement + optional machine-checkable rule to
      enable later gating).
- [ ] **Tenet scope** — project-level only, or also release/epic-level overrides?
      How conflicts resolve.
- [ ] **Base skill authoring** — where shipped base skills live in the codebase and
      their format; how projects discover which skills exist and their layer points.
- [ ] **Versioning of skills/tenets** — they live in git; how changes are surfaced
      and whether a plan records which skill version produced it.

## Depends on / feeds

- Consumes the document templates and write path from **02**.
- Exposed through the tool/prompt contracts in **01**.
