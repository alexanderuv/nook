# Execution

Where design turns into build plans. The specs under [`docs/`](../docs/) settle
*what* Nook is; this folder plans *how and in what order* it gets built.

One folder per milestone (the milestones are defined in
[`docs/README.md`](../docs/README.md), "Build order"):

- `milestone-1/` — **Structure**: projects, epics, tasks over MCP and HTTP,
  backed by Postgres.
- `milestone-2/` — **Documents** (future).
- `milestone-3/` — **Workflow: skills & tenets** (future).
- `milestone-4/` — **Human surface: web UI** (future).

Each milestone folder starts with the milestone's framing document, its PRD
(`prd-<seq>.md` — numbered kinds carry their sequence number in the filename,
so milestone 1's is `prd-1.md`) — a **PRD authored from Nook's own template**
([`artifacts/templates/prd.md`](../artifacts/templates/prd.md), via the
`author-doc` skill — we dogfood our own artifact catalog). The PRD is the
source from which the milestone's **epics** are derived; the epic breakdown
lands alongside it as it is worked out.

Conventions:

- A milestone README describes *scope and outcomes*, not implementation detail —
  the same altitude rule as the specs (`docs/07`'s principle applies here too).
- When a milestone's content conflicts with a spec, **the spec wins**; fix the
  spec first, then the milestone doc.
