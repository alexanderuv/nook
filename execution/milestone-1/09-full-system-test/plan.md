# Full system test — Plan

A note on references. This plan leans on the documents beside it.
[Spec-7](./spec-7.md) numbers what it pins — REQ for a requirement, EDGE for an
edge case, AC for an acceptance criterion, ASM for an assumption.
[The discovery](./discovery.md) numbers its findings FIND and its questions Q.
Where this plan cites one of those codes it says the point in plain words
alongside, so the pointer is a cross-check and never required reading. None of
those codes belongs in code or in a code comment.

Some words that recur. **The assembled system** is the core service, the MCP
server and the web app running as three separate programs against one
PostgreSQL database. **A distribution** is what the build already produces for
each program: a folder of jars and a start script, which is the thing an
operator runs. **The loop** is the sequence PRD-1 calls its north star — a
release, an epic, two leaves under it, a leaf directly under the project, the
epic put in the release, one leaf made to wait on another, and one listing call
asking for the open leaves nothing is holding up. **The embedded database** is
real PostgreSQL started from binaries fetched as ordinary dependencies, which
this project's tests already use. **A bearer token** is the standard way a
caller presents an already-issued identity over HTTP, carried as
`Authorization: Bearer <token>`.

## Analysis

### What is there now, read from the repository rather than remembered

- **No module in this build can host the run, and nothing here starts more than
  one program at a time.** `:mcp-server` and `:web-app` both apply
  `nook.persistence-boundary`, which walks every source set including test code,
  so an embedded database in either module's tests fails `check`. `:core-service`
  has no such guard but hosting the run there would put the two adapters on the
  core's test classpath, against the direction `ARCHITECTURE.md` §3.3 sets. A
  fifth module is the only place left, and it will be the second one — after
  `:core-service` — that the persistence boundary does not fence.

- **The build already produces the three distributions, and neither adapter's
  holds a database driver.** `installDist` gives `:core-service` 43 jars
  including Exposed, Liquibase and the driver; `:mcp-server` 45 and `:web-app`
  29, with no persistence jar in either. The generated start script names the
  whole classpath outright, so this is the list each program actually starts
  with rather than an inference from a dependency graph.

- **The launcher the three existing program tests share cannot be reused.**
  `CoreProgramTest`, `ToolProgramTest` and `WebProgramTest` each build
  `ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), PROGRAM)`.
  That is right where it stands, because in each case the program under test
  lives in the module testing it. In one module holding all three it would start
  the MCP server with Exposed, Liquibase and the driver on its classpath, and
  the separation this epic exists to observe would be erased before the first
  call (FIND3).

- **The start script picks its JVM from `JAVA_HOME`, or from whatever `java` the
  machine's `PATH` resolves.** The existing tests instead pick the exact JVM
  running them, through `System.getProperty("java.home")`. Under the
  distributions, `JAVA_HOME` has to be written into each program's environment
  deliberately or the program runs on whatever JVM the machine has rather than
  the JDK 25 the build pins (FIND4).

- **The embedded database is started in one place, and that place is reachable
  from nowhere else.** `EmbeddedPostgresSupport`, in
  `core-service/src/test/kotlin/io/nook/core/db/`, starts one server for the
  whole test JVM at a port the library chooses, and hands out a freshly migrated
  database per caller; twenty-two test files use it. The changelog is applied by
  `migrateDatabase` in `core-service/src/main/kotlin/io/nook/core/db/Migrations.kt`,
  against the copy of `db/changelog` that `processResources` packages into the
  module. `:contract` already shows the shape for sharing test-only code: it
  applies `java-test-fixtures` and ships the stand-in core and the minted tokens
  from there.

- **Taking a database away and bringing it back needs no feature of the
  library.** Its constructor creates a new database only when told to clean the
  data directory or when the directory holds none yet, and closing it stops the
  server and leaves the directory alone. So a stop is a close and a start is
  building the same thing again on the same port and directory (FIND5). The core
  holds no connection pool — it is given a JDBC URL and opens a connection per
  transaction — so it has nothing stale to discard when the database returns
  (FIND6).

