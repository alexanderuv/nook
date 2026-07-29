# Full system test approach

## Summary

- **The assembled run has nowhere in this build to live.** Both adapters are
  fenced off from a database by a check that walks their test code as well as
  their programs, and putting the run in the core would have the core's tests
  depend on its own adapters, which is the wrong way round. It needs a module of
  its own — and that module is the second in the build, after the core, with no
  such fence around it.
- **A way to start three programs each holding only its own dependencies already
  exists, and it is the thing an operator runs.** The build already produces a
  distribution per program: a folder of jars and a start script. The MCP
  server's holds 45 jars and the web app's 29, and neither holds a database
  driver at all — so "only the core connects to the database" is already true of
  what the adapters can load, before any running system is asked. What cannot be
  reused is the pattern the three existing program tests share: all three hand
  the program the test's own classpath, which in one module holding all three
  would start the MCP server with Exposed, Liquibase and the driver on it, and
  quietly erase the separation this epic exists to observe.
- **Taking the database away underneath three running programs and bringing it
  back at the same address needs no feature of the embedded database library and
  no recovery in the core.** The library skips creating a new database when the
  data directory already holds one and it was told not to clean it, so a stop is
  a close and a start is building the same thing again on the same port. The
  core holds no pool: it is given a JDBC URL, and a connection is opened per
  transaction and dropped after — there is nothing stale to discard.
- **The bound that looked like the risk has an order of magnitude of headroom.**
  A hundred abandoned writes against a real embedded PostgreSQL cost 3.9
  seconds, and launching a real program and calling through it costs about a
  second, against the two minutes this build allows any single test.
- Recommendation in brief: give the run a module of its own; start the three
  programs from the distributions the build already produces, with `JAVA_HOME`
  written into each program's environment so it runs on the JVM the build pins;
  take the database away by closing an embedded server built on a fixed port and
  a data directory it keeps, and bring it back by building the same one again;
  share the embedded-database support out of `:core-service` as test fixtures
  rather than copying it; and give the new module no bounds of its own until
  something measures a reason for them.

## Questions

- **Q1** — Where in this build can the three programs run together, and how does
  each get started holding only its own dependencies?; informs: where this
  epic's work lands, and whether [spec-7](./spec-7.md)'s AC2 — that only the core
  service connects to the database — can be asked of a running system at all.
- **Q2** — Can the run supply a PostgreSQL, take it away underneath three running
  programs, and bring it back at the same address without restarting any of
  them?; informs: spec-7's AC11 and EDGE4, and whether this spec has to be
  amended the way [spec-4](../06-mcp-server/spec-4.md) was when epic 06 found two
  of its requirements frozen inside a library.
- **Q3 (emerged)** — Do checks of this shape fit the bounds the build already
  places on every test?; informs: whether this epic's target needs bounds of its
  own and what the plan sets them to. Asked once spec-7's AC8 and AC9 — a
  hundred runs each, across three programs — were read beside the two-minute
  limit `nook.kotlin-jvm` puts on any single test.

Bound: reading, on one machine (macOS, Apple silicon, JDK 25), of this
repository, of what its own build produces, and of the source of the two
libraries the answers turn on. Nothing was written and no assembled run was
staged. That was the bound because every question here asks whether the build
and its libraries can be arranged the way spec-7 already requires, and what a
build produces answers that directly — whereas the loop, the two hundred-run
races, the deleted project and the gate are spec-7's acceptance criteria, and
running those is this epic rather than a report about it.

## Method

A reading investigation, with the build run only to look at what it produces.

**What was read.** The three programs' entry points; the three tests that already
launch a real program as its own process (`CoreProgramTest`, `ToolProgramTest`,
`WebProgramTest`); the `nook.persistence-boundary` plugin and the task behind
it; the `nook.kotlin-jvm` plugin, which is where every test's bounds are set; the
four modules' build files; `ARCHITECTURE.md` §3.3 on which part may depend on
which; and the source of the two libraries the answers turn on — the embedded
database at the pinned 2.2.2, and the data-access library's own `Database.connect`
at 1.3.1.

**What was run.** `installDist` for all three modules — the task that produces
what would be shipped — and the contents of the three folders it produced were
counted and read. One existing test that repeats a write a hundred times against
a real embedded PostgreSQL was re-run for its duration; the durations of the
tests that launch real programs were read out of the last run's own reports
rather than re-measured.

