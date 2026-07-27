# Epic 05 — Operation catalog

**Addresses:** REQ5 (the internal RPC API: the operation catalog exposed
once by the core service for both adapters).

Documents, in the order they were produced:

- [spec-3.md](./spec-3.md) — the behavior contract for the connection between
  the core service and its two adapters: the fourteen operations it carries,
  what must survive the crossing unchanged, and how a caller tells an answer
  from a refusal from a breakdown.
