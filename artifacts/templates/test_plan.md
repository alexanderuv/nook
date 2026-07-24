<!--
Test plan — build-stage artifact, typically on an epic. Answers: *how do we verify
the whole?* — the verification strategy for an entire effort: the failures that
live *between* changes (integration seams, cross-feature flows, non-functional
envelopes), which no single change's tests can catch. A leaf `plan`'s test-plan
section verifies that one change; this document is the map of how the assembled
whole gets proven — altitude, not dependency: write it to stand alone whether or
not any leaf plans exist. Two disciplines rule it. Risk sets the coverage: effort
goes where failure hurts, not evenly across features — a plan that tests everything
equally has decided nothing. The not-tested list is load-bearing: to a literal
reader — the agent writing leaf tests, the reviewer judging readiness — a surface
this plan doesn't mention reads as *someone else's problem*, so every untested
surface is either listed with its why or it is a hole; untested-by-decision and
untested-by-omission must be distinguishable on the page. Eval specs (for model or
agent behavior) are house variants of this role. Test *results* never live here —
runs, dashboards, and CI own them; this plan says what must be proven and how,
never how it's currently going. This document never tracks state in prose: the project
tracker offers structure constructs (releases, statuses, dependency edges) for projects that
want timeline/status tracking, and git already keeps document history.
-->

# {Title} — TestPlan-{seq}

## Scope

<!--
What whole this plan verifies, and the boundary drawn explicitly. In: the
features, flows, and qualities this plan takes responsibility for proving. Out:
every surface deliberately not verified here, each with its reason — covered at
another level, out of the release, low-risk and accepted, or genuinely untestable
today. The out-list is the section's payload (IEEE 829 made "features not to be
tested" a first-class section for a reason): it converts silent gaps into recorded
decisions. Link what this plan verifies against — the framing docs, the spec's
acceptance criteria when one exists — rather than restating them.
Format for the out-list: `- **<surface>** — not verified here: <why, and where
the risk lands instead>.`
-->

## Strategy

<!--
How verification effort is spent, and why — the argument, before the itemized map
below. Name where the risk concentrates (the seams most likely to break, the flows
whose failure costs most, the parts built on the least evidence) and how each
level of testing earns its place against that risk: what unit-level tests are
trusted to have caught, what only integration or end-to-end proves, where manual
or exploratory passes are worth human minutes, what runs continuously vs. before
release. State the regression stance: what existing behavior this effort could
break, and how that is guarded. A reader should finish this section knowing why
the coverage below is shaped the way it is — not just what's in it.
-->

## Coverage

<!--
The map itself: each surface or flow the whole must prove, how it gets verified,
and the priority its risk earns it. Cover the non-functional envelope as items
like any other — performance, security, reliability, accessibility — each with its
threshold ("p95 < 500 ms at 100 concurrent imports", never "performs well"); a
quality without a number can't fail, and a check that can't fail proves nothing.
Cite the spec's REQ/AC IDs where a spec exists, so coverage is checkable against
the contract. Priorities are for ordering effort, not for skipping: a low-priority
item is still in scope — anything out of scope belongs in Scope's out-list.
Format: `- **COV1 — <surface or flow>** — <level: unit|integration|e2e|manual>:
<what proves it>; priority: <high|medium|low> (<the risk that sets it>).`
-->

## Environments & data

<!--
Optional — drop when everything runs in one obvious place. Where verification
runs and on what data: the environments, how production-like each one is, and —
honestly — the gaps between test and production (scale, data shape, integrations
stubbed vs. real), because a green suite in an unrepresentative environment is
evidence about the environment, not the product. Name the test data strategy:
generated, anonymized, fixtures — and any data that cannot be used and why. What
a gap puts at risk belongs next to the gap, not discovered after it.
Format: `- **<environment>** — <what runs here>; unlike production: <the gaps,
and what they leave unproven>.`
-->

## Exit criteria

<!--
What must be observably true for the whole to count as verified — the pass/fail
line for the effort, not for any single test. Each criterion is checkable without
interpretation and names its evidence: where a reader — human or agent — looks to
see it satisfied (the CI job, the suite, the report). "All high-priority coverage
green, no open severity-1 defects, the p95 threshold held for a week" is an exit
bar; "testing is complete" is not. If two honest readers could disagree about
whether a criterion is met, it isn't one yet.
Format: `- **EXIT1** — <the condition>; evidence: <where it is observed>.`
-->

## Risks

<!--
Optional — drop if none are real. What threatens the verification itself — not
the product risks (those set Coverage priorities above), but the ways this plan
could pass while the product fails: environment gaps that mask failures, flaky
suites that train people to ignore red, surfaces that can't be exercised until
late, test data that diverges from reality. For each: the threat and what's done
about it — or the honest "accepted", so the reader knows the confidence this
plan's green actually buys.
Format: `- **RISK1** — <threat to verification validity>; <mitigation, or
"accepted — <why>">.`
-->

## Open questions

<!--
Optional — drop if none. Unresolved questions that shape the strategy or
coverage, each with who owns the answer and what it blocks. Settle each into the
section it affects and delete it here; a plan being verified against — like any
document being built from — should have this section empty and gone.
Format: `- **Q1** — <question>; owner: <who>; blocks: <COV/EXIT #s, or "the
strategy">.`
-->
