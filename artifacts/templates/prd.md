<!--
PRD — frame-stage artifact, typically on an epic. Answers: *what exactly, and for
whom?* Write one when an epic is big enough that "what are we building and how will
we know it worked" needs to be agreed before design starts. It frames the problem
and pins measurable outcomes; it does NOT specify precise testable behavior (that is
a `spec`) or the technical approach (that is a `design_doc`). Spend more time on
Problem than feels comfortable — a PRD whose Problem section is thin is a solution
looking for a justification. Keep it lean: every requirement must trace to a goal,
and agents will read this literally, so prefer numbers over adjectives ("p95 <
500 ms", never "fast"). This document never tracks state in prose: the project tracker offers
structure constructs (releases, statuses, dependency edges) for projects that want
timeline/status tracking, and git already keeps document history.
-->

# {Title} — PRD-{seq}

## Overview

<!--
One short paragraph: what this is, who it is for, and why now. A reader should be
able to stop here and correctly summarize the initiative. Link the docs this frames
against — the manifesto it serves, discovery reports that motivated it — rather
than restating them.
-->

## Problem

<!--
The single most important section. State the user/business problem in one or two
sentences — the problem, not the absence of your solution ("users can't X", never
"we lack feature Y"). Then the evidence: data, support themes, discovery findings,
links to the discovery reports. Close with why now — what makes this worth solving in
this cycle. If this section can't be written convincingly, the PRD isn't ready.
-->

## Users

<!--
Who this serves. Name the target users or personas and mark the primary one when
there are several. Express what they're trying to get done as jobs/job stories
("When …, I want to …, so I can …") rather than feature wishes. Cover the main
scenarios of use in context — enough that requirements below can be checked against
a real situation.
Format: `- **<persona>** — when <situation>, I want to <motivation>, so I can
<outcome>.` Mark the primary persona `**<persona> (primary)**`; several job
stories may share a persona.
-->

## Goals & success metrics

<!--
How we'll know it worked. 2–5 measurable outcomes, each with a metric, a target
number, and when it should be reached (e.g. "activation rate 25% → 35% within two
months of launch"). Prefer one north-star outcome plus supporting/guardrail metrics
(what must not regress). Name where each metric is observed — the event, dashboard,
or query — so success is checkable, not arguable. Qualitative signals are fine as a
supplement, never the only measure.
Format: `- **GOAL1** — <metric>: <baseline> → <target> by <when>; observed in
<event/dashboard/query>.` Mark the north star `**GOAL1 (north star)**`; guardrails
as `<metric>: must not regress below <threshold>`.
-->

## Requirements

<!--
What the product must do, as a prioritized list. Each requirement: a priority
(P0 = must ship / P1 = should / P2 = nice-to-have), a concrete capability stated in
plain language, and a one-line rationale tying it to a goal or job above — anything
that traces to no goal gets cut. Be specific enough that an implementer can tell
done from not-done, but keep exhaustive edge cases and acceptance tests out: that
is `spec`-altitude detail, too fine for a framing doc. Non-functional needs (performance, security,
reliability, accessibility) go here too when they're real constraints — always with
thresholds, never adjectives. Link mockups or prototypes inline where a picture
specifies better than prose.
Format: `- **REQ1 · P0** — <capability>. *Why:* <the goal (GOAL#) or job it
serves>.` Order by priority, P0s first.
-->

## Non-goals

<!--
What we are deliberately not doing, with a short reason for each ("not in scope
because…"). This is the scope-creep firewall and the second most load-bearing
section after Problem: it records the temptations already considered and declined,
so they don't get relitigated item by item. An empty non-goals list usually means
the scope hasn't actually been decided.
Format: `- **<the thing not being done>** — <why: conflicts with a goal, or
deferred to a later phase>.`
-->

## Milestones

<!--
Optional — drop for single-phase work. How the scope slices into phases, and why
that order: what the first slice must prove before the next is worth building
(e.g. "MVP: REQ1–REQ4, validates the core job; fast-follow: REQ5–REQ6; GA adds
P1s"). Each phase names the requirements it covers and its exit condition. This
section carries the phasing *judgment* only. Dates and progress don't belong here
regardless: if the project tracks phases at all, the tracker's release/item constructs do
that live, and a prose copy only goes stale.
Format: `- **<phase name>** — covers <REQ#s>; proves: <what this slice must
demonstrate>; exit: <condition>.` In build order.
-->

## Risks & assumptions

<!--
What could make this fail, and what we're taking on faith. Cover the four risk
lenses where relevant: value (will they want it), usability (can they use it),
feasibility (can we build it), viability (does it work for the business). For each:
the risk or assumption, its severity, and how it will be validated or mitigated.
External dependencies (third-party APIs, compliance, other teams) belong here;
dependencies on other tracked items are structure-store edges, not prose.
Format: `- **<value|usability|feasibility|viability|dependency>** — <risk or
assumption>; severity: <high|medium|low>; <how it's validated or mitigated>.`
-->

## Open questions

<!--
Optional — drop if none. Unresolved questions that block or shape the work, each
with who owns the answer. When one is settled, move the decision into the section
it affects and delete it here; a question that lingers across reviews is a risk and
should be promoted to the section above.
Format: `- **Q1** — <question>; owner: <who>.`
-->
