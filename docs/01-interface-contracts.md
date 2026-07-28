# 01 — Interface contracts

**Status:** Settled · **Milestone:** 1 (with 04)

How agents and humans talk to Nook: the MCP surface, the web app's RPC API, how a
connection selects its project, references, payloads, and errors. The *shape* comes
from ARCHITECTURE.md §5; this spec pins the contracts.

## Decided

### One operation set, one wire shape

The **core service** defines the operation set once and exposes it as an internal
RPC API; the two adapter apps reach everything through it (§3.3). That API speaks
**JSON-RPC 2.0** ([ADR-2](../architecture/adrs/adr-2.md)) — `method` names the
operation, `params` carries the project and the arguments, a call answers with
`result` or with `error {code, message, data?}`, and every reply comes back under
one HTTP status. Nook has **no wire shape of its own**: the request and reply are
the standard's, and the shared contract library defines only the operations'
parameters and entities.

- The **core service's internal RPC API** is the shared backing both adapters call;
  it is not public, and the adapters hold no store access of their own.
- The **web app's RPC API** (human/UI surface, in `:web-app`) **serves that same
  shape** rather than a second one of its own: it forwards a call to the core and
  hands back the reply, adding the access gate, HTTPS, and the UI in front of it —
  not a translation. So there is one contract to learn, one place a shape changes,
  and no second design to keep in step. A few extra plain reads may be added for the
  UI; they are ordinary operations in the same shape. This surface also backs the
  web app's **embedded authoring agent** ([06](./06-web-ui.md)): that agent runs
  skills and persists through these same operations rather than making a second trip
  out through MCP. REST was rejected for the same reason the second shape now is:
  Nook's surface is action-heavy, so RPC keeps one contract instead of a second,
  differently-shaped one. What serves that shape is JSON-RPC 2.0 rather than an
  envelope of Nook's own — the published standard for exactly this arrangement,
  and the one `:mcp-server` already speaks, MCP being defined on it
  ([ADR-2](../architecture/adrs/adr-2.md)).
- **MCP** (external-agent surface, in `:mcp-server`) is the one translation that
  cannot be avoided, because its shape is dictated by someone else's specification:
  the **seven project-scoped operations** become **tools**, and tenets and documents
  become **resources** (§5). It serves a *part* of the catalog, not all of it — the
  four instance-level operations are web-only (see project scoping below). Skills
  are **not** exposed here — they are system-level, distributed to the agent's
  environment, and run agent-side, calling these tools to persist
  ([03](./03-skills-and-tenets.md)).

> The web API first mirrored the operations in a shape of its own — the project in
> a path segment, the outcome in an HTTP status number — which meant two designs
> over one catalog: two places to change a field, two ways for one failure to
> arrive, and a second contract for the UI to be written against. Serving the core's
> own shape removes the second design rather than keeping two in step. What it gives
> up is HTTP-native semantics on the web API, which the error model below takes up.
> A second reversal followed: that shared shape was at first an envelope designed
> here, with an `outcome` field naming one of three endings. It is now JSON-RPC 2.0,
> which specifies the same arrangement and is already in the repo
> ([ADR-2](../architecture/adrs/adr-2.md)).

### Transport & project scoping

- **Transport is HTTP / streamable** for MCP in v1 (a shared, running endpoint),
  not stdio. Both apps are HTTP servers.
- **MCP binds a project per connection**, selected by a **`{projectRef}` path
  segment**: the endpoint is mounted at `/mcp/{projectRef}`. So project-scoped tools
  do **not** take a `projectId` argument — it comes from the connection, and the MCP
  server supplies it when it calls the operation. (§3.3, §5)
- **Projects themselves are not on the MCP surface.** None of `create_project`,
  `get_project`, `list_projects`, `delete_project` is a tool: a project is created
  and disposed of by a person on the web surface, and an agent is handed one to work
  in. So an agent cannot reach past the project its connection names, and cannot
  name a project anywhere — the connection is the only thing that says which one
  applies. Project slugs never change ([04](./04-structure-semantics.md)), so the
  address an agent client is configured with keeps naming the same project.
- **The connection tells the agent which project it is for.** A client's
  configuration is not something the agent itself reads, so on opening a connection
  the MCP server resolves the `{projectRef}` through `get_project` and reports that
  project's id, slug, name and description to the client. The agent can therefore
  say what it is working on without a tool for it. The server holds the resolved
  **id** for the life of the connection, since a deleted project frees its slug for
  a later one to take.
- **No clear project means no connection.** If the path names a project that does
  not exist, names none at all, or the core cannot be reached to resolve it, the
  **opening exchange fails** — so the client reports the server as unavailable and
  the agent sees no Nook tools, rather than a working-looking server whose every
  call fails. A misconfigured address is therefore visible where it was written,
  not as tool errors mid-session; the answer names the project that was asked for,
  which is what makes an unexpanded configuration placeholder self-diagnosing.
- **The web API names the project inside the request**, exactly as the core's
  connection does, and serves every operation at one address. It is not
  path-scoped — there is no `/api/{projectRef}/…` — and the four instance-level
  operations name no project at all.
- v1 is localhost, no auth (§8). stdio transport is deferred.

### Entity references

- Any item/release reference accepts **either a UUID or a slug**, resolved within the
  bound project: if the string parses as a UUID, treat it as an id; otherwise resolve
  it as a slug. Slugs are unique per project across all item types, so a slug resolves
  to exactly one item. (Slugs are lowercase-hyphen and never collide with the UUID
  form.)
