# Nook

**An agent-native project-management and artifact repository.**

Nook is where an AI agent and a human collaborate to take an idea from a one-line
epic to a fully-specified, ready-to-build plan — and where the resulting
documents live, versioned, for the life of the product.

Agents drive Nook over **MCP**; humans drive it through a **web UI**. Both operate
on the same two things:

- **Structure** — projects, releases, epics, tasks, statuses, and dependencies.
  Queryable like Asana/JIRA ("what's ready to work on?").
- **Documents** — manifestos, RFCs, design docs, and per-task implementation
  plans (analysis, background, approach, caveats, test plan). Versioned in git.

## The workflow Nook is built around

1. **Create an epic** — a name and a description.
2. **Author its manifesto** — work with an agent to write the epic's guiding
   document.
3. **Split the epic into tasks** — a *skill* breaks it into vertical, atomic
   tasks.
4. **Generate a plan per task** — a *skill* produces analysis, background, a
   high-level implementation approach, caveats, and a test plan. Regenerate it
   wholesale or iterate on it by hand.

Two things govern that workflow:

- **Tenets** — project-level rules the agent must honor (e.g. "never use XCTest,
  only Swift Testing"). Advisory in v1: injected into every agent's context.
  Think of a spec-kit constitution, less formal but just as present.
- **Skills** — the transforms above (epic→tasks, task→plan). Nook ships the core
  skills; a project **layers** its own conditions and refinements on top rather
  than replacing them. Tenets are the always-on layer.

## Architecture at a glance

- **Two stores, cleanly split.** A central **Postgres** database is the source of
  truth for *structure* (projects/releases/epics/tasks/status/dependencies).
  A **git repository** is the source of truth for *document content* — versioned,
  diffable, recoverable. They never own the same fact.
- **Documents are versioned, not branched.** The artifact repo moves forward
  linearly; it does not ride the code's branches. This is deliberate — it removes
  every consistency problem that comes from a global structure DB pointing at
  branch-local documents.
- **Nook is a service.** The artifact store is a hosted git remote Nook manages;
  the MCP endpoint is a network service; a single instance manages many projects.
- **The MCP server is the only sanctioned writer.** Every mutation routes through
  it so structure and documents stay in sync. Because content lives in git, drift
  is always recoverable, never corrupting.
- **The document API is editor-grade.** Agents edit sections and ranges by stable
  anchors — never read-the-whole-doc-then-overwrite-the-whole-doc.

See [ARCHITECTURE.md](./ARCHITECTURE.md) for the full technical direction and the
rationale behind each decision.

## Stack

| Layer            | Choice                                             |
| ---------------- | -------------------------------------------------- |
| Backend          | Kotlin + Ktor                                      |
| Agent interface  | Official Kotlin MCP SDK (network endpoint)         |
| Structure store  | PostgreSQL                                          |
| Document store   | Git (hosted remote), behind an `ArtifactStore` seam |
| Web UI           | React + TypeScript (strict)                         |

## Status

Pre-implementation. The architecture is settled (see `ARCHITECTURE.md`); code
does not exist yet. The v1 goal is a **shallow end-to-end** slice: every part
present — MCP, structure store, document store, skills, tenets, UI — none of them
deep.
