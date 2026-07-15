<!--
Design doc — decide-stage artifact, typically on an epic. Answers: *how will it
work technically?* — the architecture, data model, and interfaces of the solution,
and the tradeoffs that led there. Write one when the design has real ambiguity —
more than one plausible shape, tradeoffs worth a reviewer's eyes; when the solution
is obvious, skip the doc and build it. The measure of a design doc is that it makes
**tradeoffs explicit**: a document that narrates an implementation without weighing
anything is a manual, not a design. Its altitude: above code (interface sketches,
never formal API dumps — those go verbose and stale), below "should we do this at
all" (`rfc`) and beside "what must be observably true" (`spec`) — altitude markers,
not prerequisites; this may be the effort's only technical doc, so it stands alone.
API specs, data models, threat models, migration plans, model cards, and data
contracts are house variants of this role. Agents plan and build from this
literally, so mark what's binding and what's illustrative: an unmarked example
reads as a decision, and a sketch offered as "something like this" becomes the
interface built. Scale length to ambiguity — a page for a contained change, more
for a system — and when reality diverges during the build, update the doc in place
(git keeps the old version); a stale design doc sends every future reader
spelunking. This document never tracks state in prose: Nook has structure
constructs (releases, statuses, dependency edges) for projects that want
timeline/status tracking, and git already keeps document history.
-->

# {Title} — Design-{seq}

## Context & scope

<!--
Where this design sits, succinctly: the situation as objective fact — what exists
today, what this system will do, who calls it — with links doing the heavy lifting
(the motivating framing docs, discovery findings, prior Design-#s) rather than
restatement. State the degree of constraint: greenfield where the solution space
is open, or boxed in by legacy systems, fixed interfaces, or mandated technology —
it changes how every choice below should be judged. Then the boundary: what this
design covers and what it deliberately leaves out. Readers are brought up to
speed here, not educated from zero.
-->

## Goals & non-goals

<!--
The design's success criteria, as a short list a reviewer can score the design
against: what the system must achieve, including the non-functional envelope that
shapes the architecture (scale, latency, durability, cost — numbers with units,
never adjectives). Non-goals are the sharp edge: not negated goals ("shouldn't
crash") but reasonable properties deliberately not pursued ("ACID across
projects", "multi-region") — each with a word on why, because a reader who can't
tell a non-goal from an oversight will redesign for it.
Format: goals `- **GOAL1** — <what the design must achieve, with its threshold>.`;
non-goals `- **<the property not pursued>** (<never|not-now>) — <why>.`
-->

## Design

<!--
The design itself — overview first, then the parts where the tradeoffs live.
Open with the shape of the solution in a paragraph and, where it earns its place, a
system-context diagram: the new parts against the existing landscape, so a reader
anchors on what they already know. Then organize by `###` subsections as the
design demands — architecture and components, data model and storage, interfaces —
sketching each at the level a tradeoff is visible: what data lives where and why,
what the API promises and to whom, what happens at the failure boundaries.
Everywhere a choice was real, say why this way — the reasoning against the goals
is what reviewers review and what survives the code. Keep formal completeness out
(full schemas and API definitions belong in the code they describe); keep judgment
in. If the design replaces or migrates something live, the migration path is part
of the design, not an afterthought — give it a subsection. Mark illustrative
sketches as illustrative ("shaped like this", "for example"); anything unmarked
will be built as written.
-->

## Alternatives

<!--
The other shapes this design could have taken, weighed by their tradeoffs — the
section that turns a description into a design. For each alternative: the approach
in a sentence or two, the tradeoff it makes (what it does better, what it gives
up — argued against the goals above, with numbers where they exist), and what
tipped the decision. Steelman them: a reviewer or agent who can see a rejected
option's real strengths won't re-propose it, and a future team facing changed
constraints can tell when one deserves a second look. Include the status quo when
"change nothing" was viable.
Format: `- **OPT1 — <name>** — <the approach>; *tradeoff:* <better at …, worse
at …>; *why not:* <the goal (GOAL#) it loses on>.`
-->

## Cross-cutting concerns

<!--
The concerns every design must answer for, swept deliberately: security (attack
surface, authn/authz boundaries), privacy (what user data is touched, retained,
exposed), observability (how operators will see it working — metrics, logs,
alerts), reliability (failure modes, degradation, recovery), and compatibility
(what existing callers, data, or workflows must keep working). One entry per
concern, however short — "not applicable" is a valid answer, but it must be
written with its why, because a concern left silent reads as a concern left
unconsidered, and the gap gets discovered in review or in production.
Format: `- **<concern>** — <how the design addresses it, or "not applicable —
<why>">.`
-->

## Risks

<!--
What could make this design wrong, and what's being done about it: the assumption
that might not hold, the dependency that might not deliver, the load pattern that
might not match the model, the part of the design with the least evidence behind
it. For each: the risk, its severity, and the mitigation — or the experiment that
would retire it early ("prototype the indexer against production-scale data before
committing"). The riskiest part of a design is usually the part nobody wrote down;
naming it here is what lets the build sequence attack it first.
Format: `- **RISK1** — <risk>; severity: <high|medium|low>; <mitigation, or the
experiment that retires it>.`
-->

## Open questions

<!--
Optional — drop if none. Design choices still genuinely open, each with who owns
the answer and what it blocks. An open question here is honest and cheap; the same
question papered over inside the Design section reads as a decision and gets
built. When one is settled, write the answer into the subsection it affects and
delete it here.
Format: `- **Q1** — <question>; owner: <who>; blocks: <the part of the design
that can't proceed>.`
-->
