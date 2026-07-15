# 01 — Interface contracts

**Status:** Settled · **Milestone:** 1 (with 04)

How agents and humans talk to Nook: the MCP surface, the web app's RPC API, how a
connection selects its project, references, payloads, and errors. The *shape* comes
from ARCHITECTURE.md §5; this spec pins the contracts.

## Decided

### One operation set, three surfaces

The **core service** defines the operation set once and exposes it as an internal
RPC API; the two adapter apps translate their protocol into calls on it (§3.3). So
there are three surfaces over one contract:

- **MCP** (external-agent surface, in `:mcp-server`) exposes the operations as
  **tools**, plus tenets and documents as **resources** (§5). Skills are **not**
  exposed here — they
  are system-level, distributed to the agent's environment, and run agent-side, calling
  these tools to persist ([03](./03-skills-and-tenets.md)).
- The **web app's RPC API** (human/UI surface, in `:web-app`) **mirrors the same
  operations** in RPC style — same capabilities, one shared set of DTOs. Mirroring is
  pragmatic, not a hard 1:1 rule: an operation is mirrored where it makes sense, and
  a few extra plain reads may be added for the UI. This RPC surface also backs the web
  app's **embedded authoring agent** ([06](./06-web-ui.md)): that agent runs skills and
  persists through these same RPC operations rather than making a second trip out
  through MCP. REST was rejected: Nook's surface is action-heavy and internal, so RPC
  keeps one contract instead of a second, differently-shaped one.
- The **core service's internal RPC API** is the shared backing both adapters call;
  it is not public. The adapters hold no store access of their own.

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

- Any item/release reference accepts **either a UUID or a slug**, resolved within the
  bound project: if the string parses as a UUID, treat it as an id; otherwise resolve
  it as a slug. Slugs are unique per project across all item types, so a slug resolves
  to exactly one item. (Slugs are lowercase-hyphen and never collide with the UUID
  form.)
- A **document reference** (`docRef`) accepts **either a UUID or the document's
  path** — the path is unique per project, and it carries the item scope and (for
  fixed-name docs) the kind; creation semantics in
  [02](./02-document-layer.md).

### Payloads

- Entities serialize as JSON with: `id` (UUID string), `slug`, `name`,
  `description`, `status`, and item fields: `type` (`epic`/`task`/`bug`/`chore`),
  `parentId?` (null for a top-level item), `releaseId?` (epics), `blockedBy: [id]`
  (leaves), plus ISO-8601 `createdAt` / `updatedAt` and `createdBy` / `updatedBy`.
  (Releases serialize with `status` and `targetDate?`.)
- The **project** serializes with `id`, `slug`, `name`, `description`,
  `artifactRepoUrl?`, `ownerSubject`, and `createdAt` / `updatedAt` / `createdBy` /
  `updatedBy`. `ownerSubject` is the subject that owns the project — the tenancy root
  (§8, [08](./08-deployment-and-cloud.md)). It is **server-populated and read-only in
  v1**: the core sets it to the connection's asserted subject, or to the configured
  local default (`system`) on a single-user instance — it is *not* a `create_project` /
  `update_project` input. (It becomes user-assignable only when accounts arrive.)
- `createdBy` / `updatedBy` (and `ownerSubject`) are **subject** strings — a stable
  sign-in identity (an OIDC `sub` behind the edge gate, or the local default) — not
  display names; §8.
- A **document** serializes with `id`, `kind`, `seq?` (the per-project citation
  number, numbered kinds only — [02](./02-document-layer.md)), `name`, `title?`,
  `path`, `itemId?` (null for project-level docs), `currentVersion?`, and the
  audit fields. Document *content* is never embedded in the entity — it travels
  through the document operations.
- create / update / get return the **full entity**; `list_*` return arrays of the
  same, **newest-first**, **no pagination** in v1 (added as a cursor later).

### Operation catalog (mirrored across MCP tools and RPC)

- **Instance-level:** `create_project`, `get_project`, `list_projects`.
- **Structure (project-scoped):** `create_item(type, name, description?, parentRef?, releaseRef?)`
  — `type` is `epic`/`task`/`bug`/`chore`; for a leaf, an omitted `parentRef` makes a
  project-level item, and `releaseRef` applies to epics,
  `update_item(ref, {name?, description?, status?, type?, parentRef?, releaseRef?})` —
  setting/clearing `parentRef` reparents a leaf,
  `set_item_blocked_by(itemRef, blockerRefs[])` — **replaces** the item's blocker set
  (not incremental add/remove), `create_release(name, …)`,
  `assign_epic_to_release(epicRef, releaseRef?)`, `get_item(ref)`,
  `list_items(filter)`, `get_ready_items()`. Filter grammar and containment/status
  rules per [04](./04-structure-semantics.md).
- **Documents:** `read_doc`, `doc_outline`, `write_doc` (takes `kind` when
  creating a `docs/`-area document), `replace_section`, `prepend_to_section`,
  `append_to_section`, `apply_patch`, `doc_history` — full contracts in
  [02](./02-document-layer.md).
- **Skills** are **not** operations in this catalog. `split_epic`,
  `generate_task_plan`, and `author_manifesto` are local skills an agent runs — an
  external MCP client, or the web app's embedded authoring agent
  ([06](./06-web-ui.md)) — achieving their effect by calling the structure and
  document operations above (over MCP tools or the mirrored RPC, respectively; e.g.
  `split_epic` drives repeated `create_item` calls). See
  [03](./03-skills-and-tenets.md).

### Error model

- Failures return a **structured error**: on MCP, a tool result with `isError` and a
  payload `{ code, message, details? }`. Codes: `validation_failed`, `not_found`,
  `conflict` (e.g. slug collision), `cycle` (blocked-by). The RPC API maps the same
  codes to HTTP status (`400` / `404` / `409`).

### Resources (MCP)

- `nook://project/tenets` — the bound project's canonical tenets, and the **pull
  surface** an external agent uses to refresh its local copy (Nook stamps the current
  version on operation responses so the agent knows when to re-pull; the web app's own
  agent is preloaded and refreshed in-process instead — [03](./03-skills-and-tenets.md)).
- `nook://item/{ref}/manifesto`, `nook://item/{ref}/plan`, `nook://doc/{path}` —
  document reads (content semantics in [02](./02-document-layer.md)).

## Deferred (not open — intentionally later)

- stdio transport; authentication; list pagination (cursor); free-text search.

## Depends on / feeds

- Encodes the rules from [04](./04-structure-semantics.md).
- Document and skill contracts are completed in [02](./02-document-layer.md) and
  [03](./03-skills-and-tenets.md).
