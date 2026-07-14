# 05 — Project bootstrapping & ops

**Status:** Settled · **Milestone:** 2

How a project gets its artifact git repo, how storage is abstracted, how the apps
run, and how the consistency guarantees are operationally realized. Topology and
consistency principles come from ARCHITECTURE.md §3.3, §4.1; this spec settles the
mechanics.

## Decided

### Topology — a core service is the sole writer

- The **core service** owns Postgres, the git document store, and the single write
  path, and exposes an **internal RPC API** (localhost, no auth in v1). The web app
  and MCP server are **thin adapters** that call it and hold no store access. (§3.3)
- Because exactly one process touches a project's git repository, writes are
  serialized by a **simple in-process, per-project mutex** in the core service — no
  cross-process lock, no lock files. (This replaced an earlier peer-apps design that
  needed a distributed lock; see Appendix A.)

### Repo storage — a pluggable `RepoBackend`

- A project's git repository lives behind a **`RepoBackend`** interface, so the
  physical storage can be swapped: **local filesystem in v1**, object stores (S3) or
  others later. Nothing above it assumes a particular medium.
- The `ArtifactStore` (document operations, §4.2) sits on top of whatever repo the
  `RepoBackend` provides.

### Project → repo binding — new or existing

- `create_project` supports **both**:
  - **New** — the `RepoBackend` provisions a fresh repository for the project.
  - **Existing** — a source repo (by URL) is cloned into the `RepoBackend`.
- Either way, configuring a hosted **remote for sync** is a **separate step
  afterward** (deferred in v1 — see credentials below).
- On creation, an **initial commit** establishes a minimal self-describing scaffold
  (a README plus a placeholder for the project's tenets — skills are system-level
  and never live in the repo, [03](./03-skills-and-tenets.md)); the exact on-disk
  layout is [02](./02-document-layer.md)'s job.

### Consistency & recovery — the DB pointer is the authority

- Write order is fixed: **content to git, then advance the DB `current_version`
  pointer** to the new commit.
- The **DB `current_version` is the authority** for what is current. If the pointer
  write fails, the DB still names the previous commit, so the **previous version
  stays the truth** and the new commit is an unreferenced object git can
  garbage-collect. No compensating reset, no "ahead" state to reason about.
- Consequently, **reads resolve content by the DB's `current_version`, not by git
  `HEAD`**.
- **`fsck`** is an **on-demand** operation that detects and *reports* drift (DB
  pointers with missing objects, version mismatches, unreferenced commits);
  conservative — no aggressive auto-repair in v1.

### Configuration & running

- Each process is configured by **environment variables**: the core service takes
  the DB URL/credentials, the `RepoBackend` root, and its internal-API port; each
  adapter takes the core-service URL and its own port.
- A **Docker Compose** stack provides Postgres for local dev.
- Working repos are stored per project under the `RepoBackend` root; created at
  project creation and kept (not pruned) in v1.

## Deferred (not open — intentionally later)

- Hosted-remote **credentials/auth** (tokens, per project) and the remote-sync step.
- Non-filesystem `RepoBackend` implementations (S3, etc.).
- Deeper `fsck` auto-repair; repo/clone pruning; multi-machine core service.

## Depends on / feeds

- Provides the repository that [02](./02-document-layer.md) stores documents in.
- Realizes the single-writer and consistency principles of ARCHITECTURE.md §4.1.