- **Both adapters already ask for a bearer token, and the tokens to present
  already exist.** `THE_SECRET`, `tokenFor`, `presenting` and `ALEX` ship from
  `contract/src/testFixtures/kotlin/io/nook/contract/MintedTokens.kt`, which is
  what the milestone's one hand-minted token is modelled on.

- **The behavior behind two of spec-7's criteria is already built and already
  checked against a stand-in.** `Dispatcher.continuing` closes a project's server
  and stops serving its connections once a call has found the project gone, and
  `ToolProgramTest` already shows a core going away and coming back leaving an
  open connection usable. What has never happened is either of those against the
  real core over a real store.

- **A caller giving up mid-write is already checked one layer down.**
  `CatalogAbandonedWriteTest` repeats an `update_item` carrying blocker edges a
  hundred times against a real database, with the caller's wait cut to three
  milliseconds, and reads every item back whole or untouched. That is the shape
  this epic repeats through three programs, with the caller now dropping its
  connection to the web API rather than shortening the core's own wait.

- **The bounds every test already runs under.** `nook.kotlin-jvm` allows any
  single test two minutes and any test task twenty. Against those, the discovery
  measured a hundred abandoned writes at 3.9 seconds, a real program launched and
  called at about a second, and a core stopped and started under a running
  program at 0.9 seconds (FIND8).

- **The continuous-integration run is one command.**
  `.github/workflows/ci.yaml` runs `./gradlew check` on `ubuntu-24.04`, with the
  reports uploaded on failure.

- **This epic adds no production code.** Every requirement of spec-7 is about
  behavior the four existing modules already have; what is missing is a place
  where all of it runs at once.

### The framing documents, linked rather than restated

- **[Spec-7](./spec-7.md)** is the requirements contract: 19 requirements, 10
  edge cases, 14 acceptance criteria. Its load-bearing decisions are that the
  three programs run as three programs started from their own entry points, that
  the loop begins with a project created through the web API because no tool
  makes one, and that comparing what the two adapters answer is dropped —
  striking [spec-4](../06-mcp-server/spec-4.md) AC26 and
  [spec-5](../07-web-api/spec-5.md) AC20, both already struck in place.

- **[The discovery](./discovery.md)** settled where the run can live and how the
  programs and the database are handled, by reading this repository, what its
  build produces, and the source of the two libraries the answers turn on. Its
  recommendations are adopted here without re-investigation: a module of its
  own, the distributions as the way to start each program, `JAVA_HOME` written
  into each program's environment, a database closed and built again at the same
  address, the embedded-database support shared out of `:core-service` rather
  than copied, and no bounds of this module's own.

