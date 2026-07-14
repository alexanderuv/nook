# Nook — session guide

Agent-native project-management + artifact repository. **Pre-implementation**: this
repo is design docs and schema only; no application code exists yet.

## Where things are

- `README.md` — what Nook is, the core workflow, the stack.
- `ARCHITECTURE.md` — top-level architecture and the record of settled decisions.
- `docs/README.md` — spec index, milestones, and build order. Read this before any
  design work; it says which spec owns which area.
- `docs/0X-*.md` — one detailed spec per area. Each separates **Decided** from
  **Open**; a spec is not done until its Open list is empty.
- `db/changelog/` — Liquibase changelog for the structure store (Postgres). Enum
  code↔name maps are documented at the top of `0001-initial-schema.yaml`.

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
