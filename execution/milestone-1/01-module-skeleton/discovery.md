# Module skeleton build approach

## Summary

- The build layout is settled by current official guidance: convention plugins
  in a `build-logic` included build, a version catalog, and exactly the four
  settled modules (`:contract`, `:core-service`, `:mcp-server`, `:web-app`) —
  no source supports splitting further at skeleton stage.
- GOAL4 ("0 persistence dependencies outside core") is not self-enforcing:
  Gradle module boundaries never *fail* when a banned dependency is added
  later, and the popular architecture-test tools check source or bytecode
  references, not the build dependency graph the goal names. A small custom
  Gradle task walking each module's resolved graph, wired into `check`, is the
  one mechanism that observes exactly the stated criterion.
- Because the adapters call the core over HTTP and hold no module edge to
  `:core-service`, the check can take its strictest form: the *entire* runtime
  graph of `:contract`, `:mcp-server`, and `:web-app` must be free of
  persistence coordinates.
- A mutually compatible version set exists as of 2026-07-15 (Kotlin 2.4.10,
  JDK 25, Gradle 9.5.x — held one line back to stay fully on-matrix — Ktor
  3.5.1, Exposed 1.3.1, Liquibase 5.0.3, drivers current).
- The MCP server SDK is the one stack pick that changed: the Kotlin SDK proved
  pre-1.0 with live conformance bugs on its streamable-HTTP server, while the
  separate official **Java** MCP SDK is GA (2.0, June 2026), tracks the current
  spec, runs the official conformance suite in CI, and is directly usable from
  Kotlin — `:mcp-server` uses the Java SDK, retiring the PRD's feasibility risk
  rather than mitigating it.

## Questions

- **Q1** — What Gradle multi-module layout (module set, build-logic
  organization, dependency versioning) realizes the settled
  core-plus-thin-adapters topology?; informs: REQ1, the skeleton this epic
  commits.
- **Q2** — What mechanism observes GOAL4 — zero database/persistence
  dependencies outside the core service — in the build dependency graph,
  failing the build on violation?; informs: GOAL4's verification, wired in
  from the first commit.
- **Q3** — Which versions of Kotlin, JDK, Gradle, Ktor, Exposed,
  kotlinx.serialization, Liquibase, the JDBC drivers, and the Kotlin MCP SDK
  are current and mutually compatible?; informs: the version pins the skeleton
  commits to, and the PRD's MCP-SDK feasibility risk.
- **Q4 (emerged)** — Do the schema-portability assumptions (SQLite for tests,
  Postgres at runtime) hold at the library level in Exposed and Liquibase?;
  informs: epic 02 (REQ2) and the PRD's dual-engine assumption.
- **Q7 (emerged)** — Is a more mature official MCP server SDK available on
  another platform and usable from a Kotlin service?; informs: the
  `:mcp-server` dependency choice (REQ6) and the PRD's feasibility risk.
  Asked after FIND7 *confirmed* that risk — a confirmed risk on a chosen
  dependency puts that dependency's alternatives inside the question.

Bound: survey only — official documentation, release metadata, published
artifacts (Gradle `.module` files and jar bytecode inspected directly), and
prior-art repositories; no prototype built. That sufficed because the epic
commits a layout and version pins, both cheap to adjust until code lands on
them; the two things only running code can answer are recorded as open
questions.

## Method

Three parallel research sweeps, run 2026-07-15:

- **Layout (Q1)** — Gradle's official best-practices and build-structure docs
  (version 9.6.1), Ktor's official application-structure docs and generator
  guidance, and four open-source multi-module Kotlin/JVM repos — the official
  [`ktorio/ktor-chat`](https://github.com/ktorio/ktor-chat) sample, the
  official Ktor full-stack KMP template, and community repos
  [`csieflyman/multi-projects-architecture-with-Ktor`](https://github.com/csieflyman/multi-projects-architecture-with-Ktor)
  and
  [`mobiletoly/ktor-hexagonal-multimodule`](https://github.com/mobiletoly/ktor-hexagonal-multimodule)
  — compared on module set, contract placement, and build-logic organization.
- **Enforcement (Q2)** — candidate-by-candidate evaluation: Gradle module
  boundaries alone, Konsist, ArchUnit, `dependency-analysis-gradle-plugin`,
  Gradle built-in dependency verification, the Kordamp enforcer plugin,
  `modules-graph-assert`, `restrict-imports-enforcer-rule`, and a custom task
  over Gradle's `ResolutionResult` API. Axes: does it observe the *dependency
  graph* (versus source text or bytecode references), can it fail the build,
  and maintenance health (last release, recent activity, adoption).
- **Versions (Q3, Q4)** — Maven Central metadata and GitHub releases for exact
  versions and dates; published `.module` files and jar bytecode majors read
  directly (never recalled from memory) for compatibility floors; the MCP
  SDK's release notes and open-issue tracker for streamable-HTTP status.

A fourth sweep was added 2026-07-16, after FIND7 confirmed the PRD's SDK
risk:

- **SDK alternatives (Q7)** — all ten official MCP SDK repos compared on GA
  status, spec currency, release cadence, streamable-HTTP server support,
  maintainer, and adoption (GitHub API, npm/PyPI/NuGet/crates registry data,
  README feature lists).

Not done: nothing was built or run — MCP SDK behavior, Liquibase-on-SQLite,
and the Exposed dual-engine assumption were judged from documentation and
issue trackers only. A few cited pages were read as search excerpts rather
than in full; findings resting on those are marked suggestive.

## Findings

### FIND1 — Convention plugins in a `build-logic` included build, plus a version catalog, are the current official Gradle layout

**Confidence:** solid — stated by Gradle's own current best-practices docs
(9.6.1) and confirmed as ecosystem default in official samples · answers Q1

- Gradle's [best-practices guide](https://docs.gradle.org/current/userguide/best_practices_structuring_builds.html)
  explicitly prefers an included build (e.g. `build-logic`) over `buildSrc`
  for build logic: standard dependency resolution, fewer whole-build
  invalidations, independently developable.
- Cross-project configuration via `allprojects {}` / `subprojects {}` is
  officially labeled an "improper way to share build logic"
  ([sharing build logic](https://docs.gradle.org/current/userguide/sharing_build_logic_between_subprojects.html));
  convention plugins are the prescribed replacement. Gradle 9.0 (Aug 2025,
  Java 17+, configuration cache preferred) strengthens this: cross-config
  fights project isolation.
- Version catalogs (`gradle/libs.versions.toml`) have been stable since Gradle
  7.4 and are auto-imported; they are the assumed default in modern official
  samples, and Ktor now publishes a consumable catalog
  (`io.ktor:ktor-version-catalog`).

### FIND2 — Prior art converges on a shared contract module plus thin app modules; nothing supports splitting the core further at skeleton stage

**Confidence:** solid for the pattern (official samples), suggestive for the
"don't split further" (practitioner consensus; official docs silent on timing)
· answers Q1

- The closest official analog, [`ktorio/ktor-chat`](https://github.com/ktorio/ktor-chat)
  (active through 2026), uses `core` (shared domain objects/DTOs), `db`
  (Exposed, isolated — only server modules touch it), `server/*`, and thin
  `app/*` clients — the same shape as Nook's settled contract/core/adapters
  split.
- [Ktor's application-structure docs](https://ktor.io/docs/server-application-structure.html)
  endorse Gradle multi-module builds for infrastructure isolation as projects
  grow; the official full-stack template keeps serializable models in a shared
  `core` module.
- No source argues for pre-splitting a core into domain-vs-persistence modules
  at skeleton stage; 2025–2026 practitioner writing says split on observed
  pressure, not intuition. The strongest day-one split the evidence supports
  is exactly the one already settled: contract vs core vs adapter apps.

### FIND3 — Gradle module boundaries alone cannot assert GOAL4

**Confidence:** solid — official Gradle java-library docs on configuration
semantics · answers Q2

- `implementation` dependencies are hidden from consumers' *compile*
  classpath but "are still exposed to consumers at runtime"
  ([java-library plugin docs](https://docs.gradle.org/current/userguide/java_library_plugin.html)) —
  so a module edge to a persistence-carrying module puts Exposed/JDBC on the
  consumer's runtime graph regardless of declaration style.
- Structure has no tamper resistance: adding
  `implementation("org.jetbrains.exposed:…")` to an adapter or the contract
  library later fails nothing. GOAL4 says "observed in the build dependency
  graph" — structure expresses that graph but never *asserts* it, so a
  verification layer is warranted.

### FIND4 — Source-level architecture tools check code references, not the dependency graph — and the Kotlin-native one has stalled

**Confidence:** solid on mechanisms (each tool's own docs); suggestive on
severity of the gaps · answers Q2

- **Konsist** (Apache-2.0) runs as unit tests and scopes per module, but is
  built on the Kotlin compiler's PSI — it analyzes *source imports*, so a
  banned artifact merely present on a classpath is invisible to it. Releases
  have stalled: latest is 0.17.3 (Dec 2024, 19 months ago); the announced
  1.0.0-Beta1 never shipped; last confirmed commit Jan 2026.
- **ArchUnit** 1.4.2 (Apache-2.0, active) analyzes *bytecode references* —
  again a proxy for, not an observation of, the build graph — with documented
  Kotlin friction (synthetic-class exclusion,
  [TNG/ArchUnit#854](https://github.com/TNG/ArchUnit/issues/854)).
- `restrict-imports-enforcer-rule` (active) bans source imports;
  `modules-graph-assert` (active) asserts project-module edges only, not
  external Maven coordinates. All can fail the build; none observes what GOAL4
  names.

### FIND5 — Only a Gradle-side resolved-graph check observes GOAL4's criterion literally; the off-the-shelf version is stale

**Confidence:** solid on the Gradle API; weak on Kordamp's longevity · answers
Q2

- `Configuration.getIncoming().getResolutionResult()`
  ([API docs](https://docs.gradle.org/current/javadoc/org/gradle/api/artifacts/result/ResolutionResult.html))
  exposes the full resolved dependency graph per configuration, walkable
  without downloading artifacts; a small task that fails on banned group IDs
  (`org.jetbrains.exposed`, `org.postgresql`, `org.xerial`, `org.liquibase`)
  and is wired into `check` fails the build on exactly the stated criterion.
- The one off-the-shelf equivalent, the
  [Kordamp enforcer plugin](https://kordamp.org/enforcer-gradle-plugin/)'s
  `BannedDependencies` rule (a port of Maven's canonical
  `bannedDependencies`), is functionally exact but weakly maintained: last
  release v0.14.0 (Aug 2024), 27 stars.
- The `dependency-analysis-gradle-plugin` gives declaration *advice* (unused
  deps, api-vs-implementation) with no banned-coordinates feature found;
  Gradle's built-in "dependency verification" is checksum/signature
  provenance — both answer different questions.

### FIND6 — A mutually compatible version set exists as of 2026-07-15

**Confidence:** solid — versions from Maven Central metadata and GitHub
releases; compatibility floors measured from published `.module` files and jar
bytecode · answers Q3

| Component | Pin | Released | Note |
| --- | --- | --- | --- |
| Kotlin | 2.4.10 | 2026-07-14 | KGP fully-supported Gradle window ends at 9.5.0 |
| JDK | 25 (LTS) | 2025-09 | Highest floor in the stack is Liquibase 5.x: Java 17 (measured) |
| Gradle | 9.5.x | — | Latest is 9.6.1 (2026-06-26); pinned one line back to stay inside Kotlin 2.4.10's fully-tested window (see below) |
| Ktor | 3.5.1 | 2026-06-29 | ≥ 3.5.1 carries the Kotlin 2.4 compiler-plugin fixes |
| Exposed | 1.3.1 | 2026-07-01 | 1.x is the stable line: API-stability guarantee since 1.0.0 (2026-01-22) |
| kotlinx.serialization | 1.11.0 | 2026-04-09 | Compiler-plugin version is the Kotlin version by definition |
| Liquibase OSS | 5.0.3 | 2026-05-15 | 5.0 unbundled drivers from the CLI; irrelevant when consumed as a Gradle dep |
| PostgreSQL JDBC | 42.7.13 | 2026-07-06 | |
| SQLite JDBC (xerial) | 3.53.2.0 | 2026-06-04 | Exposed requires ≥ 3.45.0.0 |
| Java MCP SDK | 2.0.0 | 2026-06-11 | Replaces the Kotlin SDK pick — see FIND7 and FIND9 |

One off-matrix pairing: Kotlin 2.4.10 lists Gradle 9.5.0 as its max fully
supported version, and Gradle 9.6.1 lists Kotlin 2.4.0-RC as its max tested —
each is one notch past the other's window; everything else pairs cleanly on
JDK 25.

### FIND7 — The Kotlin MCP SDK supports a streamable-HTTP server, but it is pre-1.0 with live conformance bugs on that path

**Confidence:** solid — release notes, published module metadata, and issue
tracker of
[modelcontextprotocol/kotlin-sdk](https://github.com/modelcontextprotocol/kotlin-sdk)
· answers Q3

- The server-side streamable-HTTP transport exists: `StreamableHttpServerTransport`
  shipped around 0.8.3 (Jan 2026; exact first release inferred), with Ktor
  routing extensions `mcpStreamableHttp()` / `mcpStatelessStreamableHttp()` by
  0.9.0 (Mar 2026).
- It is a normal (api-visible) dependency on Ktor 3.4.3, not an embedded
  server — it resolves alongside an app's Ktor 3.5.1. Built against Kotlin
  2.3.21 (consumable from 2.4.x); Java 11 bytecode floor (measured).
- Maturity: ~monthly releases, breaking changes flagged in most minors (0.8.3,
  0.9.0, 0.13.0); 66 open issues, ~10 touching streamable HTTP, including
  server-path conformance bugs — wrong error codes for malformed requests
  (#830, #886), JSON-RPC id omitted on rejects (#866), GET-SSE reconnect
  failure (#715), protocol-version mismatch accepted (#766).
- Since 0.13.0, DNS-rebinding protection defaults ON with a
  localhost-only allowlist — which matches Nook's localhost-bound v1 as-is.

### FIND8 — The dual-engine assumption holds at the library level, with named per-engine caveats

**Confidence:** suggestive — official docs and issue trackers read as
excerpts, nothing executed · answers Q4

- Exposed 1.x officially supports both PostgreSQL and SQLite; known caveats:
  sqlite-jdbc ≥ 3.45.0.0 required, `upsert()` options differ per engine,
  `replace()` throws on PostgreSQL.
- Liquibase's SQLite support is community-tier: `addPrimaryKey`,
  `modifyDataType`, `setColumnRemarks`, and `addUniqueConstraint` change types
  are unsupported or problematic (consequences of SQLite's limited
  `ALTER TABLE`). The existing changelog's plain-SQL discipline reduces but
  does not eliminate exposure — untested until run.

### FIND9 — The official Java MCP SDK is GA and current; the Kotlin SDK sits in the bottom maturity tier

**Confidence:** solid — GitHub API, release notes, and package-registry data,
fetched 2026-07-16 · answers Q7

- The [Java SDK](https://github.com/modelcontextprotocol/java-sdk)
  (`io.modelcontextprotocol.sdk:mcp`, co-maintained with the Spring AI team)
  reached 1.0 GA 2026-02-23 and **2.0 GA 2026-06-11**, tracks the current spec
  revision (2025-11-25), ships the streamable-HTTP **server** transport in its
  core module as a plain Jakarta Servlet component (no Spring required), and is
  the only SDK repo running the official MCP conformance test suite in CI.
- Cross-SDK picture: TypeScript and Python are the reference implementations
  and most mature overall (stable since 2024; adoption two orders of magnitude
  above the rest); C# (Microsoft), Go (Google), and Rust are all GA. The
  Kotlin SDK ranks in the bottom tier with Swift/PHP/Ruby — pre-1.0, breaking
  minors, elicitation docs marked TODO, no documented auth story.
- A Java library is directly consumable from Kotlin (builder-style API, sync
  and async variants). Trade-offs: no Kotlin-idiomatic DSL, and no Ktor
  integration — the transport is servlet-hosted, so `:mcp-server`'s embedded
  server engine becomes an integration choice (Q8).

## Implications & recommendation

- **Lay the build out as convention plugins in a `build-logic` included build
  with a `libs.versions.toml` catalog** (FIND1) — it is the current official
  form, avoids `buildSrc` whole-build invalidations and discouraged
  cross-configuration, and matches the samples the skeleton will crib from.
- **Create exactly the four settled modules — `:contract`, `:core-service`,
  `:mcp-server`, `:web-app` — and split nothing else yet** (FIND2) — prior
  art's strongest day-one split is precisely this one; a domain-vs-persistence
  split inside the core has no evidence behind it at this stage.
- **Enforce GOAL4 with a small custom Gradle task walking each module's
  resolved graph, wired into `check`** (FIND3, FIND4, FIND5) — it is the only
  mechanism that observes the build dependency graph GOAL4 names and fails the
  build on it; the off-the-shelf equivalent is stale and the architecture-test
  tools watch the wrong layer. Assert the strict form: the *entire* runtime
  and compile graphs of `:contract`, `:mcp-server`, and `:web-app` contain no
  banned persistence coordinates — affordable because the adapters reach the
  core over HTTP and carry no module edge to it.
- **Pin the FIND6 set in the version catalog, on a JDK 25 toolchain, with
  Gradle held at 9.5.x** (FIND6) — staying one line behind Gradle's latest
  keeps every pairing inside its fully-tested window; move to 9.6+ when the
  Kotlin compatibility matrix catches up, not before.
- **Use the Java MCP SDK (2.0.x) in `:mcp-server`, not the Kotlin SDK**
  (FIND7, FIND9) — GA against the current spec with conformance tests in CI,
  and directly consumable from Kotlin; this retires the PRD's feasibility risk
  instead of mitigating it. Keep the dependency quarantined in `:mcp-server`;
  how its servlet-based transport gets hosted is epic 06's first integration
  question (Q8), and the MCP-first vertical slice stays worthwhile as the
  proof of that integration.

## Limitations

- **Survey-only; the chosen Java SDK's streamable-HTTP server was never run**
  — at risk: GOAL1's north-star path, and the servlet-transport-in-a-Ktor-shop
  integration (Q8); would raise confidence: a time-boxed echo-tool spike
  against the pinned SDK.
- **The version matrix is a 2026-07-15 snapshot of monthly-moving parts** — at
  risk: stale pins by the time the catalog is committed; would raise
  confidence: re-checking Maven Central at skeleton commit.
- **Dual-engine behavior judged from docs and issue trackers, never executed**
  — at risk: REQ2's SQLite-as-test-engine assumption; would raise confidence:
  applying the existing changelog to SQLite.
- **No production-grade open-source repo with this exact topology surfaced** —
  layout evidence comes from official samples and templates; at risk:
  layout unknowns that only show under real growth; would raise confidence:
  revisiting the layout once milestone 1 is built on it.

## Open questions

- **Q6** — Does the existing Liquibase changelog apply cleanly on SQLite's
  community-tier support?; matters because: REQ2 makes SQLite the test engine;
  would take: running the changelog against SQLite at epic 02's bring-up.
- **Q8** — How does `:mcp-server` host the Java SDK's servlet-based
  streamable-HTTP transport (embedded Jetty/Tomcat vs a Ktor engine), given
  the rest of the backend is Ktor?; matters because: REQ6's transport rides
  it; would take: the epic 06 echo-tool spike. (Q5, which asked whether the
  *Kotlin* SDK's server conforms well enough, is retired — superseded by the
  switch to the Java SDK, FIND9.)
