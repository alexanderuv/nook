# Module skeleton — Plan

## Analysis

The repo is pre-implementation: no Gradle build, no source code — only design
docs (`docs/`, `ARCHITECTURE.md`), the artifact templates (`artifacts/`), the
execution folder, and the Liquibase changelog under `db/changelog/` (consumed by
epic 02, untouched here). The build lands at the **repo root**, alongside those
folders.

What this epic realizes is settled upstream:

- [PRD-1](../prd-1.md) **REQ1** — the multi-module skeleton: core service,
  `:mcp-server`, `:web-app`, and a shared contract library carrying the DTOs —
  with **GOAL4** as its guardrail: zero database/persistence dependencies
  outside the core service, observed in the build dependency graph.
- [ARCHITECTURE §3.3/§7](../../../ARCHITECTURE.md) — core-plus-thin-adapters
  topology; Kotlin + Ktor backend; Exposed confined to the core service; the
  **Java** MCP SDK (not Kotlin) in `:mcp-server`.
- [The discovery report](./discovery.md) — the build layout (FIND1, FIND2), the
  version matrix (FIND6), and the GOAL4 enforcement mechanism (FIND3–FIND5).
  Ruled out there, not to be re-investigated: `buildSrc` and
  `allprojects`/`subprojects` cross-configuration (officially discouraged);
  Konsist/ArchUnit-style tools (they check source or bytecode references, not
  the dependency graph GOAL4 names); the Kordamp enforcer plugin (functionally
  exact but stale); the Kotlin MCP SDK (pre-1.0, live conformance bugs).

Constraints that bound the change: Gradle held at **9.5.x** (Kotlin 2.4.10's
fully-supported window ends there; 9.6.1 is one notch past it), **JDK 25**
toolchain, and the FIND6 version set — a 2026-07-15 snapshot of monthly-moving
parts, so pins get re-checked before the catalog is committed.

## Approach

Scaffold the build exactly as the discovery recommends: convention plugins in a
`build-logic` **included build**, a `gradle/libs.versions.toml` version
catalog, and exactly the four settled modules — `:contract`, `:core-service`,
`:mcp-server`, `:web-app` — split no further. Group and base package:
**`io.nook`**.

Modules declare their **real dependencies from day one** — Ktor in the three
app modules, Exposed/Liquibase/JDBC drivers in `:core-service` only,
kotlinx.serialization in `:contract`, the Java MCP SDK in `:mcp-server` — so
the GOAL4 check observes the real graph (an empty graph passes trivially) and
version conflicts surface here, where they're cheapest. No application code
beyond one placeholder source file per module: runnable servers, endpoints,
and wiring belong to epics 03/06/07.

GOAL4 is enforced by a small custom Gradle task in `build-logic` that walks
each module's resolved compile and runtime graphs via Gradle's
`ResolutionResult` API and fails on banned group IDs, wired into `check`. This
is the one mechanism that observes exactly what GOAL4 names (module boundaries
never *fail* on a later-added dependency; the architecture-test tools watch the
wrong layer). Because the adapters call the core over HTTP and hold no module
edge to `:core-service`, the check takes its strictest form: the *entire*
graphs of `:contract`, `:mcp-server`, and `:web-app` must be free of
persistence coordinates.

