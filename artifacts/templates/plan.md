<!--
Implementation plan — build-stage artifact on a leaf (task, bug, or chore): the
leaf's fixed-name guiding doc (`plan.md`, one per leaf, unnumbered — cited by its
item, not as a series). Answers: *how do I build this one thing?* Key rules:

- **Always item-attached.** A plan never exists at project level — a plan with
  no item has nothing to build (enforced by the write path).
- **Last document before code, executed literally.** It is run — often by an
  agent — step by step, without the conversation that produced it. The bar:
  every step executable by a reader with no context beyond this plan and the
  repo; every ambiguity left in it becomes a guess at build time, in code.
- **Start only when the deciding is done.** If an earlier-stage document this
  leaf builds on — its discovery, a pending ADR or RFC, the requirements doc
  above it — still carries a needs-action open question (one someone must
  settle by deciding or by producing missing evidence), settle it (or finish
  that document) before writing the plan. Planning over an undecided item
  doesn't remove the decision; it hides it, and the build then makes it by
  accident. Follow-up questions — ones only the coming work itself can answer
  — don't block the start; absorb each as a step or check below so the build
  answers it on the record.
- **No artifact IDs in code.** Never write step/requirement/goal/finding
  numbers (STEP3, REQ1, GOAL4) or references to markdown documents into code
  or its comments: a comment becomes authoritative the moment it is written
  and must stand on its own, timeless — artifacts drift, archive, and
  renumber. State the underlying reason in plain words instead.
- **Right altitude, right size.** Whole-system architecture is
  `design_doc`-altitude and required behavior is `spec`-altitude (markers, not
  prerequisites — this may be the only doc the leaf has); this plan is the
  route for one change. Keep it proportional: a page of plan for an afternoon
  of work inverts the value.
- **Progress lives in checkboxes only.** Steps carry checkboxes, ticked as
  execution proceeds — the one place this document tracks position on the
  route; coarser status lives on the leaf's status and in git. When reality
  diverges mid-build, update the plan in place (git keeps the old route).
  Beyond the checkboxes, never track state in prose: the project tracker
  offers structure constructs (releases, statuses, dependency edges) for
  timeline/status tracking, and git already keeps document history.
-->

# {Title} — Plan

## Analysis

<!--
What's actually there, established before deciding what to do:

- **The relevant current behavior and code** — name the files, functions,
  tables; an agent starts from these pointers.
- **The constraints that bound the change.**
- **For a bug: the root cause**, with the evidence that pins it ("the cache
  returns stale entries because eviction ignores the tenant key; repro: …").
- **Facts only, checked against the repo, not remembered** — a plan built on
  a wrong model of the current system fails at step one.
- **Link the framing docs this leaf serves** rather than restating them, and
  state what was investigated and ruled out when a builder would otherwise
  re-investigate it.
-->

## Approach

<!--
The route, argued briefly:

- **How the change will be made** — a paragraph or a few bullets.
- **Why this way over the obvious alternative** — one honest sentence on the
  road not taken usually suffices at leaf altitude (a real options analysis
  is `rfc`/`design_doc` work).
- **The blast radius** — what this change touches, and what it must leave
  untouched.
- **Unverified assumptions, named** — if part of the approach rests on one,
  say so and make proving it the first step below: the riskiest part goes
  first, while changing course is still cheap.
-->

## Steps

<!--
The route as ordered, verifiable moves:

- **One action per step**, concrete enough to execute without interpretation —
  name the file, the function, the command.
- **A verification per step** — how the builder knows the step worked before
  moving on. A step that can't be verified until three steps later is too
  big; split it.
- **Order by risk, not convenience** — the step most likely to invalidate the
  approach comes first.
- **Numbered, with a checkbox** ticked as each step lands — the plan's only
  progress mark; all other execution state lives on the leaf's status and in
  git.
- **Step IDs cite between artifacts only** — they never appear in code or
  code comments (see the header rules).
Format: `- [ ] **STEP1** — <action>; verify: <the observable check>.` In execution
order, riskiest-first where dependencies allow.
-->

## Caveats & rabbit holes

<!--
The traps, declared before someone falls in:

- **What belongs here** — the tempting refactor that isn't part of this
  change, the edge that looks quick but eats a day, the code that looks dead
  but isn't, the "while I'm here" that turns a task into an epic.
- **For each: the trap and what to do instead** ("don't touch the retry
  logic — out of scope, see the epic's docs"; "if the migration needs a
  backfill, stop and split the leaf").
- **This section is scope enforcement at build time** — for an agent, it is
  the difference between finishing the task and wandering; a known time-sink
  left unwritten will be found the expensive way.
Format: `- **<caveat|rabbit-hole|no-go>** — <the trap>; instead: <what to do>.`
-->

## Test plan

<!--
How this one change proves itself:

- **The change-specific checks** — what shows the new behavior working, the
  cases most likely to break (boundaries, failures, the bug's own repro for a
  bug fix — the test that fails before the fix and passes after), and what
  existing behavior must not regress. Name the level of each check (unit,
  integration, manual) and keep it runnable by whoever executes the plan.
  Whole-epic verification strategy is `test_plan`-altitude; this section
  verifies this leaf.
- **Standing check, comment hygiene** — sweep the change's code for artifact
  IDs and markdown-document references (e.g. grep the diff for
  STEP/REQ/GOAL/FIND/PRD/epic tokens and `.md` paths); expect zero hits — the
  header rule, verified, not trusted.
- **Standing check, conformance sweep** — re-read this plan against the final
  diff: every step ticked with its verify observed, the blast radius
  respected (nothing touched that Approach says stays untouched, nothing
  changed that the plan never named), every caveat honored, and any mid-build
  divergence already folded back into the plan text. After execution the plan
  must read as an accurate description of what was built; where it doesn't,
  either the code or the plan is wrong — fix whichever is.
- **Run both standing checks through a separate agent** where possible — one
  handed only this plan and the final diff, none of the builder's
  conversation: the builder reads its own intent into the diff, while a fresh
  reader sees only what's there — the same no-context bar this plan was
  written against.
- **Close with the finish line for the whole leaf** — the observable state
  that means done, so the builder stops when it's reached, not when they run
  out of ideas.
Format: `- **TEST1** — <level>: <the check and its expected result>.` Close with
`Done when: <the observable state that ends this leaf>.`
-->

## Rollback

<!--
Optional — drop when the change is trivially revertible (a clean `git revert`
with no data or deploy consequences). Otherwise, how to back out if the change
misbehaves after it lands:

- **What to revert, in what order.**
- **What revert alone can't undo** — data already migrated, caches already
  warmed, messages already sent — with the step that handles each.
- **Where the door closes** — if the change is irreversible past some point,
  name that point; the builder must know where it is.
-->

## Open questions

<!--
Optional — and for this kind, empty is the requirement, not the aspiration: the
plan is the last stop before code, so a question still open here means the plan
is a draft and execution hasn't earned a start.

- **Park ambiguities here while drafting** — never resolve one by silently
  picking the plausible option — then settle each into Analysis, Approach, or
  Steps and delete it.
- **A question that takes real work to settle** becomes a leaf of its own (a
  `discovery` investigation), and this plan waits on it.
Format: `- **Q1** — <question>; owner: <who>; blocks: <STEP#s, or "the approach">.`
-->
