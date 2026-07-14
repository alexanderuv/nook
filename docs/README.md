# Nook design docs

This folder holds the **detailed design specs** — one per area of the system.
[`../ARCHITECTURE.md`](../ARCHITECTURE.md) is the top-level overview and the record
of settled decisions; the documents here go a level deeper, turning that
architecture into buildable requirements.

Each spec separates two things explicitly:

- **Decided** — settled, with a link back to the architecture section it comes from.
- **Open decisions** — the questions still to resolve (a checklist). These get
  grilled out and moved into "Decided" as they're settled. A spec is not "done"
  until its open list is empty.

The principle: *when the requirements are clear, writing the code is easy.* We
finish the requirements first.

## The specs

| # | Spec | Covers | Status |
|---|------|--------|--------|
| [01](./01-interface-contracts.md) | Interface contracts | MCP tool signatures & payloads, project selection, the web HTTP API | **Settled** |
| [02](./02-document-layer.md) | Document layer | Editor-grade edit API, artifact-repo on-disk layout, attachment policy | **Settled** |
| [03](./03-skills-and-tenets.md) | Skills & tenets | Skills/tenets storage, distribution & invocation; the operate-Nook skill; tenet scope | **Settled** |
| [04](./04-structure-semantics.md) | Structure semantics | Status transitions, slug rules, query/filter model, cycle prevention | **Settled** |
| [05](./05-project-and-ops.md) | Project bootstrapping & ops | Artifact-repo provisioning, config & deployment, write-lock & reconciliation | **Settled** |
| [06](./06-web-ui.md) | Web UI | Basic human-surface workflows & v1 capabilities (screen detail is dev-time) | **Settled** |
| [07](./07-document-templates.md) | Document templates | Why template content is a development-time concern; mechanism rides 02 + 03 | **Deferred** |
| [08](./08-deployment-and-cloud.md) | Deployment & cloud | Running Nook on the internet reachable-from-anywhere, and staying open to productizing | **Direction set** |

## Build order — what we're aiming for first

The specs are numbered by *area*, not by build sequence. The intended build
sequence is by milestone, smallest-provable-loop first:

- **Milestone 1 — Structure.** Projects, epics, tasks manipulable via MCP and HTTP,
  backed by Postgres. Requires specs **04** (structure semantics) and **01**
  (contracts). This is the first thing we build.
- **Milestone 2 — Documents.** Attach and version documents (manifesto, plan) via
  the `ArtifactStore` and the editor-grade API. Requires **05** (repo
  provisioning) and **02** (document layer).
- **Milestone 3 — Workflow.** Skills (`split_epic`, `generate_task_plan`) and
  tenets on top of documents. Requires **03**; template *content* (**07**) is settled
  in development, not designed up front.
- **Milestone 4 — Human surface.** The web UI. Requires **06**.

So the design order that unblocks the most, soonest:
**04 → 01 → 05 → 02 → 03 → 06** (07 is deferred to development).
The product's real value lives in **02** and **03**; they come after the foundation
they stand on, but they are where the effort should ultimately concentrate.

## Related

- [`../ARCHITECTURE.md`](../ARCHITECTURE.md) — overview, principles, decision record.
- [`../db/`](../db/) — the structure schema (Liquibase changelog).