Blast radius: new files only — the build scaffolding and module folders. Must
leave untouched: `db/changelog/` (epic 02's), `docs/`, `artifacts/`,
`execution/` content other than this epic's folder.

## Steps

- **STEP1** — Re-check the FIND6 version pins against Maven Central (Kotlin,
  Gradle 9.5.x line, Ktor, Exposed, kotlinx.serialization, Liquibase, both JDBC
  drivers, Java MCP SDK); adopt any newer patch releases within the same
  compatibility windows. Verify: each pinned version exists on Maven Central
  and none exceeds its partner's supported window (Kotlin↔Gradle especially).
- **STEP2** — Scaffold the root build: `settings.gradle.kts` (root project
  `nook`, the four modules included, `build-logic` as an included build),
  `gradle/libs.versions.toml` with the STEP1 pins, Gradle wrapper at 9.5.x,
  `.gitignore` for build outputs. Verify: `./gradlew help` succeeds on JDK 25.
- **STEP3** — Create `build-logic` with convention plugins: a base Kotlin/JVM
  convention (JDK 25 toolchain, group `io.nook`, common test setup) and an
  application convention layering `application` onto the base for the three
  runnable modules. Verify: `./gradlew :build-logic:build` succeeds.
- **STEP4** — Create the four modules, each applying its convention plugin,
  with one placeholder source file under `io.nook.*` per module (no runnable
  behavior). Verify: `./gradlew build` compiles all four.
- **STEP5** — Declare the real dependencies from the catalog: `:contract` —
  kotlinx.serialization; `:core-service` — Ktor server, Exposed, Liquibase,
  PostgreSQL and SQLite drivers, plus a dependency on `:contract`;
  `:mcp-server` and `:web-app` — Ktor server, `:contract`, and (mcp-server
  only) the Java MCP SDK. No module edge from either adapter to
  `:core-service`. Verify: `./gradlew build` still green;
  `./gradlew :mcp-server:dependencies` shows the SDK resolving alongside Ktor
  3.5.x.
- **STEP6** — Write the boundary-check task in `build-logic`: walk the resolved
  compile and runtime graphs of `:contract`, `:mcp-server`, and `:web-app`;
  fail listing the offending path if any dependency's group is
  `org.jetbrains.exposed`, `org.postgresql`, `org.xerial`, or `org.liquibase`;
  wire it into those modules' `check`. Verify: `./gradlew check` runs the task
  and passes.
- **STEP7** — Tamper-test the check: temporarily add
  `implementation(libs.exposed.core)` to `:web-app`, confirm
  `./gradlew check` fails naming the banned coordinate and the module, then
  revert. Verify: red with the tamper in place, green after revert.

## Caveats & rabbit holes

- **No application code** — the temptation is to make the Ktor apps actually
  serve something while everything's open; instead: placeholder sources only —
  the write path is epic 03, the servers are epics 06/07.
- **Don't split `:core-service` further** — a domain-vs-persistence module
  split has no evidence behind it at skeleton stage (FIND2); instead: revisit
  only if a later milestone shows real pressure.
- **Don't chase Gradle 9.6+ or newer Kotlin lines** — each is one notch past
  the other's tested window; instead: hold 9.5.x now, move when Kotlin's
  compatibility matrix catches up.
- **The MCP SDK is a declaration only** — how its servlet-based transport is
  hosted next to Ktor is epic 06's first question (discovery Q8); instead: stop
  at the dependency resolving cleanly.
- **No CI setup** — nothing in PRD-1 asks for it; the check wires into
  `./gradlew check` locally; instead: treat CI as a later, separate decision.
- **Don't touch `db/changelog/`** — database bring-up, SQLite behavior, and
  the drift check are epic 02 (REQ2).

## Test plan

- **TEST1** — build: `./gradlew build` green on a clean checkout with JDK 25 —
  all four modules compile from their convention plugins.
- **TEST2** — build: `./gradlew check` green, and the boundary-check task is
  visibly among the executed tasks for `:contract`, `:mcp-server`, `:web-app`.
- **TEST3** — manual: the STEP7 tamper — a banned coordinate added to
  `:web-app` turns `check` red with an error naming the module and coordinate;
  reverting restores green. Repeat the tamper on `:contract` to confirm the
  check covers it too.
- **TEST4** — manual: `./gradlew :core-service:dependencies` shows Exposed,
  Liquibase, and both drivers resolving in `:core-service` — proof the check's
  green on the adapters reflects a boundary, not an empty graph.

Done when: a clean checkout builds with `./gradlew check` green, all four
modules exist with their real dependencies declared, and the tamper test has
demonstrated the GOAL4 boundary check failing red and returning to green.
