# Choosing a document kind

Which of the eleven templates to reach for, and what each covers in broad terms.
The catalog is a **menu, not a pipeline** ([docs/07](../../docs/07-document-templates.md)):
no effort needs all of them, nothing here is mandatory, and overlap between kinds
is expected — pick the ones that fit how your project works. A solo project may
write only a manifesto and plans; a product team might run PRD → design doc →
plan → retro; a spec-driven agent workflow lives on spec + plan. When two kinds
could both fit, either choice is fine — what matters is that the document answers
its question well, because for any given effort it may be the only document of its
stage that gets written.

## The matrix

Kinds are grouped by the stage of work they serve. "Typical home" is guidance,
not a constraint — except the three write-path rules: `adr` is always
project-level, `architecture` is a project singleton, and `plan` always attaches
to an item.

| Kind | Stage · typical home | The question it answers | Reach for it when | What it covers, broadly |
|---|---|---|---|---|
| `manifesto` | Frame · epic | *Why are we doing this?* | An effort is big enough that people and agents will face judgment calls the tickets don't answer, and need something to consult to make the call the way the author would. | Vision, why now, the two or three big bets and their why, decision principles, checkable success criteria, non-goals. Directional altitude — it guides judgment, not implementation. |
| `prd` | Frame · epic | *What exactly, and for whom?* | "What are we building and how will we know it worked" needs to be agreed before design starts. | The problem and its evidence, the users and their jobs, measurable goals, prioritized requirements traced to those goals, non-goals, phasing judgment, risks. Stops before precise testable behavior and technical approach. |
| `discovery` | Discover · epic or leaf | *What did we find out?* | Uncertainty should shrink before a commitment — does the problem or demand hold up (user research, competitive analysis, experiments), or how should we build it (spikes, library evaluations, prototypes)? | The questions asked, the method, findings with evidence and a confidence label, implications and a recommendation, honest limitations. It informs a decision; it never closes one. |
| `spec` | Define · epic or leaf | *What exactly must be true when this is done?* | Behavior needs to be pinned precisely enough that an implementation can be checked against it, pass/fail, without interpretation. | Scope and its boundary, concrete scenarios, testable MUST/MUST-NOT requirements, edge cases, acceptance criteria citing the requirements they verify. Stops before how — architecture and approach are not its business. |
| `rfc` | Decide · epic | *Should we do X, and how?* | A choice is big enough — new system, cross-cutting change, contested direction — that deciding it silently would surprise someone. | The proposal at reviewable depth, the alternatives at full strength, the drawbacks and risks stated by the author, prior art. A proposal written to be argued with, before the work is committed. |
| `adr` | Decide · project (always) | *What did we decide, and why?* | A significant decision has actually been made and future readers (or agents) shouldn't relitigate it. | One decision per record, a page at most: the forcing context, the decision and its scope, the options that lost and why, the consequences both ways. The stream is append-only — reversals are new ADRs, never edits. |
| `design_doc` | Decide · epic | *How will it work technically?* | The design has real ambiguity — more than one plausible shape, tradeoffs worth a reviewer's eyes. When the solution is obvious, skip the doc and build it. | The architecture, data model, and interfaces at the altitude where tradeoffs are visible, the alternatives weighed, cross-cutting concerns (security, privacy, observability, reliability, compatibility), risks. |
| `plan` | Build · leaf (item required) | *How do I build this one thing?* | Just before the work on one task, bug, or chore — the last document before code, often executed literally by an agent. | What's actually in the repo today, the chosen route and its blast radius, ordered steps each with a verification, the traps and rabbit holes to skip, how this one change proves itself, rollback when reverting isn't trivial. |
| `test_plan` | Build · epic | *How do we verify the whole?* | An effort's failures live *between* changes — integration seams, cross-feature flows, non-functional envelopes — which no single change's tests can catch. | The verified/not-verified boundary (the not-tested list carries its reasons), the risk argument behind the strategy, the coverage map with priorities, environments and their gaps, the exit bar. |
| `retro` | Learn · epic or bug | *What did doing it teach us?* | The work (or the incident — a postmortem is a retro on a bug) is done, and what actually happened can be compared with what was expected. | The factual account, what worked and should be repeated, what didn't — dug to systemic causes, blamelessly — the durable lessons, and the follow-ups spawned. |
| `architecture` | Orient · project (singleton) | *How does the system hang together today?* | Always current — one living map per project, updated in place whenever reality changes, read by whoever (usually an agent) arrives without context. | Purpose, the system boundary, components and where each lives in the code, the core structures, the key end-to-end flows, the invariants no change may break, the known weaknesses. Present state only — never a roadmap. |

## Easily confused pairs

- **Manifesto vs. PRD.** Both frame an epic. The manifesto argues *why* and sets
  direction and judgment rules; the PRD pins *what* — users, prioritized
  requirements, measurable success. An effort heavy on judgment calls wants the
  manifesto; one that needs agreed, measurable scope wants the PRD. Big efforts
  sometimes warrant both; either can stand alone.
- **PRD vs. spec.** A PRD requirement is a prioritized capability in plain
  language ("importing large files must be fast enough for daily use — P0"); a
  spec requirement is an enforceable contract ("the system MUST complete a
  10k-row import in under 2 seconds"). The PRD frames; the spec removes the
  "roughly".
- **RFC vs. ADR.** The RFC is the argument *before* the decision, written to be
  challenged; the ADR is the compact record *after*, written to be obeyed until
  superseded. A contested choice may travel through both; a choice made quickly
  and cleanly may only ever get the ADR.
- **RFC vs. design doc.** The RFC asks *should we do this, and roughly how* — it
  goes deep only where the options genuinely differ. The design doc takes the
  "should" as settled and works out the whole technical shape.
- **Design doc vs. spec.** The spec describes the system from outside — what an
  observer must be able to verify. The design doc describes it from inside — the
  architecture and tradeoffs that deliver that behavior.
- **Discovery vs. RFC/ADR.** A discovery report brings back evidence and may
  recommend, but it never closes a choice — ratifying one is `rfc`/`adr`
  territory.
- **Plan's test-plan section vs. the `test_plan` kind.** Every leaf plan carries
  a test-plan *section* proving that one change. The `test_plan` *kind* is the
  verification strategy for an assembled whole — the failures that live between
  changes.
- **Design doc vs. architecture overview.** A design doc decides a *change* — a
  future state, argued through tradeoffs. The architecture overview describes
  the *present* — and gets updated in place once a design lands.
- **Retro vs. discovery.** Both produce knowledge worth keeping. Discovery is
  deliberate investigation *before* a commitment; a retro is what the work
  itself taught you, *after*.

## Practical notes

- **Numbered vs. unnumbered.** `prd`, `rfc`, `adr`, `spec`, `design_doc`,
  `test_plan`, and `retro` carry a per-project sequence number stamped into the
  title ("RFC-3") — their citation handle from other documents. `manifesto`,
  `plan`, `architecture`, and `discovery` are unnumbered — cited by their item,
  or as "the architecture".
- **House styles are variants, not new kinds.** A Shape Up pitch or Amazon
  PR/FAQ is a manifesto/PRD in a house style; a threat model or API spec is a
  design doc; an experiment report or spike writeup is a discovery report; an
  incident report is a retro. Pick the kind by the *question the document
  answers*, not its topic.
- **None of these track state.** Statuses, releases, and dependencies are Nook
  structure constructs (offered, never required); document history is git's job.
  If a section's job would be tracking progress, it doesn't belong in any of
  these documents.
