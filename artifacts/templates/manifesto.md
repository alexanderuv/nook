<!--
Manifesto — frame-stage artifact, typically on an epic; the epic's guiding doc.
Answers: *why are we doing this?* Write one when an effort is big enough that people
(and agents) will face judgment calls the tickets don't answer — the manifesto is
what they consult to make the call the way the author would. It sets vision,
direction, and success criteria. Its altitude is directional: prioritized
requirements, testable behavior, and technical approach are other kinds' questions
(`prd`, `spec`, `design_doc`) — a project may write those too, or the manifesto may
be its only framing doc; write it to stand alone either way. Concrete beats
inspiring: a vivid, checkable picture of the future outlives a slogan. House styles
like the Shape Up pitch, Amazon PR/FAQ, or a one-pager are variants of this role. This document never tracks state in prose:
Nook has structure constructs (releases, statuses, dependency edges) for projects
that want timeline/status tracking, and git already keeps document history.
-->

# {Title} — Manifesto

## Vision

<!--
The destination. Two to four sentences describing the future state when this effort
has succeeded: what exists, who it serves, and what is different for them — written
outside-in, from the user's world, not as a feature list. The test: a newcomer
reads only this and can correctly say what we're building toward and why it matters.
Be bounded (claim your territory, don't overreach into everything adjacent) and
concrete enough to be wrong — a vision no outcome could contradict guides nothing.
Geoffrey Moore's positioning frame is a useful skeleton when stuck: "For [who], who
[need], {title} is a [category] that [benefit]. Unlike [alternative], it [key
difference]."
-->

## Why this matters

<!--
The case for doing this at all, and for doing it now. Ground it in a specific story
or observation that shows the status quo failing — one real, concrete situation
beats an abstract complaint ("users can't X when Y", never "X could be better").
Then why now: what changed — a shift in users, technology, or the project — that
makes this the right moment. Link motivating discovery reports rather than
restating them. Bring the evidence the argument needs — data, quotes, observations — and no
more; the section's job is to make the case, not to catalog everything known.
-->

## Direction

<!--
The shape of the route, in broad strokes: the two or three big bets or pillars this
effort commits to, and for each a sentence on why that way and not the obvious
alternative. Fat-marker altitude — enough that work can be recognized as on-path or
off-path, without prescribing architecture (`design_doc`) or requirements (`prd`).
State the appetite where it's a real constraint: how much this ambition is worth
("worth one focused cycle, not a quarter") anchors every later scope debate. This
section carries judgment about the route, not a schedule of it.
Format: `- **<bet name>** — <the commitment>; *why this way:* <over the obvious
alternative>.` Appetite, when stated, as a closing line: `Appetite: <how much this
is worth>.`
-->

## Principles

<!--
Optional — drop if the vision and direction already decide everything. Three to
five decision rules scoped to this effort, for resolving the tradeoffs the docs
don't anticipate. Each must be able to lose: it earns its place only if the
opposite is something a reasonable person might choose ("simple over configurable —
we cut options before adding switches" decides something; "high quality" decides
nothing). Give each principle one line of what it looks like in practice.
Project-wide norms belong in tenets, not here — only include rules specific to this
effort.
Format: `- **<short-name>** — <the rule>; *in practice:* <what it decides>.` Name
principles so they can be cited in reviews ("per **simple-over-configurable**…").
-->

## Success criteria

<!--
What must be observably true for this to have been worth doing. Three to five
outcome statements — changes in the world or in user behavior, never output ("teams
adopt X for their daily standup", not "X ships"). Prefer one north-star signal plus
guardrails (what must not get worse). Qualitative criteria are fine at this
altitude, but each must be checkable — name how we'd look ("support threads about
Y drop", "new projects choose Z unprompted"). Put numbers on criteria whenever you
have them; leave a criterion directional only when a target would be invented
rather than known.
Format: `- **CRIT1** — <outcome>; check: <where/how we'd observe it>.` Mark the
north star `**CRIT1 (north star)**`; guardrails as `<what must not get worse>`.
-->

## Non-goals

<!--
What this effort is deliberately not about, with a short reason for each ("not
doing X because…"). This is the scope-creep firewall: it records the adjacent
ambitions and tempting expansions already considered and declined, so the vision
stays bounded and the temptations don't get relitigated item by item. Distinguish
never (conflicts with the direction) from not-now (real, but not this effort) — the
second kind is where good non-goals usually come from. An empty list usually means
the boundary hasn't actually been drawn.
Format: `- **<the thing not being done>** (<never|not-now>) — <why>.`
-->

## Open questions

<!--
Optional — drop if none. Directional questions still unresolved — where the vision
or direction could still fork — each with who owns the answer. When one is settled,
fold the decision into the section it affects and delete it here. Keep the list at
this altitude: detailed requirement or implementation questions ride whatever doc
will answer them, not the manifesto.
Format: `- **Q1** — <question>; owner: <who>.`
-->
