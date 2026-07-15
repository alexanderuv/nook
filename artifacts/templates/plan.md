<!--
Implementation plan — build-stage artifact, on a leaf (task, bug, or chore); the
leaf's fixed-name guiding doc (`plan.md`, one per leaf, unnumbered — cited by its
item, not as a series). A plan always attaches to an item — never project-level,
by write-path rule: a plan with no item has nothing to build. Answers: *how do I
build this one thing?* It is the last
document before code: written just before the work, executed — often by an agent —
literally, step by step, without the conversation that produced it. That sets the
bar: every step must be executable by a reader with no context beyond this plan and
the repo, and every ambiguity left in it will be resolved by a guess at build time,
in code. Whole-system architecture is `design_doc`-altitude and required behavior
is `spec`-altitude (markers, not prerequisites — this may be the only doc the leaf
has); this plan is the route for one change. Keep it proportional: a page of plan
for an afternoon of work inverts the value. The plan describes the route, never the
position on it — no checkboxes, no progress marks, no per-step status: the leaf's
status and git history track execution. When reality diverges mid-build, update the
plan in place (git keeps the old route). This document never tracks state in prose:
Nook has structure constructs (releases, statuses, dependency edges) for projects
that want timeline/status tracking, and git already keeps document history.
-->

# {Title} — Plan

## Analysis

<!--
What's actually there, established before deciding what to do: the relevant
current behavior and code (name the files, functions, tables — an agent starts
from these pointers), the constraints that bound the change, and — for a bug — the
root cause with the evidence that pins it ("the cache returns stale entries
because eviction ignores the tenant key; repro: …"). Facts only, checked against
the repo, not remembered: a plan built on a wrong model of the current system
fails at step one. Link the framing docs this leaf serves rather than restating
them; state what was investigated and ruled out when a builder would otherwise
re-investigate it.
-->

## Approach

<!--
The route, argued briefly: how the change will be made, in a paragraph or a few
bullets, and why this way over the obvious alternative — one honest sentence on
the road not taken usually suffices at leaf altitude (a real options analysis is
`rfc`/`design_doc` work). Name the blast radius: what this change touches, what it
must leave untouched. If some part of the approach rests on an unverified
assumption, say so here and make proving it the first step below — the riskiest
part goes first, while changing course is still cheap.
-->

## Steps

<!--
The route as ordered, verifiable moves. Each step: one action, concrete enough to
execute without interpretation (name the file, the function, the command), with a
verification — how the builder knows the step worked before moving on. A step that
can't be verified until three steps later is too big; split it. Order by risk,
not convenience: the step most likely to invalidate the approach comes first.
Steps are numbered for citation, not ticked for progress — execution state lives
on the leaf's status and in git, never here.
Format: `- **STEP1** — <action>; verify: <the observable check>.` In execution
order, riskiest-first where dependencies allow.
-->

## Caveats & rabbit holes

<!--
The traps, declared before someone falls in: the tempting refactor that isn't part
of this change, the edge that looks quick but eats a day, the code that looks dead
but isn't, the "while I'm here" that turns a task into an epic. For each: the trap
and what to do instead ("don't touch the retry logic — out of scope, see the
epic's docs"; "if the migration needs a backfill, stop and split the leaf"). This
section is scope enforcement at build time — for an agent, it is the difference
between finishing the task and wandering; a known time-sink left unwritten will
be found the expensive way.
Format: `- **<caveat|rabbit-hole|no-go>** — <the trap>; instead: <what to do>.`
-->

## Test plan

<!--
How this one change proves itself: the checks that show the new behavior works,
the cases most likely to break (boundaries, failures, the bug's own repro for a
bug fix — the test that fails before the fix and passes after), and what existing
behavior must not regress. Name the level of each check (unit, integration,
manual) and keep it runnable by whoever executes the plan. Whole-epic verification
strategy is `test_plan`-altitude; this section verifies this leaf. Close with the
finish line for the whole leaf — the observable state that means done, so the
builder stops when it's reached, not when they run out of ideas.
Format: `- **TEST1** — <level>: <the check and its expected result>.` Close with
`Done when: <the observable state that ends this leaf>.`
-->

## Rollback

<!--
Optional — drop when the change is trivially revertible (a clean `git revert`
with no data or deploy consequences). How to back out if the change misbehaves
after it lands: what to revert, in what order, and what can't be undone by revert
alone — data already migrated, caches already warmed, messages already sent —
with the step that handles each. If the change is irreversible past some point,
name that point; the builder must know where the door closes.
-->

## Open questions

<!--
Optional — and for this kind, empty is the requirement, not the aspiration: the
plan is the last stop before code, so a question still open here means the plan is
a draft and execution hasn't earned a start. Park ambiguities here while drafting
— never resolve one by silently picking the plausible option — then settle each
into Analysis, Approach, or Steps and delete it. If a question can't be settled
without work, make that work a leaf of its own (a `discovery` investigation) and
let this one wait on it.
Format: `- **Q1** — <question>; owner: <who>; blocks: <STEP#s, or "the approach">.`
-->
