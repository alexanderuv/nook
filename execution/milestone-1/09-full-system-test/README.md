# Epic 09 — Full system test

**Addresses:** the goal of [PRD-1](../prd-1.md) that no single epic can meet on its
own — GOAL1, the north-star loop run over the MCP server alone against a real
database. It needs every piece of the milestone running at the same time: the store,
the core, the MCP server and the web app.

Why it exists as an epic of its own. Each adapter epic proves itself against a
stand-in core, so that its own tests need no database and start no other program.
That is the right bargain for building an adapter — but it leaves the assembled
system unproven, and nothing before this epic starts the whole of it. This epic owns
that: the one place where the real programs run together, and the one target where a
test may take a database.

Documents, in the order they were produced:

- [spec-7.md](./spec-7.md) — the requirements contract. Its load-bearing decisions:
  the three programs run as three programs, each started from its own entry point
  with its settings from outside, so that startup is exercised as an operator meets
  it; the loop starts with a project created through the web API, because no tool
  makes one; and **comparing what the two adapters answer is dropped**, which
  narrows PRD-1's second goal and strikes
  [spec-4](../06-mcp-server/spec-4.md) AC26 and
  [spec-5](../07-web-api/spec-5.md) AC20. Both adapters reach the store through the
  one core, so neither can reach a different verdict about a rule, and they are not
  meant to answer alike in any case — one answers a coding agent in the protocol's
  tool-result shape, the other answers a program in the core's own shape. What
  remains is what only assembly can show.

What it owes, carried forward from the epics that deferred it:

- **From [epic 06](../06-mcp-server/), spec-4's AC25** — the milestone loop over the
  MCP server alone: a release, an epic, two tasks under it, a project-level bug, the
  epic put in the release, the second task made to wait on the first, and then
  exactly the open leaves nothing is holding up coming back from one listing call.
- **From epic 06's [discovery](../06-mcp-server/discovery.md), one open limitation.**
  What the tools reached there was a stand-in, not the core, so nothing yet shows a
  tool call reaching the real write and read paths and coming back. This epic is what
  closes it.
- **From [epic 07](../07-web-api/), spec-5's AC19** — the same loop carried out
  through the web API alone, which is also the only program a project can be created
  through.
- **From [epic 08](../08-actor-plumbing/), spec-6's AC17** — that same loop run
  gated, with every row it writes naming the person its token was for and the agent
  its connection announced.

Beyond those debts, spec-7 adds what three real programs over one database make
observable for the first time: two callers writing the same name at once, decided by
the database's own unique constraint; a caller that walks away mid-write, decided by
a real transaction; and the core stopped and started again underneath both adapters.

The epic starts now that epics 07 and 08 have landed and there is a whole system to
start.