For Q1, three ways of starting a program were compared, on three axes: whether
the program gets only its own dependencies; whether it is the same thing an
operator would run; and what it costs to control the settings the program starts
with, which is what spec-7's AC14 turns on.

- **The test's own classpath** — what all three existing program tests do today.
- **A resolved dependency set per program**, declared in the test's own module
  and handed to it — considered and read for what it would involve, not built.
- **The distributions the build already produces** — a folder of jars and a
  start script per program, from the `application` plugin every module here
  already applies.

Not done: no module was created, no test was written, no assembled run was
staged, no database was stopped underneath anything, no real coding agent's
client was involved, and nothing was run on a machine other than this one. Five
things that leaves unsettled are recorded as limitations.

## Findings

### FIND1 — No module in this build can host the assembled run

**Confidence:** solid — read off the boundary plugin, the four module build
files, and the architecture record · answers Q1

`:mcp-server` and `:web-app` both apply `nook.persistence-boundary`, and that
plugin walks every source set, test code included, saying so in as many words:

```
// Every source set is walked, not just the main one. Test code is where an
// adapter first reaches for a database — a fixture that "just needs a table for
// a moment" — and a boundary that stopped at production code would let exactly
// that through while reporting the module clean.
```

So an embedded database in either adapter's tests fails `check`. That is the
guard working as designed, and it rules both modules out.

`:core-service` has no such guard and already starts an embedded PostgreSQL in
its tests. Hosting the run there would put `:mcp-server` and `:web-app` on the
core's test classpath — a dependency from the core to its own adapters, against
the direction `ARCHITECTURE.md` §3.3 sets, where the adapters translate their
protocol into core calls and the core knows nothing of them. `:contract` is what
all three depend on, so the same edge again from further down.

A module of its own is therefore the only place left, and it will be the second
module in this build — after `:core-service` — that the persistence boundary
does not fence.

### FIND2 — The three distributions are already separate, and neither adapter's holds a database driver

**Confidence:** solid — built and counted · answers Q1

`installDist` produces, per program, a folder of jars and a start script:

| the program's distribution | jars | persistence jars among them |
| --- | --- | --- |
| `:core-service` | 43 | `exposed-core`, `exposed-jdbc`, `exposed-java-time`, `liquibase-core`, `postgresql` |
| `:mcp-server` | 45 | none |
| `:web-app` | 29 | none |

The start script names the whole classpath outright, so this is not an inference
from a dependency graph but the list the program actually starts with:

```
CLASSPATH=$APP_HOME/lib/mcp-server.jar:$APP_HOME/lib/contract.jar:$APP_HOME/lib/ktor-client-cio-jvm-3.5.1.jar:…
```

This matters twice over. It is a way of starting each program with only its own
dependencies that costs nothing to build, because the build already builds it.
And it means spec-7's AC2 — only the core service holds a connection to the
database — is true at the level of what the adapters can even load: neither has
a driver to open one with.

### FIND3 — The pattern the three program tests share cannot be reused, because it hands every program one classpath

**Confidence:** solid — read in all three · answers Q1

`CoreProgramTest`, `ToolProgramTest` and `WebProgramTest` each launch their
program like this:

```kotlin
val builder = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), PROGRAM)
```

That is the test JVM's own classpath, and it is correct in each of those three
places for one reason: the program under test lives in the module testing it, so
the module's classpath and the program's are the same thing. In one module
holding all three, they stop being the same thing — the MCP server would start
with Exposed, Liquibase and the driver on it, and AC2 would be asked of a system
that had been assembled wrong.

The habit is worth naming because the code that does it looks right, which is the
same trap epic 05 recorded about where a request is read and epic 07 about
telling an unreachable core from a broken one.

### FIND4 — Launching the distributions costs one setting to keep the JVM the build pins

**Confidence:** solid — read out of the generated script · answers Q1

The start script finds a JVM the way any shell script does — `JAVA_HOME` if it
is set, otherwise whatever `java` the machine's `PATH` resolves:

```
if [ -n "$JAVA_HOME" ] ; then … JAVACMD=$JAVA_HOME/bin/java
else JAVACMD=java … "JAVA_HOME is not set and no 'java' command could be found in your PATH."
```

The existing tests pick the exact JVM running them, through
`System.getProperty("java.home")`, and hand the child an environment with the
`NOOK_*` settings removed and everything else inherited. Under the distributions
that inheritance is what makes the script work at all — `PATH` arrives — but the
program would then run on whatever JVM the machine resolves rather than the JDK
25 the build pins, unless `JAVA_HOME` is written into the child's environment
deliberately. One setting, and the difference between the two is invisible until
something behaves differently on one JVM.

