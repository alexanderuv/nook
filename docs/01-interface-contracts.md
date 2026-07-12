# 01 — Interface contracts

**Status:** Outline · **Milestone:** 1 (with 04)

How agents and humans talk to Nook: the MCP tool/prompt/resource contracts, how a
connection selects its project, and the web app's HTTP API. The *shape* of the MCP
surface is settled (ARCHITECTURE.md §5); this spec pins the exact contracts.

## Decided

- Nook's concerns map onto MCP's three primitives — **tools** (structure ops,
  queries, document edits, skills), **resources** (tenets, documents), **prompts**
  (skills, for human trigger). Skills are exposed as *both* tools and prompts.
  (§5, Appendix A)
- Project is bound **at the connection/session level**, so tools take epic/task
  refs relative to the current project — no `projectId` per call. (§3.3, §5)
- Initial tool list: `create_epic`, `update_epic`, `create_task`, `update_task`,
  `set_task_blocked_by`, `create_release`, `assign_epic_to_release`, `get_epic`,
  `get_task`, `list_epics`, `list_tasks(filter)`, `get_ready_tasks()`; document
  tools `read_doc`, `write_doc`, `replace_section`, `insert`, `append_to_section`,
  `apply_patch`, `doc_history`; skills `split_epic`, `generate_task_plan`,
  `author_manifesto`. (§5)

## Open decisions

- [ ] **Exact request/response payloads** for each tool (field names, types, which
      are optional). Do responses return full entities or ids + summaries?
- [ ] **Entity reference format** in tool arguments — UUID, slug, or an accepted
      either? How are refs disambiguated within the bound project?
- [ ] **Error model** — how tool failures are reported (validation, not-found,
      conflict on slug collision, blocked-by cycle). MCP error vs. structured result.
- [ ] **Project-selection mechanism** — how a connection declares its project:
      env var / launch arg (stdio) vs. handshake param (HTTP). One project per
      connection, or switchable?
- [ ] **Transport** — stdio, HTTP/streamable, or both, and for which app.
- [ ] **`list_*` filtering & pagination** — the filter grammar (status, release,
      blocked, text?), sort, and whether pagination is needed at v1 scale.
- [ ] **HTTP API** — does the web app expose the same operations as a REST/JSON API,
      and does it mirror the MCP contracts or diverge? Endpoint shapes.
- [ ] **Resource URIs** — concrete scheme beyond `nook://project/tenets` (documents,
      per-entity).

## Depends on / feeds

- Pairs with **04** (structure semantics) — the contracts encode those rules.
- Document tools are specified in detail in **02**.
- Skill tools/prompts are specified in **03**.
