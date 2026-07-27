# Epic 06 — MCP server

**Addresses:** REQ6 (streamable HTTP at `/mcp/{projectRef}`, project bound per
connection and reported to the client when the connection opens, the catalog's
seven project-scoped operations as tools, UUID-or-slug references, structured
errors).

Documents, in the order they were produced:

- [spec-4.md](./spec-4.md) — the requirements contract. Its load-bearing
  decision: **projects are not on this surface.** An agent cannot create, read,
  list, or delete a project — those belong to the human surface — so the seven
  operations that act on items and releases are the whole tool set, and none of
  them names a project. Which project applies comes from the address the
  connection was opened at, and the server reports it to the client so the agent
  can say where it is without a tool for it.
- [discovery.md](./discovery.md) — thirteen probe groups against the pinned
  protocol library, an embedded web container, and two clients — one of them the
  protocol's own Inspector, which knows nothing of Nook — settling how the server
  gets built: that a servlet-hosted transport and Ktor coexist without contest,
  that `/mcp/{projectRef}` means one protocol server per project, where the
  connection's announcement rides, and which two of spec-4's requirements the
  library cannot satisfy as written — both since amended in the spec, each
  carrying a note on what it now asks for and why.

The build plan is still to be written.
