# Document templates

Shipped skeletons for the eleven authored artifact types ([docs/07](../../docs/07-document-templates.md)).
The authoring skills ([docs/03](../../docs/03-skills-and-tenets.md)) consume these when
instantiating a document in a tenant project's artifact repo. Not sure which kind
fits? See [GUIDE.md](./GUIDE.md) — when to use which, and what each covers.

## Conventions

- One file per document kind, named after the kind: `prd.md`, `rfc.md`, `adr.md`, …
- **`##` headings are the section set.** The edit API addresses sections by heading
  path ([docs/02](../../docs/02-document-layer.md)), so these headings are the stable
  anchors skills edit against. Skills keep heading text verbatim and fill the bodies;
  they may add subsections (`###`+) beneath them freely.
- **Guidance lives in `<!-- … -->` comments.** Each section carries a guidance
  paragraph telling the author (human or agent) what belongs there and what good looks
  like. Skills read the guidance, write the content, and **delete the comment** —
  guidance never ships in an instantiated document.
- A guidance comment starting with **`Optional`** marks a section that may be dropped
  at instantiation when it doesn't apply. All other sections are required.
- **List sections prescribe an exact item format** in their guidance (a `Format:`
  line), usually with stable per-document IDs (`REQ1`, `GOAL2`, `Q3`, …). Prefixes
  are short words, not single letters, so humans can read them cold; each template's
  Format lines define its own.
  Skills follow the format verbatim — never a freehand variation — so items look
  the same across every epic and task and can be cited by ID from other sections
  and documents ("AC3 verifies REQ2", "per the manifesto's CRIT1"). IDs are stable
  once assigned: deleting an item retires its ID rather than renumbering the rest.
- **Numbered kinds carry their sequence number in the title.** The numbered kinds
  (`prd`, `rfc`, `adr`, `spec`, `design_doc`, `test_plan`, `retro`) get a
  per-project, per-kind sequence number from the structure store
  ([docs/02](../../docs/02-document-layer.md)): `RFC-3` is the third RFC in
  the project, and numbers are never reused. Template titles carry a `{seq}`
  placeholder (`# {Title} — RFC-{seq}`) that skills stamp with the allocated number
  at instantiation. The number is the document's citation handle from other
  documents and commits ("per RFC-3", "supersedes ADR-2"). `manifesto`, `plan`,
  `architecture`, and `discovery` are unnumbered — cited by their item (a
  discovery belongs to the item whose uncertainty it reduced, like a plan), or
  as "the architecture" (a per-project singleton).
- **Every template stands alone; overlap between kinds is expected and accepted**
  ([docs/07](../../docs/07-document-templates.md): the catalog is a menu, not a
  pipeline — each project picks the kinds that fit its methodology). Guidance may
  name another kind to mark *altitude* ("testable behavior is `spec`-level detail"),
  but must never presume one exists or defer required content to it ("the PRD will
  carry the numbers") — for any given project this document may be the only one of
  its stage that gets written. Do not "fix" inter-template overlap; it is by design.
- **Open-questions sections are drafting valves, not fixtures.** They exist so an
  ambiguity found while writing is marked instead of papered over with a plausible
  guess — to a literal reader, a wrong guess is indistinguishable from a decision.
  Entries drain: settle the question, fold the answer into the section it blocked,
  delete the entry, and drop the section once empty. A non-empty Open questions
  section is a sign the document needs iteration before implementation — never
  build over one. Exception: `discovery`'s is an *output* (questions the
  investigation surfaced or couldn't reach — the seed of the next one) and doesn't
  drain. `adr` has none by design: a load-bearing open question means the decision
  isn't made yet.
- **No status, changelog, or schedule-tracking sections.** Templates never bake in
  prose whose job is tracking state. the project tracker *offers* structure constructs — releases,
  statuses, dependency edges — for projects that want that tracking (offers, not
  mandates: nothing requires using them), and git already keeps document
  history; a prose copy of either only goes stale. Sections may carry the *judgment*
  behind such state (e.g. a PRD's phasing rationale in Milestones) but never track it.
