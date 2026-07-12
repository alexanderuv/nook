# 04 — Structure semantics

**Status:** Outline · **Milestone:** 1 (foundation)

The rules governing the structure store beyond what the schema already enforces:
status transitions, slugs, queries, and dependency integrity. The schema and status
*vocabulary* are settled (ARCHITECTURE.md §6, db/); the *behavior* is open.

## Decided

- Hierarchy: instance → project → (optional release) → epic → task; tasks have a
  `blocked_by` join edge. (§2.1, §8)
- Status vocabulary — epic `draft/in_progress/done/cancelled`; task
  `todo/in_progress/done/cancelled`; release `planned/in_progress/released/cancelled`.
  "Blocked" is derived, not stored. (§6)
- Readiness = `todo` and every blocker resolved (`done` or `cancelled`), via the
  `ready_task` view. (§6, db)
- Identity = UUID; slug is per-parent unique and used in paths/URLs. (§4.3, §7)

## Open decisions

- [ ] **Status transition rules** — the legal-transition state machine for each of
      epic / task / release. Can `done` reopen to `todo`? Is `cancelled` terminal?
      Are transitions validated, and where (core write path)?
- [ ] **Side effects of transitions** — e.g. does completing an epic require its
      tasks be terminal? Does cancelling an epic cascade to tasks?
- [ ] **Slug rules** — generation (from name?), allowed charset, collision handling,
      and the exact rename flow (DB update + `git mv`, atomicity).
- [ ] **Query/filter model** — the filter grammar for `list_tasks` / `list_epics`
      (status, release, blocked/ready, text search?), sort order, defaults.
- [ ] **`blocked_by` integrity** — cycle prevention (app-level), self-block already
      barred by schema; cross-project blocks allowed or not?
- [ ] **Release semantics** — can an epic move between releases freely? What does
      `released` status imply for its epics?
- [ ] **Deletion semantics** — is deletion allowed, or only cancellation? Cascades
      (FKs cascade in schema — confirm that's the intended product behavior).

## Depends on / feeds

- These rules are enforced in `:core`'s write path and surfaced through **01**.