Controlling the settings a program starts with is otherwise unaffected: the
environment is the launcher's to compose either way, which is what AC14 — a
program started with a setting missing stops and names it — needs.

### FIND5 — A database can be taken away and brought back at the same address, and the library needs no feature for it

**Confidence:** solid on what the code does, and read rather than run · answers Q2

The embedded database's constructor creates a new database only when it was told
to clean the directory, or when the directory holds none yet:

```java
if (cleanDataDirectory || !new File(dataDirectory, "postgresql.conf").exists()) {
    initdb();
}
```

and closing it stops the server properly and leaves the directory alone unless
cleaning was asked for:

```java
pgCtl(dataDirectory, "stop");
…
if (cleanDataDirectory && System.getProperty("ot.epg.no-cleanup") == null) {
    FileUtils.deleteDirectory(dataDirectory);
} else {
    LOG.info("Did not clean up directory {}", …);
}
```

Its builder takes a port, a data directory, and whether to clean that directory.
So the database going away and coming back at the same address, which AC11 and
EDGE4 both turn on, is: build it on a fixed port and a fixed directory it is told
to keep; close it; build the same thing again. There is no restart call in the
library and none is needed. Spec-7 stands here as written.

### FIND6 — Nothing in the core has to recover from a database that went away, because it holds no pool

**Confidence:** solid — read in the core's own startup and in the library's
source · answers Q2

The core is given a JDBC URL and nothing else:

```kotlin
CatalogServer(CoreCatalog(Database.connect(requiredSetting(DATABASE_SETTING))), LOOPBACK, port)
```

and that call, as the library documents and implements it, holds no connection
at all until a transaction asks for one, and gets each one from the driver
manager:

```
**Note:** This function does not immediately instantiate an actual connection to a database,
but instead provides the details necessary to do so whenever a connection is required by a transaction.
…
getNewConnection = { DriverManager.getConnection(url, user, password) },
```

So a core whose database went away and came back has no pooled connection left
over to hand out dead. What AC11 asks of the core is met by the arrangement that
is already there rather than by anything this epic adds.

### FIND7 — The binaries the embedded database runs from are declared once, for one machine, in a place nothing else can reach

**Confidence:** solid — read in the module build file and the test source ·
answers Q2

`:core-service` declares `zonky-postgres-binaries-darwin-arm64v8` as a test-only
runtime dependency, with a bill of materials aligning the default platform set to
the same PostgreSQL version; this machine is `arm64`. A new module inherits none
of that and has to declare the same.

`EmbeddedPostgresSupport` — which starts one server for the JVM and hands out a
freshly migrated database per caller — lives in `:core-service/src/test`, which
no other module can reach. `:contract` already shows the shape for sharing
test-only code across modules: it applies `java-test-fixtures`, and ships the
stand-in core and the minted tokens from there for exactly this reason, that two
copies free to differ prove less than one shared.

### FIND8 — The hundred-run shapes cost seconds, so the build's existing bounds are not the risk they looked

**Confidence:** solid — one measurement, and the rest read from the last run's
own reports · answers Q3

| what was measured | how long |
| --- | --- |
| 100 abandoned writes against a real embedded PostgreSQL, in one process | 3.9 s |
| the same, as a whole build invocation, database start included | 5.3 s |
| launching the MCP server as its own process and calling a tool through it | 1.1 s |
| the same for the web app | 0.5 s |
| a core stopped and started again under a running program | 0.9 s |
| the widest existing concurrent check, against a stand-in core | 8.6 s |

Against those, `nook.kotlin-jvm` allows any single test two minutes and any test
task twenty. This is a negative result and worth reporting as one: the bound that
looked like the obvious problem for AC8 and AC9 is roughly an order of magnitude
away, and what the assembled run adds on top is three process launches per check
at about a second each.

## Implications & recommendation

- **Give the assembled run a module of its own** (FIND1) — the two adapters are
  fenced from a database by a check that was written to catch exactly the fixture
  someone would add here, and the core cannot host it without depending on its
  own adapters. This is a consequence of decisions already made rather than a new
  one, and it is the first thing the plan has to do.
