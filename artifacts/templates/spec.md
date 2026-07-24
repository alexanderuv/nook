<!--
Spec — define-stage artifact, on an epic or a leaf. Answers: *what exactly must be
true when this is done?* It is the requirements contract: the precise, testable
behavior an implementation is checked against. Where a PRD argues why and roughly
what, a spec removes the "roughly" — and it stops before "how": architecture and
approach are `design_doc`/`plan`-altitude choices, and a spec that prescribes them
overconstrains the builder. Two disciplines rule this document. Everything is
checkable: every statement must resolve pass/fail against a running system —
numbers with units, never adjectives ("completes in <2s on a 10k-row file", never
"fast"). Nothing is guessed: agents build from this literally, so an ambiguity
discovered while writing is marked as an open question, never papered over with a
plausible-sounding sentence — a wrong guess reads exactly like a decision. SRS and
use-case documents are house variants of this role. This document never tracks
state in prose: the project tracker offers structure constructs (releases, statuses, dependency
edges) for projects that want timeline/status tracking, and git already keeps
document history.
-->

# {Title} — Spec-{seq}

## Overview & scope

<!--
What behavior this spec pins down, in a short paragraph, then the boundary drawn
explicitly: what's in scope, and what's out with a word on why ("out: bulk import —
separate effort"). The out list is what keeps the requirements below finite; an
unstated boundary gets rediscovered as scope creep. Link the docs this contract
serves (the framing PRD or manifesto, motivating discovery reports) rather than
restating their argument.
-->

## Scenarios

<!--
The concrete situations the behavior must serve, ordered by importance: for each,
who or what initiates it (a user, another system, the clock), the flow in a few
steps, and the observable outcome that counts as success. Scenarios are where a
reviewer checks the requirements against reality — every requirement below should
be exercised by at least one, and a scenario no requirement supports reveals a gap.
Keep each scenario independently testable: a runnable slice, not a mood.
Format: one `### SCEN1 — <title>` subsection per scenario, containing
`**Initiator:** <who/what>`, `**Flow:** <numbered steps>`, `**Outcome:** <the
observable success>`.
-->

## Requirements

<!--
The contract's core: a numbered list (REQ1, REQ2, …) so acceptance criteria, tests,
and reviews can cite requirements by ID. Each requirement is one enforceable statement
of required behavior — "the system MUST/MUST NOT …" — testable on its own, with the
trigger or state it applies under stated ("while offline…", "when the file exceeds
the limit…"; EARS-style phrasing is a useful discipline, not a mandate). Split
anything carrying two obligations. Non-functional requirements (performance,
security, reliability, accessibility) are requirements like any other — with
thresholds and conditions, never bare qualities. Keep *how* out: name the required
behavior, not the mechanism that delivers it.
Format: `- **REQ1** — <When/While <condition>,> the system MUST <behavior>.` Group
under `###` subheadings by area when the list grows past a dozen; numbering stays
one REQ-series across groups.
-->

## Edge cases

<!--
Where implementations quietly diverge: boundaries (empty, maximum, zero, one,
duplicate), invalid and malformed input, failures of things this behavior depends
on (network, permissions, concurrent edits), and ordering or repetition surprises
(retry, double-submit, out-of-order arrival). For each: the situation and the
required behavior, stated as flatly as a requirement — an edge case listed as a
question is a to-do, not a spec. When the answer is genuinely "don't care", say so
explicitly; silence here becomes an implementer's guess later.
Format: `- **EDGE1** — <situation>: <required behavior, or "don't care — <why>">.`
-->

## Acceptance criteria

<!--
The definition of done, as concrete pass/fail checks a tester — human or agent —
can run without interpretation. Given/When/Then reads well for flows; a plain
checklist works for rules. Each criterion cites the requirement(s) it verifies
(coverage should be total: a requirement no criterion checks is unenforceable),
states its setup precisely enough to reproduce, and yields a yes or a no — if two
honest testers could disagree on the result, the criterion isn't done. Include
criteria for the edge cases above, not just the happy paths.
Format: `- **AC1** (REQ<#s>, EDGE<#s>) — Given <setup>, when <action>, then
<observable result>.` A rule-style check may replace Given/When/Then with a single
testable statement; the ID and the (REQ/EDGE) citation are not optional.
-->

## Definitions

<!--
Optional — drop when the terms are unambiguous. The nouns the requirements lean on,
each pinned: domain terms with precise meanings ("active user: session in the last
30 days"), entities with the attributes and relationships the behavior depends on —
described by meaning, not storage. Write an entry whenever two readers could bind a
word differently; every requirement using a defined term inherits its precision.
Format: `- **<term>** — <definition>.` For entities, follow the definition with
`attributes: <the ones behavior depends on>; relates to: <other terms>.`
-->

## Assumptions

<!--
Optional — drop if none are load-bearing. What this contract takes as given without
verifying: facts about the environment, guarantees other components provide, scale
this behavior is expected to face. State each so its failure is recognizable ("we
assume file names are unique per project") — when an assumption breaks, the spec is
wrong, and knowing which one broke is what makes the fix cheap.
Format: `- **ASM1** — <assumption>; if false: <what part of this spec it breaks>.`
-->

## Open questions

<!--
Optional — drop when empty, and aim to make it empty: a spec with open questions is
not ready to build against. Every ambiguity found while writing lands here, each
with who owns the answer — never resolved by silently picking the plausible option.
When one is settled, write the decision into the requirement or edge case it
affects and delete it here.
Format: `- **Q1** — <question>; owner: <who>; blocks: <REQ/EDGE/AC #s, or "shape
of the whole">.`
-->