- A **document reference** (`docRef`) accepts **either a UUID or the document's
  path** — the path is unique per project, and it carries the item scope and the
  kind (filenames are kind-named: `plan.md`, `discovery.md`, `rfc-3.md`);
  creation semantics in [02](./02-document-layer.md).

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

### Operation catalog (one set, reached over the web API, its project-scoped part as MCP tools)

Eleven operations: four acting on the whole instance, seven inside one project. All
eleven are reachable over the web API; the seven project-scoped ones are also the
MCP tool surface.

- **Instance-level (web API only):** `create_project`, `get_project`,
  `list_projects`, `delete_project(ref)` — the last removes the project and
  everything inside it. A project's slug is fixed at creation
  ([04](./04-structure-semantics.md)); `update_project` is not in the catalog.
- **Structure (project-scoped):** `create_item(type, name, slug?, description?, parentRef?, releaseRef?)`
  — `type` is `epic`/`task`/`bug`/`chore`; for a leaf, an omitted `parentRef` makes a
  project-level item, and `releaseRef` applies to epics,
  `update_item(ref, {name?, slug?, description?, status?, type?, parentRef?, releaseRef?, blockedBy?})`,
  `create_release(name, slug?, description?, targetDate?)`,
  `update_release(ref, {name?, slug?, description?, status?, targetDate?})`,
  `get_item(ref)`, `list_items(filter)`,
  `delete_item(ref)` — removes the row, an epic's children, and the documents
  attached to any of them; returns nothing, since nothing is left to return.
  Filter grammar, containment/status rules, and what a delete reaches per
  [04](./04-structure-semantics.md).

`update_item` is the one way an item changes, whatever the field, and both updates
are **JSON Merge Patch (RFC 7396)** ([ADR-3](../architecture/adrs/adr-3.md)): a
field left out is left alone, a field carrying a value is set, a field carrying
`null` is cleared, and an array replaces rather than merges. Two deviations are
Nook's: a field that must always hold a value (`name`, `slug`, `status`, `type`)
refuses `null` instead of clearing, and a field the operation does not define is
refused rather than ignored. So supplying `slug` is the rename (a name change
alone never re-derives the slug); setting or clearing `parentRef` reparents a
leaf; setting or clearing `releaseRef` puts an epic in a release or takes it out;
and `blockedBy` **replaces** the item's whole blocker set rather than adding to
it — the standard's array rule, not a Nook one. Both deletes are permanent: nothing is
marked, and no argument, filter, or operation can ask for what is gone.

> Three operations were folded away after the write and read paths were first
> built: `assign_epic_to_release` and `set_item_blocked_by` became the
> `releaseRef` and `blockedBy` fields of `update_item`, and `get_ready_items`
> became a combination of ordinary filter parts on `list_items`. The first two
> were already fields on `create_item`, so the catalog was saying that a release
> is a field at creation and an operation at update; the third was a compound
> notion — leaf, `todo`, nothing holding it up — that the filter can express by
> composition once it can ask about blockers at all. Folding the third bought
> something the operation could not offer: the same question narrowed to one
> epic, by adding the parent part to the same call.
- **Documents:** `read_doc`, `doc_outline`, `write_doc` (creates by scope +
  kind — document paths are derived, never chosen; replaces by `docRef`),
  `replace_section`, `prepend_to_section`, `append_to_section`, `apply_patch`,
  `doc_history` — full contracts in [02](./02-document-layer.md).
- **Skills** are **not** operations in this catalog. `split_epic`,
  `generate_task_plan`, and `author_manifesto` are local skills an agent runs — an
  external MCP client, or the web app's embedded authoring agent
  ([06](./06-web-ui.md)) — achieving their effect by calling the structure and
  document operations above (over MCP tools or the web API, respectively; e.g.
  `split_epic` drives repeated `create_item` calls). See
  [03](./03-skills-and-tenets.md).

### Error model

Errors are JSON-RPC 2.0's, not Nook's ([ADR-2](../architecture/adrs/adr-2.md)).

- **A reply carries `result` or `error`**, in the body, rather than leaving the
  outcome to the HTTP status number. Every reply comes back under one status, so
  the number is not the thing that says what happened. The same rule holds on the
  internal connection and on the web API, because they are the same shape.
- An **error object** is `{ code, message, data? }`. The standard's reserved codes
  cover the request itself: `-32700` for contents that are not JSON, `-32600` for
  an envelope that is not a request, `-32601` for an operation nobody defined,
  `-32602` for invalid params, and `-32603` for a call that produced no verdict.
- **Nook's domain failures ride in the same object.** `validation_failed` *is*
  `-32602`, whether the request was unreadable or the core refused its contents;
  `not_found` is `-32001`, `conflict` (e.g. slug collision) `-32002`, and `cycle`
  (blocked-by) `-32003` — the range the specification reserves for
  implementation-defined server errors. `data.reason` carries the domain name, so
  a caller reads it without matching integers, and the details a failure already
  carries ride alongside.
- A call that produced no verdict — the core unreachable, or broken — is
  `-32603`, and says nothing about which of the two it was: which part of Nook
  failed is not the caller's business.
- **MCP maps an error onto a tool result** with `isError` and that same object —
  the one translation MCP's own specification forces.

> Replaced twice. The web API first mapped four failure codes onto `400` / `404` /
> `409`; two shared `409`, and a route matching nothing answered `404` with an
> empty body, so the number could not decide what a reply was while the body
> already could. The body-carried outcome that replaced it was then an envelope
> designed here — an `outcome` field naming an answer, a refusal or a breakdown,
> with four string codes — which is JSON-RPC 2.0 with different names
> ([ADR-2](../architecture/adrs/adr-2.md)).

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
