# Epic 05 — Operation catalog

**Addresses:** REQ5 (the internal RPC API: the operation catalog exposed
once by the core service for both adapters).

Documents, in the order they were produced:

- [spec-3.md](./spec-3.md) — the behavior contract for the connection between
  the core service and its two adapters: the eleven operations it carries,
  what must survive the crossing unchanged, and how a caller tells an answer
  from a refusal from a breakdown.
- [discovery.md](./discovery.md) — seven probe groups against the real services,
  a real server, and a real client, settling how the connection gets built: why
  the catalog goes behind one address with the operation named inside, why the
  three states of a partial update have to be written by hand rather than
  generated, that the waiting and no-resend rules hold as the client comes, that
  a caller giving up mid-write leaves the write alone, and that the loopback
  binding is the whole of the protection.
- [plan.md](./plan.md) — the build route, steps ticked as execution proceeds.
