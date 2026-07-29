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

Documents produced while building it:

- [discovery.md](./discovery.md) — where the run could live, and how the programs and
  the database are handled.
- [plan.md](./plan.md) — the eight steps, their verifications, and the divergences
  folded back into it as they were found.

## Results

**What runs.** A fifth module, `:system-test`, holds the assembled run: it applies
`nook.kotlin-jvm` alone and hangs off a target of its own, `./gradlew systemTest`,
which the continuous-integration run now asks for beside `check`. The three programs
are started from the distributions the build already produces — a folder of jars and
a start script each, which is what an operator runs — so each program holds only its
own dependencies, and the separation the run exists to observe is real rather than
asserted. Thirteen checks across seven files, about eighteen seconds all told against
a database the run owns and can take away. It passes on the laptop it was built
on and on the Linux the continuous-integration run uses, which is the first time
anything here has been shown to start on a second architecture.

**What it proves.** The milestone's loop has run over MCP against three real programs
and one real database, driven by the protocol library's own client, and returned
exactly the open leaves nothing is holding up. The same loop has run through the web
API with the MCP server not running at all. Every row either loop wrote names the
person its token was for and, over MCP, the agent its connection announced — read
back off the replies and then off the store with plain SQL, which is the other end
answering rather than the writer vouching for itself.

**The four debts it settles.**

- **Epic 06, spec-4's AC25** — the milestone loop over the MCP server alone:
  `LoopOverMcpTest`.
- **Epic 06's discovery, its open limitation** — that no tool call had ever reached
  the real write and read paths. It has now, over a real connection to a real core
  over a real store.
- **Epic 07, spec-5's AC19** — the same loop through the web API alone:
  `LoopOverWebApiTest`.
- **Epic 08, spec-6's AC17** — that loop run gated, with every row naming its person
  and its agent: both loop tests, read twice each.

**Each of spec-7's criteria, against the check that executes it.** Every check lives
in `system-test/src/systemTest/kotlin/io/nook/system/`.

| criterion | where |
| --- | --- |
| AC1 — both bring-up orders serve, nothing restarted | `BringUpTest` |
| AC2 — only the core connects to the store | `GateAndStoreTest` (the distributions read for a driver, and the store's own view of who is connected, with the core and without it) |
| AC3 — the milestone's loop over MCP | `LoopOverMcpTest` |
| AC4 — no tool makes a project, and asking for one is refused naming it | `LoopOverMcpTest` |
| AC5 — the same loop through the web API alone | `LoopOverWebApiTest` |
| AC6 — every row names its person and its agent | `LoopOverMcpTest`, off the replies and off the store |
| AC7 — a client naming nobody records no agent | **not re-run here.** Met where it stands, in epic 08's `ActingIdentityTest` and `ActorRecordedTest`; spec-7 records why |
| AC8 — two adapters, one name, a hundred times | `TwoAdaptersOneStoreTest` |
| AC9 — a caller that walks away mid-write | `TwoAdaptersOneStoreTest` |
| AC10 — the core stopped and started underneath | `GoneAndBackTest`, **through the web API alone.** The MCP half and the stopped-mid-call half wait for a leaf of their own; spec-7 records why |
| AC11 — the store stopped and started underneath | `GoneAndBackTest`, at both adapters |
| AC12 — a project deleted under a connected agent | **deferred to a leaf of its own.** The behavior is built and checked against a stand-in in epic 06; spec-7 records why |
| AC13 — a call presenting no token | `GateAndStoreTest` |
| AC14 — a program started without a setting | `BringUpTest`, each program through its own start script |

Two readings worth stating, because the words admit another. AC8's "neither call
waited on the other" is checked as *neither call gave up waiting* — structure writes
take their turn on a project by locking its row, by design, so two writes in one
project do serialize briefly and asserting they overlapped would contradict the
architecture. AC9's "the item is either there with both edges or not there at all" is
checked as *the change and both its edges landed together, or neither did*, which is
the state a create-with-edges can be half-left in; `create_item` takes no blockers, so
the write that makes them is `update_item`.

**What is deferred, with the reason.**

- **AC12, and the MCP half of AC10** — both are arrangements of their own rather than
  steps of anything here, and both already run against a stand-in. Leaves of their
  own.
- **ASM1 stays an assumption.** No real coding agent's client was pointed at the
  assembled system. The run drives the MCP server with the protocol library's own
  client, configured the way an agent's would be — the project's address, and the
  token on every request — which is a real MCP client driven by a check. Whether a
  coding agent's client can be told to hold a token in its configuration is still
  unshown, as epics 06 and 08 each recorded, and here it is a deliberate choice
  rather than a limit of the method.
- **A load probe against the assembled system** — the sixth epic to meet the question
  and the first with a whole system to point one at. Still nobody's number to want.
- **The assembled run does not hang off `check`.** Stated plainly because it is a
  cost: a change that breaks the assembled system passes an ordinary local build, and
  the continuous-integration run is the whole of the guard.