- **Start the three programs from the distributions the build already produces**
  (FIND2, FIND3, FIND4) — it gives each program only its own dependencies,
  costing nothing to build, and it is the same artifact an operator would run,
  which is what spec-7's first scenario describes. The alternative that looks
  cheapest — reusing the launcher the three program tests share — is the one that
  silently assembles the system wrong.
- **Write `JAVA_HOME` into each program's environment** (FIND4) — otherwise the
  programs run on whatever JVM the machine resolves rather than the one the build
  pins, and nothing about the run would say so.
- **Take the database away by closing an embedded server on a fixed port and a
  kept data directory, and bring it back by building the same one again**
  (FIND5, FIND6) — no library feature is missing, no requirement of spec-7 needs
  amending here, and the core needs nothing added to survive it. This is the
  question that could have forced a spec amendment the way epic 06's did, and it
  did not.
- **Share the embedded-database support out of `:core-service` rather than
  copying it** (FIND7) — `:contract` already established the shape, and the
  reason it gave applies unchanged: two copies free to differ would leave the
  assembled run and the core's own suite measuring against different databases
  while appearing to agree.
- **Give the new module no bounds of its own yet** (FIND8) — the measured shapes
  sit an order of magnitude inside what the build already allows, and a bound set
  against a guess is a bound nobody can later tell from a real one.
- **Treat AC2 as already half answered before the system runs** (FIND2) — neither
  adapter's distribution contains a driver, so the runtime examination the
  criterion asks for confirms something the build has already made impossible,
  rather than being the only thing standing between the adapters and a database.
  Worth keeping: what makes it impossible in the new module is the three
  distributions being launched separately, because the boundary check that
  enforces it elsewhere does not cover a module that needs a database itself.

## Limitations

- **Nothing was executed as an assembled run** — at risk: every claim here is
  about what the build produces and what two libraries' code does, not about
  three programs actually serving together, so a problem that only appears when
  they do is exactly what this method cannot find; would raise confidence: this
  epic's own build work, which is where all of it gets executed.
- **The database's stop and restart was read, not run** — at risk: FIND5 rests on
  one branch in a constructor and one shell call in `close()`, and a data
  directory a server refuses to start on a second time would not show up in
  either; would raise confidence: one build-close-build against a fixed port and
  directory, which is a few lines of whatever the epic writes first.
- **The durations were measured apart, not together** — at risk: FIND8's headroom
  is a sum of parts measured separately on an unloaded machine, and the assembled
  checks run three programs, a database and a hundred repetitions at once; would
  raise confidence: the first assembled check that exists, timed.
- **One machine and one architecture** — at risk: the embedded binaries are pinned
  to Apple silicon and every number above comes from this laptop, so nothing here
  says what the run costs or whether it starts anywhere else; would raise
  confidence: the same run on the machine the milestone is actually verified on.
- **No real coding agent's client was involved** — at risk: spec-7's loop is
  described as an agent's, and whether a real client can be told to present a
  token is still unshown; would raise confidence: pointing one at the built
  server. Epic 06 and epic 08 each recorded this same limitation, and this epic
  is the first place there is a whole system to point one at.

## Open questions

**Needs action:**

- **Q4** — Does the assembled run hang off `check`, or off a target someone asks
  for by name?; blocks: how the plan wires the new module, and what an ordinary
  build costs everyone afterwards; would take: a decision. FIND8 says it would
  fit inside `check` on time alone, and this epic's own README calls it "the one
  target where a test may take a database", which reads as a target of its own —
  the two have not been reconciled anywhere.

**Follow-ups:**

- **Q5** — How is "each program's open connections to the database are examined"
  actually carried out?; matters because: AC2 asks a running system a question
  FIND2 largely answers before it runs, so what remains is a confirmation whose
  mechanism nobody has chosen; would take: reading what the database's own
  activity view reports about where a connection came from, against what the
  operating system reports about each program.
- **Q6** — Does spec-7's AC14 — each program stopping when a setting is missing —
  need running again here, given that all three modules already prove it of their
  own program?; matters because: it is the one criterion in this spec that
  another test already covers, and running it twice buys nothing that assembly
  makes newly true; would take: a reading of the three existing program tests
  against AC14, when the plan reaches it.
- **Q7** — Can a real coding agent's client be told to present a token, and what
  does a person see when it is refused?; matters because: the loop spec-7
  describes is an agent's, and this is the first epic with a whole system to
  point a client at; would take: pointing one at the built programs, carried
  forward from epics 06 and 08 unchanged.
