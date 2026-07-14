# Document templates

Shipped skeletons for the ten authored artifact types ([docs/07](../../docs/07-document-templates.md)).
The authoring skills ([docs/03](../../docs/03-skills-and-tenets.md)) consume these when
instantiating a document in a tenant project's artifact repo.

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
- **No status, changelog, or schedule-tracking sections.** Templates never bake in
  prose whose job is tracking state. Nook *offers* structure constructs — releases,
  statuses, dependency edges — for projects that want that tracking (offers, not
  mandates: nothing in Nook requires using them), and git already keeps document
  history; a prose copy of either only goes stale. Sections may carry the *judgment*
  behind such state (e.g. a PRD's phasing rationale in Milestones) but never track it.
