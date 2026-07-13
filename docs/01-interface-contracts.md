# 01 — Interface contracts

**Status:** Settled · **Milestone:** 1 (with 04)

How agents and humans talk to Nook: the MCP surface, the web app's RPC API, how a
connection selects its project, references, payloads, and errors. The *shape* comes
from ARCHITECTURE.md §5; this spec pins the contracts.

## Decided

### Two surfaces, one operation set

- **MCP** (agent surface) exposes the operations as **tools**, plus tenets and
  documents as **resources**, and skills additionally as **prompts** (§5).
- The **web app's RPC API** (human/UI surface) **mirrors the same operations** in
  RPC style — same capabilities, one shared set of DTOs. Mirroring is pragmatic,
  not a hard 1:1 rule: an operation is mirrored where it makes sense, and a few
  extra plain reads may be added for the UI. REST was rejected: Nook's surface is
  action-heavy and internal, so RPC keeps one contract instead of a second,
  differently-shaped one. (Appendix-style rationale in the session record.)

### Transport & project scoping

- **Transport is HTTP / streamable** for MCP in v1 (a shared, running endpoint),
  not stdio. Both apps are HTTP servers.
- **Project is bound per connection**, selected by a **`{projectRef}` path
  segment** — the MCP endpoint is mounted at `/mcp/{projectRef}` and RPC routes at
  `/api/{projectRef}/...`. Instance-level operations (create/list/get **project**)
  are unscoped: `/api/projects`, etc.
- So project-scoped tools do **not** take a `projectId` argument — it comes from the
  connection. (§3.3, §5)
- v1 is localhost, no auth (§8). stdio transport is deferred.

### Entity references

- Any epic/task/release reference accepts **either a UUID or a slug**, resolved
  within the bound project: if the string parses as a UUID, treat it as an id;
  otherwise resolve it as a slug. (Slugs are lowercase-hyphen and never collide with
  the UUID form.)

### Payloads

- Entities serialize as JSON with: `id` (UUID string), `slug`, `name`,
  `description`, `status` (where applicable), entity-specific fields (epic:
  `releaseId?`; task: `epicId`, `blockedBy: [id]`), ISO-8601 `createdAt` /
  `updatedAt`, and `createdBy` / `updatedBy`.
- create / update / get return the **full entity**; `list_*` return arrays of the
  same, **newest-first**, **no pagination** in v1 (added as a cursor later).

### Operation catalog (mirrored across MCP tools and RPC)

- **Instance-level:** `create_project`, `get_project`, `list_projects`.
- **Structure (project-scoped):** `create_epic(name, description?, releaseRef?)`,
  `update_epic(ref, {name?, description?, releaseRef?, status?})`,
  `create_task(epicRef, name, description?)`, `update_task(ref, {…, status?})`,
  `set_task_blocked_by(taskRef, blockerRefs[])` — **replaces** the task's blocker
  set (not incremental add/remove), `create_release(name, …)`,
  `assign_epic_to_release(epicRef, releaseRef?)`, `get_epic(ref)`, `get_task(ref)`,
  `list_epics(filter)`, `list_tasks(filter)`, `get_ready_tasks()`. Filter grammar
  and status rules per [04](./04-structure-semantics.md).
- **Documents:** `read_doc`, `write_doc`, `replace_section`, `insert`,
  `append_to_section`, `apply_patch`, `doc_history` — full contracts in
  [02](./02-document-layer.md).
- **Skills** (tools + prompts): `split_epic(epicRef)`,
  `generate_task_plan(taskRef)`, `author_manifesto(epicRef)` — full contracts in
  [03](./03-skills-and-tenets.md).

### Error model

- Failures return a **structured error**: on MCP, a tool result with `isError` and a
  payload `{ code, message, details? }`. Codes: `validation_failed`, `not_found`,
  `conflict` (e.g. slug collision), `cycle` (blocked-by). The RPC API maps the same
  codes to HTTP status (`400` / `404` / `409`).

### Resources (MCP)

- `nook://project/tenets` — the composed tenets for the bound project.
- `nook://epic/{ref}/manifesto`, `nook://task/{ref}/plan`, `nook://doc/{path}` —
  document reads (content semantics in [02](./02-document-layer.md)).

## Deferred (not open — intentionally later)

- stdio transport; authentication; list pagination (cursor); free-text search.

## Depends on / feeds

- Encodes the rules from [04](./04-structure-semantics.md).
- Document and skill contracts are completed in [02](./02-document-layer.md) and
  [03](./03-skills-and-tenets.md).
