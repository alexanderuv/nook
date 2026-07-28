# Epic 09 — Full system test

**Addresses:** the two goals of [PRD-1](../prd-1.md) that no single epic can meet
on its own — GOAL1, the north-star loop run over the agent surface alone, and
GOAL2, one contract reaching the same verdict whichever door a call comes in by.
Both need every piece of the milestone running at the same time: the store, the
core, the agent surface, and the web surface.

Why it exists as an epic of its own. Each adapter epic proves itself against a
stand-in core, so that its own tests need no database and start no other program.
That is the right bargain for building an adapter — but it leaves the assembled
system unproven, and nothing before this epic starts the whole of it. This epic
owns that: the one place where the real programs run together, and the one target
where a test may take a database.

What it owes, carried forward from the epics that deferred it:

- **From [epic 06](../06-mcp-server/), spec-4's last two criteria.** The
  milestone loop over the agent surface alone — a release, an epic, two tasks
  under it, a project-level bug, the epic put in the release, the second task made
  to wait on the first, and then exactly the open leaves nothing is holding up
  coming back from one listing call. And every acceptance criterion of
  [spec-1](../03-core-write-path/spec-1.md) and
  [spec-2](../04-structure-queries/spec-2.md) that exercises the seven tool
  operations, driven through the matching tool rather than against the core
  directly, reaching the verdict it reaches in the core's own process.
- **From epic 06's [discovery](../06-mcp-server/discovery.md), one open
  limitation.** What the tools reached there was a stand-in, not the core, so
  nothing yet shows a tool call reaching the real write and read paths and coming
  back. This epic is what closes it.
- **From [epic 07](../07-web-api/), the same thing for the web surface** — its
  own equivalent run, so that the one-contract goal is observed across every door
  rather than only the two the core service's own tests already cover.

Documents are still to be written; the epic starts once epics 07 and 08 have
landed and there is a whole system to start.
