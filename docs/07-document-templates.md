# 07 — Document templates

**Status:** Settled (artifact catalog) · template *content* stays development-time · **Milestone:** 3

Which artifact types Nook ships templates for, and the principles governing that
list. The section structure of each — what headings a **manifesto** or **PRD**
actually contains — remains **deferred to development**: it is decided while
building the authoring skills and can evolve without a design-spec change.

## Decided

### The artifact catalog (v1)

Nook ships templates for **eleven authored artifact types**: ten spanning the
workflow stages *frame → discover → define → decide → build → learn*, plus one
stage-less orientation artifact — the architecture overview, whose role is
*orient*, not a workflow step. Each answers a distinct question. Kinds are level-agnostic in the schema ([02](./02-document-layer.md));
the "typical level" below is guidance the templates and skills encode, not a
constraint — with three exceptions, write-path enforced ([02](./02-document-layer.md)):
`adr` is **constrained to project level** (one decision stream per project),
`architecture` is **project-level and a singleton** (one living map per project),
and `plan` **requires an item** (a plan is the route for building one item; it
cannot float at project level):

| Stage | Artifact | Kind | Typical level | Question it answers |
|---|---|---|---|---|
| Frame | Manifesto | `manifesto` | epic | *Why are we doing this?* — vision, direction, non-goals, success criteria |
| Frame | PRD | `prd` | epic | *What exactly, for whom?* — users, problem, requirements, acceptance metrics |
| Discover | Discovery report | `discovery` | epic or leaf | *What did we find out?* — two flavors, one role: product discovery (user research, competitive analysis, experiments) and technical discovery (spikes, library evaluation, prototypes) |
| Define | Spec | `spec` | epic or leaf | *What exactly must be true when this is done?* — precise, testable behavior: requirements, edge cases, acceptance criteria |
| Decide | RFC | `rfc` | epic | *Should we do X, and how?* — options, tradeoffs, recommendation, open questions |
| Decide | ADR | `adr` | project (constrained) | *What did we decide, and why?* — context, decision, consequences |
| Decide | Design doc | `design_doc` | epic | *How will it work technically?* — architecture, data model, interfaces, risks |
| Build | Implementation plan | `plan` | leaf (item required) | *How do I build this one thing?* — analysis, background, approach, caveats, test plan |
| Build | Test plan | `test_plan` | epic | *How do we verify the whole?* — strategy, coverage, environments, acceptance |
| Learn | Retrospective | `retro` | epic or bug | *What did doing it teach us?* — what worked, what didn't, follow-up actions |
| Orient | Architecture overview | `architecture` | project (constrained, singleton) | *How does the system hang together today?* — purpose, context, components, core structures, key flows, invariants |

- `tenet` and `attachment` complete the kind enum but are **not templated
  artifacts**: tenets have their own lifecycle
  ([03](./03-skills-and-tenets.md)); attachments are deliberately freeform.
- The numbered kinds (`prd`, `rfc`, `adr`, `spec`, `design_doc`, `test_plan`,
  `retro`) carry a **per-project, per-kind sequence number** ("RFC-3",
  "PRD-2") — a stable citation handle allocated by the structure store and
  stamped into the title; mechanism in [02](./02-document-layer.md). The
  fixed-name docs (`manifesto`, `plan`, `architecture`) and `discovery` are
  unnumbered — cited by their item (an investigation belongs to the item whose
  uncertainty it reduced, like a plan), or as "the architecture".
- The **architecture overview** is the map the **ADR stream** explains: the
  overview holds the integrated current state and is updated in place as reality
  changes; each ADR records one delta and its why. The overview cites ADR#s (its
  invariants especially) and never duplicates the stream. Its sections are
  **roles, not web-dev furniture** — "core structures" means DB entities in a
  service, IRs in a compiler, the scene graph in an engine — so the kind fits
  systems that don't look like web apps (the template encodes this).
- The standalone **test plan** is the epic-level test *strategy*; every leaf
  `plan` keeps its own test-plan **section**. How the two avoid duplicating each
  other is template guidance, settled in development.
- **Postmortems are retrospectives** — a `retro` attached to a bug; incident
  reports are a template variant, not a kind.

### A menu, not a pipeline

- **The project author picks the artifacts that fit their methodology.** A solo
  engineering project may use only manifesto + plan; a product team PRD + design
  doc + plan + retro; a spec-driven agent workflow runs spec + plan; a
  decision-heavy org adds RFCs or ADRs. No workflow needs all ten, and **overlap
  between types is expected and accepted** (RFC vs ADR, manifesto vs PRD, PRD vs
  spec) — the catalog meets teams where they are instead of enforcing one
  doctrine.
- **No per-project type registry in v1.** Every kind is available to every
  project; adoption is by use, optionally steered by the project's tenets
  ("every epic gets a PRD; we don't write manifestos"). A configurable
  per-project document palette is a possible later refinement, not v1 mechanism.

