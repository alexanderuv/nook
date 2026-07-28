# Nook — session guide

Agent-native project-management + artifact repository. **Milestone 1 in progress**:
the design is settled, the core service's structure layer is built — schema, the
nine write operations, the five reads, and their tests — and so are the
connection both adapters reach the core by, `:mcp-server`, which serves the
seven project-scoped operations as tools at one address per project, and
`:web-app`, which serves all eleven at `/api`. Both doors speak JSON-RPC 2.0
over one answering side held in `:contract`. The document layer and the
git-backed artifact store are still design only.

`/api` is the **web UI's** back end (the UI itself is milestone 4), not a public
integration surface: humans reach Nook through the UI, agents through MCP, and
nobody outside this repo is written against `/api`. Treat a read the UI needs as
an operation to add, not as a contract to break.

## Where things are

- `README.md` — what Nook is, the core workflow, the stack.
- `ARCHITECTURE.md` — top-level architecture and the record of settled decisions.
- `docs/README.md` — spec index, milestones, and build order. Read this before any
  design work; it says which spec owns which area.
- `docs/0X-*.md` — one detailed spec per area. Each separates **Decided** from
  **Open**; a spec is not done until its Open list is empty.
- `db/changelog/` — Liquibase changelog for the structure store (Postgres). Enum
  code↔name maps are documented at the top of `0001-initial-schema.yaml`.
- `execution/` — build planning: one folder per milestone, each anchored by its
  PRD (`prd-<seq>.md`, authored from `artifacts/templates/prd.md`) from which
  that milestone's epics are derived.
- `artifacts/tenets.md` — the shipped base tenet set: common conventions for any
  project (timeless code comments, right fix over quick fix, verify don't trust,
  …). Honor these in every session; project tenets layer on top.

## Conventions

- **Design-first.** Requirements get settled before code. When a decision lands,
  move it from Open to Decided in the owning spec, and update `ARCHITECTURE.md`
  only if it changes top-level direction.
- **Design scope excludes implementation detail.** Specs pin mechanisms, data
  ownership, and flows. Template section content and UI screen detail are
  development-time concerns (see `docs/07`).
- **Document kinds** are defined in `docs/02-document-layer.md` and stored as
  smallint codes (see the DB changelog).
- Enums throughout the schema are smallint codes, never strings.
