# 05 — Project bootstrapping & ops

**Status:** Outline · **Milestone:** 2

Load-bearing plumbing currently hand-waved: how a project gets its artifact git
repo, how the apps are configured and run, and how the consistency guarantees are
operationally realized. The *topology* is settled (ARCHITECTURE.md §3.3, §4.1); the
*mechanics* are open.

## Decided

- The `ArtifactStore` is git-backed; syncing to a hosted remote (GitHub/GitLab) is a
  configurable behavior; no remote → local-only for dev/test. (§3.3)
- Web app and MCP server are separate peer apps embedding a shared `:core`; the
  single write path lives in `:core`; the git clone is serialized behind a
  write-lock. (§3.3, §4.1)
- Consistency: DB-then-nothing has no cross-store transaction; drift is git-
  recoverable; a `fsck`/reindex reconciles. Write order: content to git, then the
  `(path, version)` pointer to the DB. (§4.1)
- v1 is single-user, localhost, no auth; mutations carry a nominal actor. (§8)

## Open decisions

- [ ] **Project → artifact-repo binding** — on `create_project`, how is the repo
      obtained: created on the host (GitHub/GitLab API), an existing repo attached,
      or a local repo initialized? What `artifact_repo_url` holds.
- [ ] **Initial repo structure** — what's committed when a project is created (an
      empty tree? a scaffold of `skills/`, `tenets/`, `epics/`?).
- [ ] **Remote credentials/auth** — how Nook authenticates to the git host (tokens,
      per-project), and where that config lives. (v1 may be local-only.)
- [ ] **Clone management** — where working clones live on disk, one per project,
      lifecycle (created on first use? pruned?).
- [ ] **Write-lock design** — the concrete cross-process lock (lock file vs. Postgres
      advisory lock), scope (per project), and timeout/retry behavior.
- [ ] **`fsck`/reconciliation** — when it runs (startup? on demand?), what it checks,
      and how it repairs (adopt orphan files? flag missing?).
- [ ] **App configuration** — the config surface for each app (DB URL, repo roots,
      project selection) and how they're launched (Compose for dev Postgres).
- [ ] **Failure recovery** — what happens if the git write succeeds but the DB write
      fails, or vice versa.

## Depends on / feeds

- Provides the repo that **02** stores documents in.
- The write-lock realizes the single-writer guarantee from §4.1.