### Kinds are roles, not topics

A kind is a **workflow role** — the question a document answers — never a subject
matter. A threat model is a design doc about security; an experiment plan is a
discovery report about an A/B test; a PR/FAQ is a frame-stage doc in Amazon's
house style. Topic- and methodology-specific shapes are **template variants**
chosen at development time; only roles become kinds. This is the rule that keeps
the catalog from exploding.

### Template assets — `artifacts/` in the Nook repo

The template *definitions* — the shipped skeletons the authoring skills consume —
are **Nook system assets stored under `artifacts/` in the Nook repo**, alongside
the skills and distributed the same way ([03](./03-skills-and-tenets.md)). They
are not tenant documents. In a tenant project's artifact repo, instantiated
catalog docs get **no special casing**: no per-kind directories, no fixed
filenames beyond the pre-existing `manifesto.md`/`plan.md` — they are named
markdown docs in the item's **`docs/`** area, distinguished by their DB `kind`.
`attachments/` stays reserved for freeform material (and, later, binary media)
([02](./02-document-layer.md)).

### Considered, deliberately not kinds

Template variants (roles already in the catalog, in a house style):

- **Pitch (Shape Up), PR/FAQ, six-pager, one-pager/brief, project poster** —
  frame-stage variants of manifesto/PRD.
- **API spec, data model, threat model, migration plan, model card, data
  contract** — design-doc variants (technical internals; distinct from the
  requirements-contract `spec` kind). **SRS/use-case docs** — `spec` variants.
- **Experiment plan/report, competitive analysis, user-research writeup, spike
  report, discovery audit** — discovery variants. **Eval spec** — test-plan
  variant.
- **Incident report, lessons-learned/closure report** — retrospective variants.

Covered by other Nook surfaces, not documents:

- **User stories, backlogs, roadmaps, sprint/release plans** — *structure*
  (items, releases, dependencies, statuses).
- **Definition of done, working agreements, spec-kit-style constitutions** —
  *tenets*. **Decision logs** — the ADR stream. **Bug reports** — bug items.
- **Status report** — deliberately an *anti-kind*: "where are we?" is exactly
  what the structure store knows, so in Nook it is a generated view (a natural
  later skill reading structure + docs), never an authored artifact that can go
  stale.

Considered and not added:

- **Verification report** ("did it actually work — commands run, observed vs.
  expected") — proposed as the evidence-of-work artifact completing plan →
  build → verify; not adopted. Evidence lives with the change (PR, CI) for now;
  revisit if agents working from Nook plans need a report-back surface.
- **Worklog / session handoff / scratchpad** — session-scoped agent-continuity
  material; agent-side, not artifact-repo documents.
- **Risk register** — risks ride the frame/plan docs (caveats, rabbit holes) or
  a project-level doc later; not a v1 kind.

Deferred or out of scope:

- **Charter, OKRs, business case/BRD, glossary / domain-model doc** —
  project-level documents, deferred additions to [02]'s root `/docs/` area (which
  v1 materializes for the ADR stream only; the architecture overview graduated
  from this list to a v1 kind). (The glossary is a likely early addition there —
  shared vocabulary is disproportionately valuable to agents.)
- **Release notes, launch plan** — release-attached documents don't exist in the
  v1 model (documents attach to items only); they defer with that capability.
- **Runbooks, onboarding docs, FAQs, changelogs, contributing/governance docs**
  — live with the code repo, not in Nook. **GTM/marketing material** — outside
  Nook's domain.
- **Meeting notes** — plain attachments.

### Unchanged from the original deferral

- **Template content is deferred to development.** The concrete section sets (and
  whether a section is required, optional, or a skill may add ad-hoc ones) are
  chosen when the authoring skills are built, and can evolve without a design-spec
  change.
- **Source & override is settled by [03](./03-skills-and-tenets.md).** Templates
  are Nook-shipped, system-level assets consumed by the skills, with append-only
  project overlays — distributed into the agent's environment and versioned by
  Nook, exactly as skills are. There is no separate template-distribution
  mechanism to design.
- **Regeneration rides [02](./02-document-layer.md)'s edit API.** Whole-document
  regeneration uses `write_doc`; a section-wise refresh uses the section ops; a
  propose-then-accept flow composes those with the UI (06). *Which* a given skill
  uses is a skill/dev choice — the API already supports all three.

## Depends on / feeds

- Adds document kinds `prd` (7), `adr` (8), `discovery` (9), `test_plan` (10),
  `retro` (11), `spec` (12), `architecture` (13) to
  [02](./02-document-layer.md) and the structure schema (`db/changelog`).
- The mechanism lives in [02](./02-document-layer.md) (edit API) and
  [03](./03-skills-and-tenets.md) (shipped-base + overlay distribution).
- The skills in [03](./03-skills-and-tenets.md) fill these templates; their exact
  section sets are settled in development.
