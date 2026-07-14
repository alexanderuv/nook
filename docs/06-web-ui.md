# 06 — Web UI

**Status:** Outline · **Milestone:** 4

The human surface. Deliberately the last and thinnest for v1 — the workflow is
proven through the API and MCP first (ARCHITECTURE.md §9, "shallow end-to-end").
Only the stack is settled; the actual UI is undesigned.

## Decided

- **React + TypeScript (strict)**, served by the web app. (§7)
- The UI reads structure from the DB (via the web app's API) and document content
  and history through the `ArtifactStore`. (§3.4, §7)
- Because documents are forward-only (no branch skew), the UI simply shows the
  latest version or any chosen prior version — no branch/merge state to represent.
  (§3.2)

## Open decisions

- [ ] **Screen inventory** — the v1 set. Candidates: project list, project overview,
      epic view, task board/list, document viewer/editor with version history,
      tenet editor, skill triggers.
- [ ] **Primary flows** — create epic → author manifesto → split into tasks →
      generate plan per task, mirrored for a human.
- [ ] **Document editing in the UI** — read-only viewer with history in v1, or an
      editor that writes through the same editor-grade API (**02**)?
- [ ] **Skill triggering from the UI** — sharper now that skills are **agent-side**,
      not Nook-served tools ([03](./03-skills-and-tenets.md)): the web app is a human
      surface, not an agent harness, so it can't "run" a skill directly. Options to
      resolve: the UI only drives the structure/document operations manually (no skill
      button), or the web app gains its own skill runner, or skills stay agent-only in
      v1. And how results surface either way.
- [ ] **Task board semantics** — grouping (by status/release), what "ready" looks
      like visually, dependency display.
- [ ] **Auth-less v1 UX** — single-user assumptions in the UI; where the nominal
      actor comes from.
- [ ] **Build & serve** — how the React app is built and served by the Ktor web app
      (static bundle vs. dev proxy).

## Depends on / feeds

- Consumes the HTTP API from **01** and the document layer from **02**.
- Should stay minimal until Milestones 1–3 prove the workflow.
