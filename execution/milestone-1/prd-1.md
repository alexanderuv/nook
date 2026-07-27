# Milestone 1 — Structure — PRD-1

## Overview

Milestone 1 builds Nook's structure layer, the first running slice of the
system: projects, releases, epics, and leaf items (tasks/bugs/chores) stored in
Postgres and manipulable over both program surfaces — MCP for the developer's
coding agents, the web API for everything else. It is the foundation milestone:
documents, workflow skills, and the UI (milestones 2–4) all attach to the
structure this one proves. It frames the build of what
[spec 04](../../docs/04-structure-semantics.md) and
[spec 01](../../docs/01-interface-contracts.md) settled, in the sequence set by
[docs/README.md](../../docs/README.md); its requirements are the input for the
milestone's epic breakdown.

## Problem

Developers running AI-driven projects have no adequate home for the structure
of their work: it needs to be shared across a team, survive across sessions and
time, and be queryable in the moment — and today it lives in markdown files
inside the code repository, maintained by one-off skills. This repo's own
`execution/` folder is the workaround in action. Prose isn't queryable ("what
is ready to work on?" means re-reading files), state branches with the code,
and nothing is shared between collaborators or sessions except by convention;
existing trackers model work for human teams and don't combine with the
agent-driven documents that now do the organizing. AI-driven development needs
tooling shaped for its workflow — Nook aims to be that tool. Why now: the
design is settled ([ARCHITECTURE](../../ARCHITECTURE.md), specs 01–08), and
every other milestone stacks on this layer.

## Users

- **Developer (primary)** — when running an AI-driven project, I want the
  work's structure in a durable, queryable, shared store, so I can steer the
  project across sessions, collaborators, and time.
- **Coding agent** (the developer's tool) — when working a session, I want to
  ask what's ready and record items and status changes over MCP, so my output
  lands where the team sees it without the developer re-briefing me.

## Goals & success metrics

- **GOAL1 (north star)** — the provable loop: over MCP alone, create a project,
  epics, leaves, a release, and blocker edges, then get exactly the open,
  unblocked leaves from one `list_items` call asking for the leaf types, status
  `todo`, and nothing blocking — the loop exercises all four item
  types and both parented and project-level leaves; observed in an end-to-end
  script run against local Postgres, passing 100% unattended.
- **GOAL2** — one contract, checked wherever it can drift: all 11 catalog
  operations (4 instance-level + 7 project-scoped, per spec 01) reach the same
  verdict — same entity, same refusal code, same change to the store — called
  inside the core, called across the internal connection, called over the web API,
  and called as an MCP tool; observed by running one contract suite against all
  four. MCP is the only one of the four that translates, so that is where this goal
  earns its keep; the web API forwards the core's own shape, so its run is the
  guard against it quietly starting to reshape.
- **GOAL3** — rule coverage: 100% of the Decided bullets in specs 04 and 01
  that name a structure behavior map to at least one named test; observed in
  the test suite.
- **GOAL4 (guardrail)** — adapters hold no store access: 0 database/persistence
  dependencies outside the core service module; observed in the build
  dependency graph.

## Requirements

- **REQ1 · P0** — Multi-module skeleton: core service, `:mcp-server`,
  `:web-app`, and a shared contract library carrying the DTOs. *Why:* GOAL4 —
  the thin-adapter topology is enforced by module boundaries.
- **REQ2 · P0** — Database bring-up: Liquibase applies the existing changelog
  to Postgres — in production and, via embedded PostgreSQL binaries, in tests
  (ADR-1); a startup/test check guards data-access definitions against drift
  from the changelog. *Why:* GOAL3 — tested rules need a real,
  migration-managed schema.
- **REQ3 · P0** — The single write path in the core service, enforcing spec
  04's semantics: containment by type, free status movement within the
  vocabulary, slug derivation/uniqueness/override, cancel-not-delete,
  cycle-rejecting `blocked_by` replacement, releases as loose buckets. *Why:*
  GOAL1, GOAL3.
- **REQ4 · P0** — Queries: `list_items` filtering by type, status, parent,
  release, and whether anything unfinished is holding an item up; newest-first
  default ordering. *Why:* GOAL1 — the readiness question is the loop's payoff,
  and it is these filters combined rather than an operation of its own.
- **REQ5 · P0** — The internal RPC API: the operation catalog exposed once by
  the core service for both adapters. *Why:* GOAL2, GOAL4 — one contract, reached
  the same way by both front doors.
- **REQ6 · P0** — The MCP server: streamable HTTP at `/mcp/{projectRef}`,
  project bound per connection, the catalog as tools, UUID-or-slug references,
  structured errors (`validation_failed` / `not_found` / `conflict` / `cycle`).
  *Why:* GOAL1 — the agent surface is the north-star path.
- **REQ7 · P0** — The web API: `:web-app` serves the core's own request and reply
  shape outward — one address, the operation and the project named inside the
  request, the reply naming its own ending — rather than a second shape of its own.
  *Why:* GOAL2 — one contract, with the UI written against the same shape the agent
  surface calls.
- **REQ8 · P1** — Actor plumbing: every mutation records `created_by` /
  `updated_by` as subject strings (local default `system`); projects carry a
  server-populated, read-only `owner_subject`. *Why:* GOAL3 — spec'd behavior,
  and the tenancy seam later milestones assume.

## Non-goals

- **Documents and the `ArtifactStore`** — content, versions, the editor-grade
  edit API, document references — deferred to milestone 2 (specs 02/05). This
  includes artifact-repo provisioning: `create_project` leaves
  `artifactRepoUrl` null, and rename updates the slug without the paired
  `git mv` (no documents exist to move).
- **Skills and tenets** — including the operate-Nook skill and the MCP
  resources that serve tenets and documents — milestone 3 (spec 03).
- **Web UI** — milestone 4 (spec 06); `:web-app` serves only the web API until
  then.
- **Authentication, stdio transport, pagination, free-text search, a status
  transition state machine, hard deletion** — deferred by the specs themselves
  (01/04); not this milestone's scope creep to reverse.

## Risks & assumptions

- **feasibility** — MCP server SDK: the Kotlin SDK proved pre-1.0 with live
  conformance bugs, so `:mcp-server` uses the GA Java MCP SDK (2.x) from
  Kotlin (epic 01 discovery); the residual risk is hosting its servlet-based
  transport alongside a Ktor backend; severity: low; build the MCP adapter as
  the first vertical slice so it fails early if it fails at all.
- **assumption** — the embedded-PostgreSQL test path (ADR-1) holds beyond the
  one developer machine it was proven on; severity: low; run the epic 02
  discovery's probe suite in Linux CI during bring-up, before the test suite
  grows on top of it.
- **value** — milestone 1 alone doesn't demonstrate Nook's differentiating
  value, which is agent-driven documents organizing and executing the work
  (milestones 2–3); severity: low; accepted by the build order and mitigated by
  keeping this milestone a minimal slice.
