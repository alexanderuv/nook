<!--
RFC — decide-stage artifact, typically on an epic. Answers: *should we do X, and
how?* It is a proposal written to be argued with: the problem, a recommended
approach, and the alternatives at full strength, laid out so reviewers can find the
flaws before the work is committed. Write one when a choice is big enough — new
system, cross-cutting change, contested direction — that deciding it silently would
surprise someone. Where a discovery report says what was found and an ADR records
what was decided (altitude markers, not prerequisites), the RFC is the argument in
between. Two disciplines rule it. Steelman the alternatives: a reviewer — human or
agent — can only trust the recommendation if the rejected options are presented at
full strength; a weakly-argued alternative reads as a rigged comparison, and an
agent building on this will inherit the bias. Own the drawbacks: whoever implements
the proposal treats unstated costs as absent, so list what a hostile reviewer would
find — an RFC with no drawbacks section filled in honestly is advertising, not
engineering. Numbers over adjectives throughout ("adds ~2ms per request", never
"minimal overhead"). Adoption is not recorded here: industry RFC templates carry a
Status: Draft/Approved header, but in Nook that is the item's status construct —
this document never tracks state in prose: Nook has structure constructs (releases,
statuses, dependency edges) for projects that want timeline/status tracking, and
git already keeps document history.
-->

# {Title} — RFC-{seq}

## Summary

<!--
The proposal in three to five sentences: what change is being proposed, why, and
the headline tradeoff being accepted to get it. A reader should be able to stop
here and correctly report what this RFC asks the project to commit to. State it as
a position ("adopt X for Y; it costs Z"), never as a topic ("this RFC discusses
importer architecture").
-->

## Background

<!--
The context a reviewer needs to judge the proposal, written for a newcomer: the
current state, the problem with it, and why now — what changed or what's coming
that makes this worth deciding in this cycle. Describe the problem, not the absence
of the proposal ("imports fail past 10k rows", never "we don't have X yet"). Bring
evidence — data, incidents, discovery findings — by linking it, not restating it.
The test: someone new to the effort reads this section, follows its links, and can
evaluate the proposal without asking anyone.
-->

## Goals & non-goals

<!--
The yardstick the options are measured against. Goals: what any acceptable solution
must achieve — these are the decision criteria, and the proposal and alternatives
below should be judged against them, not against taste. Non-goals: what this
proposal is deliberately not solving, each with a reason — the scope firewall that
keeps review focused on the decision actually being made. A "not-now" non-goal
doubles as a future possibility: name it so the proposal isn't stretched to
accommodate it today.
Format: goals `- **GOAL1** — <what any acceptable solution must achieve>.`;
non-goals `- **<the thing not being solved>** (<never|not-now>) — <why>.`
-->

## Proposal

<!--
The recommended approach, in enough detail that a reviewer can find the problems:
what changes, how it works, what it touches (APIs, schemas, subsystems, teams), and
how it meets each goal above (cite GOAL#s). The level of detail is set by what the
decision needs — sketch the parts any option would share, and go deep where the
options genuinely differ, because that's where the decision lives. Diagrams and
interface sketches earn their place when they let a reviewer object concretely.
Writing this section is where authors catch their own design bugs; if a part resists
being written down plainly, that part isn't decided yet — take it to Open questions
rather than papering over it. A full technical design is `design_doc`-altitude
work; include here as much of it as the decision needs, no more.
-->

## Alternatives

<!--
The other ways to get the goals, each steelmanned — presented as its best advocate
would, then weighed. Include "do nothing / status quo" whenever it's viable; its
cost is the baseline every option is implicitly compared against. For each
alternative: the approach in a few sentences, its genuine advantages, its costs,
and what tips the scale to the proposal — argued from the goals above, with numbers
where they exist. An alternative that was never really considered doesn't belong
here; one that almost won deserves the most space, because it's the one the next
reviewer will raise.
Format: one `### OPT1 — <name>` subsection per alternative, containing the
approach, `*Pros:*`, `*Cons:*`, and `*Why not:* <what tips the scale to the
proposal, per GOAL#s>.`
-->

## Drawbacks & risks

<!--
What the proposal costs, and what could go wrong — stated by its author, before a
reviewer has to. Drawbacks: what gets worse by design (complexity added, options
foreclosed, migrations imposed, load shifted to another team). Risks: what could
fail in practice, each with severity and how it's mitigated or why it's accepted.
Sweep the cross-cutting concerns deliberately — security, privacy, performance,
operations, backward compatibility — and say so when one doesn't apply; a concern a
reviewer finds that the author didn't list is the classic RFC failure. If this
section is empty, the proposal hasn't been thought through, only advocated.
Format: `- **RISK1** — <drawback or risk>; severity: <high|medium|low>;
<mitigation, or "accepted — <why>">.`
-->

## Prior art

<!--
Optional — drop when the problem is genuinely novel to this project. How others
solved the same problem: other teams, other projects, the literature, the framework
everyone else uses. For each: what they did, how it worked out, and what that
implies here — cited, so a reader can weigh the source. Prior art cuts both ways
and both are worth recording: "everyone does X" is evidence for X, and "Y tried
this and walked it back" is evidence that deserves a response in the proposal.
Format: `- **<who/what>** — <what they did and how it turned out>; here: <what it
implies for this proposal>.` Link every entry.
-->

## Open questions

<!--
Optional — drop if none, but treat an empty list with suspicion on a proposal this
size. What isn't settled, split honestly: questions review must answer before the
proposal can be adopted, and questions that can be deferred to implementation
without changing the decision. Filing a hard question here is the honest move —
an ambiguity papered over with a plausible sentence reads exactly like a decision,
and review can't catch what it can't see. When one is settled, fold the answer into
the section it affects and delete it here.
Format: `- **Q1** — <question>; owner: <who>; settle: <before adoption | during
build>.`
-->
