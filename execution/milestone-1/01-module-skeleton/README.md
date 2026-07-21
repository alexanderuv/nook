# Epic 01 — Module skeleton

**Addresses:** REQ1 (multi-module skeleton: core service, `:mcp-server`,
`:web-app`, shared contract library), with GOAL4 as its guardrail (zero
persistence dependencies outside the core service, observed in the build
dependency graph).

**Status: done.** Executed 2026-07-21 from [plan.md](./plan.md), which was built
on [discovery.md](./discovery.md)'s layout, enforcement, and version findings.

## What landed

- Gradle **9.5.1** build at the repo root: wrapper, `settings.gradle.kts` (root
  project `nook`), version catalog at `gradle/libs.versions.toml` — the
  discovery's version matrix re-checked against Maven Central on execution day
  (one patch adopted: sqlite-jdbc 3.53.2.1), JDK 25 toolchain, group `io.nook`.
- **`build-logic` included build** with three convention plugins:
  `nook.kotlin-jvm` (toolchain, group, test setup), `nook.application` (layers
  `application` on the base), and `nook.persistence-boundary` (the GOAL4 check).
- The **four settled modules**, each with real dependencies and one placeholder
  source: `:contract` (kotlinx.serialization), `:core-service` (Ktor, Exposed,
  Liquibase, PostgreSQL + SQLite drivers, `:contract`), `:mcp-server` (Ktor,
  `:contract`, Java MCP SDK 2.0.0), `:web-app` (Ktor, `:contract`). No adapter
  holds a module edge to `:core-service`.
- The **boundary check**: a custom task walking each guarded module's resolved
  compile and runtime graphs via Gradle's `ResolutionResult` API, failing on the
  four banned persistence groups with the offending path named, wired into
  `check` for `:contract`, `:mcp-server`, and `:web-app`.

## Verification

The plan's full test plan ran green: `./gradlew build` and `check` on JDK 25,
the boundary task visibly executing for the three guarded modules, tamper tests
red-then-green on both `:web-app` and `:contract`, and `:core-service`'s graph
confirmed non-empty (Exposed, Liquibase, both drivers resolving) so the
adapters' green reflects a real boundary. An independent verification agent —
given only the plan and the deliverable files — reran the checks, reproduced the
tamper test, and returned PASS with zero deviations on both the comment-hygiene
check and the plan-conformance sweep.

## Left open (by design)

- How `:mcp-server` hosts the Java MCP SDK's servlet-based transport next to
  Ktor — epic 06's first question (discovery Q8).
- Database bring-up, SQLite behavior, and the changelog drift check — epic 02
  (discovery Q6).