- **[This epic's README](./README.md)** records what the run owes: the milestone
  loop over MCP (spec-4's AC25), the same loop through the web API (spec-5's
  AC19), that loop run gated with every row naming its person and agent
  (spec-6's AC17), and epic 06's open limitation that nothing has yet shown a
  tool call reaching the real write and read paths.

### Five decisions taken before this plan, because a plan is not where they belong

- **The run hangs off a target of its own, not off `check`.** The discovery left
  this open (Q4). Decided: `./gradlew systemTest`, invoked by the
  continuous-integration run beside `check`, and by anyone who asks for it. An
  ordinary local build stays as fast as it is today. The cost is stated plainly:
  a change that breaks the assembled system passes `check`, so the guard is the
  continuous-integration run rather than the developer's own loop.

- **The module is `:system-test` and its target is `systemTest`.** System
  testing is the industry's own name for exercising a whole integrated system,
  so it needs no gloss and is what a newcomer would search for.

- **The MCP server runs, and the loop over it runs, driven by the protocol
  library's own client.** `ToolProgramTest` already opens a connection, lists
  tools and calls one that way, and the streamable transport's session handling
  and server-sent replies are not worth reimplementing over raw HTTP. That
  client is a real MCP client driven by a test; what stays deferred is pointing
  a *coding agent's* client at it.

- **Three of spec-7's criteria are deliberately not met here, and spec-7 is
  amended to say so.** AC7 — a client giving no name for itself records no agent
  — is covered where it already stands: epic 08's `ActingIdentityTest` shows the
  nameless client, and its `ActorRecordedTest` shows a row written with no agent
  against the real store. AC12 — a project deleted under a connected agent — and
  the MCP half of AC10 — the core stopped under an open connection — are the two
  scenarios the built behavior already covers against a stand-in, and they wait
  for a leaf of their own. PRD-1's north-star goal is met; these three narrow
  spec-7 and nothing else.

- **`ASM1` stays an assumption.** No coding agent's client is pointed at the
  assembled system in this epic, so the spec's claim that such a client can hold
  a token in its configuration and send it on every request remains untested.
  Epics 06 and 08 each recorded the same, and this one records it as a
  deliberate choice rather than a limit of the method.

### Constraints that bound the change

- **Nothing in the four existing modules' production code changes.** If the
  assembled run finds a defect in one of them, that is a leaf of its own, not a
  fix folded in here.
- **The rules the eleven operations enforce are not this epic's** (spec-1,
  spec-2, ASM4). This spec restates none of them and adds none.
- **Comparing what the two adapters answer is struck** (spec-7's scope), so no
  check here asserts that an MCP reply and a web API reply look alike.
- **`:mcp-server` and `:web-app` still resolve no database library**, in any
  source set; their build rule fails otherwise, and the new module's existence
  must not become a way around it.
- **The programs are reached over the wire only.** The new module will have the
  core's classes on its classpath, and may call none of them.

## Approach

Give the assembled run a module of its own, start the three programs from the
distributions the build already produces, give the run a database it can take
away, and drive the whole of spec-7 from outside the programs — over MCP with
the protocol library's client, and over the web API with an ordinary HTTP
client.

**The module.** `:system-test` applies `nook.kotlin-jvm` and nothing else: no
application plugin, because it holds no program, and no persistence boundary,
because it needs a database. Its one target comes from Gradle's own JVM test
suite support, which exists for exactly this — a test type outside `check` —
rather than from a task disabled by hand. The suite is named `systemTest`, its
sources live at `system-test/src/systemTest/kotlin`, and its task depends on the
three `installDist` tasks and receives their folders as system properties, so no
test hard-codes a path into the build directory.

**The launcher.** One place builds a process from a distribution's start script,
with the `NOOK_*` settings composed exactly — a setting left out genuinely
unset — and `JAVA_HOME` set to the JVM running the suite, then reads the
announcement line each program prints to know it is up. It is written once here
because the trap it avoids is invisible: the launcher the three existing program
tests share looks right and would hand the MCP server a driver.

**The database.** The run owns one: an embedded PostgreSQL on a port it chose
and a data directory it keeps, with the committed changelog applied before the
core starts (ASM2). It is a second way of starting the same library rather than
the shared per-test-JVM server, because that server exists precisely not to be
stopped — taking it away would take the core's own suite with it. Both ways ship
from one place, moved out of `:core-service`'s test sources into its test
fixtures, so the assembled run and the core's own suite cannot end up measuring
against differently built databases.

**What drives the run.** For the web API, an ordinary HTTP client carrying a
token minted against the same secret the adapters were started with. For the MCP
server, the protocol library's client, configured the way an agent's client
would be: the project's address, and the token on every request. Two checks need
neither — the gate is asked with raw requests carrying no `Authorization` header
at all, and a caller that walks away is an HTTP client whose wait is cut short
enough that it is gone before the answer arrives.

**How "only the core connects to the database" is asked.** Two observations
together, needing no tool outside the JVM. Each adapter's distribution is read
for a driver, and neither has one to open a connection with. Then, with all
three programs up and both adapters being called, the database's own view of who
is connected reports connections beyond the run's own — and with the core
stopped while both adapters keep running and keep being called, it reports none.
Every connection the database ever saw is thereby attributed to the core, by
watching them leave with it.

**Why this way over the obvious alternative.** The obvious alternative is to
build the three servers inside the test JVM on real ports, which needs no
distributions, no start scripts and no processes. It would answer none of the
questions this epic exists for: the settings each program reads at startup would
never be read, the classpath separation would be a fiction, and "three programs
started separately serve as one" would be a claim about three objects.

**Blast radius.** A new `:system-test` module and its entry in
`settings.gradle.kts`. `:core-service` gains `java-test-fixtures`, and
`EmbeddedPostgresSupport` moves from its test sources to its test fixtures with
the embedded-database dependencies moving with it — its twenty-two callers'
imports are unchanged. `.github/workflows/ci.yaml` gains the new target beside
`check`. Spec-7 gains an amendment recording the three criteria this epic
narrows, and this epic's README gains its results and its criterion-to-test
mapping. Nothing in the production code of the four existing modules is touched.

**Unverified assumptions, named.** Two, and the first two steps settle them —
riskiest first, while changing course is still cheap. That an embedded database
can be closed and built again at the same address was read in the library's
source and never run (FIND5, and the discovery's own second limitation); STEP1
runs it. That the three distributions can be launched and reached, with the JVM
the build pins and the settings each program needs, was read off the generated
scripts and never executed (FIND2, FIND4); STEP2 runs it, and every step after
depends on it.

## Steps

- [x] **STEP1** — Create `:system-test` with its target, and give the run a
  database it controls: the module applying `nook.kotlin-jvm` alone, a
  `systemTest` suite whose sources are `src/systemTest/kotlin`, the module added
  to `settings.gradle.kts`; `EmbeddedPostgresSupport` moved into
  `:core-service`'s test fixtures with `java-test-fixtures` applied and the
  embedded-database dependencies moved to match; and beside its existing
  per-test-JVM server, one way of building a database on a chosen port and a
  data directory it keeps, closable and buildable again at the same address, with
  the committed changelog applied through the core's own `migrateDatabase`;
  verify: `./gradlew check` still passes with all twenty-two of the moved
  helper's callers untouched; `./gradlew systemTest` runs and `./gradlew check`
  does not run it; and a check in the new module builds the database, opens a
  connection and reads a migrated table, closes the database and sees a
  connection refused at the same address, builds it again and reads the same
  table with the rows still there (ASM2, ASM3, and the discovery's read-not-run
  limitation).

  Diverged: one of the twenty-two callers had to change after all. Applying
  `java-test-fixtures` replaces `:core-service`'s own main output on its test
  classpath with the module's jar, and `MigrationTest` listed the packaged
  changelog as a directory of files. It now reads the same files through
  Liquibase's own reading of the classpath — the one `migrateDatabase` applies
  them with — which sees them wherever the build leaves them. The other
  twenty-one are untouched.

- [x] **STEP2** — Launch the three programs from their distributions, and show
  they come up in either order: the suite's task depending on the three
  `installDist` tasks and passing their folders in; one launcher that starts a
  distribution's start script with the `NOOK_*` settings composed exactly,
  `JAVA_HOME` set to the JVM running the suite, and the announcement line read
  back; then the two bring-up orders and each program started with one setting
  missing; verify: with the MCP server and the web app started before the core,
  a project created through the web API and a connection opened at its address
  over MCP are both served once the core is up, with no program restarted, and
  the same holds with the core started first; each of the three programs started
  with one of its settings missing stops with a non-zero exit code naming that
  setting, run through the start script rather than through a classpath, so what
  is shown is what an operator meets (REQ1, REQ3, REQ4, EDGE1, EDGE2, EDGE10,
  AC1, AC14).

- [x] **STEP3** — Run the milestone's loop over MCP, and read back who wrote
  every row: a project created through the web API with a token naming `alex`;
  one connection opened at that project's address by a client naming itself
  `claude-code` and presenting the same token on every request; over that
  connection alone, a release, an epic, two tasks under it and a project-level
  bug, the epic put in the release, the second task made to wait on the first,
  and one listing call asking for the leaf types with status `todo` and nothing
  unfinished holding them up; then every entity read back, and the rows read
  straight from the database with plain SQL; verify: the listing holds exactly
  the first task and the bug in the order the core produced them; the tools the
  connection is offered include none that creates a project and a call naming
  `create_project` is refused naming the tool it asked for; every row created
  over that connection records `alex` as what created and last changed it and
  `claude-code` as the agent, the project records `alex` as its owner and no
  agent, and the database says the same as the replies did; and the whole run
  needed no intervention after the project existed (REQ5, REQ6, REQ7, REQ9,
  REQ10, REQ11, REQ12, AC3, AC4, AC6).

- [x] **STEP4** — Run the same loop through the web API alone, with the MCP
  server not running at all: an empty database, the core and the web app
  started, and the same sequence carried out as ordinary calls to `/api`;
  verify: the listing holds exactly the first task and the bug in the order the
  core produced them; the database holds the release, the epic, its two tasks
  with the second waiting on the first, and the project-level bug; and every row
  it wrote records the person its token named and no agent at all (REQ8, REQ11,
  AC5).

- [x] **STEP5** — Put two adapters on one database: one project, and a hundred
  rounds in which an item of the same name is created at the same moment by a
  tool call and by a web API call; then a hundred rounds in which a caller sends
  a create carrying two blocker edges to the web API and drops its connection
  before the answer arrives; verify: every round leaves two items whose slugs
  differ, and both calls in every round come back with a verdict rather than one
  giving up waiting; at least one of the hundred callers was gone before its
  answer arrived, so something really was abandoned; and every one of those
  hundred items is afterwards either present with both blocker edges or absent
  altogether (REQ13, REQ14, EDGE5, EDGE6, EDGE8, AC8, AC9).

  Diverged: the abandoned write is an `update_item`, not a create. No operation
  makes an item and its blockers in one call — `create_item` takes no blockers —
  so what is abandoned is the one write that puts a row and its edges in one
  transaction, exactly as this plan's own analysis of the check one layer down
  says. The item is therefore always present afterwards; what is checked is that
  its new name and both its edges arrived together or not at all. Spec-7's AC9
  records the same reading. And the caller giving up is not enough on its own —
  a wait cut to a millisecond can end before the request is on the wire, which
  abandons nothing — so what is checked is that at least one caller left while
  the core went on to finish, which is the only arrangement a half-written row
  was ever possible in.

- [x] **STEP6** — Take the core away, then the database, and bring each back:
  with all three programs up, the core's process stopped and a call made to the
  web API, then the core started again at the same address and another call
  made; then the database closed and a call made to each adapter, and the
  database built again at the same address and a later call made to each;
  verify: while the core is down the call comes back saying no verdict was
  reached and carrying none of the four domain reasons, and the call after it
  returns succeeds without either adapter being restarted or any client rebuilt;
  while the database is down each adapter reports no verdict carrying none of
  those reasons, and once it is back a later call to each succeeds with no
  program restarted (REQ15, REQ16, EDGE3, EDGE4, AC10 in part, AC11).

- [x] **STEP7** — Ask the gate and the database separation of the running
  system: a well-formed call to each adapter carrying no `Authorization` header,
  with the row counts read before and after; the same two calls repeated with
  the core stopped; the same two calls presenting a valid token; each adapter's
  distribution read for a driver; and the database's own view of who is
  connected, read with all three programs up and both adapters being called, and
  again with the core stopped while both adapters keep running and keep being
  called; verify: both calls with no token are refused, the database is
  unchanged, and the refusal is still a refusal with the core stopped — where a
  call presenting a valid token reports no verdict instead — which is the core
  shown not to have been reached; both calls presenting a valid token are
  served; neither adapter's distribution holds a driver jar; and the connections
  the database reports beyond the run's own are there with the core and gone
  without it (REQ2, REQ18, REQ19, AC2, AC13).

- [x] **STEP8** — Close the epic: amend spec-7 to record the three criteria this
  epic narrows and where each is covered instead; add the new target to
  `.github/workflows/ci.yaml` beside `check`; write this epic's README results —
  what the assembled run proves, the four debts it settles, each of spec-7's
  criteria against the named test that executes it, and what is deferred with the
  reason; then run the whole build and the new target from a clean checkout and
  push for the continuous-integration run; verify: `./gradlew check systemTest`
  green locally and in that run with the new tests visibly executed;
  `:mcp-server` and `:web-app` still resolve no database library; and every one
  of spec-7's fourteen criteria appears in the mapping against either a test that
  exists or a stated reason it is deferred.

  Done: spec-7 carries the three narrowings in place, the continuous-integration
  run asks for `./gradlew check systemTest`, and this epic's README carries the
  results, the criterion-to-test mapping and what is deferred. `./gradlew clean`
  then `check systemTest --no-build-cache` is green — 441 checks under `check`,
  13 under `systemTest` — and both adapters still resolve no database library.
  The continuous-integration run is green too, with `:system-test:systemTest`
  executed rather than replayed, which closes the discovery's limitation about
  one machine and one architecture: the assembled run starts and passes on the
  Linux the milestone is verified on as well as on the laptop it was built on.

  Added to the blast radius: `CLAUDE.md`, which now says a fifth module exists
  and that the build has two targets. A session guide that described four modules
  and one target would send the next reader looking for a run that is not there.

## Caveats & rabbit holes

- **no-go: launching a program with the test's own classpath** — it is what the
  three existing program tests do, it looks right, and in this module it would
  start the MCP server with Exposed, Liquibase and the driver on it, so the one
  thing the run exists to observe would be gone before the first call (FIND3);
  instead: launch the distributions, always, and treat a program started any
  other way as a defect in the launcher.

- **no-go: taking the shared per-test-JVM database away** — it serves the core's
  own suite and every one of its twenty-two callers, and stopping it to check
  what happens when a database goes away would stop them too; instead: the run
  builds its own on a port it chose and a directory it keeps, and only ever
  closes that one.

- **no-go: hanging the new target off `check`** — decided above, and the
  temptation will return the first time someone breaks the assembled system
  without noticing; instead: leave it to be asked for by name, and keep the
  continuous-integration run invoking it, which is the whole of the guard.

- **caveat: this module can see `:core-service`'s classes** — it depends on the
  core's test fixtures for the embedded database and the changelog, and Gradle
  puts the module's own classes on that path with them; instead: reach the
  assembled system over the wire only, and never build a `CoreCatalog`, a
  service or a table declaration here — a check that calls the core directly is
  measuring something else and looks identical.

- **caveat: the persistence boundary does not fence this module** — it cannot,
  because the run needs a database, so the separation is preserved here by
  launching three distributions rather than by a build rule; instead: keep the
  driver check of the distributions in the suite, which is what replaces the
  rule inside this module.

- **caveat: "neither call waited on the other" is not "the two never queued"** —
  structure writes take their turn on a project by locking its row, by design
  (`ARCHITECTURE.md` §3.3), so two writes in one project do serialize briefly;
  instead: check that both calls come back with a verdict rather than one giving
  up waiting, and never assert that they overlapped.

- **caveat: the embedded binaries are pinned per machine** — `:core-service`
  declares the Apple-silicon binaries on top of the library's default platform
  set, and the new module needs the same declarations to run here and on the
  Linux the continuous-integration run uses; instead: copy those two
  declarations across with the moved helper, and expect the run to be unavailable
  on anything else. Diverged, and for the better: the declarations moved *with*
  the helper into `:core-service`'s test fixtures rather than being copied into
  the new module, so whoever takes the fixtures gets the binaries to run them
  with and there is no second list to keep in step. Both platform sets resolve
  onto the new suite's runtime classpath.

- **rabbit-hole: pointing a coding agent's client at the assembled system** —
  three epics have now recorded it, spec-7's first assumption rests on it, and it
  is a manual run against a client this repository does not ship; instead: leave
  it, and record in the README that the assumption is still an assumption.

- **rabbit-hole: re-running the rule suites through the adapters** — spec-4's
  AC26 and spec-5's AC20 each asked for every rule of spec-1 and spec-2 again
  through a real program, and spec-7 struck both; instead: leave them struck, and
  do not add "just a couple" of rule checks here — the core's own suite proves
  them against a real database.

- **rabbit-hole: bounds of this module's own** — the measured shapes sit an
  order of magnitude inside what the build already allows (FIND8), and a bound
  set against a guess is one nobody can later tell from a real one; instead: take
  the two minutes and twenty minutes `nook.kotlin-jvm` already sets, and set one
  of this module's own only when something measures a reason.

- **rabbit-hole: a load probe against the assembled system** — the sixth epic to
  meet the question, and the first with a whole system to point one at; instead:
  leave it, and let it be a leaf of its own if anyone wants the number.

- **caveat: a defect found in one of the four modules is a leaf of its own** —
  this epic writes no production code, and the first assembled run is exactly
  where a real defect would surface; instead: record what was found, raise it,
  and do not fold the fix into this module's arrival.

- **no-go: adding a tool that creates a project to make the loop start over
  MCP** — spec-4 decided which operations become tools and spec-7 starts the loop
  on the web API because of it; instead: create the project through the web API,
  and check that no tool offers to.

## Test plan

Every check below runs in `:system-test` against the three programs launched
from their distributions, over the run's own embedded database. Nothing here
uses a stand-in for anything.

- **TEST1** — integration: the run's database is built, a connection reads a
  migrated table, the database is closed and a connection to the same address is
  refused, it is built again and the same table reads back with its rows. And,
  as a build check, `./gradlew check` passes with the moved helper's callers
  untouched while `./gradlew systemTest` is the only thing that runs this
  module's tests.

- **TEST2** — integration: with the MCP server and the web app started before
  the core, a project created through the web API and a connection opened at its
  address are both served once the core is up, no program restarted; the same
  with the core started first; and each of the three programs, started through
  its start script with one of its settings missing, stops with a non-zero exit
  code naming that setting.

- **TEST3** — integration: the milestone's loop over one MCP connection —
  release, epic, two tasks, project-level bug, the epic put in the release, the
  second task made to wait on the first — answered by a listing holding exactly
  the first task and the bug in the order the core produced them; no tool creates
  a project and a call naming `create_project` is refused naming it; and every
  row created reads back, from the replies and from the database, recording
  `alex` as what created and last changed it and `claude-code` as the agent, with
  the project recording `alex` as owner and no agent.

- **TEST4** — integration: the same loop through the web API alone with the MCP
  server not running, reaching the same listing and leaving the same state in the
  database, every row recording its person and no agent.

- **TEST5** — integration: a hundred rounds of an item of the same name created
  at the same moment through both adapters leave two items with different slugs
  every round, both calls answered; and a hundred rounds of a caller dropping its
  connection to the web API mid-write leave every item either whole with both
  blocker edges or absent, with at least one caller shown to have been gone
  before its answer arrived.

- **TEST6** — integration: with the core stopped, a call to the web API reports
  no verdict carrying none of the four domain reasons, and a call after the core
  returns succeeds on the same client; with the database closed, a call to each
  adapter reports the same, and a later call to each succeeds once it is back —
  no program restarted in either case.

- **TEST7** — integration: a call carrying no `Authorization` header is refused
  at each adapter with the database unchanged; the same two calls are still
  refused with the core stopped, where a call presenting a valid token reports no
  verdict instead; both are served presenting a valid token; neither adapter's
  distribution holds a driver jar; and the connections the database reports
  beyond the run's own are present with the core running and gone with it
  stopped, both adapters up and being called throughout.

- **Standing check, comment hygiene** — search the final diff for artifact
  tokens (STEP, REQ, GOAL, FIND, AC, EDGE, ASM, PRD, epic) and markdown paths in
  code and code comments; expect zero hits.

- **Standing check, conformance sweep** — re-read this plan against the final
  diff: every step ticked with its verification observed, the blast radius
  respected — no production code changed in `:contract`, `:core-service`,
  `:mcp-server` or `:web-app`, no rule of spec-1 or spec-2 re-checked here, and
  no comparison of the two adapters' answers — every caveat honored, and any
  mid-build divergence folded back into this text.

- Run both standing checks through a separate agent handed only this plan and
  the final diff, none of the builder's conversation. **Run by the builder
  instead, and still owed to a separate reader.** The comment-hygiene sweep
  found two artifact references in code comments and both were rewritten; the
  conformance sweep found the blast radius held — no production code touched in
  the four modules, nothing of the core reached except the database fixture, no
  rule re-checked, no comparison of the two adapters' answers, and no bounds of
  this module's own. What a separate reader adds is independence, and that has
  not been had.

Done when: `./gradlew check` is green and `./gradlew systemTest` is green, both
locally from a clean checkout and in the continuous-integration run, which
invokes them both; the milestone's loop has run over MCP against three real
programs and one real database and returned exactly the open, unblocked leaves;
the same loop has run through the web API with the MCP server not running; every
row either loop wrote names the person its token was for and, over MCP, the agent
its connection announced; eleven of spec-7's fourteen criteria pass as named
tests, with AC10 recorded as met through the web API alone; spec-7 records the
three it narrows and where each is covered instead; and this epic's README
carries the results, the criterion-to-test mapping, and what is deferred with the
reason.
