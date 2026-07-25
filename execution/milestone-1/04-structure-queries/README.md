# Epic 04 — Structure queries

**Addresses:** REQ4 (`list_items` filters by type/status/parent/release,
`get_ready_items` from the `ready_item` view, newest-first default).

Documents, in the order they were produced:

- [spec-2.md](./spec-2.md) — the behavior contract for the five reads
  (`get_project`, `list_projects`, `get_item`, `list_items`,
  `get_ready_items`): requirements, edge cases, and acceptance criteria.
- [discovery.md](./discovery.md) — ten probes against real PostgreSQL settling
  how the reads get built: what it takes for a deleted row to give up its
  handle, why the readiness view must be rebuilt, how the listing filter
  composes, and why every read wants a read-only transaction that reads one
  moment. It also settles that the deleted mark and the deletion the spec
  assumes are built here, in this epic. Its recommendation still speaks of a
  restore action alongside delete; that was dropped afterwards — deletion is
  final to every caller, and bringing a row back is deferred with physical
  removal.
- [plan.md](./plan.md) — the build route, steps ticked as execution proceeds.
  No spec pins deletion, so the plan carries its rules itself, taken from the
  structure-semantics document.
