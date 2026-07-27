# 06 — Web UI

**Status:** Settled · **Milestone:** 4

The human surface. Deliberately the last and thinnest slice — the workflow is proven
through the API and MCP first (ARCHITECTURE.md §9, "shallow end-to-end"). This spec
settles the **basic workflows** the UI must support and the **capabilities** it exposes
in v1. Screen-level and visual detail (exact screens, layouts, board styling) is
development-time product design, not pinned down here — the same stance 07 takes on
template content.

## Decided

### Stack & data path

- **React + TypeScript (strict)**, served by the web app. (§7)
- The UI reaches structure and document content/history **only through the web app's
  RPC API** (which the core service backs with the DB and `ArtifactStore`) — the web
  app is a thin adapter and opens no store itself. (§3.3, §3.4)
- Documents are **forward-only** (no branch skew), so the UI shows the latest version
  or any chosen prior version — no branch/merge state to represent. (§3.2)

### The embedded authoring agent

- **The web app hosts an embedded authoring agent**, not just manual controls. Because
  Nook owns this harness, it comes up with the skill/tenet cache **preloaded** — base
  skills, the operate-Nook skill, project skill layers, and the project's tenets
  ([03](./03-skills-and-tenets.md)) — so it **runs skills** exactly as any agent does.
  It persists through the web app's **RPC** operations — the same op set the manual
  controls use, *not* a second trip out through MCP. So skills are triggerable from the
  web while the web app stays a thin adapter with no store access; every write lands in
  the core service (§3.3, §5).

### Basic v1 workflows

The coarse journeys the UI must support (their screen-level realization is development's
call):

1. **Project** — create a project (fresh repo or clone an existing one,
   [05](./05-project-and-ops.md)) and select/switch between projects.
2. **Browse & triage** — a project overview; list epics, releases, and tasks; filter by
   status and type; the **"what's ready to work on"** list, which is that same
   listing with the leaf types, status `todo`, and nothing blocking
   ([01](./01-interface-contracts.md)).
3. **Author a document — free-form, agent-assisted.** The generalized flow is *authoring
   a document*; a document may be any kind (manifesto, plan, RFC, design doc, tenet). The
   human either drives the embedded agent to draft it — via the matching skill
   (`author_manifesto`, `generate_task_plan`, …) — or writes it directly. **No rigid
   epic → manifesto → split → plan wizard is imposed**; that common sequence is just one
   path through a free-form capability, and the skills are entry points, not fixed steps.
4. **Read & hand-edit documents.** Documents *and* tenets are **fully editable in the
   UI** through [02](./02-document-layer.md)'s editor-grade section ops (not a read-only
   viewer) — an agent-drafted doc and a human-edited doc are the same artifact (§1).
   Version history and prior-version viewing are included.
5. **Structure edits** — create/update/cancel epics and tasks, set blockers, assign
   epics to releases, and rename, per [04](./04-structure-semantics.md). (Agent skills
   such as `split_epic` also create structure; the manual controls are the escape hatch.)

Note on documents vs. structure: a **task** is a structure entity (a row); its **plan**
is a document. Workflow 3 authors *documents*; creating the task itself is a structure op
(workflow 5, or an agent skill). A single "create" surface may blend the two, but they
remain distinct operations underneath.

## Out of scope — development-time detail

Considered and deliberately left to the build, not specced here (they carry no
architectural decision):

- **Screen inventory & layout** — the concrete set of screens and their arrangement.
- **Task-board semantics** — grouping (by status/release), how "ready" reads visually,
  dependency display.
- **Skill-run presentation** — how a run is initiated (per-epic button, chat surface,
  both), how agent progress/streaming is shown, how results are presented for review.
- **Auth-less v1 UX** — single-user assumptions; the nominal actor comes from config
  (§8).
- **Build & serve** — how the React bundle is built and served by the Ktor web app.

## Depends on / feeds

- Consumes the HTTP/RPC API from [01](./01-interface-contracts.md), the document layer
  from [02](./02-document-layer.md), the skills/tenets model from
  [03](./03-skills-and-tenets.md), and structure semantics from
  [04](./04-structure-semantics.md).
- Should stay minimal until Milestones 1–3 prove the workflow.
