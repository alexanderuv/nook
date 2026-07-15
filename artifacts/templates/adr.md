<!--
ADR — decide-stage artifact, and the one kind that is **project-level by rule**:
ADRs never attach to an epic or leaf; the project keeps a single decision stream
(ADR-1, ADR-2, …), because a decision constrains the whole project, supersession
must work across efforts, and a log fragmented per epic is one that readers miss
decisions in. Answers: *what did we decide, and why?* One decision per ADR,
recorded at the moment it's made — not a proposal (that's `rfc`-altitude work) and
not a full technical design (`design_doc`): an ADR is the compact, durable record
of a choice that was significant enough to explain. Keep it to a page; the
discipline of the kind is brevity. Title it as the decision itself ("Use Postgres
for the structure store"), never the topic ("Database choice"). The ADR stream is
the project's constraint memory: agents and newcomers
consult it to learn what's already settled without relitigating, and a literal
reader treats every record here as binding until superseded — so record only
decisions actually made (an aspiration or a leaning is not an ADR yet), and state
them so compliance is checkable. The stream is append-only: reversing a decision
means a new ADR that names the one it replaces — never a silent edit. The
superseded ADR gets exactly one after-the-fact change: a blockquote line right
under its title, `> Superseded by ADR-9.` — so a reader landing on it can't
mistake a dead decision for a live one. There is no Status field beyond that, and
no open-questions section by design: if a load-bearing question is still open, the
decision isn't made, and this document isn't ready to write. This document never
tracks state in prose: Nook has structure constructs (releases, statuses,
dependency edges) for projects that want timeline/status tracking, and git already
keeps document history.
-->

# {Title} — ADR-{seq}

## Context

<!--
The situation that forced a choice, in a few sentences a newcomer can follow: what
was being built or changed, what made the default insufficient, and the forces in
tension (requirements, constraints, deadlines, prior decisions — cite ADR#s when
one bears on this). Close with the decision drivers: the qualities or constraints
any acceptable option had to satisfy, because they are the standard the options
below were judged by. Facts only — the argument for the winner belongs in Decision,
and evidence is linked (discovery findings, benchmarks, incidents), not restated.
If this ADR supersedes another, say so here and say what changed since that
decision was right.
-->

## Decision

<!--
The decision itself, first sentence, active voice: "We will <do the thing>." Then
the why — how the choice satisfies the decision drivers better than the options
below, argued from evidence, not taste. Include the decision's scope: where it
applies, and where it deliberately doesn't ("structure store only; the artifact
repo is out of scope"). A reader must be able to quote the first sentence as the
project's position and check work against it — if the sentence can't be complied
with or violated, it isn't a decision yet.
-->

## Options considered

<!--
The road not taken — the serious contenders, each with what ruled it out. This
section is most of why the record is worth keeping: the chosen option is already
argued above, but the rejections are what stop the next person (or agent) from
re-proposing an alternative that already lost, and they preserve the option's real
strengths so a future revisit starts honest. Include "do nothing / status quo"
when it was viable. Options no one seriously weighed don't belong here.
Format: `- **OPT1 — <name>** — <the option in a sentence>; *ruled out:* <the
driver or evidence that killed it — its genuine strengths noted where real>.`
-->

## Consequences

<!--
What follows from the decision — both directions, from the perspective of everyone
it lands on. Gains: what becomes easier, possible, or cheaper. Costs: what becomes
harder, what options are foreclosed, what new obligations appear (migrations to
run, invariants to maintain, skills to acquire, load shifted to another team). A
consequences list with no costs means the analysis stopped early — every real
decision buys something with something. Downstream readers build on this section
literally: an unstated cost will be discovered by whoever hits it.
Format: `- **<gain|cost>** — <consequence>; lands on: <who or what carries it>.`
-->

## Revisit when

<!--
Optional — drop when nothing foreseeable would reopen the choice. The conditions
under which this decision should be reconsidered, stated concretely enough that an
agent can notice one has occurred ("write volume exceeds ~1k docs/day", "the
library goes unmaintained", "we add a second tenant"). This is not a status to
poll — it arms the future reader with the decision's expiry conditions, so the
stream stays trustworthy without anyone auditing it.
Format: `- **<condition>** — reconsider: <what part of the decision it reopens>.`
-->
