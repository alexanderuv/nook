<!--
Discovery report — an investigation run to reduce uncertainty *before a commitment*,
on an epic or a leaf. Answers: *what did we find out?* It comes in two flavors, one
role: **product discovery** — before committing to build: does the problem, the
demand, or the assumption hold up? (user research, competitive analysis,
experiments, feasibility checks), feeding what-to-build docs; and **technical
discovery** — before committing to an approach: how should we build it? (technical
spikes, library and framework evaluation, integration-pattern and best-practice
digs, prototypes), feeding how-to-build docs. Both are the same act — an
investigation that produced knowledge worth outliving the session that found it —
so both are this one kind, and either can happen at any point in the lifecycle.
The cardinal rule: report evidence, not wishes. A discovery report earns its keep
by being trustworthy — negative and inconclusive results are
reported as prominently as confirmations, claims carry their evidence and
confidence, and what the author *thinks* stays distinguishable from what the data
*shows*. This is written for literal readers: whoever builds on this — often an
agent — will treat an unqualified claim as fact and carry it into requirements and
plans, so the confidence label on a finding is what keeps a weak signal from
hardening into a constraint two documents later. It informs a decision; ratifying the decision is a different act (an `rfc`,
`adr`, or `design_doc`) — this doc may recommend, but it doesn't close the choice.
This document never tracks state in prose: Nook has structure constructs (releases,
statuses, dependency edges) for projects that want timeline/status tracking, and git
already keeps document history.
-->

# {Title}

## Summary

<!--
The answer, up front. Three to five sentences or bullets: what we set out to learn,
the headline findings, and what they imply. Most readers stop here — write it so
they can, and so nothing below contradicts it. State findings as conclusions with
their strength ("X is viable but costs Y", "no evidence users want Z"), not as
topics ("we looked into X"). "We learned nothing conclusive" is a valid, honest
summary when it's true.
-->

## Questions

<!--
What this investigation set out to learn, and why. One to three sharp questions —
each answerable in principle by evidence ("can the importer sustain 10k rows/s?",
never "look into importer performance") — and for each, the decision or work it
informs. If effort was deliberately bounded (a time-boxed spike), say what the
bound was and why that was enough. Questions that emerged mid-investigation and got
answered belong here too, marked as such; it's the found-along-the-way list that
makes the investigation reproducible.
Format: `- **Q1** — <question>; informs: <the decision or work>.` Mark
mid-investigation additions `**Q3 (emerged)**`. Bound, when one applied, as a
closing line: `Bound: <time-box or scope limit, and why it sufficed>.`
-->

## Method

<!--
How we investigated — written so a reader can judge how much weight the findings
deserve. Name the approach (prototype, benchmark, interviews, product teardown,
data analysis, prior-art / library survey) and the concrete setup: what was built
or read, who was talked to and how they were chosen, what data over what period, on
what environment. For a technology or library evaluation, this is where the
candidates and the comparison axes live — which options were considered, which were
ruled out before hands-on trial and why, and what criteria (performance, license,
maintenance health, fit) they were judged on. Include what was *not* tried when a
reader would otherwise assume it was. Honesty about shortcuts here is what makes the findings credible; keep it
plain enough for a non-specialist.
-->

## Findings

<!--
The core of the report. One finding per subsection (###): the claim as the heading
or first sentence, then the evidence — numbers with their baseline, quotes,
benchmark output, screenshots — and a confidence level (solid / suggestive / weak,
with the reason). Organize by the structure the reader needs, never chronologically:
by question or theme for product discovery; by candidate for a technical-discovery
evaluation (a subsection per option, each with its pros, cons, and the evidence
behind them, judged on the axes named in Method — a comparison table earns its place
here). Prior art counts as evidence: what existing frameworks, papers, or other
teams already settled, cited, is a finding, not a preamble. Surprises and negative
results are findings, not footnotes — what didn't work, and lessons the hard way,
are often the payload. Keep interpretation out; a finding is what was observed, and
what it means goes below. Link raw material (transcripts, datasets, notebooks) as
attachments rather than inlining it.
Format: one `### FIND1 — <the claim>` subsection per finding, opening with
`**Confidence:** <solid|suggestive|weak> — <reason> · answers Q<#>`, then the
evidence.
-->

## Implications & recommendation

<!--
What the findings mean, and what we'd do about it. Interpret: which options do the
findings open, close, or reprice? Then recommend — a definite position stated as a
recommendation, each point tracing to a finding above (an implication with no
finding behind it is opinion, and says so explicitly). Cover the "so what" for the
work this discovery informs: what should now be built, avoided, or investigated
further. This section advises the decision; recording one actually made, with its
tradeoffs, is `adr`/`rfc`-altitude work.
Format: `- **<recommendation>** (FIND<#s>) — <the reasoning from those findings>.`
An unbacked point uses `(opinion)` in place of finding IDs.
-->

## Limitations

<!--
What this investigation cannot tell you. Sample too small, environment unlike
production, competitor data from marketing pages, time-box hit before X was tested
— every study has these; naming them is what separates a report from a pitch. For
each: the limitation and what it puts at risk if ignored. Where confidence in a
finding is low, say what additional evidence would raise it — that's tomorrow's
discovery question, pre-scoped.
Format: `- **<limitation>** — at risk: <what breaks if this is ignored>; would
raise confidence: <the additional evidence>.`
-->

## Open questions

<!--
Optional — drop if none. What this investigation surfaced but didn't answer: new
questions raised by the findings, and the original questions the method or
time-box couldn't reach. For each, why it matters and what kind of investigation
would answer it. This section is the seed of the next discovery item — keep entries
sharp enough to be picked up as one.
Format: `- **Q<#>** — <question>; matters because: <why>; would take: <the kind of
investigation>.` Continue the numbering from Questions above — one Q-series per
document.
-->
